package com.example.kafkaviz.controller;

import com.example.kafkaviz.kafka.ClusterConnection;
import com.example.kafkaviz.model.vo.ApiResponse;
import com.example.kafkaviz.model.vo.ClusterInfo;
import com.example.kafkaviz.model.vo.ClusterMode;
import com.example.kafkaviz.service.ClusterService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.ExecutionException;

/**
 * 集群信息端点。
 *
 * <p>{@code clusterId} 只用于绑定路径模板;**真正干活的是 resolver 注入的
 * {@link ClusterConnection}** —— 配置不存在会在这里就变成 40404,连不上变 50302,
 * 方法体拿到的必定是一个可用连接。
 */
@RestController
@RequestMapping("/api/c/{clusterId}/cluster")
public class ClusterController {

    private final ClusterService clusterService;

    public ClusterController(ClusterService clusterService) {
        this.clusterService = clusterService;
    }

    /** 集群基本信息:controller id 与全部 broker(id/host/port/rack)。 */
    @GetMapping("/info")
    public ApiResponse<ClusterInfo> getClusterInfo(@PathVariable long clusterId,
                                                   ClusterConnection connection)
            throws ExecutionException, InterruptedException {
        return ApiResponse.ok(clusterService.describeCluster(connection));
    }

    /**
     * 返回<b>该集群</b>的运行模式(KRAFT / ZOOKEEPER)。
     * 前端据此决定显示 ZK 节点浏览器还是 KRaft 元数据浏览器。
     */
    @GetMapping("/mode")
    public ApiResponse<ClusterMode> getClusterMode(@PathVariable long clusterId,
                                                   ClusterConnection connection) {
        return ApiResponse.ok(clusterService.getClusterMode(connection));
    }
}
