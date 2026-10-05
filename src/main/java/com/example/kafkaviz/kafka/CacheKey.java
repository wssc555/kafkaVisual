package com.example.kafkaviz.kafka;

/**
 * 缓存复合键。
 *
 * <p>多集群下"key 是裸 String"不再成立:{@code topicNames + false} 这个 key
 * 在 A、B 两个集群之间是共享的,TTL 内 B 会读到 A 的 topic 列表 ——
 * 这不是"不够优雅",是真实的数据串集群。
 *
 * <p>用 record 而不是拼字符串:record 的 {@code equals/hashCode} 由编译器按组件生成,
 * 天然避免"分隔符恰好出现在参数里"造成的键碰撞(集群名/topic 名允许各种字符)。
 *
 * <p>SpEL 用法(注解里只能写字面量表达式,故提供静态工厂):
 * <pre>{@code
 * @Cacheable(value = CacheConfig.TOPIC_NAMES,
 *            key = "T(com.example.kafkaviz.kafka.CacheKey).of(#connection.clusterId(), #includeInternal)")
 * }</pre>
 *
 * @param clusterId 集群 id
 * @param key       集群内的原始键(布尔 / 字符串 / null)
 */
public record CacheKey(long clusterId, Object key) {

    /** SpEL 静态工厂:比 {@code new CacheKey(...)} 更稳(SpEL 的 new 表达式对全限定名与构造器解析更挑剔)。 */
    public static CacheKey of(long clusterId, Object key) {
        return new CacheKey(clusterId, key);
    }
}
