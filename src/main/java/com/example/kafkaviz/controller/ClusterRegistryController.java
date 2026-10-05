package com.example.kafkaviz.controller;

import com.example.kafkaviz.model.dto.ClusterUpsertRequest;
import com.example.kafkaviz.model.dto.ClusterValidateRequest;
import com.example.kafkaviz.model.vo.ApiResponse;
import com.example.kafkaviz.model.vo.ClusterSummary;
import com.example.kafkaviz.model.vo.ClusterValidateResult;
import com.example.kafkaviz.service.ClusterRegistryService;
import com.example.kafkaviz.service.ClusterValidationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * 集群注册表端点。
 *
 * <p><b>不带集群段</b>:这些端点本身就是"管理所有集群",前缀固定 {@code /api/clusters}。
 * 功能端点才走 {@code /api/c/{clusterId}/...}。
 *
 * <p>错误码:① 不存在 → 404/40404;② 未连接/连接中 → 503/50302;
 * ③ 参数问题(@Valid / IllegalArgumentException / 认证校验)→ 400/40001;
 * ④ 测连时认证失败或握手失败 → 500/50001。无新增码。
 */
@RestController
@RequestMapping("/api/clusters")
public class ClusterRegistryController {

    private final ClusterRegistryService registryService;
    private final ClusterValidationService validationService;

    public ClusterRegistryController(ClusterRegistryService registryService,
                                     ClusterValidationService validationService) {
        this.registryService = registryService;
        this.validationService = validationService;
    }

    /** 全部集群 + 连接状态(含归档配置,<b>不含</b>任何口令)。 */
    @GetMapping
    public ApiResponse<List<ClusterSummary>> listClusters() {
        return ApiResponse.ok(registryService.list());
    }

    /**
     * 轻量状态轮询:只回 id + displayState + errorSummary。
     *
     * <p>Dashboard 每 10s 刷一次,这条路径必须便宜 —— 所以不做配置全量回显。
     * 注意与 {@code /{id}} 的路径冲突:Spring 会优先匹配更具体的字面量段,
     * 且本类没有 {@code GET /{id}},不存在歧义。
     */
    @GetMapping("/status")
    public ApiResponse<List<ClusterSummary>> clusterStatuses() {
        return ApiResponse.ok(registryService.statuses());
    }

    /** 新建(不自动连接)。 */
    @PostMapping
    public ApiResponse<ClusterSummary> createCluster(@Valid @RequestBody ClusterUpsertRequest req) {
        return ApiResponse.ok(registryService.create(req));
    }

    /**
     * 保存前测试连接:不落库、不缓存,只建一个一次性 AdminClient
     * 做 {@code describeCluster()}。
     *
     * <p>刻意<b>不加</b> {@code @Valid}:测连不需要集群名,复用 upsert DTO 时
     * 强校验 name 只会平添无用约束;地址与凭据的校验由 Service 层的
     * {@code ClusterUpsertValidator} 承担(与保存共用同一套规则)。
     *
     * <p>携带 {@code clusterId} 时,留空的秘密字段(口令/私钥/clientSecret/JAAS)
     * 由服务端从库中补全 —— 编辑已有集群时无需重输凭据。
     *
     * <p>失败口径:校验失败 400/40001;超时 503/50302;认证或 TLS 握手失败、
     * 其余 Kafka 失败 500/50001。
     */
    @PostMapping("/validate")
    public ApiResponse<ClusterValidateResult> validateCluster(@RequestBody ClusterValidateRequest req)
            throws ExecutionException, InterruptedException {
        return ApiResponse.ok(validationService.validate(req));
    }

    /** 修改;连接参数变化时先断开(下次请求按新参数重建)。 */
    @PutMapping("/{id}")
    public ApiResponse<ClusterSummary> updateCluster(@PathVariable long id,
                                                     @Valid @RequestBody ClusterUpsertRequest req) {
        return ApiResponse.ok(registryService.update(id, req));
    }

    /** 删除:先断开连接,再清空该集群的归档数据与收藏,最后删配置。 */
    @DeleteMapping("/{id}")
    public ApiResponse<ClusterSummary> deleteCluster(@PathVariable long id) {
        registryService.delete(id);
        // 被删对象已不存在,回显一个最小摘要表示操作成功
        return ApiResponse.ok(ClusterSummary.builder().id(id).displayState("OFFLINE").build());
    }

    /** 显式建连(异步):立即返回 CONNECTING,状态交给轮询。 */
    @PostMapping("/{id}/connect")
    public ApiResponse<ClusterSummary> connectCluster(@PathVariable long id) {
        return ApiResponse.ok(registryService.connect(id));
    }

    /** 断开回收(归档器一并停止;已有归档数据保留)。 */
    @PostMapping("/{id}/disconnect")
    public ApiResponse<ClusterSummary> disconnectCluster(@PathVariable long id) {
        return ApiResponse.ok(registryService.disconnect(id));
    }
}
