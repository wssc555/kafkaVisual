package com.example.kafkaviz.inspection.collector;

import com.example.kafkaviz.config.KafkaProperties;
import com.example.kafkaviz.inspection.metric.MetricScope;
import com.example.kafkaviz.kafka.ClusterConnection;
import com.example.kafkaviz.kafka.KafkaFutures;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.LogDirDescription;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.ReplicaInfo;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Topic 级指标采集(批量)。
 *
 * <p>一次 collect 最多四路 RPC,与实例数、指标数无关:
 * <ol>
 *   <li>{@code describeTopics(scopeInstances)} —— 分区数 / 副本数 / URP;</li>
 *   <li>{@code listOffsets(latest)} —— 消息总量(按需);</li>
 *   <li>{@code describeLogDirs(brokerIds)} —— 日志字节(按需);</li>
 *   <li>{@code describeCluster()} —— 只为拿 brokerIds。</li>
 * </ol>
 *
 * <p><b>brokerIds 必须由 describeCluster 显式取回</b>:kafka-clients 3.9 的
 * {@code describeLogDirs(空列表)} 不会查询任何 broker(实现只是遍历入参),
 * 与 KIP-113 文档里的"空列表=全部"不一致 —— 以实际实现为准。
 */
@Component
public class TopicCollector implements MetricCollector {

    private final long timeoutMs;

    public TopicCollector(KafkaProperties kafkaProperties) {
        this.timeoutMs = kafkaProperties.getAdmin().getDefaultApiTimeoutMs();
    }

    /** 副本数取首个分区的副本数(Kafka 保证同 topic 内一致);无分区时记 0。 */
    private static int replicationFactor(List<TopicPartitionInfo> partitions) {
        return partitions.isEmpty() ? 0 : partitions.get(0).replicas().size();
    }

    private static boolean hasUnderReplicated(List<TopicPartitionInfo> partitions) {
        for (TopicPartitionInfo p : partitions) {
            if (p.isr().size() < p.replicas().size()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public MetricScope scope() {
        return MetricScope.TOPIC;
    }

    @Override
    public Map<String, Map<String, String>> collect(ClusterConnection connection,
                                                    Collection<String> scopeInstances,
                                                    Set<String> metricKeys) throws Exception {
        AdminClient admin = connection.getAdminClient();
        List<String> topics = new ArrayList<>(scopeInstances);
        if (topics.isEmpty()) {
            return Map.of();
        }

        Map<String, TopicDescription> descs = KafkaFutures.await(
                admin.describeTopics(topics).all(), timeoutMs, "describeTopics:inspection");

        boolean needMessageCount = metricKeys.contains("message_count_total");
        boolean needLogSize = metricKeys.contains("log_size_bytes");

        Map<String, Long> messageCounts = needMessageCount ? fetchMessageCounts(admin, descs) : Map.of();
        Map<String, Long> logSizes = needLogSize ? fetchLogSizes(admin, connection) : Map.of();

        Map<String, Map<String, String>> out = new HashMap<>();
        for (String topic : topics) {
            TopicDescription desc = descs.get(topic);
            if (desc == null) {
                continue; // topic 在两次调用之间被删:该 scope 本轮无值
            }
            Map<String, String> values = new HashMap<>();
            List<TopicPartitionInfo> partitions = desc.partitions();
            for (String key : metricKeys) {
                switch (key) {
                    case "partition_count" -> values.put(key, String.valueOf(partitions.size()));
                    case "replication_factor" -> values.put(key, String.valueOf(replicationFactor(partitions)));
                    case "message_count_total" -> values.put(key,
                            String.valueOf(messageCounts.getOrDefault(topic, 0L)));
                    case "log_size_bytes" -> values.put(key, String.valueOf(logSizes.getOrDefault(topic, 0L)));
                    case "under_replicated" -> values.put(key, String.valueOf(hasUnderReplicated(partitions)));
                    default -> {
                        // 未知键跳过
                    }
                }
            }
            out.put(topic, values);
        }
        return out;
    }

    /**
     * 消息总量 = 各分区 latest offset 之和。
     *
     * <p>两次 listOffsets(earliest/latest)才能算"现存消息数",但目录语义
     * ({@code message_count_total})是"该 topic 消息总量",故只取 endOffset 之和,
     * 与计划 §5.2 的口径一致 —— 少一路 RPC,且不受 retention 清理造成的
     * beginningOffset 前移影响(那个量不是"总量")。
     */
    private Map<String, Long> fetchMessageCounts(AdminClient admin, Map<String, TopicDescription> descs)
            throws Exception {
        Map<TopicPartition, OffsetSpec> specs = new LinkedHashMap<>();
        for (Map.Entry<String, TopicDescription> e : descs.entrySet()) {
            for (TopicPartitionInfo p : e.getValue().partitions()) {
                specs.put(new TopicPartition(e.getKey(), p.partition()), OffsetSpec.latest());
            }
        }
        if (specs.isEmpty()) {
            return Map.of();
        }
        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> infos = KafkaFutures.await(
                admin.listOffsets(specs).all(), timeoutMs, "listOffsets.latest:inspection");
        Map<String, Long> totals = new HashMap<>();
        for (Map.Entry<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> e : infos.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            totals.merge(e.getKey().topic(), e.getValue().offset(), Long::sum);
        }
        return totals;
    }

    /**
     * 日志字节 = 该 topic 全部非 future 副本的 size 之和。
     *
     * <p>一次 describeLogDirs 拿全量后按 topic 聚合:客户端拿到的副本表本来就是
     * 完整的(没有服务端按 topic 过滤的 API),在内存里分组不增加网络传输。
     */
    private Map<String, Long> fetchLogSizes(AdminClient admin, ClusterConnection connection)
            throws Exception {
        List<Integer> brokerIds = KafkaFutures
                .await(connection.getAdminClient().describeCluster().nodes(), timeoutMs,
                        "describeCluster.nodes:inspection")
                .stream()
                .map(org.apache.kafka.common.Node::id)
                .sorted()
                .toList();
        if (brokerIds.isEmpty()) {
            return Map.of();
        }
        Map<Integer, Map<String, LogDirDescription>> all = KafkaFutures.await(
                admin.describeLogDirs(brokerIds).allDescriptions(), timeoutMs, "describeLogDirs:inspection");
        Map<String, Long> sizes = new HashMap<>();
        for (Map<String, LogDirDescription> dirs : all.values()) {
            for (LogDirDescription dir : dirs.values()) {
                Map<TopicPartition, ReplicaInfo> replicas = dir.replicaInfos();
                if (replicas == null) {
                    continue;
                }
                for (Map.Entry<TopicPartition, ReplicaInfo> e : replicas.entrySet()) {
                    if (e.getValue().isFuture()) {
                        continue;
                    }
                    sizes.merge(e.getKey().topic(), e.getValue().size(), Long::sum);
                }
            }
        }
        return sizes;
    }
}
