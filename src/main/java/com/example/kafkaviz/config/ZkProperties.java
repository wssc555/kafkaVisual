package com.example.kafkaviz.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * ZooKeeper 连接配置。
 *
 * <p>双模支持:当 {@code connectString} 为空时,本工具运行在 KRaft 模式,
 * 不会创建 {@link org.apache.curator.framework.CuratorFramework} Bean,
 * ZK 相关的 {@code /api/zk/**} 端点也不会注册;业务功能(Cluster/Topic/
 * ConsumerGroup/Message)全部通过 {@link org.apache.kafka.clients.admin.AdminClient}
 * 与集群通信,与 ZK 无关,在两种模式下都正常工作。
 *
 * <p>当 {@code connectString} 非空时,本工具同时连接 Kafka 和 ZK,运行在 ZK 模式,
 * 暴露 ZK 节点浏览器功能。
 */
@Getter
@Setter
@Validated
@ConfigurationProperties("zk")
public class ZkProperties {

    /**
     * ZK 连接地址。空字符串(默认)表示 KRaft 模式,不连接 ZK。
     * 仅当需要 ZK 节点浏览器功能时配置(对应 Kafka &lt; 4.0 的 ZK 模式集群)。
     */
    private String connectString = "";

    private int sessionTimeoutMs = 30000;
    private int connectionTimeoutMs = 10000;

    /**
     * 递归列出时的最大展开深度(根节点深度为 0,直接子节点深度 1)。
     * 默认 5 覆盖 /brokers/topics/&lt;topic&gt;/partitions/&lt;p&gt;/state 这类常见路径。
     */
    private int maxRecursiveDepth = 5;

    /** 递归列出时的最大返回节点数,超出即截断。 */
    private int maxRecursiveNodes = 2000;

    /**
     * 递归列出时同层 stat 的并发度。
     *
     * <p>同一个 ZK session 的在途请求数必须有上界,否则客户端缓冲膨胀、session 超时
     * 风险上升;16 是保守值,跨机房带宽紧张时可调到 8。
     */
    private int parallelStatFetches = 16;

    /**
     * 是否启用 ZK 模式(即是否配置了 connect-string)。
     */
    public boolean isZkModeEnabled() {
        return connectString != null && !connectString.isBlank();
    }
}