package com.example.kafkaviz.kafka.security;

import com.example.kafkaviz.kafka.AuthType;
import com.example.kafkaviz.kafka.ClusterDefinition;

import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;

/**
 * 认证策略分发器。
 *
 * <p>连接工厂只调用本类的 {@link #apply(Properties, ClusterDefinition)},由它按
 * {@code authType} 选择具体策略 —— 这样"加一种认证方式"的改动面是
 * "新增一个实现 + 在本类注册一行",而不是在工厂里再插一段 switch。
 *
 * <p>无状态、纯函数式(策略实例都是无字段的),因此以静态常量持有,不做 Spring Bean ——
 * 它们不依赖任何容器服务,注入进来只会增加测试与阅读负担。
 */
public final class SecurityConfigurers {

    private static final Map<AuthType, SecurityConfigurer> BY_TYPE = new EnumMap<>(AuthType.class);

    static {
        BY_TYPE.put(AuthType.NONE, new NoAuthConfigurer());
        BY_TYPE.put(AuthType.PASSWORD, new SaslPasswordConfigurer());
        BY_TYPE.put(AuthType.MTLS, new MtlsConfigurer());
        BY_TYPE.put(AuthType.OAUTH, new OAuthConfigurer());
        BY_TYPE.put(AuthType.CUSTOM, new CustomConfigurer());
    }

    private SecurityConfigurers() {
    }

    /**
     * 按集群定义的认证方式注入配置。
     *
     * <p>调用方必须保证基础键(bootstrap.servers、超时、序列化)已在<b>之前</b>写入 ——
     * CUSTOM 的附加属性会覆盖非保留键,这是刻意保留的逃生能力。
     *
     * @throws IllegalStateException 枚举有值但未注册策略(开发期错误,应当立刻暴露)
     */
    public static void apply(Properties props, ClusterDefinition definition) {
        AuthType type = definition.auth().type();
        SecurityConfigurer configurer = BY_TYPE.get(type);
        if (configurer == null) {
            throw new IllegalStateException("No SecurityConfigurer registered for authType " + type);
        }
        configurer.apply(props, definition);
    }
}
