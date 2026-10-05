package com.example.kafkaviz.config;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Kafka 全局配置。
 *
 * <p>{@link #bootstrapServers} 与 {@link #security} <b>不是</b>连接参数的事实源 ——
 * 它们是「首次启动种子配置」:仅当集群表为空时由
 * {@code ClusterSeedImporter} 导入成第一条集群记录。
 * 真正的连接参数在 {@code cluster_config} 表里,每个集群一份。
 *
 * <p>仍然全局生效的部分:超时({@link Admin})、消费者池默认值({@link Consumer})、
 * 生产者默认值({@link Producer})。这些由 {@code ClusterConnectionFactory} 注入到
 * 每个集群的客户端里。
 *
 * <p>{@code @NotBlank} 已移除:零集群配置时的启动是合法的(前端显示"添加集群"引导),
 * 若继续强校验,空值会让上下文启动失败,用户反而无法进入 UI 去配置。
 */
@Data
@ConfigurationProperties("kafka")
public class KafkaProperties {

    /** 首次启动的种子 bootstrap servers;空 = 不导入种子(表为空时则是零集群启动)。 */
    private String bootstrapServers = "";

    private Consumer consumer = new Consumer();

    private Producer producer = new Producer();

    private Admin admin = new Admin();

    /** 首次启动的种子 SASL 配置。 */
    private Security security = new Security();

    @Getter
    @Setter
    public static class Consumer {
        private int poolSize = 10;
        private long borrowTimeoutMs = 3000;
        private long pollTimeoutMs = 1000;
        private int queryMaxCount = 1000;
        /** 超过该 UTF-8 字节数的 value 不做 JSON 美化(保护 CPU 与响应体)。 */
        private int maxFormatBytes = 65536;
    }

    /**
     * 消息生产配置(共享 {@code KafkaProducer} 无需池化)。
     */
    @Getter
    @Setter
    public static class Producer {
        /** acks 级别(all / 1 / 0)。 */
        private String acks = "all";
        /** 同步 send 等待超时(ms)。 */
        private long sendTimeoutMs = 10000;
    }

    /**
     * AdminClient / Consumer 的阻塞超时配置。
     * 约束:requestTimeoutMs &lt; defaultApiTimeoutMs &lt; 前端 axios timeout(15000)。
     */
    @Getter
    @Setter
    public static class Admin {
        /** 单次 Kafka 调用的总时间预算(ms)。 */
        private int defaultApiTimeoutMs = 10000;
        /** 单次网络请求超时(ms),AdminClient 内部在此时间内重试。 */
        private int requestTimeoutMs = 5000;
    }

    /**
     * Kafka SASL 认证配置。protocol 为空时表示不启用认证。
     */
    @Getter
    @Setter
    public static class Security {
        /** 空字符串表示无认证；可选 SASL_PLAINTEXT / SASL_SSL */
        private String protocol = "";
        /** SASL 机制：PLAIN / SCRAM-SHA-256 / SCRAM-SHA-512 */
        private String saslMechanism = "PLAIN";
        private String username = "";
        private String password = "";
    }
}