package com.example.kafkaviz.kafka;

import com.example.kafkaviz.config.KafkaProperties;
import com.example.kafkaviz.exception.ClusterNotFoundException;
import com.example.kafkaviz.exception.ServiceUnavailableException;
import com.example.kafkaviz.storage.ClusterConfigStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * 多集群连接注册表。
 *
 * <p>职责:惰性建连、状态机、空闲回收、显式连接/断开、@PreDestroy 全量回收。
 *
 * <p>三个刻意的设计决定(代码里看不到原因,写在这里):
 * <ol>
 *   <li><b>失败冷却</b>:探活失败后 {@value #ERROR_RETRY_COOLDOWN_MS}ms 内不再重试,
 *       直接复用缓存的错误摘要抛 50302。否则一个死集群会让每个请求都白等一次
 *       {@code kafka.admin.default-api-timeout-ms}(10s),多集群 Dashboard 直接不可用。
 *       用户显式 {@code connectAsync} 会绕过冷却(那是明确的重试意图)。</li>
 *   <li><b>首次建连同期限时</b>:建连与探活都在调用线程内完成,探活的
 *       {@code describeCluster} 走 {@link KafkaFutures#await} 超时纪律,
 *       因此最坏阻塞可控(≈ {@code default-api-timeout-ms})。</li>
 *   <li><b>Kafka 异常不外泄为 50000</b>:探活/建连的异常一律被捕获、落成 ERROR
 *       摘要并转 50302,因为"集群连不上"是正常业务状态而非服务端 bug。</li>
 * </ol>
 *
 * <p>关于归档器:{@link ArchiverStarter} 由 {@code ObjectProvider} 注入 —— 容器中
 * 没有实现类时 {@code getIfAvailable()} 返回 null,管理器照常工作。
 */
@Component
public class ClusterConnectionManager {

    /** 探活失败后的重试冷却窗口。 */
    static final long ERROR_RETRY_COOLDOWN_MS = 30_000L;
    private static final Logger log = LoggerFactory.getLogger(ClusterConnectionManager.class);
    private final ClusterConfigStore store;
    private final ClusterConnectionFactory factory;
    private final ObjectProvider<ArchiverStarter> archiverStarter;
    private final ExecutorService kafkaOpsExecutor;
    /** Admin 调用的阻塞上限,见 kafka.admin.default-api-timeout-ms。 */
    private final long timeoutMs;
    /** 空闲回收阈值(分钟),见 cluster.idle-evict-minutes。 */
    private final long idleEvictMinutes;

    private final Map<Long, ManagedConnection> registry = new ConcurrentHashMap<>();

    public ClusterConnectionManager(ClusterConfigStore store,
                                    ClusterConnectionFactory factory,
                                    ObjectProvider<ArchiverStarter> archiverStarter,
                                    ExecutorService kafkaOpsExecutor,
                                    KafkaProperties kafkaProperties,
                                    @Value("${cluster.idle-evict-minutes:30}") long idleEvictMinutes) {
        this.store = store;
        this.factory = factory;
        this.archiverStarter = archiverStarter;
        this.kafkaOpsExecutor = kafkaOpsExecutor;
        this.timeoutMs = kafkaProperties.getAdmin().getDefaultApiTimeoutMs();
        this.idleEvictMinutes = idleEvictMinutes;
    }

    // ---------------------------------------------------------------
    // 对外:取连接
    // ---------------------------------------------------------------

    private static String summarize(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root
                && (root.getMessage() == null || root.getMessage().isBlank())) {
            root = root.getCause();
        }
        String msg = root.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = root.getClass().getSimpleName();
        }
        return msg.length() > 300 ? msg.substring(0, 300) + "..." : msg;
    }

    /**
     * 取该集群的可用连接(惰性建连)。
     *
     * @throws ClusterNotFoundException   集群配置不存在 → 404/40404
     * @throws ServiceUnavailableException 正在连接中,或探活失败/处于失败冷却(50302)
     */
    public ClusterConnection getOrConnect(long clusterId) {
        ClusterDefinition definition = store.findById(clusterId)
                .orElseThrow(() -> new ClusterNotFoundException(clusterId));

        ManagedConnection managed = registry.computeIfAbsent(clusterId, id -> new ManagedConnection(definition));

        ClusterConnection current = managed.connection;
        if (current != null && !current.isClosed() && managed.state == ClusterState.CONNECTED) {
            current.touch();
            // 防御(窄竞态):touch 与返回之间,空闲回收线程可能在锁内复查 idleMillis
            // 通过后 close 本连接——本线程读到的还是旧的 CONNECTED 与未关闭状态。
            // 复查 isClosed() 把"借出已关闭连接"的窗口压到指令级;即便残余命中,
            // 后果也只是单次请求报 Kafka 客户端已关闭异常,下次请求重建,可接受。
            if (!current.isClosed()) {
                return current;
            }
        }

        synchronized (managed.lock) {
            // 配置可能已被编辑过,以库中最新定义为准(连接参数变更走的是 disconnect + 重连)。
            // 在锁内写:保证后续 connectLocked 读到的就是这份最新定义,不会出现
            // "锁外读到的 definition 与锁内实际使用的定义不一致"的日志/异常文案漂移。
            managed.definition = definition;
            // 双检:等锁期间可能已被别的线程连上
            current = managed.connection;
            if (current != null && !current.isClosed() && managed.state == ClusterState.CONNECTED) {
                current.touch();
                return current;
            }
            if (managed.state == ClusterState.CONNECTING) {
                throw new ServiceUnavailableException(
                        "Cluster '" + definition.name() + "' is connecting, please retry later");
            }
            if (managed.state == ClusterState.ERROR && System.nanoTime() < managed.nextRetryAtNanos) {
                // 冷却窗口内不重试:复用摘要,避免每个请求都白等一次 10s 超时
                throw new ServiceUnavailableException("Cluster '" + definition.name()
                        + "' is not reachable (last error at " + managed.errorAt + "): " + managed.errorSummary);
            }
            return connectLocked(managed);
        }
    }

    /**
     * 显式建连(异步):立即返回,调用方看到的是 CONNECTING。
     *
     * <p>用于管理端点的"连接"按钮 —— 显式意图,因此不受失败冷却限制。
     */
    public void connectAsync(long clusterId) {
        ClusterDefinition definition = store.findById(clusterId)
                .orElseThrow(() -> new ClusterNotFoundException(clusterId));
        ManagedConnection managed = registry.computeIfAbsent(clusterId, id -> new ManagedConnection(definition));
        managed.definition = definition;

        ClusterConnection current = managed.connection;
        if (current != null && !current.isClosed() && managed.state == ClusterState.CONNECTED) {
            current.touch();
            return;
        }
        if (managed.state == ClusterState.CONNECTING) {
            // 已在连接中:幂等返回,不重复提交
            return;
        }

        synchronized (managed.lock) {
            managed.state = ClusterState.CONNECTING;
            managed.nextRetryAtNanos = 0L;
        }
        try {
            kafkaOpsExecutor.execute(() -> {
                try {
                    synchronized (managed.lock) {
                        if (managed.state == ClusterState.CONNECTING) {
                            connectLocked(managed);
                        }
                    }
                } catch (ServiceUnavailableException e) {
                    // 失败已落成 ERROR 摘要,异步路径无处抛出 —— 记 INFO,前端靠状态轮询看到
                    log.info("Async connect failed for cluster {}: {}", clusterId, e.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            synchronized (managed.lock) {
                markError(managed, "connect executor overloaded: " + e.getMessage());
            }
            throw new ServiceUnavailableException("Connect executor overloaded, please retry later", e);
        }
    }

    /** 显式断开:close 并置 DISCONNECTED。已断开时为 no-op。 */
    public void disconnect(long clusterId) {
        ManagedConnection managed = registry.get(clusterId);
        if (managed == null) {
            return;
        }
        synchronized (managed.lock) {
            closeQuietly(managed);
            managed.state = ClusterState.DISCONNECTED;
            managed.errorSummary = null;
            managed.errorAt = null;
            managed.nextRetryAtNanos = 0L;
        }
        log.info("Disconnected cluster {}", clusterId);
    }

    /**
     * 重启该集群的归档器({@code archive_enabled} 变更后的动态启停)。
     *
     * <p>语义:先停旧的,再按最新定义决定是否重建。
     * 集群当前没连上时什么都不做 —— 下次建连会按最新定义启动
     * ({@link #startArchiver} 在 {@code connectLocked} 里已经按 {@code archiveEnabled} 判断)。
     */
    public void restartArchiver(long clusterId) {
        ManagedConnection managed = registry.get(clusterId);
        if (managed == null) {
            return;
        }
        ClusterDefinition definition = store.findById(clusterId).orElse(null);
        if (definition == null) {
            return;
        }
        synchronized (managed.lock) {
            ClusterConnection conn = managed.connection;
            if (conn == null || managed.state != ClusterState.CONNECTED) {
                return;
            }
            // 无论启用与否都要先停:否则关闭归档时旧归档器会一直跑下去
            conn.stopArchiver();
            startArchiver(conn, definition);
        }
        log.info("Archiver restarted with latest definition for cluster {} (archiveEnabled={})",
                clusterId, definition.archiveEnabled());
    }

    /** 全部集群的展示态(供 {@code /api/clusters} 与状态轮询聚合)。 */
    public List<ManagedStatus> statuses() {
        List<ManagedStatus> out = new ArrayList<>();
        for (ClusterDefinition definition : store.list()) {
            ManagedConnection managed = registry.get(definition.id());
            if (managed == null) {
                out.add(new ManagedStatus(definition.id(), definition.name(),
                        ClusterState.DISCONNECTED, null, null));
            } else {
                out.add(new ManagedStatus(definition.id(), definition.name(),
                        managed.state, managed.errorSummary, managed.errorAt));
            }
        }
        return out;
    }

    /** 单集群展示态;集群不存在时返回 null(供管理端点回显)。 */
    public ManagedStatus statusOf(long clusterId) {
        ClusterDefinition definition = store.findById(clusterId).orElse(null);
        if (definition == null) {
            return null;
        }
        ManagedConnection managed = registry.get(clusterId);
        return managed == null
                ? new ManagedStatus(clusterId, definition.name(), ClusterState.DISCONNECTED, null, null)
                : new ManagedStatus(clusterId, definition.name(), managed.state,
                        managed.errorSummary, managed.errorAt);
    }

    /**
     * 只校验集群存在性,不做建连(归档端点对离线集群必须可用)。
     *
     * @throws ClusterNotFoundException 配置不存在 → 404/40404
     */
    public ClusterDefinition requireExists(long clusterId) {
        return store.findById(clusterId).orElseThrow(() -> new ClusterNotFoundException(clusterId));
    }

    /**
     * 已建连(未关闭)的连接快照,供 Dashboard 多集群聚合复用。
     */
    public List<ClusterConnection> listConnected() {
        List<ClusterConnection> out = new ArrayList<>();
        registry.forEach((id, managed) -> {
            ClusterConnection conn = managed.connection;
            if (conn != null && !conn.isClosed() && managed.state == ClusterState.CONNECTED) {
                out.add(conn);
            }
        });
        out.sort(Comparator.comparingLong(ClusterConnection::clusterId));
        return out;
    }

    // ---------------------------------------------------------------
    // 空闲回收 / 关闭
    // ---------------------------------------------------------------

    /** 管理器里当前已登记的集群 id(含仅 STATUS 记录、未建连的)。 */
    public List<Long> trackedClusterIds() {
        return new ArrayList<>(registry.keySet());
    }

    /**
     * 每分钟扫一次,回收空闲超过 {@code cluster.idle-evict-minutes} 的连接。
     *
     * <p>回收后状态置 DISCONNECTED(而不是 ERROR):这是<b>正常</b>的资源管理行为,
     * 下次请求会重建,不该在 Dashboard 上显示成故障。
     */
    @Scheduled(fixedDelay = 60_000L)
    public void evictIdleConnections() {
        long limitMs = idleEvictMinutes * 60_000L;
        registry.forEach((clusterId, managed) -> {
            ClusterConnection conn = managed.connection;
            if (conn == null || managed.state != ClusterState.CONNECTED) {
                return;
            }
            if (conn.idleMillis() < limitMs) {
                return;
            }
            synchronized (managed.lock) {
                if (managed.connection != null && managed.connection.idleMillis() >= limitMs) {
                    log.info("Evicting idle connection for cluster {} (idle {} ms > {} ms)",
                            clusterId, managed.connection.idleMillis(), limitMs);
                    closeQuietly(managed);
                    managed.state = ClusterState.DISCONNECTED;
                }
            }
        });
    }

    // ---------------------------------------------------------------
    // 内部:建连
    // ---------------------------------------------------------------

    /** 进程退出:依次 close 所有连接(AdminClient / Producer flush / ConsumerPool / Curator)。 */
    @PreDestroy
    public void closeAll() {
        log.info("Closing {} managed cluster connection(s)...", registry.size());
        registry.values().forEach(managed -> {
            synchronized (managed.lock) {
                closeQuietly(managed);
                managed.state = ClusterState.DISCONNECTED;
            }
        });
    }

    /** 必须在 {@code managed.lock} 内调用。失败时抛 50302,绝不外泄 Kafka 异常。 */
    private ClusterConnection connectLocked(ManagedConnection managed) {
        ClusterDefinition definition = managed.definition;
        managed.state = ClusterState.CONNECTING;
        ClusterConnection conn = null;
        try {
            conn = factory.create(definition);
            probe(conn, definition);
            managed.connection = conn;
            managed.state = ClusterState.CONNECTED;
            managed.errorSummary = null;
            managed.errorAt = null;
            managed.nextRetryAtNanos = 0L;
            log.info("Cluster {} ({}) connected", definition.id(), definition.name());
            startArchiver(conn, definition);
            return conn;
        } catch (Exception e) {
            if (conn != null) {
                // 半成品必须回收,否则每次失败都泄漏一组客户端句柄
                try {
                    conn.close();
                } catch (Exception closeEx) {
                    log.warn("Error closing half-built connection of cluster {}: {}",
                            definition.id(), closeEx.getMessage());
                }
            }
            markError(managed, summarize(e));
            throw new ServiceUnavailableException("Cluster '" + definition.name()
                    + "' is not reachable: " + managed.errorSummary, e);
        }
    }

    /** 探活:{@code describeCluster} 走超时纪律,保证建连最坏阻塞可控。 */
    private void probe(ClusterConnection conn, ClusterDefinition definition)
            throws java.util.concurrent.ExecutionException, InterruptedException {
        String kafkaClusterId = KafkaFutures.await(
                conn.getAdminClient().describeCluster().clusterId(), timeoutMs,
                "describeCluster.clusterId:cluster=" + definition.id());
        log.debug("Cluster {} probe ok (kafka clusterId={})", definition.id(), kafkaClusterId);
    }

    private void startArchiver(ClusterConnection conn, ClusterDefinition definition) {
        if (!definition.archiveEnabled()) {
            return;
        }
        ArchiverStarter starter = archiverStarter.getIfAvailable();
        if (starter == null) {
            // 容器中没有 ArchiverStarter 实现,静默跳过
            return;
        }
        try {
            ClusterConnection.ClusterArchiverHandle handle = starter.start(conn, definition);
            if (handle != null) {
                conn.startArchiver(handle);
            }
        } catch (Exception e) {
            // 归档器启动失败只影响归档能力,不能把业务连接拖成 ERROR
            log.error("Failed to start archiver for cluster {}: {}", definition.id(), e.getMessage(), e);
        }
    }

    /** 必须在 {@code managed.lock} 内调用。 */
    private void markError(ManagedConnection managed, String summary) {
        managed.state = ClusterState.ERROR;
        managed.errorSummary = summary;
        managed.errorAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        managed.nextRetryAtNanos = System.nanoTime() + ERROR_RETRY_COOLDOWN_MS * 1_000_000L;
    }

    /** 必须在 {@code managed.lock} 内调用。 */
    private void closeQuietly(ManagedConnection managed) {
        ClusterConnection conn = managed.connection;
        if (conn == null) {
            return;
        }
        try {
            conn.close();
        } catch (Exception e) {
            log.warn("Error closing connection of cluster {}: {}", managed.definition.id(), e.getMessage());
        } finally {
            managed.connection = null;
        }
    }

    // ---------------------------------------------------------------
    // 内部:登记项
    // ---------------------------------------------------------------

    private static final class ManagedConnection {

        /** 每项一把锁:建连/断开/回收互斥,但不阻塞其他集群。 */
        private final Object lock = new Object();

        private volatile ClusterDefinition definition;
        private volatile ClusterConnection connection;
        private volatile ClusterState state = ClusterState.DISCONNECTED;
        private volatile String errorSummary;
        private volatile String errorAt;
        /** 冷却截止时刻(nanoTime);0 = 无冷却。 */
        private volatile long nextRetryAtNanos;

        private ManagedConnection(ClusterDefinition definition) {
            this.definition = definition;
        }
    }

    /**
     * 对外状态视图(不含任何凭据)。
     *
     * @param clusterId    集群 id
     * @param name         显示名
     * @param state        内部四态
     * @param errorSummary 最近一次失败摘要(仅 ERROR 态有值)
     * @param errorAt      失败时刻(ISO-8601,仅 ERROR 态有值)
     */
    public record ManagedStatus(long clusterId, String name, ClusterState state,
                                String errorSummary, String errorAt) {

        /** ONLINE / OFFLINE / CONNECTING —— 前端只消费这个。 */
        public String displayState() {
            return state.displayState();
        }
    }
}
