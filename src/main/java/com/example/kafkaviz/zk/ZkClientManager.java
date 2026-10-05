package com.example.kafkaviz.zk;

import com.example.kafkaviz.config.ZkProperties;
import com.example.kafkaviz.exception.InvalidZkPathException;
import com.example.kafkaviz.exception.ZkDeleteRootException;
import com.example.kafkaviz.exception.ZkNodeNotFoundException;
import com.example.kafkaviz.model.vo.ZkNode;
import org.apache.curator.framework.CuratorFramework;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ZK 节点操作。<b>不是 Spring Bean</b>:多集群下 ZK 可用性按<b>集群</b>判断,
 * 不由全局配置决定;实例由 {@code ClusterConnectionFactory} 用 {@code new} 造,
 * 随 {@code ClusterConnection} 生命周期存在。
 *
 * <p>{@link #close()} 除了关 {@code zk-ops} 线程池,还要关 Curator 连接。
 *
 * <p>{@code zk-ops} 线程池仍是本对象内部字段:一个集群一份,不同集群的慢 ZK 请求
 * 不会互相占满线程。
 */
public class ZkClientManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ZkClientManager.class);

    private final CuratorFramework curator;
    private final ZkProperties zkProperties;
    /**
     * 递归列出的 stat 获取专用线程池:独立于 kafkaOpsExecutor,
     * 避免 ZK 的慢请求占满 Kafka 聚合操作的线程。
     */
    private final ExecutorService zkOpsExecutor;

    public ZkClientManager(CuratorFramework curator, ZkProperties zkProperties) {
        this.curator = curator;
        this.zkProperties = zkProperties;
        this.zkOpsExecutor = createZkOpsExecutor(zkProperties.getParallelStatFetches());
    }

    private ExecutorService createZkOpsExecutor(int concurrency) {
        AtomicInteger sequence = new AtomicInteger(0);
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "zk-ops-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        // 有界队列 + CallerRunsPolicy:队列满时由调用线程自己跑,形成天然背压,
        // 宁可退化成串行也不放大 ZK 的在途请求数。
        return new ThreadPoolExecutor(
                concurrency, concurrency, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(concurrency * 4),
                threadFactory,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * 校验 ZK path 是否合法。
     */
    public void validatePath(String path) {
        if (path == null || path.isBlank()) {
            throw new InvalidZkPathException("path must not be empty");
        }
        if (!path.equals("/") && path.endsWith("/")) {
            throw new InvalidZkPathException("path must not end with '/': " + path);
        }
    }

    /**
     * 列出子节点（非递归时附带 stat，递归时展开子树）。
     *
     * @param recursive 递归展开;受 {@code zk.max-recursive-depth} / {@code zk.max-recursive-nodes}
     *                  保护,超限时返回部分结果并置 {@code truncated=true}
     */
    public ZkNode listChildren(String path, boolean recursive) {
        validatePath(path);

        try {
            Stat stat = curator.checkExists().forPath(path);
            if (stat == null) {
                throw new ZkNodeNotFoundException(path);
            }

            ZkNode root = buildNode(path, stat);
            List<ZkNode> children = new ArrayList<>();

            List<String> childNames = curator.getChildren().forPath(path);
            boolean truncated = false;
            for (String childName : childNames) {
                String childPath = childPath(path, childName);
                if (recursive) {
                    if (collectRecursive(childPath, children)) {
                        truncated = true;
                    }
                } else {
                    // 不再逐个 checkExists:非递归从 N+2 次串行 RPC 降到固定 2 次
                    // (父节点 checkExists + getChildren)。子节点 stat 为 null,
                    // 由 ApiResponse 的 NON_NULL 从 JSON 中省略 —— 子节点在 getChildren
                    // 之后被删除是极低概率事件,宽容返回比"再查一次"更符合性能目标。
                    children.add(buildNode(childPath, null));
                }
            }

            root.setChildren(children);
            if (truncated) {
                root.setTruncated(Boolean.TRUE);
                log.warn("ZK recursive listing truncated: path={}, nodes>={}, depth>={}",
                        path, zkProperties.getMaxRecursiveNodes(), zkProperties.getMaxRecursiveDepth());
            }
            return root;

        } catch (ZkNodeNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to list ZK children for path: " + path, e);
        }
    }

    /**
     * 分层 BFS 收集所有子孙节点。
     *
     * <p>两层保护 + 一层优化:
     * <ul>
     *   <li>深度上限:{@code zk.max-recursive-depth}(根节点深度 0,直接子节点深度 1);</li>
     *   <li>节点数上限:{@code zk.max-recursive-nodes} —— 误传 {@code /} 或 {@code /brokers}
     *       这类大子树时不会"永不返回";</li>
     *   <li>同层 stat 并行获取:把 N 次串行 RTT 压到 {@code ceil(N / parallelStatFetches)}。</li>
     * </ul>
     *
     * @return true 表示因上限被截断(结果不完整),false 表示已完整展开
     */
    private boolean collectRecursive(String startPath, List<ZkNode> result) throws Exception {
        final int maxDepth = zkProperties.getMaxRecursiveDepth();
        final int maxNodes = zkProperties.getMaxRecursiveNodes();
        boolean truncated = false;

        List<NodeEntry> level = List.of(new NodeEntry(startPath, 1));
        while (!level.isEmpty()) {
            if (result.size() >= maxNodes) {
                return true;
            }
            List<Stat> stats = fetchStatsParallel(level);

            List<NodeEntry> next = new ArrayList<>();
            for (int i = 0; i < level.size(); i++) {
                NodeEntry entry = level.get(i);
                Stat stat = stats.get(i);
                if (stat == null) {
                    continue; // 节点在读取前已被删除:宽容跳过
                }
                if (result.size() >= maxNodes) {
                    return true;
                }
                result.add(buildNode(entry.path(), stat));

                List<String> children = curator.getChildren().forPath(entry.path());
                if (children.isEmpty()) {
                    continue;
                }
                if (entry.depth() >= maxDepth) {
                    // 还有子节点但已达深度上限:结果不完整,需如实标记
                    truncated = true;
                    continue;
                }
                for (String child : children) {
                    next.add(new NodeEntry(childPath(entry.path(), child), entry.depth() + 1));
                }
            }
            // 层内按路径排序:并行会打乱顺序,排序后输出稳定且更易读
            next.sort(Comparator.comparing(NodeEntry::path));
            level = next;
        }
        return truncated;
    }

    /**
     * 并行获取一层内所有节点的 stat(并发度由线程池上限保证)。
     *
     * <p>节点已不存在时对应位置为 null;其他异常原样抛出,由 {@link #listChildren}
     * 统一包装(不把"ZK 不可用"伪装成"返回部分结果")。
     */
    private List<Stat> fetchStatsParallel(List<NodeEntry> level) throws Exception {
        List<CompletableFuture<Stat>> futures = new ArrayList<>(level.size());
        for (NodeEntry entry : level) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return curator.checkExists().forPath(entry.path());
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, zkOpsExecutor));
        }

        List<Stat> stats = new ArrayList<>(futures.size());
        for (CompletableFuture<Stat> future : futures) {
            try {
                stats.add(future.join());
            } catch (CompletionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception checked) {
                    throw checked;
                }
                throw e;
            }
        }
        return stats;
    }

    /**
     * 删除节点及其所有子节点。
     */
    public void deleteRecursive(String path) {
        validatePath(path);
        if ("/".equals(path)) {
            throw new ZkDeleteRootException();
        }

        try {
            Stat stat = curator.checkExists().forPath(path);
            if (stat == null) {
                throw new ZkNodeNotFoundException(path);
            }
            curator.delete().deletingChildrenIfNeeded().forPath(path);
        } catch (ZkNodeNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete ZK path: " + path, e);
        }
    }

    private String childPath(String parentPath, String childName) {
        return parentPath.equals("/") ? "/" + childName : parentPath + "/" + childName;
    }

    private ZkNode buildNode(String path, Stat stat) {
        if (stat == null) {
            // 节点在 getChildren 之后被删除:宽容返回,stat 字段为 null(JSON 中省略)
            return ZkNode.builder().path(path).build();
        }

        ZkNode.ZkNodeStat nodeStat = ZkNode.ZkNodeStat.builder()
                .czxid(stat.getCzxid())
                .mzxid(stat.getMzxid())
                .ctime(stat.getCtime())
                .mtime(stat.getMtime())
                .version(stat.getVersion())
                .cversion(stat.getCversion())
                .aversion(stat.getAversion())
                .ephemeralOwner(stat.getEphemeralOwner())
                .dataLength(stat.getDataLength())
                .numChildren(stat.getNumChildren())
                .pzxid(stat.getPzxid())
                .build();

        return ZkNode.builder()
                .path(path)
                .stat(nodeStat)
                .build();
    }

    /**
     * 关闭:先停 stat 并发池,再关 Curator 连接。
     *
     * <p>由 {@code ClusterConnection#close()} 调用。
     * 幂等性由 ClusterConnection 的 AtomicBoolean 保证,这里不重复加锁。
     */
    public void close() {
        zkOpsExecutor.shutdown();
        try {
            // Curator 的 close() 本身幂等;连接不可用时也可能抛错,不能让它挡住线程池的关闭
            curator.close();
        } catch (Exception e) {
            log.warn("Error closing Curator for cluster ZK client: {}", e.getMessage());
        }
    }

    /** BFS 层内元素:路径 + 相对根节点的深度(根节点的直接子节点深度为 1)。 */
    private record NodeEntry(String path, int depth) {
    }
}
