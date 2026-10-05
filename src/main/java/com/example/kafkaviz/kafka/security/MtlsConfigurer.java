package com.example.kafkaviz.kafka.security;

import com.example.kafkaviz.kafka.ClusterDefinition;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.config.SslConfigs;

import java.util.Properties;

/**
 * mTLS 客户端证书策略。
 *
 * <p>用 kafka-clients 的 <b>PEM 内置支持</b>而不是 keystore 文件路径:
 * {@code ssl.keystore.type=PEM} 时,{@code ssl.keystore.certificate.chain} 与
 * {@code ssl.keystore.key} 直接接受 PEM <b>文本</b>(而非路径),正好与本项目
 * "证书材料加密入库、按需取出"的存储方式对齐,也免去桌面用户管理文件路径。
 *
 * <p><b>三个必须记住的约束</b>(不满足就会在建连时报 SSL 配置异常):
 * <ol>
 *   <li>私钥必须是 <b>PKCS#8</b>({@code -----BEGIN PRIVATE KEY-----})或
 *       <b>加密 PKCS#8</b>({@code -----BEGIN ENCRYPTED PRIVATE KEY-----});
 *       默认 SSL engine factory 不认 PKCS#1 的 {@code -----BEGIN RSA PRIVATE KEY-----};
 *       <br>实测 kafka-clients 3.9 的
 *       {@code ssl.keystore.key} 文档明确支持加密 PKCS#8 —— 只要同时给出
 *       {@code ssl.key.password}。因此本策略<b>支持</b>加密私钥(私钥口令同样加密入库),
 *       而不是把它拒之门外;PKCS#1 仍然拒绝并给出转换提示。</li>
 *   <li>私钥口令走 {@code ssl.key.password};PEM 模式下 {@code ssl.keystore.password}
 *       不被支持(keystore 没有"库口令"概念),不要混用;</li>
 *   <li>CA 证书可选:不填就沿用 JVM 默认信任库({@code ssl.truststore.location} 未配置时的
 *       默认行为),适合"服务端证书由公共 CA 签发"的场景。</li>
 * </ol>
 */
public class MtlsConfigurer implements SecurityConfigurer {

    /** PEM 文本模式(而非 JKS/PKCS12 文件) —— kafka-clients 自 2.7 起支持。 */
    private static final String PEM = "PEM";

    @Override
    public void apply(Properties props, ClusterDefinition definition) {
        // mTLS 恒为 SSL:这是"双向证书认证"的定义,与 tlsEnabled 无关
        props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SSL");

        props.put(SslConfigs.SSL_KEYSTORE_TYPE_CONFIG, PEM);
        props.put(SslConfigs.SSL_KEYSTORE_CERTIFICATE_CHAIN_CONFIG, definition.auth().sslClientCertPem());
        props.put(SslConfigs.SSL_KEYSTORE_KEY_CONFIG, definition.auth().sslClientKeyPem());

        if (definition.auth().hasClientKeyPassword()) {
            // 未加密的 PKCS#8 私钥也会带上本键:PEM 加载器只在遇到
            // "ENCRYPTED PRIVATE KEY" 时读取口令,多余的一份口令不会造成解析失败
            props.put(SslConfigs.SSL_KEY_PASSWORD_CONFIG, definition.auth().sslClientKeyPassword());
        }

        if (definition.auth().hasTrustCerts()) {
            // 只在真的给了 CA 时才声明 PEM 信任库:否则默认信任库(系统 CA)才是正确语义
            props.put(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG, PEM);
            props.put(SslConfigs.SSL_TRUSTSTORE_CERTIFICATES_CONFIG, definition.auth().sslTrustCertsPem());
        }

        SslTuning.applyHostnameVerification(props, definition);
    }
}
