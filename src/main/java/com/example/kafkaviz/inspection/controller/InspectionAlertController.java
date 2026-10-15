package com.example.kafkaviz.inspection.controller;

import com.example.kafkaviz.inspection.model.vo.AlertDetailVO;
import com.example.kafkaviz.inspection.model.vo.AlertRecordVO;
import com.example.kafkaviz.inspection.model.vo.InspectionOverviewVO;
import com.example.kafkaviz.inspection.service.AlertRecordService;
import com.example.kafkaviz.model.vo.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 告警中心 + 巡检概览(<b>全局</b> {@code /api/inspection/alerts}、{@code /api/inspection/overview})。
 *
 * <p>全局而非集群段:告警中心的价值正是"所有集群的问题一屏看完",
 * 集群维度由 {@code clusterId} 过滤参数提供(而不是靠路径)。
 */
@RestController
@RequestMapping("/api/inspection")
@ConditionalOnProperty(name = "inspection.enabled", havingValue = "true", matchIfMissing = true)
public class InspectionAlertController {

    private final AlertRecordService alertService;

    public InspectionAlertController(AlertRecordService alertService) {
        this.alertService = alertService;
    }

    /** 告警列表(多条件过滤 + 分页,排序固定 fired_at DESC)。 */
    @GetMapping("/alerts")
    public ApiResponse<List<AlertRecordVO>> listAlerts(
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) Long ruleId,
            @RequestParam(required = false) Long clusterId,
            @RequestParam(required = false) Boolean acknowledged,
            @RequestParam(required = false) String firedFrom,
            @RequestParam(required = false) String firedTo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = page <= 0 ? 1 : page;
        int safeSize = size <= 0 ? 20 : size;
        return ApiResponse.ok(alertService.search(state, severity, ruleId, clusterId, acknowledged,
                firedFrom, firedTo, safePage, safeSize));
    }

    /** 与列表同过滤条件的总数(供前端分页器)。 */
    @GetMapping("/alerts/count")
    public ApiResponse<Long> countAlerts(@RequestParam(required = false) String state,
                                         @RequestParam(required = false) String severity,
                                         @RequestParam(required = false) Long ruleId,
                                         @RequestParam(required = false) Long clusterId,
                                         @RequestParam(required = false) Boolean acknowledged,
                                         @RequestParam(required = false) String firedFrom,
                                         @RequestParam(required = false) String firedTo) {
        return ApiResponse.ok(alertService.count(state, severity, ruleId, clusterId, acknowledged,
                firedFrom, firedTo));
    }

    @GetMapping("/alerts/unread-count")
    public ApiResponse<Map<String, Object>> unreadCount() {
        return ApiResponse.ok(Map.of("count", alertService.unreadCount()));
    }

    @GetMapping("/alerts/{id}")
    public ApiResponse<AlertDetailVO> getAlert(@PathVariable long id) {
        return ApiResponse.ok(alertService.detail(id));
    }

    /**
     * 确认告警。body 可选 {@code {"by":"operator"}} —— 本期无登录态,
     * 操作人由前端传入,留 {@code acknowledged_by} 字段以便将来接鉴权。
     */
    @PostMapping("/alerts/{id}/ack")
    public ApiResponse<AlertRecordVO> ackAlert(@PathVariable long id,
                                               @RequestBody(required = false) Map<String, String> body) {
        String by = body == null ? null : body.get("by");
        return ApiResponse.ok(alertService.acknowledge(id, by));
    }

    /** 巡检概览(跨集群聚合)。 */
    @GetMapping("/overview")
    public ApiResponse<InspectionOverviewVO> overview() {
        return ApiResponse.ok(alertService.overview());
    }
}
