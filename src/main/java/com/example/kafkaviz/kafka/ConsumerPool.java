package com.example.kafkaviz.kafka;

import com.example.kafkaviz.config.KafkaProperties;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.PooledObjectFactory;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 每集群独立的浏览消费者池,基于 Apache Commons Pool2 的 {@link GenericObjectPool} 实现。
 *
 * <p>生命周期归 {@link ClusterConnection}(构造时创建、{@code close()} 时销毁),因此不是
 * Spring Bean。造消费者由 {@code factory.createConsumer(definition)} 提供,池参数取全局
 * {@code kafka.consumer.*}(pool-size / borrow-timeout-ms)。
 *
 * <p><b>实现要点</b>
 * <ul>
 *   <li>借取快路径不消耗名额,名额只约束存活实例数:{@code maxTotal} 由 pool2 内部
 *       createCount 维护,取用空闲实例不增加计数;</li>
 *   <li>创建失败不会丢失名额:pool2 的 {@code create()} 对 makeObject 失败自动回滚计数;</li>
 *   <li>归还时清空 assignment,防止下一次借用继承上一次的订阅:
 *       {@link ConsumerFactory#passivateObject};</li>
 *   <li>空闲队列满时关闭实例而不是丢在地上:归还时 idle 超过 {@code maxIdle} 由 pool2
 *       自动销毁(本实现 {@code maxIdle == maxTotal},该分支理论不可达,仅作防御保留)。</li>
 * </ul>
 *
 * <p><b>与直觉不同的两处有意设计</b>
 * <ul>
 *   <li>passivate(重置 assignment)失败时实例被<b>销毁</b>,而不是吞掉异常带病回池
 *       —— 见 {@link ConsumerFactory#passivateObject};</li>
 *   <li>{@link #close()} 只销毁空闲实例,借出中的实例在归还时销毁(pool2 对已关闭池的
 *       returnObject 直接走 destroy)。不跨线程强关借出实例:KafkaConsumer 非线程安全,
 *       与持有方的 poll 并发有并发修改风险。代价是断开集群瞬间在途请求的资源释放延迟
 *       ≤ 一个在途请求时长(有界)。</li>
 * </ul>
 *
 * <p><b>改池配置前必读(踩过的坑,勿删)</b>
 * <ul>
 *   <li>{@code maxIdle} 不得调小于 {@code maxTotal}:会出现「归还即销毁」,池有效容量单调下降,
 *       最终表现为 50301 频发且重启前不可恢复;</li>
 *   <li>不要开启 abandoned 泄漏检测:单次 borrow 的合法占用可达分钟级
 *       (MessageService 的 poll 循环上界 = count × poll-timeout-ms),会误杀在途消费者;</li>
 *   <li>不要开启 eviction 空闲回收与 JMX:前者会改掉「消费者活到
 *       {@code ClusterConnection.close()}」的既有语义,后者会为每个集群注册一个 MBean
 *       (桌面应用不需要,还要躲重名)。</li>
 * </ul>
 */
public class ConsumerPool implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConsumerPool.class);

    /**
     * 销毁实例时的关闭硬上界(秒)。浏览型 consumer 没有位移要提交,2s 足够;销毁路径不能被
     * KafkaConsumer 内部默认的 30s 超时拖住 —— 它串在空闲回收线程与进程退出上
     * (与 {@code ClusterConnection} 关闭 producer 的限时纪律一致)。
     */
    private static final long DESTROY_CLOSE_TIMEOUT_SECONDS = 2L;

    private final ClusterDefinition definition;
    private final long borrowTimeoutMs;
    private final GenericObjectPool<KafkaConsumer<byte[], byte[]>> pool;

    /**
     * 本池是否已关闭。
     *
     * <p>只用于 borrow 时判别 pool2 抛出的 {@link IllegalStateException} 到底是不是「池已关闭」——
     * 另一种可能是工厂侧自己抛的 IllegalStateException(连接参数问题),那必须原样穿透,
     * 不能误报成 50301 把配置错误伪装成池问题。
     */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public ConsumerPool(ClusterConnectionFactory factory, ClusterDefinition definition, KafkaProperties props) {
        this.definition = definition;
        this.borrowTimeoutMs = props.getConsumer().getBorrowTimeoutMs();
        this.pool = new GenericObjectPool<>(new ConsumerFactory(factory, definition),
                buildConfig(props.getConsumer().getPoolSize(), borrowTimeoutMs));
    }

    /**
     * 池配置。刻意在构造函数链里局部构建、不对外暴露引用:池参数只允许来自全局
     * {@code kafka.consumer.*},后来人(含未来的自己)改不到,免得踩 maxIdle &lt; maxTotal 的坑。
     */
    private static GenericObjectPoolConfig<KafkaConsumer<byte[], byte[]>> buildConfig(int maxSize,
                                                                                      long borrowTimeoutMs) {
        GenericObjectPoolConfig<KafkaConsumer<byte[], byte[]>> config = new GenericObjectPoolConfig<>();
        // 存活实例数上界:存活实例数 ≤ pool-size
        config.setMaxTotal(maxSize);
        // 空闲上界必须等于 maxTotal(小于它 → 归还即销毁,见类注释)
        config.setMaxIdle(maxSize);
        // 保持惰性创建:不预热,首个请求才建消费者
        config.setMinIdle(0);
        // 池满时阻塞等待归还,超过 maxWait 抛 NoSuchElementException(由 borrow 翻译成 50301)
        config.setBlockWhenExhausted(true);
        config.setMaxWait(Duration.ofMillis(borrowTimeoutMs));
        // KafkaConsumer 没有便宜的探活手段(ping),不做借出/归还校验
        config.setTestOnBorrow(false);
        config.setTestOnReturn(false);
        config.setTestWhileIdle(false);
        // 归还顺序默认 LIFO:浏览请求无公平性诉求,后归还的实例先被复用(热实例)
        config.setLifo(true);
        // 每集群一个池:不注册 JMX MBean(免得为每个集群生成无意义 MBean)
        config.setJmxEnabled(false);
        return config;
    }

    /**
     * 从池中借取一个 Consumer,超时抛 {@link ConsumerPoolExhaustedException}(HTTP 503/50301)。
     *
     * <p><b>异常翻译规则(勿改,否则错误码会漂)</b>
     * <ul>
     *   <li>{@link NoSuchElementException}:池已达 maxTotal 且等待空闲对象超时 → 50301;</li>
     *   <li>{@link IllegalStateException}:本池已关闭(断开集群与并发 borrow 的竞态窗口)
     *       → 50301,pool2 的 isClosed 检查保证快速失败,避免在该窗口泄漏新消费者;</li>
     *   <li>工厂异常({@link ConsumerFactory#makeObject} 抛出的 RuntimeException,如连接参数/
     *       序列化错误)原样穿透 → 既有 50001 路径,不伪装成 50301。</li>
     * </ul>
     */
    public KafkaConsumer<byte[], byte[]> borrow() {
        try {
            return pool.borrowObject(Duration.ofMillis(borrowTimeoutMs));
        } catch (NoSuchElementException e) {
            log.warn("Consumer pool exhausted for cluster {} (active={}, idle={}, waiters={})",
                    definition.id(), pool.getNumActive(), pool.getNumIdle(), pool.getNumWaiters());
            throw new ConsumerPoolExhaustedException("Consumer pool exhausted, please retry later");
        } catch (IllegalStateException e) {
            if (!closed.get()) {
                // 池没关却抛 ISE:是工厂侧自己的错误,原样穿透(见 closed 字段注释)
                throw e;
            }
            throw new ConsumerPoolExhaustedException("Consumer pool is closed for cluster " + definition.id());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConsumerPoolExhaustedException("Interrupted while waiting for consumer");
        } catch (Exception e) {
            // borrowObject 声明了 throws Exception(工厂侧 checked 失败)的兜底,不让调用方看到 checked
            throw new IllegalStateException("Unexpected error borrowing consumer of cluster "
                    + definition.id(), e);
        }
    }

    /**
     * 归还 Consumer(pool2 会先 passivate 清空 assignment 再放回空闲队列)。
     *
     * <p><b>本方法永不抛出</b>:归还发生在 try-with-resources 的关闭路径上
     * ({@link BorrowedConsumer}),抛异常会把一次已经成功的浏览请求变成 50000。二次归还 /
     * 归还非本池对象(pool2 抛 IllegalStateException)只告警;池已关闭时归还路径在 pool2 内部
     * 走销毁,同样不抛。
     */
    public void release(KafkaConsumer<byte[], byte[]> consumer) {
        if (consumer == null) {
            return;
        }
        try {
            pool.returnObject(consumer);
        } catch (RuntimeException e) {
            log.warn("Failed to return consumer to pool of cluster {}: {}", definition.id(), e.getMessage(), e);
        }
    }

    /**
     * 关闭池:销毁全部空闲实例并把池标记为已关闭(此后 {@link #borrow()} 快速失败)。
     *
     * <p>借出中的实例不在此处强关(原因见类注释行为差异②),由持有方自然归还时销毁。
     * 幂等:重复调用直接返回。由 {@link ClusterConnection#close()} 调用(不走 Spring 销毁回调)。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        log.info("Closing consumer pool of cluster {} (active={}, idle={})...",
                definition.id(), pool.getNumActive(), pool.getNumIdle());
        try {
            pool.close();
        } catch (Exception e) {
            log.warn("Error closing consumer pool of cluster {}: {}", definition.id(), e.getMessage());
        }
    }

    /**
     * KafkaConsumer 的池化工厂:创建 / 重置 / 销毁三条路径。
     *
     * <p>本类无状态(pool2 约定工厂必须线程安全,状态都放在 {@code PooledObject} 里),
     * 因此天然满足。
     */
    static final class ConsumerFactory implements PooledObjectFactory<KafkaConsumer<byte[], byte[]>> {

        private final ClusterConnectionFactory factory;
        private final ClusterDefinition definition;

        ConsumerFactory(ClusterConnectionFactory factory, ClusterDefinition definition) {
            this.factory = factory;
            this.definition = definition;
        }

        /**
         * KafkaConsumer 构造不触网,失败基本是配置/序列化问题;异常不在此处包装,原样上抛,
         * 由 {@link ConsumerPool#borrow()} 透传成既有 50001 路径。
         */
        @Override
        public PooledObject<KafkaConsumer<byte[], byte[]>> makeObject() {
            KafkaConsumer<byte[], byte[]> consumer = factory.createConsumer(definition);
            log.debug("Created consumer for cluster {}", definition.id());
            return new DefaultPooledObject<>(consumer);
        }

        /** 销毁:限时关闭,失败只记日志(pool2 也会吞掉本方法的异常,自己记一条更可靠)。 */
        @Override
        public void destroyObject(PooledObject<KafkaConsumer<byte[], byte[]>> p) {
            KafkaConsumer<byte[], byte[]> consumer = p.getObject();
            if (consumer == null) {
                return;
            }
            try {
                consumer.close(Duration.ofSeconds(DESTROY_CLOSE_TIMEOUT_SECONDS));
            } catch (Exception e) {
                log.warn("Error closing consumer of cluster {}", definition.id(), e);
            }
        }

        /**
         * 归还前重置状态:清空 assignment,防止下一次借用继承上一次的订阅。
         *
         * <p><b>失败时重抛</b>(见类注释「与直觉不同的两处有意设计」①):
         * pool2 收到 passivate 异常会 invalidate + destroy —— 坏实例销毁而不是带病回池。
         * 该异常由 pool2 内部吞掉,不会污染 try-with-resources 里的业务异常;这里自己记日志
         * 是因为 pool2 默认不打印被吞掉的异常。
         */
        @Override
        public void passivateObject(PooledObject<KafkaConsumer<byte[], byte[]>> p) throws Exception {
            KafkaConsumer<byte[], byte[]> consumer = p.getObject();
            if (consumer == null) {
                return;
            }
            try {
                consumer.assign(Collections.emptyList());
            } catch (Exception e) {
                log.warn("Failed to reset consumer assignment for cluster {}, instance will be destroyed",
                        definition.id(), e);
                throw e;
            }
        }

        /** 未开启 testOnBorrow / testOnReturn / testWhileIdle,本方法不会被调用。 */
        @Override
        public boolean validateObject(PooledObject<KafkaConsumer<byte[], byte[]>> p) {
            return true;
        }

        /** 无操作:assignment 由调用方(MessageService)在借用后显式设置。 */
        @Override
        public void activateObject(PooledObject<KafkaConsumer<byte[], byte[]>> p) {
            // no-op
        }
    }
}
