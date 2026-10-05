package com.example.kafkaviz.model.dto;

import com.example.kafkaviz.kafka.AuthSpec;
import com.example.kafkaviz.kafka.AuthType;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Locale;
import java.util.Map;

/**
 * {@code POST /api/clusters} / {@code PUT /api/clusters/{id}} /
 * {@code POST /api/clusters/validate} 的请求体。
 *
 * <p><b>可选字段的缺省口径</b>:authType → 见 {@link #authTypeOrDefault()}(无值时按 {@code securityProtocol} 反推);
 * tlsEnabled → 由 {@code securityProtocol} 反推;verifyHostname → {@code true};
 * zkConnectString → {@code ""}(KRaft);archiveEnabled → {@code true};
 * archiveRetentionDays → {@code 30};sortOrder → {@code 0}。
 *
 * <p><b>秘密字段三态</b>(与 {@code ClusterConfigStore} 一致,避免前端"没动某个框"
 * 就把凭据清空)。适用于 password 及全部秘密材料(client 证书/私钥/私钥口令/
 * CA/clientSecret/JAAS):
 * <ul>
 *   <li>{@code null} —— 保持库中原值(新建时为"无");</li>
 *   <li>{@code "******"} —— 同上(对打码哨兵的防御);</li>
 *   <li>空串 —— 显式清空;</li>
 *   <li>其他 —— 作为新值加密落库。</li>
 * </ul>
 * 前端编辑已有集群时应<b>省略</b>未修改的字段(而不是提交空串)。
 *
 * <p><b>附加属性({@code customProps})的三态略有不同</b>:{@code null} = 不修改;
 * 空对象 = 清空;非空对象 = 整体替换(结构化字段无法用哨兵表达"没改")。
 *
 * <p><b>securityProtocol / saslMechanism 的处理</b>:除 {@code CUSTOM} 外,
 * 这两个字段为<b>忽略项</b> —— 真正落库的是按 authType 派生的值
 * (见 {@link #deriveSecurityProtocol()} / {@link #deriveSaslMechanism()})。
 * 只有在请求体没有 authType 时,它们才作为反推依据被读取。
 */
@Data
public class ClusterUpsertRequest {

    @NotBlank(message = "name must not be blank")
    private String name;

    @NotBlank(message = "bootstrapServers must not be blank")
    private String bootstrapServers;

    // ---------------------------------------------------------------
    // 认证判别符与传输层
    // ---------------------------------------------------------------

    /** NONE / PASSWORD / MTLS / OAUTH / CUSTOM;缺省按 {@code securityProtocol} 反推。 */
    private String authType;

    /** NONE/PASSWORD/OAUTH 的传输加密开关;缺省由 {@code securityProtocol} 反推。 */
    private Boolean tlsEnabled;

    /** 是否校验服务器主机名(SSL/SASL_SSL 下有效);缺省 true。 */
    private Boolean verifyHostname;

    // ---------------------------------------------------------------
    // mTLS(MTLS)
    // ---------------------------------------------------------------

    /** 客户端证书链 PEM(明文提交,加密落库)。 */
    private String sslClientCertPem;

    /** 客户端私钥 PEM:PKCS#8(未加密)或加密 PKCS#8(此时必须给口令)。 */
    private String sslClientKeyPem;

    /** 私钥口令(仅加密私钥需要;秘密材料)。 */
    private String sslClientKeyPassword;

    /** CA 证书 PEM;留空 = 使用 JVM 默认信任库。 */
    private String sslTrustCertsPem;

    // ---------------------------------------------------------------
    // OAuth2(OAUTH)
    // ---------------------------------------------------------------

    /** token 端点 URL(非秘密,需回显)。 */
    private String oauthTokenUrl;

    /** OAuth client id(非秘密,需回显)。 */
    private String oauthClientId;

    /** OAuth client secret(秘密材料)。 */
    private String oauthClientSecret;

    /** 可选 scope(非秘密,需回显)。 */
    private String oauthScope;

    // ---------------------------------------------------------------
    // 逃生舱(CUSTOM)
    // ---------------------------------------------------------------

    /** 完整 JAAS 串(原文生效,不转义;秘密材料)。 */
    private String customJaas;

    /** 附加客户端属性;null = 不修改,{} = 清空。 */
    private Map<String, String> customProps;

    // ---------------------------------------------------------------
    // 其余集群字段
    // ---------------------------------------------------------------

    /** CUSTOM 时作为手选协议;其余类型忽略(由 authType 派生)。 */
    private String securityProtocol;

    /** CUSTOM 时作为手选机制;PASSWORD 时为 PLAIN / SCRAM-SHA-256 / SCRAM-SHA-512。 */
    private String saslMechanism;

    private String username;

    /** 明文口令,三态语义见类注释。 */
    private String password;

    /** 空 = 该集群 KRaft 模式,不创建 Curator,ZK 端点返回 50302。 */
    private String zkConnectString;

    private Boolean enabled;

    private Boolean archiveEnabled;

    private Integer archiveRetentionDays;

    private Integer sortOrder;

    // ---------------------------------------------------------------
    // 缺省值归一化(Store 只认原始类型,避免到处判空)
    // ---------------------------------------------------------------

    public String securityProtocolOrDefault() {
        return securityProtocol == null ? "" : securityProtocol.trim();
    }

    public String saslMechanismOrDefault() {
        return (saslMechanism == null || saslMechanism.isBlank()) ? "PLAIN" : saslMechanism.trim();
    }

    public String usernameOrDefault() {
        return username == null ? "" : username.trim();
    }

    public String zkConnectStringOrDefault() {
        return zkConnectString == null ? "" : zkConnectString.trim();
    }

    public boolean enabledOrDefault() {
        return enabled == null || enabled;
    }

    public boolean archiveEnabledOrDefault() {
        return archiveEnabled == null || archiveEnabled;
    }

    /**
     * 认证判别符。
     *
     * <p><b>兼容层(入站反推)</b>:请求体没带 authType 时,从既有的
     * {@code securityProtocol} 反推 —— 未带 authType 的客户端、脚本、以及
     * {@code ClusterSeedImporter} 都走这条路径。
     *
     * @throws IllegalArgumentException 显式给了未知取值(→ 40001)
     */
    public String authTypeOrDefault() {
        if (authType != null && !authType.isBlank()) {
            return AuthType.parse(authType).name();
        }
        return AuthType.inferFromLegacy(securityProtocolOrDefault(), saslMechanismOrDefault()).name();
    }

    /** 传输加密开关:未显式提交时从 {@code securityProtocol} 反推。 */
    public boolean tlsEnabledOrDefault() {
        if (tlsEnabled != null) {
            return tlsEnabled;
        }
        String protocol = securityProtocolOrDefault().toUpperCase(Locale.ROOT);
        return protocol.endsWith("_SSL") || "SSL".equals(protocol);
    }

    public boolean verifyHostnameOrDefault() {
        return verifyHostname == null || verifyHostname;
    }

    public String oauthTokenUrlOrDefault() {
        return oauthTokenUrl == null ? "" : oauthTokenUrl.trim();
    }

    public String oauthClientIdOrDefault() {
        return oauthClientId == null ? "" : oauthClientId.trim();
    }

    /** secret 不 trim(与 {@code BuiltinOAuthLoginCallbackHandler} 同口径)。 */
    public String oauthClientSecretOrDefault() {
        return oauthClientSecret == null ? "" : oauthClientSecret;
    }

    public String oauthScopeOrDefault() {
        return oauthScope == null ? "" : oauthScope.trim();
    }

    /** 附加属性;null 托底为空映射(仅供"读取语义"使用,三态判定请直接看 {@link #getCustomProps()})。 */
    public Map<String, String> customPropsOrEmpty() {
        return customProps == null ? Map.of() : customProps;
    }

    /** 保留天数:缺省 30;非正数视为非法输入(40001),避免配出"0 天即删光"。 */
    public int archiveRetentionDaysOrDefault() {
        if (archiveRetentionDays == null) {
            return 30;
        }
        if (archiveRetentionDays < 1) {
            throw new IllegalArgumentException("archiveRetentionDays must be >= 1");
        }
        return archiveRetentionDays;
    }

    public int sortOrderOrDefault() {
        return sortOrder == null ? 0 : sortOrder;
    }

    // ---------------------------------------------------------------
    // 派生(调用入口 —— 规则本体在 AuthSpec)
    // ---------------------------------------------------------------

    /** 请求体 → 认证材料值对象。 */
    public AuthSpec toAuthSpec() {
        return new AuthSpec(
                authTypeOrDefault(),
                tlsEnabledOrDefault(),
                verifyHostnameOrDefault(),
                sslClientCertPem,
                sslClientKeyPem,
                sslClientKeyPassword,
                sslTrustCertsPem,
                oauthTokenUrlOrDefault(),
                oauthClientIdOrDefault(),
                oauthClientSecretOrDefault(),
                oauthScopeOrDefault(),
                customJaas,
                customPropsOrEmpty());
    }

    /** 物化 {@code security_protocol} 列值(CUSTOM 用手选协议)。 */
    public String deriveSecurityProtocol() {
        return toAuthSpec().deriveSecurityProtocol(securityProtocolOrDefault());
    }

    /** 物化 {@code sasl_mechanism} 列值(CUSTOM 用手选机制)。 */
    public String deriveSaslMechanism() {
        // 传"原始值"而不是 saslMechanismOrDefault():后者的 PLAIN 兜底只对 PASSWORD 成立,
        // 若传给 CUSTOM 会把"用户没填机制"写成 PLAIN(PASSWORD 的默认值由 AuthSpec 内部补)
        String raw = saslMechanism == null ? "" : saslMechanism.trim();
        return toAuthSpec().deriveSaslMechanism(raw);
    }
}
