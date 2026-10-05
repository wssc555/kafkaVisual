package com.example.kafkaviz.service;

import com.example.kafkaviz.config.KafkaProperties;
import com.example.kafkaviz.exception.ServiceUnavailableException;
import com.example.kafkaviz.kafka.ClusterConnection;
import com.example.kafkaviz.kafka.KafkaFutures;
import com.example.kafkaviz.kafka.TopicAdminSupport;
import com.example.kafkaviz.model.vo.ClusterMetadata;
import com.example.kafkaviz.model.vo.ConfigsUpdateResult;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.DescribeAclsResult;
import org.apache.kafka.clients.admin.DescribeConfigsResult;
import org.apache.kafka.clients.admin.DescribeLogDirsResult;
import org.apache.kafka.clients.admin.LogDirDescription;
import org.apache.kafka.clients.admin.ReplicaInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.acl.AclBinding;
import org.apache.kafka.common.acl.AclBindingFilter;
import org.apache.kafka.common.config.ConfigResource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * 基于 {@link AdminClient} 的集群元数据查询。
 *
 * <p>本服务提供的所有方法在 ZK 模式与 KRaft 模式下都可用 —— 这正是 AdminClient
 * 的设计契约:对客户端透明地隐藏后端元数据存储(旧版 ZK znodes 或新版 KRaft
 * 内部 topic)。
 */
@Service
public class ClusterMetadataService {

    /** Admin 调用的阻塞上限,见 kafka.admin.default-api-timeout-ms。 */
    private final long timeoutMs;

    public ClusterMetadataService(KafkaProperties kafkaProperties) {
        this.timeoutMs = kafkaProperties.getAdmin().getDefaultApiTimeoutMs();
    }

    /**
     * 获取指定 broker 的配置项(合并静态默认与动态覆盖后的最终值)。
     */
    public ClusterMetadata.BrokerConfig describeBrokerConfigs(ClusterConnection connection, int brokerId)
            throws ExecutionException, InterruptedException {
        AdminClient admin = connection.getAdminClient();
        ConfigResource cr = new ConfigResource(ConfigResource.Type.BROKER, String.valueOf(brokerId));
        DescribeConfigsResult result = admin.describeConfigs(List.of(cr));
        Config config = KafkaFutures.await(result.all(), timeoutMs,
                "describeConfigs.broker:" + brokerId).get(cr);
        Map<String, String> configs = new TreeMap<>();
        if (config != null) {
            for (ConfigEntry e : config.entries()) {
                configs.put(e.name(), e.value());
            }
        }
        return ClusterMetadata.BrokerConfig.builder()
                .brokerId(brokerId)
                .configs(configs)
                .build();
    }

    /**
     * 获取指定 topic 的配置项。
     */
    public ClusterMetadata.TopicConfig describeTopicConfigs(ClusterConnection connection, String topic)
            throws ExecutionException, InterruptedException {
        AdminClient admin = connection.getAdminClient();
        ConfigResource cr = new ConfigResource(ConfigResource.Type.TOPIC, topic);
        DescribeConfigsResult result = admin.describeConfigs(List.of(cr));
        Config config = KafkaFutures.await(result.all(), timeoutMs,
                "describeConfigs.topic:" + topic).get(cr);
        Map<String, String> configs = new TreeMap<>();
        if (config != null) {
            for (ConfigEntry e : config.entries()) {
                configs.put(e.name(), e.value());
            }
        }
        return ClusterMetadata.TopicConfig.builder()
                .topic(topic)
                .configs(configs)
                .build();
    }

    /**
     * 列出该集群上的所有 ACL 规则。
     *
     * <p>无 ACL 授权的集群(或返回 {@code SecurityDisabledException})时返回空列表,
     * 不向上抛 —— 元数据浏览器作为只读视图,空 ACL 是合法状态。
     */
    public List<ClusterMetadata.AclInfo> describeAcls(ClusterConnection connection) {
        AdminClient admin = connection.getAdminClient();
        try {
            DescribeAclsResult result = admin.describeAcls(AclBindingFilter.ANY);
            Collection<AclBinding> bindings = KafkaFutures.await(result.values(), timeoutMs, "describeAcls");
            if (bindings == null) {
                return List.of();
            }
            return bindings.stream()
                    .map(b -> ClusterMetadata.AclInfo.builder()
                            .principal(b.entry().principal())
                            .host(b.entry().host())
                            .operation(b.entry().operation().name())
                            .permissionType(b.entry().permissionType().name())
                            .resourceType(b.pattern().resourceType().name())
                            .resourceName(b.pattern().name())
                            .build())
                    .collect(Collectors.toList());
        } catch (ServiceUnavailableException e) {
            // 超时/集群不可用不能伪装成"空 ACL":必须先于下面的宽泛 catch 抛出,
            // 否则集群卡顿时 ACL 页面会静默变成空列表而不是报 50302。
            throw e;
        } catch (Exception e) {
            // 未启用 ACL(SecurityDisabledException)或其他授权错误 —— 视为空 ACL,不阻塞 UI
            return List.of();
        }
    }

    /**
     * 在线增量修改 topic 配置。
     *
     * <p>仅更新请求中出现的键,未提及的键保持不变;value 为 null 表示删除该
     * 配置覆盖项(恢复 broker 默认值)。与 {@link #describeTopicConfigs} 构成读写闭环。
     *
     * @throws org.apache.kafka.common.errors.InvalidConfigurationException 配置键值非法
     */
    public ConfigsUpdateResult updateTopicConfigs(ClusterConnection connection, String topic,
                                                  Map<String, String> configs)
            throws ExecutionException, InterruptedException {
        AdminClient admin = connection.getAdminClient();
        // 存在性校验走 describeTopics(与 TopicService 同一收敛口径)
        TopicAdminSupport.assertTopicExists(admin, topic, timeoutMs);

        ConfigResource cr = new ConfigResource(ConfigResource.Type.TOPIC, topic);
        List<AlterConfigOp> ops = configs.entrySet().stream()
                .map(e -> e.getValue() == null
                        ? new AlterConfigOp(new ConfigEntry(e.getKey(), null), AlterConfigOp.OpType.DELETE)
                        : new AlterConfigOp(new ConfigEntry(e.getKey(), e.getValue()), AlterConfigOp.OpType.SET))
                .toList();

        // incrementalAlterConfigs 是增量语义:只改请求里出现的键,不覆盖未提及的键
        KafkaFutures.await(admin.incrementalAlterConfigs(Map.of(cr, ops)).all(), timeoutMs,
                "incrementalAlterConfigs.topic:" + topic);
        return ConfigsUpdateResult.builder().topic(topic).updated(true).build();
    }

    /**
     * 解析查询用的 broker id 列表:显式指定时只查该 broker,否则查全部。
     *
     * <p>注意:kafka-clients 3.9 的 {@code describeLogDirs(空列表)} 不会查询任何
     * broker —— KafkaAdminClient 只是遍历传入的 brokerIds,空列表直接返回空结果
     * (与 KIP-113 文档"空列表=所有 broker"的语义不一致,以实际实现为准)。
     * 因此必须先 {@code describeCluster()} 拿到全部 broker id,再显式传入。
     */
    public List<Integer> listBrokerIds(ClusterConnection connection, Integer brokerId)
            throws ExecutionException, InterruptedException {
        if (brokerId != null) {
            return List.of(brokerId);
        }
        return KafkaFutures.await(connection.getAdminClient().describeCluster().nodes(),
                        timeoutMs, "describeCluster.nodes")
                .stream()
                .map(org.apache.kafka.common.Node::id)
                .sorted()
                .toList();
    }

    /**
     * 列出该集群所有 broker 的日志目录状态(包括每个 broker 上各 partition 副本的字节数)。
     */
    public List<ClusterMetadata.LogDirInfo> describeLogDirs(ClusterConnection connection)
            throws ExecutionException, InterruptedException {
        return describeLogDirs(connection, listBrokerIds(connection, null), null);
    }

    /**
     * 调用方已知 brokerIds 时使用,避免内部重复 {@code describeCluster}
     * (Dashboard 已拿过 broker 列表,是主要受益方)。
     */
    public List<ClusterMetadata.LogDirInfo> describeLogDirs(ClusterConnection connection,
                                                            Collection<Integer> brokerIds)
            throws ExecutionException, InterruptedException {
        return describeLogDirs(connection, brokerIds, null);
    }

    /**
     * 列出指定 broker 的日志目录明细。
     *
     * <p>说明:{@code topicFilter} 只能缩小响应体与 VO 构造量,<b>不能</b>减少
     * broker→客户端的网络传输 —— AdminClient 没有服务端过滤/聚合 API,broker 总是
     * 返回完整副本表。真正减少传输量只能靠按 brokerId 过滤或缓存。
     *
     * @param topicFilter 非空时只返回该 topic 的副本行;null/空白则不过滤
     */
    public List<ClusterMetadata.LogDirInfo> describeLogDirs(ClusterConnection connection,
                                                            Collection<Integer> brokerIds, String topicFilter)
            throws ExecutionException, InterruptedException {
        Map<Integer, Map<String, LogDirDescription>> all = fetchLogDirs(connection, brokerIds);
        boolean filterByTopic = topicFilter != null && !topicFilter.isBlank();
        List<ClusterMetadata.LogDirInfo> list = new ArrayList<>();
        for (Map.Entry<Integer, Map<String, LogDirDescription>> e : all.entrySet()) {
            List<ClusterMetadata.PartitionLogInfo> partitions = new ArrayList<>();
            String error = null;
            for (Map.Entry<String, LogDirDescription> dir : e.getValue().entrySet()) {
                LogDirDescription desc = dir.getValue();
                if (desc.error() != null) {
                    error = desc.error().getMessage();
                }
                Map<TopicPartition, ReplicaInfo> replicas = desc.replicaInfos();
                if (replicas == null) {
                    continue;
                }
                for (Map.Entry<TopicPartition, ReplicaInfo> ri : replicas.entrySet()) {
                    if (filterByTopic && !topicFilter.equals(ri.getKey().topic())) {
                        continue;
                    }
                    partitions.add(ClusterMetadata.PartitionLogInfo.builder()
                            .topic(ri.getKey().topic())
                            .partition(ri.getKey().partition())
                            .size(ri.getValue().size())
                            .brokerId(e.getKey())
                            .future(ri.getValue().isFuture())
                            .build());
                }
            }
            list.add(ClusterMetadata.LogDirInfo.builder()
                    .brokerId(e.getKey())
                    .error(error)
                    .partitions(partitions)
                    .build());
        }
        return list;
    }

    /**
     * 按 (brokerId, logDir) 聚合的汇总视图:只返回该 log dir 的总字节数与分区数,
     * 不构造逐分区 VO。大集群下响应体可从 MB 级降到 KB 级。
     *
     * <p>口径与 Dashboard 的 {@code totalLogSizeBytes} 一致:跳过 future 副本;
     * {@code includeInternal=false}(默认)时排除 {@code _} 前缀的内部 topic。
     * 单个 log dir 出错时仍返回该条目并透出 error,此时两个计数为 0。
     */
    public List<ClusterMetadata.LogDirSummary> describeLogDirsSummary(ClusterConnection connection,
                                                                      Collection<Integer> brokerIds,
                                                                      boolean includeInternal)
            throws ExecutionException, InterruptedException {
        Map<Integer, Map<String, LogDirDescription>> all = fetchLogDirs(connection, brokerIds);
        List<ClusterMetadata.LogDirSummary> list = new ArrayList<>();
        for (Map.Entry<Integer, Map<String, LogDirDescription>> e : all.entrySet()) {
            for (Map.Entry<String, LogDirDescription> dir : e.getValue().entrySet()) {
                LogDirDescription desc = dir.getValue();
                long totalSize = 0;
                int partitionCount = 0;
                Map<TopicPartition, ReplicaInfo> replicas = desc.replicaInfos();
                if (replicas != null) {
                    for (Map.Entry<TopicPartition, ReplicaInfo> ri : replicas.entrySet()) {
                        if (ri.getValue().isFuture()) {
                            continue;
                        }
                        if (!includeInternal && ri.getKey().topic().startsWith("_")) {
                            continue;
                        }
                        totalSize += ri.getValue().size();
                        partitionCount++;
                    }
                }
                list.add(ClusterMetadata.LogDirSummary.builder()
                        .brokerId(e.getKey())
                        .logDir(dir.getKey())
                        .totalSize(totalSize)
                        .partitionCount(partitionCount)
                        .error(desc.error() == null ? null : desc.error().getMessage())
                        .build());
            }
        }
        return list;
    }

    /** 一次 describeLogDirs 调用,供明细与汇总两条路径复用。 */
    private Map<Integer, Map<String, LogDirDescription>> fetchLogDirs(ClusterConnection connection,
                                                                     Collection<Integer> brokerIds)
            throws ExecutionException, InterruptedException {
        DescribeLogDirsResult result = connection.getAdminClient().describeLogDirs(brokerIds);
        return KafkaFutures.await(result.allDescriptions(), timeoutMs, "describeLogDirs");
    }
}
