package com.example.kafkaviz.kafka;

import com.example.kafkaviz.config.KafkaProperties;
import com.example.kafkaviz.config.ZkProperties;
import com.example.kafkaviz.kafka.security.SecurityConfigurers;
import com.example.kafkaviz.zk.ZkClientManager;
import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Properties;
import java.util.UUID;

/**
 * 按 {@link ClusterDefinition} 造 Kafka / ZK 客户端的<b>纯工厂</b>(无状态)。
 *
 * <p>入参为「集群连接参数 + 全局超时/池默认值」,同一进程可服务任意多个集群。
 *
 * <p>超时纪律:AdminClient / Consumer 的阻塞上限取 {@code kafka.admin.*} 全局值
 * (见 {@code KafkaProperties.Admin});本类只造客户端,<b>不做任何 RPC</b> ——
 * 每一次异步调用仍必须走 {@link KafkaFutures#await}。
 */
@Component
public class ClusterConnectionFactory {

    private static final Logger log = LoggerFactory.getLogger(ClusterConnectionFactory.class);

    private final KafkaProperties kafkaProperties;
    private final ZkProperties zkProperties;

    public ClusterConnectionFactory(KafkaProperties kafkaProperties, ZkProperties zkProperties) {
        this.kafkaProperties = kafkaProperties;
        this.zkProperties = zkProperties;
    }

    /**
     * 半成品回收辅助:资源以<b>参数</b>传入(而非方法引用)—— try 块内赋值过的局部
     * 变量不是 effectively final,lambda/方法引用无法捕获;null 直接跳过。
     * 关闭失败只记 debug,绝不能让清理异常掩盖原始失败原因。
     */
    private static void closeQuietly(String what, ClusterDefinition definition, AutoCloseable resource) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception e) {
            log.debug("Failed to close half-built {} of cluster {}: {}",
                    what, definition.id(), e.getMessage());
        }
    }

    /**
     * 造一个完整的集群连接(AdminClient + Producer + ConsumerPool + 可选 Curator/ZkClientManager)。
     *
     * <p>不在此处探活 —— "建客户端"与"连得上"是两件事,探活由
     * {@link ClusterConnectionManager} 负责(它才好把失败落成 ERROR 摘要)。
     *
     * <p><b>半成品自回收</b>:各客户端是顺序创建的,任何一步抛异常时,调用方
     * ({@code connectLocked})只持有 null,回收不到本方法已建好的前几步客户端 ——
     * 所以清理必须在工厂内部完成,否则每次失败都泄漏一组客户端句柄。
     */
    public ClusterConnection create(ClusterDefinition definition) {
        AdminClient adminClient = createAdminClient(definition);
        KafkaProducer<byte[], byte[]> producer = null;
        ConsumerPool pool = null;
        CuratorFramework curator = null;
        ZkClientManager zkClientManager = null;
        try {
            producer = createProducer(definition);
            pool = new ConsumerPool(this, definition, kafkaProperties);

            if (definition.zkConfigured()) {
                curator = createCurator(definition);
                zkClientManager = new ZkClientManager(curator, zkProperties);
            }

            log.info("Created connection for cluster {} ({}), zk={}",
                    definition.id(), definition.name(), definition.zkConfigured());
            return new ClusterConnection(definition, adminClient, producer, pool, curator, zkClientManager);
        } catch (RuntimeException e) {
            // 关闭顺序与创建顺序相反。ZkClientManager.close() 内部会关 Curator,
            // 而 Curator.close() 本身幂等,重复关无副作用。
            closeQuietly("zk-client", definition, zkClientManager);
            closeQuietly("curator", definition, curator);
            closeQuietly("consumer-pool", definition, pool);
            closeQuietly("producer", definition, producer);
            closeQuietly("admin-client", definition, adminClient);
            throw e;
        }
    }

    // ---------------------------------------------------------------
    // Kafka 客户端
    // ---------------------------------------------------------------

    public AdminClient createAdminClient(ClusterDefinition definition) {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, definition.bootstrapServers());
        // 显式超时:默认 60s 会占满 Tomcat 线程(前端 axios 15s 即超时重试),
        // 按 kafka.admin.* 钳制到 10s/5s,使任意一次 Admin 调用阻塞时间可控。
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG,
                kafkaProperties.getAdmin().getDefaultApiTimeoutMs());
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG,
                kafkaProperties.getAdmin().getRequestTimeoutMs());
        applySecurity(props, definition);
        return AdminClient.create(props);
    }

    /** 共享生产者:{@code KafkaProducer} 线程安全,与 {@code KafkaConsumer} 不同,无需池化。 */
    public KafkaProducer<byte[], byte[]> createProducer(ClusterDefinition definition) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, definition.bootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, kafkaProperties.getProducer().getAcks());
        applySecurity(props, definition);
        return new KafkaProducer<>(props);
    }

    /**
     * 浏览用消费者:{@code assign()} 手动分配 + 随机 {@code group.id} ——
     * 对真实消费组零影响(见 {@code ConsumerPool} 的注释)。
     */
    public KafkaConsumer<byte[], byte[]> createConsumer(ClusterDefinition definition) {
        Properties props = baseConsumerProps(definition);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "kafka-viz-" + UUID.randomUUID());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none");
        return new KafkaConsumer<>(props);
    }

    /**
     * 归档消费者:{@code subscribe()} + 固定真实组名。
     *
     * <p>与浏览消费的关键差异:归档需要组协调与分区自动分配,所以用 {@code subscribe}
     * 与固定组名 {@code kafkaviz-archive-<clusterId>} —— 该组会出现在消费组列表里,
     * 属预期行为(UI 加前缀徽标说明,不做隐藏过滤)。
     *
     * @param clusterId 集群 id,用于组名
     */
    public KafkaConsumer<byte[], byte[]> createArchiveConsumer(ClusterDefinition definition, long clusterId) {
        Properties props = baseConsumerProps(definition);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "kafkaviz-archive-" + clusterId);
        // 归档语义:从头补齐,offset 提交由归档器手动完成
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // 单轮 poll 的内存上界:大消息集群下避免一次 fetch 打爆堆
        props.put(ConsumerConfig.FETCH_MAX_BYTES_CONFIG, "4194304");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "500");
        // 归档器用 Pattern 订阅全量业务 topic:即使元数据里出现当前不存在的 topic,
        // 也不能顺手把它创建出来(KIP-361 的自动创建语义),否则一次误订阅会污染业务集群。
        props.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, "false");
        return new KafkaConsumer<>(props);
    }

    /**
     * 两类消费者共用的基础配置,保证超时纪律一致。
     *
     * <p>Consumer 的 {@code endOffsets/beginningOffsets/offsetsForTimes} 是同步 API,
     * 无 future 可超时,只能靠客户端级超时兜底,故与 AdminClient 共用同一组值。
     */
    private Properties baseConsumerProps(ClusterDefinition definition) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, definition.bootstrapServers());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG,
                kafkaProperties.getAdmin().getDefaultApiTimeoutMs());
        props.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG,
                kafkaProperties.getAdmin().getRequestTimeoutMs());
        applySecurity(props, definition);
        return props;
    }

    // ---------------------------------------------------------------
    // ZooKeeper
    // ---------------------------------------------------------------

    /**
     * 按集群配置建 Curator(connectString 非空才会被调用)。
     *
     * <p>session / connection timeout 取全局 {@code zk.*} 默认值。
     */
    public CuratorFramework createCurator(ClusterDefinition definition) {
        RetryPolicy retryPolicy = new ExponentialBackoffRetry(1000, 3);
        CuratorFramework curator = CuratorFrameworkFactory.builder()
                .connectString(definition.zkConnectString())
                .sessionTimeoutMs(zkProperties.getSessionTimeoutMs())
                .connectionTimeoutMs(zkProperties.getConnectionTimeoutMs())
                .retryPolicy(retryPolicy)
                .build();
        curator.start();
        return curator;
    }

    // ---------------------------------------------------------------
    // 认证(委托给 kafka/security 的策略实现)
    // ---------------------------------------------------------------

    /**
     * 按集群的 {@code authType} 注入认证参数。
     *
     * <p>五种认证方式(无认证 / 用户名口令 / mTLS /
     * OAuth / 自定义)各自封成 {@code SecurityConfigurer} 实现,本方法只负责一次委托调用。
     *
     * <p><b>顺序纪律</b>:基础键(地址、超时、序列化)先写、认证键后写、自定义
     * 附加属性最后覆盖 —— 当前调用点都满足(本方法在 baseConsumerProps/createAdminClient
     * 里都是最后一步),新增客户端类型时不要打破这一点。
     */
    private void applySecurity(Properties props, ClusterDefinition definition) {
        SecurityConfigurers.apply(props, definition);
    }
}
