package com.example.kafkaviz.service;

import com.example.kafkaviz.exception.ServiceUnavailableException;
import com.example.kafkaviz.kafka.ClusterConnection;
import com.example.kafkaviz.model.vo.DeleteResult;
import com.example.kafkaviz.model.vo.ZkChildrenResult;
import com.example.kafkaviz.model.vo.ZkNode;
import com.example.kafkaviz.zk.ZkClientManager;
import org.springframework.stereotype.Service;

/**
 * ZK 节点浏览(<b>按集群</b>判断可用性)。
 *
 * <p>控制器常驻,"这个集群有没有 ZK"是运行时属性:无 ZK 的集群调用 ZK 端点 →
 * <b>503 / 50302</b>。前端据此把 ZK 入口的显隐跟随"当前激活集群是否 zkAvailable"。
 */
@Service
public class ZkService {

    /**
     * 列出子节点。
     *
     * <p>递归超限时 {@code truncated=true}:仍返回 200 + code=0,只是数据不完整,
     * 由调用方通过该标记感知(未截断时为 null,被 NON_NULL 从 JSON 省略)。
     *
     * @throws ServiceUnavailableException 该集群未配置 ZooKeeper(KRaft),或连接已失效
     */
    public ZkChildrenResult listChildren(ClusterConnection connection, String path, boolean recursive) {
        ZkClientManager manager = requireZk(connection);
        ZkNode root = manager.listChildren(path, recursive);
        return ZkChildrenResult.builder()
                .path(root.getPath())
                .stat(root.getStat())
                .children(root.getChildren() != null ? root.getChildren() : java.util.Collections.emptyList())
                .truncated(root.getTruncated())
                .build();
    }

    /**
     * 删除节点。
     *
     * @throws ServiceUnavailableException 该集群未配置 ZooKeeper(KRaft),或连接已失效
     */
    public DeleteResult deleteNode(ClusterConnection connection, String path) {
        requireZk(connection).deleteRecursive(path);
        return DeleteResult.builder()
                .path(path)
                .deleted(true)
                .build();
    }

    /**
     * 取该集群的 ZK 客户端;没有就抛 50302。
     *
     * <p>{@code zkAvailable()} 与 {@code getZkClientManager() != null} 理论上等价,
     * 两个都判是防御 {@code ClusterConnection} 未来把二者拆开(比如 ZK 懒建连)。
     */
    private ZkClientManager requireZk(ClusterConnection connection) {
        if (!connection.zkAvailable() || connection.getZkClientManager() == null) {
            throw new ServiceUnavailableException("Cluster '" + connection.definition().name()
                    + "' has no ZooKeeper configured (KRaft mode) — the ZK browser is unavailable for this cluster");
        }
        return connection.getZkClientManager();
    }
}
