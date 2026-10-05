package com.example.kafkaviz.kafka;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 一个 Kafka 集群的完整定义(对应 {@code cluster_config} 一行)。
 *
 * <p><b>口令安全约定</b>:{@link #password()} 是<b>明文</b>,只在 JVM 内存中存在,
 * 从库里读出时即时解密。因此:
 * <ul>
 *   <li>本 record 永远不直接被 Controller 返回(对外一律用 {@code ClusterSummary});</li>
 *   <li>{@link #toString()} 被覆写为打码版本 —— record 的默认 toString 会打印全部组件,
 *       一旦有人 {@code log.info("cluster={}", def)} 就会把口令写进日志文件;</li>
 *   <li>访问器标注 {@link JsonIgnore},防止未来被意外序列化。</li>
 * </ul>
 *
 * <p><b>字段分工</b>:{@link #auth()} 承载认证判别符与
 * 全部认证材料(证书/OAuth/自定义),而 {@link #securityProtocol()} 与
 * {@link #saslMechanism()} 是<b>物化派生列</b> —— 由 {@link AuthSpec} 按
 * authType 算出后落库,连接工厂照旧只读它们(读路径零改动)。
 *
 * @param id                   自增主键,功能端点路径里的 {@code {clusterId}}
 * @param name                 显示名,唯一
 * @param bootstrapServers     Kafka bootstrap servers
 * @param securityProtocol     空 = 无认证;SASL_PLAINTEXT / SASL_SSL / SSL(物化派生列)
 * @param saslMechanism        PLAIN / SCRAM-SHA-256 / SCRAM-SHA-512 / OAUTHBEARER(物化派生列)
 * @param username             SASL 账号
 * @param password             SASL 口令(明文,仅内存)
 * @param auth                 认证判别符 + 认证材料(永不为 null)
 * @param zkConnectString      空 = 该集群 KRaft 模式,ZK 浏览器入口隐藏
 * @param enabled              是否启用(保留字段:当前未参与连接调度,但随配置持久化)
 * @param archiveEnabled       是否启用消息归档
 * @param archiveRetentionDays 归档保留天数
 * @param sortOrder            列表排序权重
 * @param createdAt            ISO-8601 文本
 * @param updatedAt            ISO-8601 文本
 */
public record ClusterDefinition(
        long id,
        String name,
        String bootstrapServers,
        String securityProtocol,
        String saslMechanism,
        String username,
        String password,
        AuthSpec auth,
        String zkConnectString,
        boolean enabled,
        boolean archiveEnabled,
        int archiveRetentionDays,
        int sortOrder,
        String createdAt,
        String updatedAt) {

    /** {@code auth} 永远非 null:缺失即等价于"无认证",避免每个消费点各自判空。 */
    public ClusterDefinition {
        if (auth == null) {
            auth = AuthSpec.none();
        }
    }

    /** 该集群是否配置了 ZooKeeper(空串 = KRaft)。 */
    public boolean zkConfigured() {
        return zkConnectString != null && !zkConnectString.isBlank();
    }

    /**
     * 是否启用 SASL。
     *
     * <p>无认证集群也可能带 {@code SSL}(TLS 加密但无身份认证),那不属于 SASL,
     * 因此判据是"协议以 SASL 开头"而不是"协议非空"。
     */
    public boolean saslEnabled() {
        return securityProtocol != null
                && securityProtocol.trim().toUpperCase(java.util.Locale.ROOT).startsWith("SASL");
    }

    /** 口令永不参与序列化。 */
    @JsonIgnore
    public String password() {
        return password;
    }

    /** 口令永不参与日志/字符串拼接。 */
    @Override
    public String toString() {
        return "ClusterDefinition[id=" + id + ", name=" + name
                + ", bootstrapServers=" + bootstrapServers
                + ", authType=" + (auth == null ? AuthSpec.none().authType() : auth.authType())
                + ", securityProtocol=" + securityProtocol
                + ", saslMechanism=" + saslMechanism
                + ", username=" + username
                + ", password=" + (password == null || password.isEmpty() ? "<none>" : "******")
                + ", zkConnectString=" + (zkConnectString == null || zkConnectString.isEmpty() ? "<none>" : "******")
                + ", enabled=" + enabled
                + ", archiveEnabled=" + archiveEnabled
                + ", archiveRetentionDays=" + archiveRetentionDays
                + ", sortOrder=" + sortOrder + "]";
    }
}
