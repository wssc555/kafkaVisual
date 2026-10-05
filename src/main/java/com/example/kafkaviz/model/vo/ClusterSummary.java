package com.example.kafkaviz.model.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 集群列表 / 状态回显 VO。
 *
 * <p><b>零凭据泄露</b>:不含 password / password_cipher 任何形态的字段。
 * 含 {@code username} 是必要的 —— 编辑对话框要回显账号,
 * 而账号本身不是秘密(真正的秘密只有口令)。秘密材料
 * (私钥 / client secret / JAAS / 附加属性)同样<b>只回布尔位</b>,
 * 见 {@link #credentialPresence};非秘密的可回显字段(token URL / client id / scope)
 * 则直接回显,否则编辑对话框无法工作。
 *
 * <p>注意 {@code securityProtocol} / {@code saslMechanism} 是<b>物化派生列</b>:
 * 值由 {@code authType + tlsEnabled}(CUSTOM 时为用户手选)算出,不再是用户直接提交的
 * 原始值。展示与判断请优先用 {@link #authType}。
 */
@Data
@Builder
public class ClusterSummary {

    private long id;

    private String name;

    private String bootstrapServers;

    /**
     * 认证方式判别符:NONE / PASSWORD / MTLS / OAUTH / CUSTOM。
     *
     * <p>对任何已有集群都非空。
     */
    private String authType;

    /** 传输加密开关(NONE/PASSWORD/OAUTH 有效;MTLS 恒加密)。 */
    private boolean tlsEnabled;

    /** 是否校验服务器主机名(SSL/SASL_SSL 有效)。 */
    private boolean verifyHostname;

    /** 物化派生列:空 / PLAINTEXT / SSL / SASL_PLAINTEXT / SASL_SSL。 */
    private String securityProtocol;

    /** 物化派生列:PLAIN / SCRAM-* / OAUTHBEARER / 空。 */
    private String saslMechanism;

    private String username;

    // ---- 认证材料的"存在性"(秘密本身永不回传) ----

    /** 各秘密材料是否已配置;编辑对话框据此渲染"已配置/未配置"占位。 */
    private CredentialPresence credentialPresence;

    // ---- 非秘密的回显字段(编辑需要) ----

    /** OAuth token 端点(OAUTH)。 */
    private String oauthTokenUrl;

    /** OAuth client id(OAUTH)。 */
    private String oauthClientId;

    /** OAuth scope(OAUTH,可空)。 */
    private String oauthScope;

    /** 空 = 该集群 KRaft 模式。 */
    private String zkConnectString;

    /** 该集群是否有 ZK(前端据此决定是否显示 ZK 浏览器入口)。 */
    private boolean zkAvailable;

    /** ONLINE / OFFLINE / CONNECTING(由 {@code ClusterState.displayState()} 归并)。 */
    private String displayState;

    /** 最近一次失败摘要;非 ERROR 态为 null。 */
    private String errorSummary;

    /** 最近一次失败时刻(ISO-8601);非 ERROR 态为 null。 */
    private String errorAt;

    private boolean enabled;

    private boolean archiveEnabled;

    private int archiveRetentionDays;

    private int sortOrder;

    private String createdAt;

    private String updatedAt;
}
