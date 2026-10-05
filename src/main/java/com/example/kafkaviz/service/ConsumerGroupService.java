package com.example.kafkaviz.service;

import com.example.kafkaviz.config.CacheConfig;
import com.example.kafkaviz.config.KafkaProperties;
import com.example.kafkaviz.exception.ConsumerGroupNotFoundException;
import com.example.kafkaviz.exception.GroupActiveException;
import com.example.kafkaviz.kafka.ClusterConnection;
import com.example.kafkaviz.kafka.KafkaFutures;
import com.example.kafkaviz.kafka.TopicAdminSupport;
import com.example.kafkaviz.model.dto.ResetOffsetsRequest;
import com.example.kafkaviz.model.vo.ConsumerGroupDetail;
import com.example.kafkaviz.model.vo.ConsumerGroupOverview;
import com.example.kafkaviz.model.vo.GroupDeleteResult;
import com.example.kafkaviz.model.vo.ResetOffsetsResult;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.MemberDescription;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.TopicPartition;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 消费组查询 / offset 重置 / 删组。
 *
 * <p>列表缓存的 key 用 {@code CacheKey(clusterId, 'all')} 复合键,
 * 否则 A 集群的组列表会在 TTL 内被 B 集群读到。
 */
@Service
public class ConsumerGroupService {

    /** Admin 调用的阻塞上限,见 kafka.admin.default-api-timeout-ms。 */
    private final long timeoutMs;

    public ConsumerGroupService(KafkaProperties kafkaProperties) {
        this.timeoutMs = kafkaProperties.getAdmin().getDefaultApiTimeoutMs();
    }

    /**
     * 列出该集群的所有消费组。
     *
     * <p>名称列表属秒级~分钟级才变化的数据,加 10s 短 TTL 缓存;删组后主动失效。
     *
     * <p>注意:归档消费组 {@code kafkaviz-archive-<clusterId>} 也会出现在这里,
     * 属预期行为,不做过滤。
     */
    @Cacheable(value = CacheConfig.GROUP_NAMES,
            key = "T(com.example.kafkaviz.kafka.CacheKey).of(#connection.clusterId(), 'all')")
    public List<String> listGroups(ClusterConnection connection) throws ExecutionException, InterruptedException {
        AdminClient admin = connection.getAdminClient();
        return KafkaFutures.await(admin.listConsumerGroups().all(), timeoutMs, "listConsumerGroups").stream()
                .map(ConsumerGroupListing::groupId)
                .sorted()
                .toList();
    }

    /**
     * 查询消费组的跨全部 topic 聚合消费进度与 lag。
     *
     * <p>无任何已提交 offset 时:topics 为空列表,totalLag=0,state/memberCount 照常返回。
     * 组不存在/DEAD → {@link ConsumerGroupNotFoundException}(40402)。
     */
    public ConsumerGroupOverview describeAll(ClusterConnection connection, String group)
            throws ExecutionException, InterruptedException {
        return describeAll(connection, group, null);
    }

    /**
     * 查询消费组消费进度;onlyTopic 非空时只计算目标 topic 的分区 endOffsets,
     * 使单 topic 查询的复杂度从 O(全组分区数) 降为 O(目标 topic 分区数)。
     *
     * <p>listConsumerGroupOffsets 本身按组全量拉取(AdminClient API 限制),
     * 但 endOffsets 只针对需要展示的分区调用。
     */
    private ConsumerGroupOverview describeAll(ClusterConnection connection, String group, String onlyTopic)
            throws ExecutionException, InterruptedException {
        AdminClient admin = connection.getAdminClient();

        ConsumerGroupDescription groupDesc = describeGroupDesc(connection, group);
        String state = groupDesc.state().toString();
        int memberCount = groupDesc.members().size();

        // 先一次遍历成员建立"分区 → 成员"索引,避免对每个分区再遍历全部成员
        // (O(P×M)),百成员 × 数千分区的组约数十万次循环,每次查询都重复支付。
        Map<TopicPartition, MemberDescription> ownerByTp = new HashMap<>();
        for (MemberDescription member : groupDesc.members()) {
            if (member.assignment() == null || member.assignment().topicPartitions() == null) {
                continue;
            }
            for (TopicPartition assigned : member.assignment().topicPartitions()) {
                // putIfAbsent:同一分区被分配给多个成员(异常状态)时保留第一个,与原 break 语义一致
                ownerByTp.putIfAbsent(assigned, member);
            }
        }

        // 全部已提交 offset
        Map<TopicPartition, OffsetAndMetadata> allOffsets = KafkaFutures.await(
                admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata(), timeoutMs,
                "listConsumerGroupOffsets:" + group);

        if (allOffsets.isEmpty()) {
            return ConsumerGroupOverview.builder()
                    .group(group)
                    .state(state)
                    .memberCount(memberCount)
                    .topics(Collections.emptyList())
                    .totalLag(0)
                    .build();
        }

        // 只把需要展示的分区交给 endOffsets(单 topic 查询不扫描全组其他 topic)
        List<TopicPartition> needEndOffsets = onlyTopic == null
                ? new ArrayList<>(allOffsets.keySet())
                : allOffsets.keySet().stream()
                        .filter(tp -> tp.topic().equals(onlyTopic))
                        .toList();

        // 目标 topic 无已提交 offset:与全量空返回一致(partitions=[],totalLag=0)
        if (needEndOffsets.isEmpty()) {
            return ConsumerGroupOverview.builder()
                    .group(group)
                    .state(state)
                    .memberCount(memberCount)
                    .topics(Collections.emptyList())
                    .totalLag(0)
                    .build();
        }

        // 一次 listOffsets 拿 logEndOffset,不占用 Consumer 池 —— 消费组进度查询是高频路径,
        // 把"能用 AdminClient 干的活"留给默认仅 10 的 Consumer 池会直接推高 50301 概率。
        Map<TopicPartition, OffsetSpec> latestSpecs = new LinkedHashMap<>();
        for (TopicPartition tp : needEndOffsets) {
            latestSpecs.put(tp, OffsetSpec.latest());
        }
        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> infos = KafkaFutures.await(
                admin.listOffsets(latestSpecs).all(), timeoutMs, "listOffsets.latest:group=" + group);
        Map<TopicPartition, Long> endOffsets = new HashMap<>();
        infos.forEach((tp, info) -> endOffsets.put(tp, info == null ? 0L : info.offset()));

        // 按 topic 分组,名称排序保证输出稳定
        Map<String, List<TopicPartition>> byTopic = new TreeMap<>();
        for (TopicPartition tp : needEndOffsets) {
            byTopic.computeIfAbsent(tp.topic(), k -> new ArrayList<>()).add(tp);
        }

        long totalLag = 0;
        List<ConsumerGroupOverview.TopicProgress> topics = new ArrayList<>();
        for (Map.Entry<String, List<TopicPartition>> e : byTopic.entrySet()) {
            String topic = e.getKey();
            List<TopicPartition> tps = e.getValue();
            tps.sort(Comparator.comparingInt(TopicPartition::partition));

            long topicLag = 0;
            List<ConsumerGroupDetail.PartitionOffset> partitions = new ArrayList<>();
            for (TopicPartition tp : tps) {
                long currentOffset = allOffsets.get(tp).offset();
                long logEndOffset = endOffsets.getOrDefault(tp, 0L);
                long lag = Math.max(0, logEndOffset - currentOffset);
                topicLag += lag;

                // O(1) 查表取该 partition 的持有成员
                MemberDescription owner = ownerByTp.get(tp);
                String memberId = owner == null ? null : owner.consumerId();
                String clientId = owner == null ? null : owner.clientId();
                String host = owner == null ? null : owner.host();

                partitions.add(ConsumerGroupDetail.PartitionOffset.builder()
                        .partition(tp.partition())
                        .currentOffset(currentOffset)
                        .logEndOffset(logEndOffset)
                        .lag(lag)
                        .memberId(memberId)
                        .clientId(clientId)
                        .host(host)
                        .build());
            }

            totalLag += topicLag;
            topics.add(ConsumerGroupOverview.TopicProgress.builder()
                    .topic(topic)
                    .partitions(partitions)
                    .totalLag(topicLag)
                    .build());
        }

        return ConsumerGroupOverview.builder()
                .group(group)
                .state(state)
                .memberCount(memberCount)
                .topics(topics)
                .totalLag(totalLag)
                .build();
    }

    /**
     * 查询指定 group 在指定 topic 上的消费进度与 Lag。
     *
     * <p>基于 {@link #describeAll} 按 topic 过滤:
     * 该 topic 无已提交 offset 时返回 partitions=[]、totalLag=0、state 照常。
     */
    public ConsumerGroupDetail describe(ClusterConnection connection, String group, String topic)
            throws ExecutionException, InterruptedException {
        ConsumerGroupOverview all = describeAll(connection, group, topic);
        for (ConsumerGroupOverview.TopicProgress tp : all.getTopics()) {
            if (tp.getTopic().equals(topic)) {
                return ConsumerGroupDetail.builder()
                        .group(group)
                        .topic(topic)
                        .state(all.getState())
                        .partitions(tp.getPartitions())
                        .totalLag(tp.getTotalLag())
                        .build();
            }
        }
        return ConsumerGroupDetail.builder()
                .group(group)
                .topic(topic)
                .state(all.getState())
                .partitions(Collections.emptyList())
                .totalLag(0)
                .build();
    }

    private enum ResetStrategy {
        EARLIEST, LATEST, TO_OFFSET, TO_TIMESTAMP;

        static ResetStrategy of(String s) {
            try {
                return valueOf(s);
            } catch (IllegalArgumentException e) {
                // @Pattern 已保证可达性,此处仅作防御
                throw new IllegalArgumentException("Unsupported strategy: " + s);
            }
        }
    }

    /**
     * 策略解析:给定策略与分区列表,返回各分区目标 offset。
     *
     * <p>三种需要查 Kafka 的策略都走 AdminClient 的 {@code listOffsets},
     * resetOffsets 全链路零池占用。
     */
    private Map<TopicPartition, Long> resolveOffsets(ClusterConnection connection, ResetStrategy strategy,
            List<TopicPartition> tps, ResetOffsetsRequest req)
            throws ExecutionException, InterruptedException {
        if (strategy == ResetStrategy.TO_OFFSET) {
            // 纯内存构造,不碰 Kafka
            return tps.stream()
                    .collect(Collectors.toMap(tp -> tp, tp -> req.getOffset(),
                            (a, b) -> a, LinkedHashMap::new));
        }

        AdminClient admin = connection.getAdminClient();
        Map<TopicPartition, OffsetSpec> specs = new LinkedHashMap<>();
        for (TopicPartition tp : tps) {
            specs.put(tp, specFor(strategy, req));
        }
        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> infos = KafkaFutures.await(
                admin.listOffsets(specs).all(), timeoutMs,
                "listOffsets." + strategy.name().toLowerCase() + ":" + req.getTopic());

        // 按 tps 顺序填充,保证 ResetOffsetsResult.reset 的输出顺序不变。
        // info.offset() < 0 表示无命中(时间戳晚于所有消息),跳过即可 —— 这不是放宽
        // EARLIEST/LATEST 的语义:它们对空分区也返回 0,不会是负数。
        Map<TopicPartition, Long> resolved = new LinkedHashMap<>();
        for (TopicPartition tp : tps) {
            ListOffsetsResult.ListOffsetsResultInfo info = infos.get(tp);
            if (info == null || info.offset() < 0) {
                continue;
            }
            resolved.put(tp, info.offset());
        }
        return resolved;
    }

    /** TO_OFFSET 在 {@link #resolveOffsets} 中提前返回,不会走到此处。 */
    private OffsetSpec specFor(ResetStrategy strategy, ResetOffsetsRequest req) {
        return switch (strategy) {
            case EARLIEST -> OffsetSpec.earliest();
            case LATEST -> OffsetSpec.latest();
            case TO_TIMESTAMP -> OffsetSpec.forTimestamp(req.getTimestamp());
            case TO_OFFSET -> throw new IllegalStateException("TO_OFFSET is resolved in memory");
        };
    }

    /**
     * 重置消费组已提交 offset。
     *
     * <p>支持四种策略:EARLIEST / LATEST / TO_OFFSET / TO_TIMESTAMP。
     * <b>仅允许 Empty 状态(无活跃成员)执行</b> —— 防止活跃成员的自动提交覆盖重置结果。
     *
     * <p>不改组名列表,故不需要失效 groupNames 缓存。
     *
     * @param req 请求体(strategy=TO_OFFSET 必带 offset;TO_TIMESTAMP 必带 timestamp)
     * @return 实际写入的 partition → offset 列表;TO_TIMESTAMP 无命中分区被跳过;
     *         全部分区均无命中时返回 reset=[],resetCount=0,且不产生任何 Kafka 写入
     */
    public ResetOffsetsResult resetOffsets(ClusterConnection connection, String group, ResetOffsetsRequest req)
            throws ExecutionException, InterruptedException {
        AdminClient admin = connection.getAdminClient();

        // 存在性 + 状态校验(复用 describeGroupDesc,含 DEAD → 40402)
        ConsumerGroupDescription groupDesc = describeGroupDesc(connection, group);
        if (groupDesc.state() != ConsumerGroupState.EMPTY) {
            throw new GroupActiveException(group, groupDesc.state());
        }

        String topic = req.getTopic();
        int total = TopicAdminSupport.describeTopicOrThrow(admin, topic, timeoutMs).partitions().size();

        List<Integer> targets = (req.getPartitions() == null || req.getPartitions().isEmpty())
                ? IntStream.range(0, total).boxed().toList()
                : req.getPartitions();
        for (Integer p : targets) {
            // 越界分区不会被子 alterConsumerGroupOffsets 友好拒绝,必须自行校验
            if (p == null || p < 0 || p >= total) {
                throw new IllegalArgumentException("Partition does not exist: " + p);
            }
        }

        List<TopicPartition> tps = targets.stream().map(p -> new TopicPartition(topic, p)).toList();
        Map<TopicPartition, Long> resolved = resolveOffsets(
                connection, ResetStrategy.of(req.getStrategy()), tps, req);

        // 全无命中:不调用 Kafka,直接返回空结果(resetCount=0)
        if (resolved.isEmpty()) {
            return ResetOffsetsResult.builder()
                    .group(group).topic(topic)
                    .reset(Collections.emptyList())
                    .resetCount(0)
                    .build();
        }

        Map<TopicPartition, OffsetAndMetadata> toCommit = new LinkedHashMap<>();
        resolved.forEach((tp, off) -> toCommit.put(tp, new OffsetAndMetadata(off)));
        KafkaFutures.await(admin.alterConsumerGroupOffsets(group, toCommit).all(), timeoutMs,
                "alterConsumerGroupOffsets:" + group);

        List<ResetOffsetsResult.PartitionReset> list = resolved.entrySet().stream()
                .map(e -> ResetOffsetsResult.PartitionReset.builder()
                        .partition(e.getKey().partition())
                        .offset(e.getValue())
                        .build())
                .toList();

        return ResetOffsetsResult.builder().group(group).topic(topic).reset(list)
                .resetCount(list.size()).build();
    }

    /**
     * 删除消费组(仅 Empty 状态;活跃成员由 Kafka 拒绝删除,预检查给出更清晰的 409)。
     *
     * <p>组名列表变更,删除后失效 groupNames 缓存,保证列表页立即看不到该组。
     */
    @CacheEvict(value = CacheConfig.GROUP_NAMES, allEntries = true)
    public GroupDeleteResult deleteGroup(ClusterConnection connection, String group)
            throws ExecutionException, InterruptedException {
        ConsumerGroupDescription desc = describeGroupDesc(connection, group);
        if (desc.state() != ConsumerGroupState.EMPTY) {
            throw new GroupActiveException(group, desc.state());
        }
        KafkaFutures.await(connection.getAdminClient().deleteConsumerGroups(List.of(group)).all(),
                timeoutMs, "deleteConsumerGroups:" + group);
        return GroupDeleteResult.builder().group(group).deleted(true).build();
    }

    /**
     * 查询消费组描述并做存在性校验,供 {@link #describeAll} 及 offset 重置 / 删组复用。
     *
     * <p>DescribeGroups 协议对不存在的 group 不返回错误,而是返回 State=DEAD 的空描述
     * (错误码 NONE);GroupIdNotFoundException 分支通常也不会触发,但保留兜底。
     * DEAD 状态(组元数据已被移除,等价于组不存在)统一映射为 40402。
     */
    private ConsumerGroupDescription describeGroupDesc(ClusterConnection connection, String group)
            throws ExecutionException, InterruptedException {
        AdminClient admin = connection.getAdminClient();
        try {
            ConsumerGroupDescription desc = KafkaFutures.await(
                    admin.describeConsumerGroups(List.of(group)).describedGroups().get(group),
                    timeoutMs, "describeConsumerGroups:" + group);
            if (desc.state() == ConsumerGroupState.DEAD) {
                throw new ConsumerGroupNotFoundException(group);
            }
            return desc;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof org.apache.kafka.common.errors.GroupIdNotFoundException) {
                throw new ConsumerGroupNotFoundException(group);
            }
            throw e;
        }
    }
}
