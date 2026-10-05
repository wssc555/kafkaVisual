package com.example.kafkaviz.kafka.security;

import com.example.kafkaviz.kafka.ClusterDefinition;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.security.plain.PlainLoginModule;
import org.apache.kafka.common.security.scram.ScramLoginModule;

import java.util.Properties;

/**
 * 用户名口令认证策略(SASL/PLAIN 与 SASL/SCRAM)。
 *
 * <p>写协议、机制与 JAAS 串。三点值得注意:
 * <ul>
 *   <li><b>协议按 {@code tlsEnabled} 派生</b>({@code SASL_SSL} / {@code SASL_PLAINTEXT}),
 *       不读库里的 {@code security_protocol} 列 —— 该列是 AuthSpec 的物化镜像
 *       (写入时由同一套派生规则生成),以 AuthSpec 为唯一权威可以避免"两个来源打架";</li>
 *   <li><b>PLAIN 必须配 TLS</b>:{@code SASL_PLAINTEXT + PLAIN} 会把口令明文过网,
 *       前端已就"关闭传输加密"给出警告,后端不做硬拦截(内网调试场景确有需求);</li>
 *   <li><b>JAAS 串必须转义</b>:凭据里出现 {@code \} 或 {@code "} 会破坏
 *       {@code username="..." password="..."} 的字面量结构,见 {@link #escapeJaas(String)}。</li>
 * </ul>
 */
public class SaslPasswordConfigurer implements SecurityConfigurer {

    @Override
    public void apply(Properties props, ClusterDefinition definition) {
        props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG,
                definition.auth().tlsEnabled() ? "SASL_SSL" : "SASL_PLAINTEXT");

        String mechanism = definition.saslMechanism();
        if (mechanism == null || mechanism.isBlank()) {
            mechanism = "PLAIN";
        }
        props.put(SaslConfigs.SASL_MECHANISM, mechanism);

        String jaasConfig = String.format(
                "%s required username=\"%s\" password=\"%s\";",
                loginModuleFor(mechanism),
                escapeJaas(definition.username()),
                escapeJaas(definition.password()));
        props.put(SaslConfigs.SASL_JAAS_CONFIG, jaasConfig);

        SslTuning.applyHostnameVerification(props, definition);
    }

    /** SCRAM 两种机制用 {@code ScramLoginModule},其余落 {@code PlainLoginModule}(含 null 兜底)。 */
    private String loginModuleFor(String mechanism) {
        if (mechanism == null) {
            return PlainLoginModule.class.getName();
        }
        return switch (mechanism) {
            case "SCRAM-SHA-256", "SCRAM-SHA-512" -> ScramLoginModule.class.getName();
            default -> PlainLoginModule.class.getName();
        };
    }

    /** 转义 JAAS 配置中的反斜杠和双引号,防止凭据含特殊字符破坏配置串。 */
    private String escapeJaas(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
