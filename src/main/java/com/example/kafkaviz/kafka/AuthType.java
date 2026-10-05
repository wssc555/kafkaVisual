package com.example.kafkaviz.kafka;

/**
 * 集群认证方式判别符。
 *
 * <p><b>它只是判别符，不是最终喂给 Kafka 的值</b>：真正的 {@code security.protocol} /
 * {@code sasl.mechanism} 由 {@link AuthSpec#deriveSecurityProtocol(String)} /
 * {@link AuthSpec#deriveSaslMechanism(String)} 从本枚举+子字段派生后落库
 * （物化派生列），连接工厂仍按那两个字段建连 —— 这样下游读路径零改动。
 *
 * <p>取值与覆盖面：
 * <ul>
 *   <li>{@link #NONE} —— 无认证；{@code tlsEnabled} 决定是否走 {@code SSL} 传输加密；</li>
 *   <li>{@link #PASSWORD} —— 用户名口令（SASL/PLAIN 或 SASL/SCRAM-SHA-256/512）；</li>
 *   <li>{@link #MTLS} —— 双向 TLS（客户端证书 + 私钥 PEM，可选 CA）；</li>
 *   <li>{@link #OAUTH} —— SASL/OAUTHBEARER + 内置 token 端点客户端（client_credentials）；</li>
 *   <li>{@link #CUSTOM} —— 逃生舱：完整 JAAS + 附加客户端属性 + 手选协议/机制
 *       （覆盖 Kerberos、Delegation Token、云厂商 SASL 扩展）。</li>
 * </ul>
 *
 * <p><b>兼容策略（入站反推、出站物化）</b>：旧客户端提交的请求体没有
 * {@code authType}，由 {@link #inferFromLegacy(String, String)} 从既有的
 * {@code securityProtocol}/{@code saslMechanism} 反推，因此旧前端/脚本不会被破坏。
 */
public enum AuthType {

    NONE,
    PASSWORD,
    MTLS,
    OAUTH,
    CUSTOM;

    /**
     * 解析线值（大小写不敏感）。
     *
     * @throws IllegalArgumentException 未知取值（→ 40001）；空值归 {@link #NONE}
     */
    public static AuthType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        String normalized = raw.trim().toUpperCase(java.util.Locale.ROOT);
        for (AuthType type : values()) {
            if (type.name().equals(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown authType: '" + raw + "' (expected one of "
                + java.util.Arrays.toString(values()) + ")");
    }

    /**
     * 从旧字段反推（兼容层）。
     *
     * <p>映射：{@code SASL_*} → {@link #PASSWORD}；{@code SSL} → {@link #MTLS}；
     * 其余（含空串）→ {@link #NONE}。旧 UI 产不出纯 {@code SSL}，这里按 mTLS 归类是
     * 防御性处理（只有证书才能让纯 SSL 连接真正建立起来）。
     */
    public static AuthType inferFromLegacy(String securityProtocol, String saslMechanism) {
        String protocol = securityProtocol == null ? "" : securityProtocol.trim().toUpperCase(java.util.Locale.ROOT);
        if (protocol.startsWith("SASL")) {
            return PASSWORD;
        }
        if ("SSL".equals(protocol)) {
            return MTLS;
        }
        return NONE;
    }
}
