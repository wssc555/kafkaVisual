package com.example.kafkaviz.kafka;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 一个集群的<b>认证材料全集</b>。
 *
 * <p>挂在 {@link ClusterDefinition#auth()} 上，与既有的
 * {@code securityProtocol}/{@code saslMechanism}/{@code username}/{@code password}
 * 并存 —— 后者是两个<b>物化派生列</b>，由本类的
 * {@link #deriveSecurityProtocol(String)} / {@link #deriveSaslMechanism(String)}
 * 按 {@link AuthType} 算出后落库，连接工厂照旧只认它们。
 *
 * <p><b>口令安全约定（与 {@link ClusterDefinition} 同口径）</b>：
 * {@link #sslClientKeyPem()} / {@link #sslClientKeyPassword()} /
 * {@link #oauthClientSecret()} / {@link #customJaas()} / {@link #customProps()}
 * 都是<b>明文</b>，只应存在于 JVM 内存中（从库里读出时即时解密）。因此：
 * <ul>
 *   <li>本 record 永不直接被 Controller 返回（对外一律 {@code ClusterSummary}
 *       + {@code credentialPresence} 布尔位）；</li>
 *   <li>{@link #toString()} 覆写为"只报是否存在、不报值"的版本 ——
 *       record 默认 toString 会打印全部组件，一次 {@code log.info("auth={}", spec)}
 *       就会把私钥/secret 写进日志文件；</li>
 *   <li>秘密字段访问器标注 {@link JsonIgnore}，防止未来被意外序列化。</li>
 * </ul>
 *
 * <p>非秘密字段（{@code oauthTokenUrl} / {@code oauthClientId} / {@code oauthScope}）
 * 参与序列化是刻意的：编辑对话框需要回显它们。
 *
 * @param authType           判别符名，取值见 {@link AuthType}（缺省 NONE）
 * @param tlsEnabled         NONE/PASSWORD/OAUTH 的传输加密开关（MTLS 恒加密，忽略本值）
 * @param verifyHostname     是否校验服务器主机名（SSL/SASL_SSL 下有效，默认 true）
 * @param sslClientCertPem   客户端证书链 PEM（MTLS）
 * @param sslClientKeyPem    客户端私钥 PEM（MTLS，PKCS#8）
 * @param sslClientKeyPassword 私钥口令：仅当私钥是加密 PKCS#8 时才需要（MTLS）
 * @param sslTrustCertsPem   CA 证书 PEM（MTLS 可选；空 = 使用 JVM 默认信任库）
 * @param oauthTokenUrl      token 端点 URL（OAUTH）
 * @param oauthClientId      OAuth client id（OAUTH，非秘密）
 * @param oauthClientSecret  OAuth client secret（OAUTH，秘密）
 * @param oauthScope         可选 scope（OAUTH，非秘密）
 * @param customJaas         完整 JAAS 串原文（CUSTOM，秘密：可能内嵌口令）
 * @param customProps        附加客户端属性（CUSTOM，整体视为秘密）
 */
public record AuthSpec(
        String authType,
        boolean tlsEnabled,
        boolean verifyHostname,
        String sslClientCertPem,
        String sslClientKeyPem,
        String sslClientKeyPassword,
        String sslTrustCertsPem,
        String oauthTokenUrl,
        String oauthClientId,
        String oauthClientSecret,
        String oauthScope,
        String customJaas,
        Map<String, String> customProps) {

    /**
     * CUSTOM 里禁止出现的保留键。
     *
     * <p>这四类键由程序自己写入（地址、协议、机制、JAAS）；允许用户在附加属性里再写
     * 一遍只会造出自相矛盾的配置，排查成本极高，因此直接 40001 拒绝。
     */
    public static final List<String> RESERVED_CUSTOM_PROP_KEYS = List.of(
            "bootstrap.servers", "security.protocol", "sasl.mechanism", "sasl.jaas.config");

    /** 归一化：空串托底 + 附加属性去空键空值，使下游无需到处判空。 */
    public AuthSpec {
        authType = (authType == null || authType.isBlank())
                ? AuthType.NONE.name()
                : authType.trim().toUpperCase(Locale.ROOT);
        sslClientCertPem = nullToEmpty(sslClientCertPem);
        sslClientKeyPem = nullToEmpty(sslClientKeyPem);
        sslClientKeyPassword = nullToEmpty(sslClientKeyPassword);
        sslTrustCertsPem = nullToEmpty(sslTrustCertsPem);
        oauthTokenUrl = nullToEmpty(oauthTokenUrl);
        oauthClientId = nullToEmpty(oauthClientId);
        oauthClientSecret = nullToEmpty(oauthClientSecret);
        oauthScope = nullToEmpty(oauthScope);
        customJaas = nullToEmpty(customJaas);
        customProps = sanitizeProps(customProps);
    }

    /** 缺省认证：无认证、不加密。 */
    public static AuthSpec none() {
        return new AuthSpec(AuthType.NONE.name(), false, true,
                "", "", "", "", "", "", "", "", "", Map.of());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 附加属性清洗：丢弃空键/空值条目。
     *
     * <p>刻意<b>不</b>用会抛 NPE 的 {@code Map.copyOf} 原样处理 —— 非法输入应当在
     * 校验层落 40001，而不是在模型构造时炸成 50000（见 {@code ClusterUpsertValidator}，
     * 那里对 null 值给出明确指认）。
     *
     * <p><b>public 的原因</b>：{@code ClusterConfigStore.serializeProps} 写库前
     * 也用它清洗，保证"写入即净化"与构造期清洗<b>同源</b>——否则含 null 值的 map 经
     * 非 REST 路径写库后，读回时会被静默丢弃，造成写入/读回不对称。
     */
    public static Map<String, String> sanitizeProps(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (k != null && !k.isBlank() && v != null) {
                out.put(k.trim(), v);
            }
        });
        return Map.copyOf(out);
    }

    public AuthType type() {
        return AuthType.parse(authType);
    }

    // ---------------------------------------------------------------
    // 派生规则（全仓唯一事实源，前端注释与之对齐）
    // ---------------------------------------------------------------

    /**
     * 派生 {@code security.protocol} 列值。
     *
     * <p>NONE 且未开 TLS 时返回<b>空串</b>而不是字面量 {@code PLAINTEXT} ——
     * "空串 = 无认证"是建表以来的既有约定（{@code security_protocol DEFAULT ''}），
     * 改成 PLAINTEXT 会让存量行的语义漂移、并让 {@link ClusterDefinition#saslEnabled()}
     * 一类"协议是否非空"的判断失真。
     *
     * @param explicitProtocol CUSTOM 下手选的协议（其余类型忽略该参数）
     */
    public String deriveSecurityProtocol(String explicitProtocol) {
        return switch (type()) {
            case NONE -> tlsEnabled ? "SSL" : "";
            case PASSWORD, OAUTH -> tlsEnabled ? "SASL_SSL" : "SASL_PLAINTEXT";
            case MTLS -> "SSL";
            case CUSTOM -> explicitProtocol == null ? "" : explicitProtocol.trim();
        };
    }

    /**
     * 派生 {@code sasl.mechanism} 列值。
     *
     * <p>PASSWORD 沿用请求里显式提交的机制（PLAIN / SCRAM-SHA-256 / SCRAM-SHA-512，
     * 白名单由校验器保证）；OAUTH 恒为 OAUTHBEARER；NONE/MTLS 置空。
     *
     * @param explicitMechanism 请求里显式提交的机制
     */
    public String deriveSaslMechanism(String explicitMechanism) {
        String explicit = explicitMechanism == null ? "" : explicitMechanism.trim();
        return switch (type()) {
            case PASSWORD -> explicit.isEmpty() ? "PLAIN" : explicit;
            case OAUTH -> "OAUTHBEARER";
            case NONE, MTLS -> "";
            case CUSTOM -> explicit;
        };
    }

    // ---------------------------------------------------------------
    // 存在性判断（回显用，绝不返回值本身）
    // ---------------------------------------------------------------

    public boolean hasClientCert() {
        return !sslClientCertPem.isEmpty();
    }

    public boolean hasClientKey() {
        return !sslClientKeyPem.isEmpty();
    }

    public boolean hasClientKeyPassword() {
        return !sslClientKeyPassword.isEmpty();
    }

    public boolean hasTrustCerts() {
        return !sslTrustCertsPem.isEmpty();
    }

    public boolean hasOAuthClientSecret() {
        return !oauthClientSecret.isEmpty();
    }

    public boolean hasCustomJaas() {
        return !customJaas.isEmpty();
    }

    public boolean hasCustomProps() {
        return !customProps.isEmpty();
    }

    // ---------------------------------------------------------------
    // 秘密字段：永不序列化
    // ---------------------------------------------------------------

    @JsonIgnore
    public String sslClientKeyPem() {
        return sslClientKeyPem;
    }

    @JsonIgnore
    public String sslClientKeyPassword() {
        return sslClientKeyPassword;
    }

    @JsonIgnore
    public String oauthClientSecret() {
        return oauthClientSecret;
    }

    @JsonIgnore
    public String customJaas() {
        return customJaas;
    }

    @JsonIgnore
    public Map<String, String> customProps() {
        return customProps;
    }

    /** 证书链本身不是密钥，但仍属敏感材料：同样不打进日志/序列化。 */
    @JsonIgnore
    public String sslClientCertPem() {
        return sslClientCertPem;
    }

    @JsonIgnore
    public String sslTrustCertsPem() {
        return sslTrustCertsPem;
    }

    /** 打码版本：只报"有没有"，绝不报值。 */
    @Override
    public String toString() {
        return "AuthSpec[authType=" + authType
                + ", tlsEnabled=" + tlsEnabled
                + ", verifyHostname=" + verifyHostname
                + ", clientCert=" + (hasClientCert() ? "<set>" : "<none>")
                + ", clientKey=" + (hasClientKey() ? "<set>" : "<none>")
                + ", clientKeyPassword=" + (hasClientKeyPassword() ? "******" : "<none>")
                + ", trustCerts=" + (hasTrustCerts() ? "<set>" : "<none>")
                + ", oauthTokenUrl=" + oauthTokenUrl
                + ", oauthClientId=" + oauthClientId
                + ", oauthClientSecret=" + (hasOAuthClientSecret() ? "******" : "<none>")
                + ", oauthScope=" + oauthScope
                + ", customJaas=" + (hasCustomJaas() ? "******" : "<none>")
                + ", customProps=" + (hasCustomProps() ? "<" + customProps.size() + " entries>" : "<none>")
                + "]";
    }
}
