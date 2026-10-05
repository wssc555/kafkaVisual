package com.example.kafkaviz.controller;

import com.example.kafkaviz.kafka.ClusterConnection;
import com.example.kafkaviz.model.vo.ApiResponse;
import com.example.kafkaviz.model.vo.DeleteResult;
import com.example.kafkaviz.model.vo.ZkChildrenResult;
import com.example.kafkaviz.service.ZkService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ZK 节点浏览器端点。
 *
 * <p>「有没有 ZK」是<b>集群级</b>属性(KRaft 集群无 ZK),无全局开关。因此:
 * <ul>
 *   <li>对无 ZK 的集群访问时,端点返回 <b>503 / 50302</b>
 *       (由 {@code ZkService.requireZk} 抛出);</li>
 *   <li>前端据 {@code /api/c/{id}/cluster/mode} 的 {@code zkAvailable} 决定入口显隐,
 *       正常路径下不会触发该 503。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/c/{clusterId}/zk")
public class ZkController {

    private final ZkService zkService;

    public ZkController(ZkService zkService) {
        this.zkService = zkService;
    }

    /** 列出 ZK 节点的子节点;{@code recursive=true} 时递归展开子树。 */
    @GetMapping("/children")
    public ApiResponse<ZkChildrenResult> listChildren(@PathVariable long clusterId,
                                                      ClusterConnection connection,
                                                      @RequestParam String path,
                                                      @RequestParam(defaultValue = "false") boolean recursive) {
        return ApiResponse.ok(zkService.listChildren(connection, path, recursive));
    }

    /** 删除 ZK 节点(连子节点一并删除;根节点拒绝删除)。 */
    @DeleteMapping("/node")
    public ApiResponse<DeleteResult> deleteNode(@PathVariable long clusterId,
                                                ClusterConnection connection,
                                                @RequestParam String path) {
        return ApiResponse.ok(zkService.deleteNode(connection, path));
    }
}
