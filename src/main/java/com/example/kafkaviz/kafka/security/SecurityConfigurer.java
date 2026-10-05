package com.example.kafkaviz.kafka.security;

import com.example.kafkaviz.kafka.ClusterDefinition;

import java.util.Properties;

/**
 * 认证配置注入策略。
 *
 * <p>五种认证方式(无认证 / 用户名口令 / mTLS / OAuth / 自定义)各有一个实现,
 * 继续堆分支会让一个方法同时承担五种配置语义、并且互相干扰(例如 OAuth 不需要 JAAS、
 * mTLS 不碰 SASL)。
 *
 * <p>实现类必须<b>只</b>负责"按定义往 Properties 里写认证相关键":
 * <ul>
 *   <li>不创建客户端、不做任何 RPC(工厂才是建客户端的地方);</li>
 *   <li>不读全局超时配置(那些键由工厂在调用本接口之前写入);</li>
 *   <li>无状态、线程安全(单例 Bean/常量持有,可能被并发复用)。</li>
 * </ul>
 */
public interface SecurityConfigurer {

    /**
     * 注入认证配置。
     *
     * @param props      待填充的客户端配置(调用方保证已写入 bootstrap.servers 与超时)
     * @param definition 集群定义(auth 组件永不为 null)
     */
    void apply(Properties props, ClusterDefinition definition);
}
