package com.example.kafkaviz.kafka.security;

import com.example.kafkaviz.kafka.AuthSpec;
import com.example.kafkaviz.kafka.ClusterDefinition;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.config.SaslConfigs;

import java.util.Properties;

/**
 * 自定义逃生舱策略(CUSTOM)。
 *
 * <p>用途:一次投入覆盖长尾认证 —— Kerberos(GSSAPI)、Delegation Token、
 * 云厂商 SASL 扩展(AWS MSK IAM 等)。做法是"把程序不认识的配置原样交给 Kafka":
 * 协议/机制手选 + 完整 JAAS 串 + 附加客户端属性。
 *
 * <p>三条刻意的行为约定:
 * <ol>
 *   <li><b>JAAS 不转义</b>:与用户名口令路径(sasl.jaas.config 由程序拼装、必须转义)
 *       相反,这里是用户自己写的完整串,程序只做搬运。前端文案已提示"原样生效";</li>
 *   <li><b>附加属性最后写入,允许覆盖</b>:逃生舱的意义就是"能做程序没想到的事",
 *       因此它在工厂基础键之后合并(见 {@code ClusterConnectionFactory} 的调用顺序);
 *       但保留键(bootstrap.servers / security.protocol / sasl.mechanism /
 *       sasl.jaas.config)在校验层被拒绝(40001),避免自相矛盾的配置;</li>
 *   <li><b>不依赖外部 jar 的机制仍需自行提供 jar</b>:例如 MSK IAM 需要 AWS SDK 在
 *       classpath 上。桌面版无法动态加载 jar,这条限制已在 UI 文案中说明。</li>
 * </ol>
 */
public class CustomConfigurer implements SecurityConfigurer {

    @Override
    public void apply(Properties props, ClusterDefinition definition) {
        AuthSpec auth = definition.auth();

        // 协议与机制是"手选"值:它们的存在形式就是 ClusterDefinition 的两个物化列
        String protocol = definition.securityProtocol();
        if (protocol != null && !protocol.isBlank()) {
            props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, protocol.trim());
        }
        String mechanism = definition.saslMechanism();
        if (mechanism != null && !mechanism.isBlank()) {
            props.put(SaslConfigs.SASL_MECHANISM, mechanism.trim());
        }

        if (auth.hasCustomJaas()) {
            props.put(SaslConfigs.SASL_JAAS_CONFIG, auth.customJaas());
        }

        // 最后合并:附加属性可覆盖前面写入的任何非保留键
        if (auth.hasCustomProps()) {
            props.putAll(auth.customProps());
        }

        SslTuning.applyHostnameVerification(props, definition);
    }
}
