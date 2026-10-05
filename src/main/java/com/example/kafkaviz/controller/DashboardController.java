package com.example.kafkaviz.controller;

import com.example.kafkaviz.kafka.ClusterConnection;
import com.example.kafkaviz.model.vo.ApiResponse;
import com.example.kafkaviz.model.vo.DashboardOverview;
import com.example.kafkaviz.model.vo.MultiClusterDashboard;
import com.example.kafkaviz.service.DashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Dashboard 端点。
 *
 * <p>两个端点<b>没有共同前缀</b>,因此不用类级 {@code @RequestMapping}:
 * <ul>
 *   <li>{@code GET /api/c/{clusterId}/dashboard/overview} —— 单集群视图,
 *       {@code clusterId} 经 resolver 解析为已连接的 {@link ClusterConnection};
 *       失败整体 500(单集群视图失败就该如实报错)。</li>
 *   <li>{@code GET /api/dashboard/multi-overview} —— 跨集群聚合,<b>不带集群段</b>
 *       (聚合本身是全局视图);per-cluster 容错,单集群失败只毁自己那张卡片。</li>
 * </ul>
 */
@RestController
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /** 单集群运行概览。 */
    @GetMapping("/api/c/{clusterId}/dashboard/overview")
    public ApiResponse<DashboardOverview> overview(@PathVariable long clusterId,
                                                  ClusterConnection connection)
            throws ExecutionException, InterruptedException {
        return ApiResponse.ok(dashboardService.getOverview(connection));
    }

    /**
     * 多集群卡片总览。
     *
     * <p>在线集群并行复用单集群聚合;离线/连接中集群回本地归档条数;
     * 单集群失败转卡片 errorSummary,不拖垮整页。零集群返回空数组。
     */
    @GetMapping("/api/dashboard/multi-overview")
    public ApiResponse<List<MultiClusterDashboard>> multiOverview() {
        return ApiResponse.ok(dashboardService.getMultiOverview());
    }
}
