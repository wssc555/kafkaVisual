package com.example.kafkaviz.kafka.security;

import com.example.kafkaviz.kafka.ClusterDefinition;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.config.SaslConfigs;

import java.util.Properties;

/**
 * OAuth2 / SASL-OAUTHBEARER 策略。
 *
 * <p>与其它机制的<b>关键差异</b>:OAUTHBEARER 的客户端登录不走 JAAS LoginModule 参数,
 * 而是走一个 {@code AuthenticateCallbackHandler}:
 * <ul>
 *   <li>必须设 {@link SaslConfigs#SASL_LOGIN_CALLBACK_HANDLER_CLASS_CONFIG},指向
 *       {@link BuiltinOAuthLoginCallbackHandler};</li>
 *   <li><b>不设</b> {@code sasl.jaas.config} —— 设了不生效,反而让排查者误以为
 *       "口令写错了"。这是 OAUTHBEARER 最容易踩的静默坑:漏配 login handler 时
 *       Kafka 会退到 {@code UnsecuredOAuthBearer*} 测试实现,表现为"能连上但不认证";</li>
 *   <li>handler 通过自定义配置键读取端点与凭据({@code kafkaviz.oauth.*}),
 *       见 {@link BuiltinOAuthLoginCallbackHandler} 的常量。</li>
 * </ul>
 */
public class OAuthConfigurer implements SecurityConfigurer {

    @Override
    public void apply(Properties props, ClusterDefinition definition) {
        props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG,
                definition.auth().tlsEnabled() ? "SASL_SSL" : "SASL_PLAINTEXT");
        props.put(SaslConfigs.SASL_MECHANISM, "OAUTHBEARER");
        props.put(SaslConfigs.SASL_LOGIN_CALLBACK_HANDLER_CLASS,
                BuiltinOAuthLoginCallbackHandler.class.getName());

        // 凭据经自定义键传给 handler:这些键只在本进程内传给一个 callback handler,
        // 不会被 Kafka 打日志(与 sasl.jaas.config 不同,后者在异常消息里可能被回显)
        props.put(BuiltinOAuthLoginCallbackHandler.TOKEN_URL_CONFIG, definition.auth().oauthTokenUrl());
        props.put(BuiltinOAuthLoginCallbackHandler.CLIENT_ID_CONFIG, definition.auth().oauthClientId());
        props.put(BuiltinOAuthLoginCallbackHandler.CLIENT_SECRET_CONFIG, definition.auth().oauthClientSecret());
        if (!definition.auth().oauthScope().isEmpty()) {
            props.put(BuiltinOAuthLoginCallbackHandler.SCOPE_CONFIG, definition.auth().oauthScope());
        }

        SslTuning.applyHostnameVerification(props, definition);
    }
}
