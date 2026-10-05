package com.example.kafkaviz.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 短 TTL 缓存配置。
 *
 * <p>缓存的是"秒级~分钟级才变化"的数据:集群信息(broker 列表 / clusterId / controller)、
 * topic 与消费组名称列表。不加缓存时每次页面切换都要全量打一次 Kafka,叠加"慢请求占
 * Tomcat 线程"的问题会放大拥堵。
 *
 * <p>TTL 取 10s 是刻意保守:新增 broker / 外部脚本建的 topic 最多 10s 后在页面可见。
 * 本应用自己的写操作(建/删 topic、删组)通过 {@code @CacheEvict} 主动失效,写后立即可见。
 *
 * <p>说明:Spring 缓存不会缓存方法抛出的异常 —— 集群不可达时的 50302 不会被缓存,
 * 后续请求仍会真实调用 Kafka。
 *
 * <p>可用 {@code cache.enabled=false} 整体关闭缓存(默认开启):测试基建会绕过 REST API
 * 直连 AdminClient / KafkaConsumer 造资源(见 {@code BaseApiIT.createTopic}、
 * {@code ConsumerGroupApiIT} 的直连建组),这些路径不会触发 {@code @CacheEvict},
 * 共享 Spring 上下文 + TTL 内断言"新建资源必须出现在列表"会读到陈旧缓存。
 * 关闭后本配置类被跳过,{@code @EnableCaching} 随之失效,{@code @Cacheable} 变成空操作。
 *
 * <p>缓存键统一为 {@code kafka/CacheKey} 复合键
 * {@code (clusterId, arg)},集群之间天然隔离。容量按"活跃集群数"评估后取 400
 * (≈ 200 × 2 个活跃集群);单集群部署下仍远大于实际 key 数,只是桶更大而已。
 */
@Configuration
@EnableCaching
@ConditionalOnProperty(name = "cache.enabled", havingValue = "true", matchIfMissing = true)
public class CacheConfig {

    /** 集群信息缓存名,见 {@code ClusterService.describeCluster()}。 */
    public static final String CLUSTER_INFO = "clusterInfo";
    /** topic 名称列表缓存名,见 {@code TopicService.listTopics(boolean)}。 */
    public static final String TOPIC_NAMES = "topicNames";
    /** 消费组名称列表缓存名,见 {@code ConsumerGroupService.listGroups()}。 */
    public static final String GROUP_NAMES = "groupNames";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        // 容量 400:多集群下每集群约占 3 个 key(topicNames×2 + groupNames + clusterInfo),
        // 200 在 10+ 集群时会被 LRU 挤出,导致缓存命中率骤降(退化成每次打 Kafka)。
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(400)
                .expireAfterWrite(Duration.ofSeconds(10)));
        return manager;
    }
}
