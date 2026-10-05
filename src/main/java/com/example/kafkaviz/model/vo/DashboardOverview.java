package com.example.kafkaviz.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 集群健康概览聚合结果,供 Dashboard 首页渲染。
 *
 * <p>一次调用聚合集群规模、副本健康、日志体量与消费组数量;
 * 所有数据来自 {@link org.apache.kafka.clients.admin.AdminClient},双模式可用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardOverview {

    private String clusterId;

    /** 集群运行模式:ZOOKEEPER / KRAFT。 */
    private String mode;

    private int controllerId;

    private int brokerCount;

    /** 业务 Topic 数(过滤 {@code _} 前缀,与 GET /topics 口径一致)。 */
    private int topicCount;

    /** 内部 Topic 数(_ 前缀),如 __consumer_offsets。 */
    private long internalTopicCount;

    /** 业务 Topic 的分区总数。 */
    private int partitionCount;

    /** ISR 收缩分区数(isr.size &lt; replicas.size)。 */
    private int underReplicatedPartitions;

    /** 无 Leader 分区数(leader.id() == -1)。 */
    private int offlinePartitions;

    /**
     * 业务 Topic 的全部副本日志字节总和(过滤 {@code _} 前缀内部 topic,
     * 含 follower 副本,非"去重后"的逻辑大小)。
     */
    private long totalLogSizeBytes;

    private int consumerGroupCount;
}