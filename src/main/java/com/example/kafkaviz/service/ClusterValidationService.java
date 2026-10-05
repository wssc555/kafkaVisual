package com.example.kafkaviz.service;

import com.example.kafkaviz.config.KafkaProperties;
import com.example.kafkaviz.exception.ClusterNotFoundException;
import com.example.kafkaviz.kafka.AuthSpec;
import com.example.kafkaviz.kafka.ClusterConnectionFactory;
import com.example.kafkaviz.kafka.ClusterDefinition;
import com.example.kafkaviz.kafka.KafkaFutures;
import com.example.kafkaviz.model.dto.ClusterValidateRequest;
import com.example.kafkaviz.model.vo.ClusterValidateResult;
import com.example.kafkaviz.model.vo.StorageInfo;
import com.example.kafkaviz.storage.ClusterConfigStore;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.common.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.concurrent.ExecutionException;

/**
 * 保存前"测试连接"服务。
 *
 * <p>设计要点:
 * <ol>
 *   <li><b>与保存共用同一套路径</b>:同一个 {@link ClusterUpsertValidator}、同一个
 *       {@link ClusterConnectionFactory}(因此同一个安全策略实现)、同一套超时配置 ——
 *       否则会出现"测连通过、保存连不上"的分裂;</li>
 *   <li><b>不落库、不缓存、不建 Producer/ConsumerPool</b>:只建一个一次性
 *       AdminClient 并 {@code describeCluster()}。这是最轻量且足以证明认证链路
 *       (协议、证书、SASL/JAAS、token 获取)真的能通的探针;</li>
 *   <li><b>凭据可不重输</b>:提交 {@code clusterId} 时,留空(null 或打码哨兵)的
 *       秘密字段从库中补全 —— 加密落库的价值正在于此:用户改地址顺手测个连通性,
 *       不需要把口令/私钥再敲一遍;</li>
 *   <li><b>错误码不新增</b>:校验失败 → 40001({@code IllegalArgumentException});
 *       超时 → 50302({@link KafkaFutures#await} 的既定行为);认证/握手失败与其余
 *       Kafka 异常 → 50001(由 {@code GlobalExceptionHandler} 按
 *       {@code KafkaException} / {@code ExecutionException} 映射)。</li>
 * </ol>
 */
@Service
public class ClusterValidationService {

    private static final Logger log = LoggerFactory.getLogger(ClusterValidationService.class);

    private final ClusterUpsertValidator validator;
    private final ClusterConfigStore store;
    private final ClusterConnectionFactory connectionFactory;
    private final KafkaProperties kafkaProperties;

    public ClusterValidationService(ClusterUpsertValidator validator,
                                   ClusterConfigStore store,
                                   ClusterConnectionFactory connectionFactory,
                                   KafkaProperties kafkaProperties) {
        this.validator = validator;
        this.store = store;
        this.connectionFactory = connectionFactory;
        this.kafkaProperties = kafkaProperties;
    }

    /** 三态口径:"未提交"既包括 null 也包括打码哨兵。 */
    private static boolean isOmitted(String value) {
        return value == null || StorageInfo.PASSWORD_MASK.equals(value);
    }

    /**
     * 试连(不落库)。
     *
     * @throws IllegalArgumentException 校验失败 → 400/40001
     * @throws ClusterNotFoundException 携带的 clusterId 不存在 → 404/40404
     */
    public ClusterValidateResult validate(ClusterValidateRequest req)
            throws ExecutionException, InterruptedException {
        if (req.getClusterId() != null) {
            ClusterDefinition stored = store.findById(req.getClusterId())
                    .orElseThrow(() -> new ClusterNotFoundException(req.getClusterId()));
            fillOmittedFields(req, stored);
        }

        // 补全之后再校验:此时请求体已是"全量凭据",因此用 validateForCreate 的严格口径
        validator.validateForCreate(req);

        ClusterDefinition probe = probeDefinition(req);
        long startedAt = System.currentTimeMillis();
        // try-with-resources:Admin 继承 AutoCloseable,探针客户端必须用完即关,
        // 否则每次测连都会留下一个带网络线程的客户端
        try (AdminClient adminClient = connectionFactory.createAdminClient(probe)) {
            Collection<Node> nodes = KafkaFutures.await(
                    adminClient.describeCluster().nodes(),
                    kafkaProperties.getAdmin().getDefaultApiTimeoutMs(),
                    "validate.describeCluster");
            long elapsedMs = System.currentTimeMillis() - startedAt;
            log.info("Cluster validation OK: servers={}, brokers={}, elapsedMs={}",
                    probe.bootstrapServers(), nodes.size(), elapsedMs);
            return ClusterValidateResult.builder()
                    .ok(true)
                    .brokerCount(nodes.size())
                    .elapsedMs(elapsedMs)
                    .build();
        }
    }

    /**
     * 用一个"无主键探针"定义去建客户端。
     *
     * <p>{@code id=0} 只是占位:它不参与任何持久化,只出现在日志里用于指认
     * "这是测连请求"(真实集群 id 从 1 开始,不会撞上)。
     */
    private ClusterDefinition probeDefinition(ClusterValidateRequest req) {
        return new ClusterDefinition(
                0L,
                "validate-probe",
                req.getBootstrapServers().trim(),
                req.deriveSecurityProtocol(),
                req.deriveSaslMechanism(),
                req.usernameOrDefault(),
                req.getPassword(),
                req.toAuthSpec(),
                req.zkConnectStringOrDefault(),
                true,
                false,
                30,
                0,
                null,
                null);
    }

    /**
     * 用库中值补全请求里留空的字段。
     *
     * <p>与 upsert 的三态语义对齐:{@code null} 或打码哨兵都表示"不修改",
     * 因此都从库中取值。非秘密字段(端点 URL / client id / 机制 / 协议)也一并补全 ——
     * 它们通常由前端回显提交,但脚本调用可能省略,补全后测连行为才与保存一致。
     */
    private void fillOmittedFields(ClusterValidateRequest req, ClusterDefinition stored) {
        AuthSpec auth = stored.auth();

        if (isOmitted(req.getPassword())) {
            req.setPassword(stored.password());
        }
        if (isOmitted(req.getSslClientCertPem())) {
            req.setSslClientCertPem(auth.sslClientCertPem());
        }
        if (isOmitted(req.getSslClientKeyPem())) {
            req.setSslClientKeyPem(auth.sslClientKeyPem());
        }
        if (isOmitted(req.getSslClientKeyPassword())) {
            req.setSslClientKeyPassword(auth.sslClientKeyPassword());
        }
        if (isOmitted(req.getSslTrustCertsPem())) {
            req.setSslTrustCertsPem(auth.sslTrustCertsPem());
        }
        if (isOmitted(req.getOauthClientSecret())) {
            req.setOauthClientSecret(auth.oauthClientSecret());
        }
        if (isOmitted(req.getCustomJaas())) {
            req.setCustomJaas(auth.customJaas());
        }
        if (req.getCustomProps() == null) {
            // 存进去的是不可变 Map,拷一份可变副本交给请求对象,避免后续 setter 语义混乱
            req.setCustomProps(new LinkedHashMap<>(auth.customProps()));
        }

        if (req.getAuthType() == null || req.getAuthType().isBlank()) {
            req.setAuthType(auth.authType());
        }
        if (req.getTlsEnabled() == null) {
            req.setTlsEnabled(auth.tlsEnabled());
        }
        if (req.getVerifyHostname() == null) {
            req.setVerifyHostname(auth.verifyHostname());
        }
        if (req.getSecurityProtocol() == null) {
            req.setSecurityProtocol(stored.securityProtocol());
        }
        if (req.getSaslMechanism() == null || req.getSaslMechanism().isBlank()) {
            req.setSaslMechanism(stored.saslMechanism());
        }
        if (req.getUsername() == null) {
            req.setUsername(stored.username());
        }
        if (req.getOauthTokenUrl() == null) {
            req.setOauthTokenUrl(auth.oauthTokenUrl());
        }
        if (req.getOauthClientId() == null) {
            req.setOauthClientId(auth.oauthClientId());
        }
        if (req.getOauthScope() == null) {
            req.setOauthScope(auth.oauthScope());
        }
    }
}
