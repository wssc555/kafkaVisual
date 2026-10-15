package com.example.kafkaviz.inspection.controller;

import com.example.kafkaviz.inspection.metric.MetricCatalog;
import com.example.kafkaviz.inspection.model.dto.CreateInspectionRuleRequest;
import com.example.kafkaviz.inspection.model.dto.ToggleRuleRequest;
import com.example.kafkaviz.inspection.model.dto.UpdateInspectionRuleRequest;
import com.example.kafkaviz.inspection.model.vo.InspectionRulePageVO;
import com.example.kafkaviz.inspection.model.vo.InspectionRuleVO;
import com.example.kafkaviz.inspection.model.vo.MetricCatalogItemVO;
import com.example.kafkaviz.inspection.model.vo.RuleTestResult;
import com.example.kafkaviz.inspection.service.InspectionRuleService;
import com.example.kafkaviz.kafka.ClusterConnection;
import com.example.kafkaviz.kafka.ClusterDefinition;
import com.example.kafkaviz.model.vo.ApiResponse;
import com.example.kafkaviz.web.ClusterId;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 巡检规则 REST(<b>集群段</b> {@code /api/c/{clusterId}/inspection/rules})。
 *
 * <p>挂集群段的理由:规则天然属于某个集群(topic 名 / group id 只在集群内有意义,
 * 唯一键是 {@code (cluster_id, name)}),跨集群同名合法。
 *
 * <p>连接语义(与归档/收藏同哲学):
 * <ul>
 *   <li>CRUD / 启停 / 删除 / 指标目录 —— {@code existenceOnly},集群离线照样可用;</li>
 *   <li>试运行 —— 需要 {@link ClusterConnection}(惰性建连),离线得到 50302,
 *       集群不存在得到 40404,都由 {@code ClusterArgumentResolver} 负责。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/c/{clusterId}/inspection")
@ConditionalOnProperty(name = "inspection.enabled", havingValue = "true", matchIfMissing = true)
public class InspectionRuleController {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_SIZE = 20;

    private final InspectionRuleService ruleService;

    public InspectionRuleController(InspectionRuleService ruleService) {
        this.ruleService = ruleService;
    }

    /**
     * 指标目录(供规则构建器下拉)。
     *
     * <p>KRaft 集群不下发 ZK 级指标 —— 前端根本看不到,配合创建时的 40005 校验,
     * 从源头杜绝"配了一条永远采不到值的规则"。
     */
    @GetMapping("/metrics")
    public ApiResponse<List<MetricCatalogItemVO>> metrics(@PathVariable long clusterId,
                                                          @ClusterId(existenceOnly = true) ClusterDefinition cluster) {
        return ApiResponse.ok(MetricCatalog.listFor(cluster.zkConfigured()));
    }

    /** 规则列表(分页 + 过滤)。 */
    @GetMapping("/rules")
    public ApiResponse<InspectionRulePageVO> listRules(@PathVariable long clusterId,
                                                       @ClusterId(existenceOnly = true) ClusterDefinition cluster,
                                                       @RequestParam(required = false) Boolean enabled,
                                                       @RequestParam(required = false) String scope,
                                                       @RequestParam(required = false) String name,
                                                       @RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        int safePage = page <= 0 ? DEFAULT_PAGE : page;
        int safeSize = size <= 0 ? DEFAULT_SIZE : size;
        return ApiResponse.ok(ruleService.list(clusterId, enabled, scope, name, safePage, safeSize));
    }

    @GetMapping("/rules/{id}")
    public ApiResponse<InspectionRuleVO> getRule(@PathVariable long clusterId,
                                                 @ClusterId(existenceOnly = true) ClusterDefinition cluster,
                                                 @PathVariable long id) {
        return ApiResponse.ok(ruleService.detail(clusterId, id));
    }

    @PostMapping("/rules")
    public ApiResponse<InspectionRuleVO> createRule(@PathVariable long clusterId,
                                                    @ClusterId(existenceOnly = true) ClusterDefinition cluster,
                                                    @Valid @RequestBody CreateInspectionRuleRequest req) {
        return ApiResponse.ok(ruleService.create(clusterId, req));
    }

    @PutMapping("/rules/{id}")
    public ApiResponse<InspectionRuleVO> updateRule(@PathVariable long clusterId,
                                                    @ClusterId(existenceOnly = true) ClusterDefinition cluster,
                                                    @PathVariable long id,
                                                    @Valid @RequestBody UpdateInspectionRuleRequest req) {
        return ApiResponse.ok(ruleService.update(clusterId, id, req));
    }

    @DeleteMapping("/rules/{id}")
    public ApiResponse<Void> deleteRule(@PathVariable long clusterId,
                                        @ClusterId(existenceOnly = true) ClusterDefinition cluster,
                                        @PathVariable long id) {
        ruleService.delete(clusterId, id);
        return ApiResponse.ok();
    }

    /** 启停:启用重排 next_fire_at,禁用清空(ticker 自然不再命中)。 */
    @PostMapping("/rules/{id}/toggle")
    public ApiResponse<InspectionRuleVO> toggleRule(@PathVariable long clusterId,
                                                    @ClusterId(existenceOnly = true) ClusterDefinition cluster,
                                                    @PathVariable long id,
                                                    @RequestBody(required = false) ToggleRuleRequest req) {
        boolean enabled = req == null || req.isEnabled();
        return ApiResponse.ok(ruleService.toggle(clusterId, id, enabled));
    }

    /**
     * 试运行:立即评估一次并返回结果,<b>不发告警、不写状态机</b>。
     *
     * <p>采集失败 → {@code InspectionExecutionException} → 50004。
     */
    @PostMapping("/rules/{id}/test")
    public ApiResponse<RuleTestResult> testRule(@PathVariable long clusterId,
                                                ClusterConnection connection,
                                                @PathVariable long id) {
        return ApiResponse.ok(ruleService.test(clusterId, id, connection));
    }
}
