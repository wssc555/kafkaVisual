package com.example.kafkaviz.model.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 认证材料的"存在性"回显。
 *
 * <p>为什么需要它:编辑对话框要让用户看出"这个集群已经配了客户端证书 / 已经存过
 * client secret",但<b>任何秘密的明文与密文都不得回传</b>(零凭据泄露口径)。
 * 于是只回布尔位 —— 这与 {@code StorageInfo.PASSWORD_MASK} 是同一思路的不同表达:
 * 口令只有一个,用哨兵值足够;证书/私钥/secret 有多个,用布尔位更清晰。
 *
 * <p>字段命名与 {@code AuthSpec} 的 {@code hasXxx()} 一一对应。
 */
@Data
@Builder
public class CredentialPresence {

    /** 是否已存 SASL 口令(PASSWORD)。 */
    private boolean password;

    /** 是否已存客户端证书链(MTLS)。 */
    private boolean clientCert;

    /** 是否已存客户端私钥(MTLS)。 */
    private boolean clientKey;

    /** 是否已存私钥口令(MTLS,仅加密私钥场景)。 */
    private boolean clientKeyPassword;

    /** 是否已存自备 CA 证书(MTLS;false = 使用 JVM 默认信任库)。 */
    private boolean trustCerts;

    /** 是否已存 OAuth client secret(OAUTH)。 */
    private boolean oauthClientSecret;

    /** 是否已存自定义 JAAS 串(CUSTOM)。 */
    private boolean customJaas;

    /** 是否已存附加客户端属性(CUSTOM)。 */
    private boolean customProps;
}
