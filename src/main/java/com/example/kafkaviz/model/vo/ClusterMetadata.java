package com.example.kafkaviz.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * KRaft 模式下的集群元数据 VO 集合,作为 ZK 节点浏览器在 KRaft 模式的等价替代。
 *
 * <p>所有数据均来自 {@link org.apache.kafka.clients.admin.AdminClient},在 ZK 与 KRaft
 * 两种集群模式下都可用 — AdminClient 的设计契约即对客户端透明地兼容两种后端。
 */
public class ClusterMetadata {

    private ClusterMetadata() {
        // 工具类,只容纳嵌套 VO
    }

    /** 单个 Broker 的配置项快照。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BrokerConfig {
        private int brokerId;
        /** key=配置名,value=配置值(包含静态默认与动态覆盖合并后的最终值)。 */
        private Map<String, String> configs;
    }

    /** 单个 Topic 的配置项快照。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopicConfig {
        private String topic;
        private Map<String, String> configs;
    }

    /** 一条 ACL 规则的可读表示。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AclInfo {
        private String principal;       // 用户/服务名,如 User:alice
        private String host;            // 主机,如 * 或 1.2.3.4
        private String operation;      // READ / WRITE / CREATE / ...
        private String permissionType; // ALLOW / DENY
        private String resourceType;   // TOPIC / GROUP / CLUSTER / ...
        private String resourceName;   // 资源名,如 my-topic;通配为字面值
    }

    /** 单个 broker 的日志目录视图。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LogDirInfo {
        private int brokerId;
        /** 错误信息,null 表示该 broker 的 log dir 正常。 */
        private String error;
        /** 该 broker 上所有副本的日志信息(按 topic/partition 列出)。 */
        private List<PartitionLogInfo> partitions;
    }

    /** 单个 partition 副本在某个 broker log dir 上的状态。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PartitionLogInfo {
        private String topic;
        private int partition;
        /** 该副本的日志字节数。 */
        private long size;
        private int brokerId;
        /** 是否为 future 副本(正在从 leader 迁移中的目标副本)。 */
        private boolean future;
    }

    /**
     * 单个 broker 上单个 log dir 的汇总视图(不含逐分区明细)。
     *
     * <p>大集群下逐分区明细可达上万行、JSON 数十 MB;默认视图只给汇总,
     * 需要明细时再按 broker / topic 过滤拉取。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LogDirSummary {
        private int brokerId;
        /** log dir 绝对路径。 */
        private String logDir;
        /** 该 log dir 下副本总字节数(跳过 future 副本)。 */
        private long totalSize;
        /** 该 log dir 下的分区副本数。 */
        private int partitionCount;
        /** 该 log dir 的错误信息;null 表示正常。 */
        private String error;
    }
}
