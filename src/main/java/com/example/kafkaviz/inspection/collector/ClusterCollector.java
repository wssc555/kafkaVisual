package com.example.kafkaviz.inspection.collector;

import com.example.kafkaviz.config.KafkaProperties;
import com.example.kafkaviz.inspection.metric.MetricCatalog;
import com.example.kafkaviz.inspection.metric.MetricScope;
import com.example.kafkaviz.kafka.ClusterConnection;
import com.example.kafkaviz.kafka.KafkaFutures;
import com.example.kafkaviz.model.vo.ClusterInfo;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.TopicPartitionInfo;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 集群级指标采集。
 *
 * <p>底层调用:一次 {@code describeCluster}(broker 列表 / controller)+ 一次
 * {@code listTopics} + 一次 {@code describeTopics}(URP / offline / leader 分布)。
 * 三路在<b>同一次 collect 调用内只各打一次</b>,与"规则有几个集群级指标"无关 ——
 * 这正是 {@code CollectorRegistry} 按 (scope, metricSet) 合并的意义。
 *
 * <p>{@code controller_alive} 的判定:controller 节点非空且 id >= 0。
 * {@code online_broker_count} 取"承载至少一个 Leader 分区的 Broker 数" —— 这是
 * 零额外 RPC 下最贴近"在线"的可观测量(未承载分区的 broker 会被漏计,但那种
 * broker 对可用性本就无贡献)。
 */
@Component
public class ClusterCollector implements MetricCollector {

    private final long timeoutMs;

    public ClusterCollector(KafkaProperties kafkaProperties) {
        this.timeoutMs = kafkaProperties.getAdmin().getDefaultApiTimeoutMs();
    }

    @Override
    public MetricScope scope() {
        return MetricScope.CLUSTER;
    }

    @Override
    public Map<String, Map<String, String>> collect(ClusterConnection connection,
                                                    Collection<String> scopeInstances,
                                                    Set<String> metricKeys) throws Exception {
        AdminClient admin = connection.getAdminClient();

        ClusterInfo info = describeCluster(admin);
        Map<String, TopicDescription> descs = describeAllTopics(admin);

        int underReplicated = 0;
        int offline = 0;
        Set<Integer> leaderBrokers = new HashSet<>();
        for (TopicDescription td : descs.values()) {
            for (TopicPartitionInfo p : td.partitions()) {
                if (p.isr().size() < p.replicas().size()) {
                    underReplicated++;
                }
                if (p.leader() == null || p.leader().id() < 0) {
                    offline++;
                } else {
                    leaderBrokers.add(p.leader().id());
                }
            }
        }

        Map<String, String> values = new HashMap<>();
        for (String key : metricKeys) {
            switch (key) {
                case "broker_count" -> values.put(key, String.valueOf(info.getBrokers().size()));
                case "controller_alive" -> values.put(key, String.valueOf(info.getControllerId() >= 0));
                case "under_replicated_partitions" -> values.put(key, String.valueOf(underReplicated));
                case "offline_partitions" -> values.put(key, String.valueOf(offline));
                case "online_broker_count" -> values.put(key, String.valueOf(leaderBrokers.size()));
                default -> {
                    // 未知指标键:静默跳过(目录校验已在规则创建时做过,这里是双保险)
                }
            }
        }

        // 集群级只有一个 scope 实例,key 固定为 CLUSTER
        Map<String, Map<String, String>> out = new HashMap<>();
        out.put(MetricCatalog.CLUSTER_SCOPE_ID, values);
        return out;
    }

    private ClusterInfo describeCluster(AdminClient admin) throws Exception {
        var result = admin.describeCluster();
        String clusterId = KafkaFutures.await(result.clusterId(), timeoutMs, "describeCluster.clusterId:inspection");
        var controller = KafkaFutures.await(result.controller(), timeoutMs, "describeCluster.controller:inspection");
        List<org.apache.kafka.common.Node> nodes = new java.util.ArrayList<>(
                KafkaFutures.await(result.nodes(), timeoutMs, "describeCluster.nodes:inspection"));
        List<ClusterInfo.BrokerInfo> brokers = nodes.stream()
                .map(n -> ClusterInfo.BrokerInfo.builder()
                        .id(n.id()).host(n.host()).port(n.port()).rack(n.rack()).build())
                .toList();
        return ClusterInfo.builder()
                .clusterId(clusterId)
                .controllerId(controller == null ? -1 : controller.id())
                .brokers(brokers)
                .build();
    }

    /** 空 topic 列表时不发 describeTopics(部分版本对空集合抛异常)。 */
    private Map<String, TopicDescription> describeAllTopics(AdminClient admin) throws Exception {
        Set<String> names = KafkaFutures.await(admin.listTopics().names(), timeoutMs, "listTopics:inspection");
        if (names.isEmpty()) {
            return Map.of();
        }
        return KafkaFutures.await(admin.describeTopics(names).all(), timeoutMs, "describeTopics:inspection");
    }
}
