package com.example.kafkaviz.controller;

import com.example.kafkaviz.kafka.ClusterConnection;
import com.example.kafkaviz.model.dto.UpdateTopicConfigsRequest;
import com.example.kafkaviz.model.vo.ApiResponse;
import com.example.kafkaviz.model.vo.ClusterMetadata;
import com.example.kafkaviz.model.vo.ConfigsUpdateResult;
import com.example.kafkaviz.service.ClusterMetadataService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * 集群元数据端点。
 *
 * <p>所有数据均来自 {@link org.apache.kafka.clients.admin.AdminClient},在 ZK 与 KRaft
 * 两种模式下都可用;前端通过 {@code /api/c/{id}/cluster/mode} 得知模式后,
 * 决定 ZK 浏览器入口是否显示。
 */
@RestController
@RequestMapping("/api/c/{clusterId}/cluster/metadata")
public class ClusterMetadataController {

    private final ClusterMetadataService metadataService;

    public ClusterMetadataController(ClusterMetadataService metadataService) {
        this.metadataService = metadataService;
    }

    /** 单个 broker 的配置项。 */
    @GetMapping("/broker-configs/{brokerId}")
    public ApiResponse<ClusterMetadata.BrokerConfig> getBrokerConfigs(@PathVariable long clusterId,
                                                                     ClusterConnection connection,
                                                                     @PathVariable int brokerId)
            throws ExecutionException, InterruptedException {
        return ApiResponse.ok(metadataService.describeBrokerConfigs(connection, brokerId));
    }

    /** 单个 topic 的配置项。 */
    @GetMapping("/topic-configs/{topic}")
    public ApiResponse<ClusterMetadata.TopicConfig> getTopicConfigs(@PathVariable long clusterId,
                                                                   ClusterConnection connection,
                                                                   @PathVariable String topic)
            throws ExecutionException, InterruptedException {
        return ApiResponse.ok(metadataService.describeTopicConfigs(connection, topic));
    }

    /** 在线增量修改 topic 配置(value=null 表示删除该覆盖项,恢复 broker 默认)。 */
    @PatchMapping("/topic-configs/{topic}")
    public ApiResponse<ConfigsUpdateResult> updateTopicConfigs(@PathVariable long clusterId,
                                                              ClusterConnection connection,
                                                              @PathVariable String topic,
                                                              @Valid @RequestBody UpdateTopicConfigsRequest req)
            throws ExecutionException, InterruptedException {
        return ApiResponse.ok(metadataService.updateTopicConfigs(connection, topic, req.getConfigs()));
    }

    /** 该集群上的所有 ACL 规则(未启用 ACL 时返回空列表)。 */
    @GetMapping("/acls")
    public ApiResponse<List<ClusterMetadata.AclInfo>> getAcls(@PathVariable long clusterId,
                                                              ClusterConnection connection) {
        return ApiResponse.ok(metadataService.describeAcls(connection));
    }

    /**
     * 日志目录明细。默认返回全部 broker;指定 {@code brokerId} 时只查该 broker
     * —— 这是唯一能真正减少 broker→客户端传输量的手段(AdminClient 无服务端过滤)。
     *
     * @param topic 可选,只返回该 topic 的副本行
     */
    @GetMapping("/log-dirs")
    public ApiResponse<List<ClusterMetadata.LogDirInfo>> getLogDirs(@PathVariable long clusterId,
                                                                   ClusterConnection connection,
                                                                   @RequestParam(required = false) Integer brokerId,
                                                                   @RequestParam(required = false) String topic)
            throws ExecutionException, InterruptedException {
        List<Integer> brokerIds = metadataService.listBrokerIds(connection, brokerId);
        return ApiResponse.ok(metadataService.describeLogDirs(connection, brokerIds, topic));
    }

    /**
     * 日志目录汇总(每 broker 每 log dir 一行,不含逐分区明细)。
     *
     * <p>口径与 Dashboard 的 totalLogSizeBytes 一致:跳过 future 副本,
     * {@code includeInternal=false}(默认)时排除 {@code _} 前缀内部 topic。
     */
    @GetMapping("/log-dirs/summary")
    public ApiResponse<List<ClusterMetadata.LogDirSummary>> getLogDirsSummary(
            @PathVariable long clusterId,
            ClusterConnection connection,
            @RequestParam(required = false) Integer brokerId,
            @RequestParam(defaultValue = "false") boolean includeInternal)
            throws ExecutionException, InterruptedException {
        List<Integer> brokerIds = metadataService.listBrokerIds(connection, brokerId);
        return ApiResponse.ok(metadataService.describeLogDirsSummary(connection, brokerIds, includeInternal));
    }
}
