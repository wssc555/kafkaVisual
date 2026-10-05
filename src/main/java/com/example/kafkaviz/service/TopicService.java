package com.example.kafkaviz.service;

import com.example.kafkaviz.config.CacheConfig;
import com.example.kafkaviz.config.KafkaProperties;
import com.example.kafkaviz.kafka.ClusterConnection;
import com.example.kafkaviz.kafka.KafkaFutures;
import com.example.kafkaviz.kafka.TopicAdminSupport;
import com.example.kafkaviz.model.dto.CreateTopicRequest;
import com.example.kafkaviz.model.vo.PartitionsUpdateResult;
import com.example.kafkaviz.model.vo.TopicActionResult;
import com.example.kafkaviz.model.vo.TopicDetail;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.NewPartitions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * Topic 查询与运维。
 *
 * <p>topic 存在性校验统一走 {@link TopicAdminSupport},不做"全量 listTopics + contains"。
 *
 * <p>缓存 key 用 {@code CacheKey(clusterId, arg)} 复合键;
 * {@code @CacheEvict(allEntries = true)} 保持"全量失效"——写操作后多清一个集群的
 * 缓存只是多一次回源,不会读到错数据,而精确失效需要拼出完整键集合,得不偿失。
 */
@Service
public class TopicService {

    private final KafkaProperties kafkaProperties;
    /** Admin 调用的阻塞上限,见 kafka.admin.default-api-timeout-ms。 */
    private final long timeoutMs;

    public TopicService(KafkaProperties kafkaProperties) {
        this.kafkaProperties = kafkaProperties;
        this.timeoutMs = kafkaProperties.getAdmin().getDefaultApiTimeoutMs();
    }

    /**
     * 列出该集群的所有 Topic，可选是否包含内部 Topic。
     *
     * <p>名称列表属秒级~分钟级才变化的数据,加 10s 短 TTL 缓存
     * (见 {@code config/CacheConfig});写操作主动失效,保证"刚创建的 topic 立刻可见"。
     */
    @Cacheable(value = CacheConfig.TOPIC_NAMES,
            key = "T(com.example.kafkaviz.kafka.CacheKey).of(#connection.clusterId(), #includeInternal)")
    public List<String> listTopics(ClusterConnection connection, boolean includeInternal)
            throws ExecutionException, InterruptedException {
        AdminClient admin = connection.getAdminClient();
        Set<String> topics = KafkaFutures.await(admin.listTopics().names(), timeoutMs, "listTopics");
        return topics.stream()
                .filter(t -> includeInternal || !t.startsWith("_"))
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * 获取 Topic 详情（partition 元数据 + beginningOffset / endOffset）。
     *
     * <p>分区离线 / leader 迁移中时 {@code leader} 返回 {@code -1},前端据此显示"离线"。
     */
    public TopicDetail describeTopic(ClusterConnection connection, String name)
            throws ExecutionException, InterruptedException {
        AdminClient admin = connection.getAdminClient();

        // 一次 describeTopics 同时完成"存在性校验 + 取描述":
        // 不做"全量 listTopics + contains"——大集群上那是一次完整的 Metadata 抓取。
        TopicDescription desc = TopicAdminSupport.describeTopicOrThrow(admin, name, timeoutMs);
        List<TopicPartitionInfo> partitions = desc.partitions();

        List<TopicPartition> tps = partitions.stream()
                .map(p -> new TopicPartition(name, p.partition()))
                .toList();

        // 空分区列表时不发 listOffsets:空 map 在部分 kafka-clients 版本会抛异常
        if (tps.isEmpty()) {
            return TopicDetail.builder().name(name).partitions(List.of()).build();
        }

        // beginning/end offset 用 AdminClient.listOffsets 获取,不占用 Consumer 池 ——
        // Consumer 池是全应用最紧张的资源(默认仅 10/集群),而这里只是两次只读 offset 查询。
        // 注意:一个 TopicPartition 在同一次 listOffsets 中只能对应一个 OffsetSpec,
        // 因此 earliest / latest 必须两次调用(两次互不依赖)。
        Map<TopicPartition, OffsetSpec> earliestSpecs = new HashMap<>();
        Map<TopicPartition, OffsetSpec> latestSpecs = new HashMap<>();
        for (TopicPartition tp : tps) {
            earliestSpecs.put(tp, OffsetSpec.earliest());
            latestSpecs.put(tp, OffsetSpec.latest());
        }

        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> beginningOffsets =
                KafkaFutures.await(admin.listOffsets(earliestSpecs).all(), timeoutMs,
                        "listOffsets.earliest:" + name);
        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> endOffsets =
                KafkaFutures.await(admin.listOffsets(latestSpecs).all(), timeoutMs,
                        "listOffsets.latest:" + name);

        List<TopicDetail.PartitionInfo> partitionInfos = partitions.stream().map(p -> {
            TopicPartition tp = new TopicPartition(name, p.partition());
            return TopicDetail.PartitionInfo.builder()
                    .partition(p.partition())
                    // 分区离线 / leader 迁移瞬间 p.leader() 为 null,直接 .id() 会 NPE 落 50000。
                    // 与 DashboardService 的判空口径一致,统一用 -1 表示"离线无 leader",
                    // 前端对 <0 显示"离线"(见 frontend/src/views/TopicDetail.vue)。
                    .leader(p.leader() == null ? -1 : p.leader().id())
                    .replicas(p.replicas().stream().map(r -> r.id()).toList())
                    .isr(p.isr().stream().map(r -> r.id()).toList())
                    .beginningOffset(offsetOf(beginningOffsets.get(tp)))
                    .endOffset(offsetOf(endOffsets.get(tp)))
                    .build();
        }).toList();

        return TopicDetail.builder()
                .name(name)
                .partitions(partitionInfos)
                .build();
    }

    /**
     * 创建 Topic。
     */
    @CacheEvict(value = CacheConfig.TOPIC_NAMES, allEntries = true)
    public TopicActionResult createTopic(ClusterConnection connection, CreateTopicRequest req)
            throws ExecutionException, InterruptedException {
        AdminClient admin = connection.getAdminClient();
        NewTopic newTopic = new NewTopic(req.getName(), req.getPartitions(), (short) req.getReplicationFactor().intValue());
        if (req.getConfigs() != null && !req.getConfigs().isEmpty()) {
            newTopic.configs(req.getConfigs());
        }
        KafkaFutures.await(admin.createTopics(List.of(newTopic)).all(), timeoutMs,
                "createTopics:" + req.getName());
        return TopicActionResult.builder()
                .name(req.getName())
                .created(true)
                .build();
    }

    /**
     * 删除 Topic。
     */
    @CacheEvict(value = CacheConfig.TOPIC_NAMES, allEntries = true)
    public TopicActionResult deleteTopic(ClusterConnection connection, String name)
            throws ExecutionException, InterruptedException {
        AdminClient admin = connection.getAdminClient();
        KafkaFutures.await(admin.deleteTopics(List.of(name)).all(), timeoutMs, "deleteTopics:" + name);
        return TopicActionResult.builder()
                .name(name)
                .deleted(true)
                .build();
    }

    /**
     * 扩容 Topic 分区数。
     *
     * <p>Kafka 仅支持增加分区,不支持缩减。partitions 为扩容后的<b>总</b>分区数(非增量);
     * 新分区副本由 broker 按 RackAwareMode 自动分配,不支持手工指定 assignment。
     *
     * <p>不改名称列表,故不需要失效 topicNames 缓存。
     *
     * @param target 扩容后的总分区数
     * @throws IllegalArgumentException 目标分区数不大于当前分区数
     */
    public PartitionsUpdateResult expandPartitions(ClusterConnection connection, String name, int target)
            throws ExecutionException, InterruptedException {
        AdminClient admin = connection.getAdminClient();

        int current = TopicAdminSupport.describeTopicOrThrow(admin, name, timeoutMs).partitions().size();

        // 预检查给出清晰报错,早于 Kafka 的 InvalidPartitionsException(50001)
        if (target <= current) {
            throw new IllegalArgumentException("Partition count can only be increased, current is " + current);
        }

        KafkaFutures.await(admin.createPartitions(Map.of(name, NewPartitions.increaseTo(target))).all(),
                timeoutMs, "createPartitions:" + name);
        return PartitionsUpdateResult.builder().name(name).partitions(target).updated(true).build();
    }

    /** listOffsets 未返回该分区时,保持原 {@code getOrDefault(tp, 0L)} 的宽容语义。 */
    private long offsetOf(ListOffsetsResult.ListOffsetsResultInfo info) {
        return info == null ? 0L : info.offset();
    }
}
