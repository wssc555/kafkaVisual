package com.example.kafkaviz.inspection.collector;

import com.example.kafkaviz.config.KafkaProperties;
import com.example.kafkaviz.inspection.metric.MetricScope;
import com.example.kafkaviz.kafka.ClusterConnection;
import com.example.kafkaviz.kafka.KafkaFutures;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 消费组级指标采集(批量)。
 *
 * <p>lag 口径与 {@code ConsumerGroupService.describeAll()} 完全一致:
 * {@code endOffset − committed},负值按 0 计(分区被 truncate 时会出现负值)。
 * 差别只在于<b>批量化</b>:一次 {@code describeConsumerGroups(全部组)} + 每组的
 * {@code listConsumerGroupOffsets} + <b>一次合并的</b> {@code listOffsets}。
 *
 * <p><b>零消费干扰</b>:全程 {@code AdminClient},不借 {@code ConsumerPool}、
 * 不 poll、不提交 offset(加入消费组会让组成员数变化,那是另一种干扰)。
 */
@Component
public class ConsumerGroupCollector implements MetricCollector {

    private final long timeoutMs;

    public ConsumerGroupCollector(KafkaProperties kafkaProperties) {
        this.timeoutMs = kafkaProperties.getAdmin().getDefaultApiTimeoutMs();
    }

    @Override
    public MetricScope scope() {
        return MetricScope.CONSUMER_GROUP;
    }

    @Override
    public Map<String, Map<String, String>> collect(ClusterConnection connection,
                                                    Collection<String> scopeInstances,
                                                    Set<String> metricKeys) throws Exception {
        AdminClient admin = connection.getAdminClient();
        List<String> groups = List.copyOf(scopeInstances);
        if (groups.isEmpty()) {
            return Map.of();
        }

        Map<String, ConsumerGroupDescription> descs = describeGroups(admin, groups);

        boolean needLag = metricKeys.contains("lag_total");
        Map<String, Long> lags = needLag ? fetchLags(admin, descs.keySet()) : Map.of();

        Map<String, Map<String, String>> out = new HashMap<>();
        for (String group : groups) {
            ConsumerGroupDescription desc = descs.get(group);
            if (desc == null) {
                continue;
            }
            Map<String, String> values = new HashMap<>();
            String state = desc.state() == null ? "UNKNOWN" : desc.state().toString();
            for (String key : metricKeys) {
                switch (key) {
                    case "state" -> values.put(key, state);
                    case "lag_total" -> values.put(key, String.valueOf(lags.getOrDefault(group, 0L)));
                    case "member_count" -> values.put(key, String.valueOf(desc.members().size()));
                    case "dead" -> values.put(key, String.valueOf("DEAD".equals(state)));
                    default -> {
                        // 未知键跳过
                    }
                }
            }
            out.put(group, values);
        }
        return out;
    }

    /**
     * 批量取组描述。
     *
     * <p>{@code describeConsumerGroups(全部组).describedGroups()} 返回的是
     * {@code Map<groupId, KafkaFuture<ConsumerGroupDescription>>} —— 一次 RPC
     * 把全部组的请求都发出去了,这里逐个 {@code await} 只是收结果(<b>总耗时 ≈ 最慢的那个,
     * 不是累加</b>),与 {@code ConsumerGroupService.describeGroupDesc} 的用法一致。
     *
     * <p>组不存在 / DEAD 时 Kafka 不报错,而是返回 {@code state=DEAD} 的空描述 ——
     * 因此这里不需要 try/catch,{@code dead} 指标正是靠它成立的。
     */
    private Map<String, ConsumerGroupDescription> describeGroups(AdminClient admin, List<String> groups)
            throws Exception {
        Map<String, org.apache.kafka.common.KafkaFuture<ConsumerGroupDescription>> futures =
                admin.describeConsumerGroups(groups).describedGroups();
        Map<String, ConsumerGroupDescription> out = new LinkedHashMap<>();
        for (String group : groups) {
            org.apache.kafka.common.KafkaFuture<ConsumerGroupDescription> future = futures.get(group);
            if (future == null) {
                continue;
            }
            ConsumerGroupDescription desc = KafkaFutures.await(future, timeoutMs,
                    "describeConsumerGroups:" + group);
            if (desc != null) {
                out.put(group, desc);
            }
        }
        return out;
    }

    /**
     * 逐组取 committed offset(AdminClient 无批量 API),但把全部分区的
     * endOffset 合并成<b>一次</b> listOffsets —— N 个组从 2N 次降到 N+1 次。
     */
    private Map<String, Long> fetchLags(AdminClient admin, Set<String> groups) throws Exception {
        Map<String, Map<TopicPartition, OffsetAndMetadata>> committedByGroup = new LinkedHashMap<>();
        for (String group : groups) {
            Map<TopicPartition, OffsetAndMetadata> committed = KafkaFutures.await(
                    admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata(), timeoutMs,
                    "listConsumerGroupOffsets:" + group);
            if (committed != null && !committed.isEmpty()) {
                committedByGroup.put(group, committed);
            }
        }
        if (committedByGroup.isEmpty()) {
            return Map.of();
        }

        Map<TopicPartition, OffsetSpec> specs = new LinkedHashMap<>();
        for (Map<TopicPartition, OffsetAndMetadata> committed : committedByGroup.values()) {
            for (TopicPartition tp : committed.keySet()) {
                specs.putIfAbsent(tp, OffsetSpec.latest());
            }
        }
        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> endOffsets = KafkaFutures.await(
                admin.listOffsets(specs).all(), timeoutMs, "listOffsets.latest:inspection-lag");

        Map<String, Long> lags = new HashMap<>();
        for (Map.Entry<String, Map<TopicPartition, OffsetAndMetadata>> e : committedByGroup.entrySet()) {
            long total = 0;
            for (Map.Entry<TopicPartition, OffsetAndMetadata> tp : e.getValue().entrySet()) {
                ListOffsetsResult.ListOffsetsResultInfo info = endOffsets.get(tp.getKey());
                long end = info == null ? 0L : info.offset();
                total += Math.max(0, end - tp.getValue().offset());
            }
            lags.put(e.getKey(), total);
        }
        return lags;
    }
}
