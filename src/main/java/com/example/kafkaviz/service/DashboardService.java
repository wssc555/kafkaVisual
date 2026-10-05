package com.example.kafkaviz.service;

import com.example.kafkaviz.archive.ClusterArchiveService;
import com.example.kafkaviz.config.KafkaProperties;
import com.example.kafkaviz.exception.ServiceUnavailableException;
import com.example.kafkaviz.kafka.ClusterConnection;
import com.example.kafkaviz.kafka.ClusterConnectionManager;
import com.example.kafkaviz.kafka.ClusterDefinition;
import com.example.kafkaviz.kafka.KafkaFutures;
import com.example.kafkaviz.model.vo.ClusterInfo;
import com.example.kafkaviz.model.vo.ClusterMetadata;
import com.example.kafkaviz.model.vo.DashboardOverview;
import com.example.kafkaviz.model.vo.MultiClusterDashboard;
import com.example.kafkaviz.storage.ClusterConfigStore;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.TopicPartitionInfo;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * 单集群健康概览聚合。
 *
 * <p>纯 {@link AdminClient} 聚合,ZK 与 KRaft 双模式行为一致。任一 Kafka 调用失败整体 500,
 * 不做部分降级 —— <b>部分降级是跨集群聚合({@link #getMultiOverview()})的事</b>,
 * 那里失败只影响一张卡片;单集群视图失败就该如实报错。
 *
 * <p>三路互不依赖的调用先并行发起,依赖就绪立刻续发后续调用,
 * 墙钟时间取决于最长依赖链而非各段之和。
 */
@Service
public class DashboardService {

    /** 复用 clusterId/controller/brokers + mode,保证口径与现有端点一致。 */
    private final ClusterService clusterService;
    /** 复用 describeLogDirs(brokerIds)(不再内部重复 describeCluster)。 */
    private final ClusterMetadataService metadataService;
    private final ExecutorService kafkaOpsExecutor;
    /** Admin 调用的阻塞上限,见 kafka.admin.default-api-timeout-ms。 */
    private final long timeoutMs;
    /** 多集群聚合:已建连快照(只含 CONNECTED,不触发建连)。 */
    private final ClusterConnectionManager connectionManager;
    /** 多集群聚合:集群定义列表(含离线集群)。 */
    private final ClusterConfigStore clusterConfigStore;
    /** 多集群聚合:离线集群的本地归档条数。 */
    private final ClusterArchiveService archiveService;

    public DashboardService(ClusterService clusterService,
                            ClusterMetadataService metadataService,
                            ExecutorService kafkaOpsExecutor,
                            KafkaProperties kafkaProperties,
                            ClusterConnectionManager connectionManager,
                            ClusterConfigStore clusterConfigStore,
                            ClusterArchiveService archiveService) {
        this.clusterService = clusterService;
        this.metadataService = metadataService;
        this.kafkaOpsExecutor = kafkaOpsExecutor;
        this.timeoutMs = kafkaProperties.getAdmin().getDefaultApiTimeoutMs();
        this.connectionManager = connectionManager;
        this.clusterConfigStore = clusterConfigStore;
        this.archiveService = archiveService;
    }

    public DashboardOverview getOverview(ClusterConnection connection) throws ExecutionException, InterruptedException {
        AdminClient admin = connection.getAdminClient();

        // 1) 三路互不依赖的调用并行发起。AdminClient 线程安全,并发不会额外建连。
        CompletableFuture<ClusterInfo> clusterFuture = submit(() -> clusterService.describeCluster(connection));
        CompletableFuture<Set<String>> topicsFuture = submit(
                () -> KafkaFutures.await(admin.listTopics().names(), timeoutMs, "listTopics"));
        CompletableFuture<Integer> groupCountFuture = submit(
                () -> KafkaFutures.await(admin.listConsumerGroups().all(), timeoutMs, "listConsumerGroups").size());

        // 2) logDirs 依赖 broker 列表:cluster 一回来就续发,与 topics/groups 重叠执行。
        //    复用已拿到的 brokerIds,消除原先 describeLogDirs 内部的第二次 describeCluster。
        ClusterInfo info = KafkaFutures.join(clusterFuture);
        List<Integer> brokerIds = info.getBrokers().stream()
                .map(ClusterInfo.BrokerInfo::getId)
                .sorted()
                .toList();
        CompletableFuture<List<ClusterMetadata.LogDirInfo>> logDirsFuture =
                submit(() -> metadataService.describeLogDirs(connection, brokerIds));

        // 3) describeTopics 依赖 topic 名称集合
        Set<String> all = KafkaFutures.join(topicsFuture);
        long internalCount = all.stream().filter(t -> t.startsWith("_")).count();
        List<String> business = all.stream().filter(t -> !t.startsWith("_")).sorted().toList();
        CompletableFuture<Map<String, TopicDescription>> descsFuture =
                submit(() -> describeBusinessTopics(admin, business));

        // 4) 逐个 join 收取结果(不用 allOf(...).join():那会把异常包成 CompletionException,
        //    绕过 KafkaFutures.join 的异常还原,40401/40901/40004 映射会退化成 50000)
        int groupCount = KafkaFutures.join(groupCountFuture);
        List<ClusterMetadata.LogDirInfo> logDirs = KafkaFutures.join(logDirsFuture);
        Map<String, TopicDescription> descs = KafkaFutures.join(descsFuture);

        int partitionCount = 0;
        int underReplicated = 0;
        int offline = 0;
        for (TopicDescription td : descs.values()) {
            for (TopicPartitionInfo p : td.partitions()) {
                partitionCount++;
                if (p.isr().size() < p.replicas().size()) {
                    underReplicated++;
                }
                // 分区无 leader 时 leader 可能为 null,必须先判 null 再取 id
                if (p.leader() == null || p.leader().id() < 0) {
                    offline++;
                }
            }
        }

        long totalLogSizeBytes = 0;
        for (ClusterMetadata.LogDirInfo dir : logDirs) {
            if (dir.getPartitions() == null) {
                continue;
            }
            for (ClusterMetadata.PartitionLogInfo p : dir.getPartitions()) {
                if (p.isFuture()) {
                    continue;
                }
                // 与 topicCount / partitionCount 口径一致:排除 _ 前缀内部 topic
                if (p.getTopic() != null && p.getTopic().startsWith("_")) {
                    continue;
                }
                totalLogSizeBytes += p.getSize();
            }
        }

        // mode 是纯内存判断(读连接的 zkAvailable),不进线程池
        String mode = clusterService.getClusterMode(connection).getMode().name();

        return DashboardOverview.builder()
                .clusterId(info.getClusterId())
                .mode(mode)
                .controllerId(info.getControllerId())
                .brokerCount(info.getBrokers().size())
                .topicCount(business.size())
                .internalTopicCount(internalCount)
                .partitionCount(partitionCount)
                .underReplicatedPartitions(underReplicated)
                .offlinePartitions(offline)
                .totalLogSizeBytes(totalLogSizeBytes)
                .consumerGroupCount(groupCount)
                .build();
    }

    /** business 为空时不发 describeTopics(空列表调 describeTopics 在部分版本会抛异常)。 */
    private Map<String, TopicDescription> describeBusinessTopics(AdminClient admin, List<String> business)
            throws ExecutionException, InterruptedException {
        if (business.isEmpty()) {
            return Map.of();
        }
        return KafkaFutures.await(admin.describeTopics(business).all(), timeoutMs, "describeTopics");
    }

    /** 提交到专用线程池;池拒绝(AbortPolicy)时转 50302,而非退回兜底的 50000。 */
    private <T> CompletableFuture<T> submit(KafkaFutures.CheckedSupplier<T> task) {
        try {
            return KafkaFutures.supply(task, kafkaOpsExecutor);
        } catch (RejectedExecutionException e) {
            throw new ServiceUnavailableException("Kafka ops executor overloaded, please retry later", e);
        }
    }

    // ---------------------------------------------------------------
    // 跨集群聚合
    // ---------------------------------------------------------------

    /**
     * 多集群卡片总览:{@code GET /api/dashboard/multi-overview}(不带集群段)。
     *
     * <p>聚合策略:
     * <ul>
     *   <li>以 {@code ClusterConfigStore.list()} 为准遍历<b>全部</b>集群(含离线/连接中);</li>
     *   <li>在线集群({@link ClusterConnectionManager#listConnected()} 只含 CONNECTED,
     *       <b>不触发建连</b> —— 否则一次 Dashboard 轮询会把所有死集群都探一遍,
     *       每个白等一次 10s 超时)并行复用单集群 {@link #getOverview(ClusterConnection)};</li>
     *   <li>per-cluster try/catch:单集群指标失败只毁自己那张卡片,转 errorSummary;</li>
     *   <li>离线集群查本地归档条数({@code archive_enabled} 才查),COUNT 失败容错为 -1,
     *       与 Kafka 连接无关(离线可用)。</li>
     * </ul>
     *
     * <p>零集群返回空数组(前端渲染"添加集群"引导)。卡片顺序 = 配置列表顺序
     * (store 按 sort_order 返回),稳定可预期。
     */
    public List<MultiClusterDashboard> getMultiOverview() {
        List<ClusterDefinition> definitions = clusterConfigStore.list();
        if (definitions.isEmpty()) {
            return List.of();
        }

        Map<Long, ClusterConnection> connected = new HashMap<>();
        for (ClusterConnection conn : connectionManager.listConnected()) {
            connected.put(conn.clusterId(), conn);
        }

        // 每集群一个任务并行构建;buildCard 自吞一切异常(future 永不异常完成),
        // 因此这里的 join 只是收序,不会把 50000 泄给全局
        List<CompletableFuture<MultiClusterDashboard>> futures = new ArrayList<>(definitions.size());
        for (ClusterDefinition definition : definitions) {
            ClusterConnection conn = connected.get(definition.id());
            try {
                futures.add(submit(() -> buildCard(definition, conn)));
            } catch (ServiceUnavailableException e) {
                // 单集群的 buildCard 本就自吞一切异常,这里捕获的是 submit() 自身的
                // RejectedExecutionException(线程池拒绝)。单集群调度失败不能拖垮全局:
                // 该卡片置 errorSummary,其余集群照常并行。
                futures.add(CompletableFuture.completedFuture(
                        errorCard(definition, "OFFLINE", "Kafka ops executor overloaded: " + e.getMessage())));
            }
        }

        List<MultiClusterDashboard> out = new ArrayList<>(definitions.size());
        for (CompletableFuture<MultiClusterDashboard> future : futures) {
            out.add(future.join());
        }
        return out;
    }

    /** 单卡片构建:任何异常都收敛为该卡片的 errorSummary,绝不上抛。 */
    private MultiClusterDashboard buildCard(ClusterDefinition definition, ClusterConnection connection) {
        if (connection != null) {
            try {
                return MultiClusterDashboard.builder()
                        .clusterId(definition.id())
                        .name(definition.name())
                        .displayState("ONLINE")
                        .overview(getOverview(connection))
                        .build();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return errorCard(definition, "ONLINE", "Overview interrupted: " + e.getMessage());
            } catch (Exception e) {
                return errorCard(definition, "ONLINE", e.getMessage());
            }
        }

        // 离线 / 连接中:本地归档量照常可查(外部 PG/MySQL 时以数据库可达为前提)
        Long archived = null;
        if (definition.archiveEnabled()) {
            try {
                archived = archiveService.countMessages(definition.id());
            } catch (Exception e) {
                archived = -1L;
            }
        }
        ClusterConnectionManager.ManagedStatus status = connectionManager.statusOf(definition.id());
        return MultiClusterDashboard.builder()
                .clusterId(definition.id())
                .name(definition.name())
                .displayState(status == null ? "OFFLINE" : status.displayState())
                .archivedMessages(archived)
                .errorSummary(status == null ? null : status.errorSummary())
                .build();
    }

    /** 在线但指标聚合失败:overview 置空,摘要进卡片(displayState 仍按连接真实状态)。 */
    private MultiClusterDashboard errorCard(ClusterDefinition definition, String displayState, String summary) {
        return MultiClusterDashboard.builder()
                .clusterId(definition.id())
                .name(definition.name())
                .displayState(displayState)
                .errorSummary(summary)
                .build();
    }
}
