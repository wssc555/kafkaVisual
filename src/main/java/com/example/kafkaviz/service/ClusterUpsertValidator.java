package com.example.kafkaviz.service;

import com.example.kafkaviz.kafka.AuthSpec;
import com.example.kafkaviz.kafka.AuthType;
import com.example.kafkaviz.model.dto.ClusterUpsertRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 集群 upsert / 测连请求的认证校验器。
 *
 * <p>由 {@code POST|PUT /api/clusters} 与 {@code POST /api/clusters/validate}
 * <b>共用</b> —— 测连与保存必须走同一套校验,否则会出现
 * "测连通过、保存被拒"或反过来的分裂体验。
 *
 * <p>失败一律抛 {@link IllegalArgumentException} → {@code GlobalExceptionHandler}
 * 映射 400/40001,<b>不新增错误码</b>。
 *
 * <p>校验只做"格式与完备性"判断,不做密码学判断:证书与私钥是否真的配对、CA 是否
 * 签发了该证书,只有建连时才能知道(测连端点就是用来暴露这类问题的)。
 *
 * <p><b>三态字段的处理</b>:秘密字段 {@code null} 表示"保持库中原值",此时校验器
 * 无从判断库中内容,故<b>跳过</b>格式校验 —— 用 {@code requireCredentials} 参数
 * 区分场景:新建与测连(true,请求体即全量)必须给齐;更新(false)允许省略。
 */
@Component
public class ClusterUpsertValidator {

    /** PASSWORD 允许的 SASL 机制(白名单,防止写出 broker 不认识的机制)。 */
    private static final List<String> SASL_MECHANISMS = List.of("PLAIN", "SCRAM-SHA-256", "SCRAM-SHA-512");

    /** CUSTOM 允许手选的协议(四值)—— 其它取值等于让用户写错配置去撞 Kafka 报错。 */
    private static final List<String> CUSTOM_PROTOCOLS =
            List.of("PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL");

    private static final String CERT_HEADER = "-----BEGIN CERTIFICATE-----";

    private static final String PKCS8_KEY_HEADER = "-----BEGIN PRIVATE KEY-----";

    private static final String ENCRYPTED_PKCS8_KEY_HEADER = "-----BEGIN ENCRYPTED PRIVATE KEY-----";

    /** PKCS#1 私钥头:默认 SSL engine factory 不支持,单独指认以便给出转换提示。 */
    private static final String PKCS1_KEY_HEADER = "-----BEGIN RSA PRIVATE KEY-----";

    /** 已提交且非空(三态里的"新值")。 */
    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private static void requireHeader(String pem, String header, String field) {
        if (pem.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank when submitted "
                    + "(omit the field to keep the stored value)");
        }
        if (!pem.contains(header)) {
            throw new IllegalArgumentException(field + " must be PEM text containing '" + header + "'");
        }
    }

    /** 更新场景:允许省略秘密字段(省略 = 保持库中原值)。 */
    public void validateForUpdate(ClusterUpsertRequest req) {
        validate(req, false);
    }

    // ---------------------------------------------------------------
    // PASSWORD
    // ---------------------------------------------------------------

    /** 新建 / 测连场景:秘密字段必须给齐。 */
    public void validateForCreate(ClusterUpsertRequest req) {
        validate(req, true);
    }

    // ---------------------------------------------------------------
    // MTLS
    // ---------------------------------------------------------------

    /**
     * @param requireCredentials true = 本次请求体是凭据全量(新建 / 测连),
     *                           false = 允许省略(更新,省略即沿用库中原值)
     */
    public void validate(ClusterUpsertRequest req, boolean requireCredentials) {
        if (req.getBootstrapServers() == null || req.getBootstrapServers().isBlank()) {
            throw new IllegalArgumentException("bootstrapServers must not be blank");
        }
        // 保留天数归入本校验器:认证类规则统一在 Service 层生效,
        // "哪些规则在哪个层生效"才可预测 —— Store 侧保留二次防御
        if (req.getArchiveRetentionDays() != null && req.getArchiveRetentionDays() < 1) {
            throw new IllegalArgumentException("archiveRetentionDays must be >= 1");
        }

        // 未知 authType 在此抛 40001(parse 的契约)
        AuthType type = AuthType.parse(req.authTypeOrDefault());
        switch (type) {
            case NONE -> {
                // 无认证:没有额外要求(是否开 TLS 由 tlsEnabled 决定)
            }
            case PASSWORD -> validatePassword(req, requireCredentials);
            case MTLS -> validateMtls(req, requireCredentials);
            case OAUTH -> validateOAuth(req, requireCredentials);
            case CUSTOM -> validateCustom(req);
        }
    }

    private void validatePassword(ClusterUpsertRequest req, boolean requireCredentials) {
        String mechanism = req.saslMechanismOrDefault().toUpperCase(Locale.ROOT);
        if (!SASL_MECHANISMS.contains(mechanism)) {
            throw new IllegalArgumentException("saslMechanism must be one of " + SASL_MECHANISMS
                    + " for authType PASSWORD but was '" + req.saslMechanismOrDefault() + "'");
        }
        // 凭据必填性与其他认证方式对齐:新建/测连时缺口令应在 40001 就指认,
        // 而不是放过去让 SASL 握手失败、以 50001"认证失败"这种模糊错误收场
        if (requireCredentials && !isPresent(req.getPassword())) {
            throw new IllegalArgumentException("authType PASSWORD requires password");
        }
    }

    // ---------------------------------------------------------------
    // OAUTH
    // ---------------------------------------------------------------

    private void validateMtls(ClusterUpsertRequest req, boolean requireCredentials) {
        String cert = req.getSslClientCertPem();
        String key = req.getSslClientKeyPem();
        String keyPassword = req.getSslClientKeyPassword();
        String trustCerts = req.getSslTrustCertsPem();

        if (requireCredentials && !isPresent(cert)) {
            throw new IllegalArgumentException("authType MTLS requires sslClientCertPem (PEM certificate chain)");
        }
        if (cert != null) {
            requireHeader(cert, CERT_HEADER, "sslClientCertPem");
        }

        if (requireCredentials && !isPresent(key)) {
            throw new IllegalArgumentException("authType MTLS requires sslClientKeyPem (PEM private key)");
        }
        if (key != null) {
            validatePrivateKey(key, keyPassword, requireCredentials);
        }

        if (trustCerts != null && !trustCerts.isBlank()) {
            requireHeader(trustCerts, CERT_HEADER, "sslTrustCertsPem");
        }
    }

    // ---------------------------------------------------------------
    // CUSTOM
    // ---------------------------------------------------------------

    /**
     * 私钥格式校验。
     *
     * <p>接受两形态:未加密 PKCS#8 与加密 PKCS#8(后者必须给口令 —— 这是
     * kafka-clients 3.9 PEM 引擎的实测能力,见 {@code MtlsConfigurer} 的说明)。
     * PKCS#1 明确指认并给出 openssl 转换命令。
     */
    private void validatePrivateKey(String key, String keyPassword, boolean requireCredentials) {
        if (key.isBlank()) {
            throw new IllegalArgumentException("sslClientKeyPem must not be blank when submitted "
                    + "(omit the field to keep the stored key)");
        }
        if (key.contains(ENCRYPTED_PKCS8_KEY_HEADER)) {
            boolean passwordMissing = keyPassword == null ? requireCredentials : keyPassword.isBlank();
            if (passwordMissing) {
                throw new IllegalArgumentException("sslClientKeyPem is an encrypted PKCS#8 key — "
                        + "sslClientKeyPassword is required");
            }
            return;
        }
        if (key.contains(PKCS1_KEY_HEADER)) {
            throw new IllegalArgumentException("sslClientKeyPem is PKCS#1 ('BEGIN RSA PRIVATE KEY'), which the "
                    + "default SSL engine factory does not support. Convert it to unencrypted PKCS#8 with: "
                    + "openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem");
        }
        if (!key.contains(PKCS8_KEY_HEADER)) {
            throw new IllegalArgumentException("sslClientKeyPem must be a PEM private key "
                    + "('-----BEGIN PRIVATE KEY-----' or '-----BEGIN ENCRYPTED PRIVATE KEY-----')");
        }
    }

    // ---------------------------------------------------------------
    // 工具
    // ---------------------------------------------------------------

    private void validateOAuth(ClusterUpsertRequest req, boolean requireCredentials) {
        String tokenUrl = req.oauthTokenUrlOrDefault();
        String clientId = req.oauthClientIdOrDefault();

        if (requireCredentials && tokenUrl.isEmpty()) {
            throw new IllegalArgumentException("authType OAUTH requires oauthTokenUrl");
        }
        if (!tokenUrl.isEmpty() && !(tokenUrl.startsWith("http://") || tokenUrl.startsWith("https://"))) {
            throw new IllegalArgumentException("oauthTokenUrl must be an http(s) URL but was '" + tokenUrl + "'");
        }
        if (req.getOauthTokenUrl() != null && req.getOauthTokenUrl().isBlank()) {
            throw new IllegalArgumentException("oauthTokenUrl must not be blank when submitted "
                    + "(omit the field to keep the stored value)");
        }

        if (requireCredentials && clientId.isEmpty()) {
            throw new IllegalArgumentException("authType OAUTH requires oauthClientId");
        }
        if (requireCredentials && !isPresent(req.getOauthClientSecret())) {
            throw new IllegalArgumentException("authType OAUTH requires oauthClientSecret "
                    + "(grant type is always client_credentials)");
        }
    }

    private void validateCustom(ClusterUpsertRequest req) {
        String protocol = req.securityProtocolOrDefault();
        if (protocol.isEmpty()) {
            throw new IllegalArgumentException("authType CUSTOM requires securityProtocol, one of " + CUSTOM_PROTOCOLS);
        }
        if (!CUSTOM_PROTOCOLS.contains(protocol.toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("securityProtocol must be one of " + CUSTOM_PROTOCOLS
                    + " but was '" + protocol + "'");
        }

        Map<String, String> props = req.getCustomProps();
        if (props == null) {
            return;
        }
        for (Map.Entry<String, String> entry : props.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("customProps contains a blank key");
            }
            if (entry.getValue() == null) {
                // Map.copyOf 会对 null 值抛 NPE(→ 50000);在校验层给出 40001 才是正确语义
                throw new IllegalArgumentException("customProps['" + key + "'] must not be null");
            }
            String normalized = key.trim().toLowerCase(Locale.ROOT);
            if (AuthSpec.RESERVED_CUSTOM_PROP_KEYS.contains(normalized)) {
                throw new IllegalArgumentException("customProps must not contain the reserved key '" + key
                        + "' (reserved: " + AuthSpec.RESERVED_CUSTOM_PROP_KEYS
                        + "). Use the dedicated fields instead");
            }
        }
    }
}
