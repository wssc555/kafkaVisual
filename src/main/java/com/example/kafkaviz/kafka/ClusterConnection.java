package com.example.kafkaviz.kafka;

import com.example.kafkaviz.zk.ZkClientManager;
import org.apache.curator.framework.CuratorFramework;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单个集群的客户端集合(非 Spring Bean,由 {@link ClusterConnectionFactory} 产出,
 * 由 {@link ClusterConnectionManager} 持有生命周期)。
 *
 * <p>按集群隔离的东西全在这里:AdminClient、共享 Producer、ConsumerPool、
 * 以及该集群<b>可选</b>的 Curator + {@link ZkClientManager}(只有配了
 * {@code zk_connect_string} 的集群才有)。
 *
 * <p>线程安全的点:{@link #close()} 幂等({@link AtomicBoolean}),
 * {@link #touch()} / {@link #lastAccessNanos()} 用 volatile —— 空闲回收线程与
 * 业务线程并发读写。
 */
public class ClusterConnection implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ClusterConnection.class);

    /**
     * 关闭生产者的硬上界(秒):{@code close(Duration)} 在此时间内未完成的在途消息
     * 会被放弃。权衡:5s 足够正常 ack=all 批次发完;极端拥塞时宁可丢"关闭瞬间的
     * 最后一批产出",也不能拖住空闲回收线程与进程退出。
     */
    private static final long PRODUCER_CLOSE_TIMEOUT_SECONDS = 5L;

    private final ClusterDefinition definition;
    private final AdminClient adminClient;
    private final KafkaProducer<byte[], byte[]> producer;
    private final ConsumerPool consumerPool;
    /** 该集群未配 ZK 时为 null。 */
    private final CuratorFramework curator;
    /** 与 curator 同生共死:无 ZK 的集群为 null。 */
    private final ZkClientManager zkClientManager;

    private final AtomicBoolean closed = new AtomicBoolean(false);
    /** 最近一次被业务触碰的时刻,仅供空闲回收判断。 */
    private volatile long lastAccessNanos = System.nanoTime();
    /** 归档器句柄,未启动 / 已停止时为 null。 */
    private volatile ClusterArchiverHandle archiver;

    public ClusterConnection(ClusterDefinition definition,
                             AdminClient adminClient,
                             KafkaProducer<byte[], byte[]> producer,
                             ConsumerPool consumerPool,
                             CuratorFramework curator,
                             ZkClientManager zkClientManager) {
        this.definition = definition;
        this.adminClient = adminClient;
        this.producer = producer;
        this.consumerPool = consumerPool;
        this.curator = curator;
        this.zkClientManager = zkClientManager;
    }

    public long clusterId() {
        return definition.id();
    }

    public ClusterDefinition definition() {
        return definition;
    }

    public AdminClient getAdminClient() {
        return adminClient;
    }

    public KafkaProducer<byte[], byte[]> getProducer() {
        return producer;
    }

    public ConsumerPool getConsumerPool() {
        return consumerPool;
    }

    /** 该集群是否有 ZooKeeper。前端据此决定是否显示 ZK 浏览器入口。 */
    public boolean zkAvailable() {
        return zkClientManager != null;
    }

    /** 可空:无 ZK 的集群返回 null,调用方(如 ZkService)应先判 {@link #zkAvailable()}。 */
    public ZkClientManager getZkClientManager() {
        return zkClientManager;
    }

    /** 可空:无 ZK 的集群返回 null。 */
    public CuratorFramework getCurator() {
        return curator;
    }

    public boolean isClosed() {
        return closed.get();
    }

    /** 标记一次业务访问,推迟空闲回收。 */
    public void touch() {
        this.lastAccessNanos = System.nanoTime();
    }

    public long lastAccessNanos() {
        return lastAccessNanos;
    }

    /** 距离上次访问的空闲时长(ms)。 */
    public long idleMillis() {
        return (System.nanoTime() - lastAccessNanos) / 1_000_000L;
    }

    // ---------------------------------------------------------------
    // 归档器挂接
    // ---------------------------------------------------------------

    /** 启动归档器(重复调用时先停旧的);由 {@link ClusterConnectionManager} 在建连成功后调用。 */
    public void startArchiver(ClusterArchiverHandle handle) {
        ClusterArchiverHandle previous = this.archiver;
        if (previous != null) {
            previous.stop();
        }
        this.archiver = handle;
    }

    /** 停止归档器;未启动时为 no-op。 */
    public void stopArchiver() {
        ClusterArchiverHandle current = this.archiver;
        if (current != null) {
            current.stop();
            this.archiver = null;
        }
    }

    public boolean archiverRunning() {
        ClusterArchiverHandle current = this.archiver;
        return current != null && current.isRunning();
    }

    /**
     * 依次关闭:归档器 → ConsumerPool → Producer(限时 close,含 flush 语义)→ AdminClient → Curator。
     *
     * <p>幂等:重复调用直接返回。每个子步骤都单独 try/catch —— 一个资源关不掉
     * 不能阻止其他资源释放(否则就是句柄泄漏)。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        log.info("Closing connection for cluster {} ({})", definition.id(), definition.name());
        stopArchiver();

        try {
            consumerPool.close();
        } catch (Exception e) {
            log.warn("Error closing consumer pool of cluster {}: {}", definition.id(), e.getMessage());
        }

        try {
            // producer.close(Duration) 内部会先把在途消息发完(带超时),等价于 flush + close,
            // 但有硬上界:broker 不可达时裸 flush() 只受 delivery.timeout.ms(默认 120s)约束,
            // 会把 disconnect / 空闲回收(持有集群锁)/ @PreDestroy 全部阻塞到分钟级,
            // 回收线程挂住后该集群的所有后续请求都会排队等锁。
            producer.close(java.time.Duration.ofSeconds(PRODUCER_CLOSE_TIMEOUT_SECONDS));
        } catch (Exception e) {
            log.warn("Error closing producer of cluster {}: {}", definition.id(), e.getMessage());
        }

        try {
            adminClient.close();
        } catch (Exception e) {
            log.warn("Error closing admin client of cluster {}: {}", definition.id(), e.getMessage());
        }

        // ZkClientManager.close() 会顺带关闭 Curator(线程池 + 连接)
        if (zkClientManager != null) {
            try {
                zkClientManager.close();
            } catch (Exception e) {
                log.warn("Error closing ZK client of cluster {}: {}", definition.id(), e.getMessage());
            }
        }
    }

    // ---------------------------------------------------------------
    // 关闭
    // ---------------------------------------------------------------

    /**
     * 归档器的最小契约。
     *
     * <p>定义在 kafka 包内、不直接依赖 archive 包的具体实现,是为了让
     * {@code ClusterConnection} 不与归档实现耦合(归档是可选能力)。
     */
    public interface ClusterArchiverHandle {
        void stop();

        boolean isRunning();
    }
}
