package com.example.kafkaviz.kafka.security;

import com.example.kafkaviz.kafka.ClusterDefinition;
import org.apache.kafka.common.config.SslConfigs;

import java.util.Properties;

/**
 * TLS 相关公共键的收口点。
 *
 * <p>目前只有一件小事:<b>关闭服务器主机名校验</b>。自签证书 / 内网 IP 直连时
 * CN/SAN 与地址不匹配是 mTLS 与 SASL_SSL 的第一大故障源,因此提供显式开关
 * ({@code verifyHostname=false} → 把 {@code ssl.endpoint.identification.algorithm}
 * 置为空串)。
 *
 * <p>只写"关闭"这一侧:开启是 Kafka 默认值({@code https}),显式写回默认值没有意义,
 * 反而会让"用户想用集群侧配置覆盖"的余地消失。
 */
final class SslTuning {

    private SslTuning() {
    }

    /** {@code verifyHostname=false} 时关闭端点识别(主机名校验)。 */
    static void applyHostnameVerification(Properties props, ClusterDefinition definition) {
        if (!definition.auth().verifyHostname()) {
            props.put(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG, "");
        }
    }
}
