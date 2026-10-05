package com.example.kafkaviz.controller;

import com.example.kafkaviz.kafka.ClusterConnection;
import com.example.kafkaviz.model.dto.ResetOffsetsRequest;
import com.example.kafkaviz.model.vo.ApiResponse;
import com.example.kafkaviz.model.vo.ConsumerGroupDetail;
import com.example.kafkaviz.model.vo.ConsumerGroupOverview;
import com.example.kafkaviz.model.vo.GroupDeleteResult;
import com.example.kafkaviz.model.vo.ResetOffsetsResult;
import com.example.kafkaviz.service.ConsumerGroupService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * 消费组端点。
 *
 * <p>注意:归档消费组 {@code kafkaviz-archive-<clusterId>} 会出现在列表里 ——
 * 属预期行为,不做隐藏过滤。
 */
@RestController
@RequestMapping("/api/c/{clusterId}/consumer-groups")
public class ConsumerGroupController {

    private final ConsumerGroupService consumerGroupService;

    public ConsumerGroupController(ConsumerGroupService consumerGroupService) {
        this.consumerGroupService = consumerGroupService;
    }

    /** 全部消费组名。 */
    @GetMapping
    public ApiResponse<List<String>> listConsumerGroups(@PathVariable long clusterId,
                                                        ClusterConnection connection)
            throws ExecutionException, InterruptedException {
        return ApiResponse.ok(consumerGroupService.listGroups(connection));
    }

    /** 消费组跨全部 topic 的聚合消费进度。 */
    @GetMapping("/{group}")
    public ApiResponse<ConsumerGroupOverview> describeGroup(@PathVariable long clusterId,
                                                            ClusterConnection connection,
                                                            @PathVariable String group)
            throws ExecutionException, InterruptedException {
        return ApiResponse.ok(consumerGroupService.describeAll(connection, group));
    }

    /** 消费组在单个 topic 上的消费进度明细(逐分区 offset/lag 与成员分配)。 */
    @GetMapping("/{group}/topics/{topic}")
    public ApiResponse<ConsumerGroupDetail> describeConsumerGroup(@PathVariable long clusterId,
                                                                  ClusterConnection connection,
                                                                  @PathVariable String group,
                                                                  @PathVariable String topic)
            throws ExecutionException, InterruptedException {
        return ApiResponse.ok(consumerGroupService.describe(connection, group, topic));
    }

    /** 重置消费组已提交 offset(仅 Empty 状态)。 */
    @PostMapping("/{group}/offsets/reset")
    public ApiResponse<ResetOffsetsResult> resetOffsets(@PathVariable long clusterId,
                                                        ClusterConnection connection,
                                                        @PathVariable String group,
                                                        @Valid @RequestBody ResetOffsetsRequest req)
            throws ExecutionException, InterruptedException {
        return ApiResponse.ok(consumerGroupService.resetOffsets(connection, group, req));
    }

    /** 删除消费组(仅 Empty 状态)。 */
    @DeleteMapping("/{group}")
    public ApiResponse<GroupDeleteResult> deleteConsumerGroup(@PathVariable long clusterId,
                                                              ClusterConnection connection,
                                                              @PathVariable String group)
            throws ExecutionException, InterruptedException {
        return ApiResponse.ok(consumerGroupService.deleteGroup(connection, group));
    }
}
