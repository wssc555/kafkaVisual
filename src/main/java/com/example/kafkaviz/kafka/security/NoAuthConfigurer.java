package com.example.kafkaviz.kafka.security;

import com.example.kafkaviz.kafka.ClusterDefinition;
import org.apache.kafka.clients.CommonClientConfigs;

import java.util.Properties;

/**
 * 无认证(NONE)策略。
 *
 * <p>两种形态:
 * <ul>
 *   <li>{@code tlsEnabled=false} —— 不写任何认证键,等价于 Kafka 默认的
 *       {@code PLAINTEXT}(也让存量"空协议"行行为完全不变);</li>
 *   <li>{@code tlsEnabled=true} —— 只启用传输加密({@code SSL}),<b>不做身份认证</b>。
 *       它只解决链路机密性,不构成任何认证能力,前端文案已明确这一点。</li>
 * </ul>
 */
public class NoAuthConfigurer implements SecurityConfigurer {

    @Override
    public void apply(Properties props, ClusterDefinition definition) {
        if (!definition.auth().tlsEnabled()) {
            return;
        }
        props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SSL");
        SslTuning.applyHostnameVerification(props, definition);
    }
}
