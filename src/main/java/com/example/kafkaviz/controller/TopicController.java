package com.example.kafkaviz.controller;

import com.example.kafkaviz.kafka.ClusterConnection;
import com.example.kafkaviz.model.dto.CreateTopicRequest;
import com.example.kafkaviz.model.dto.ExpandPartitionsRequest;
import com.example.kafkaviz.model.vo.ApiResponse;
import com.example.kafkaviz.model.vo.PartitionsUpdateResult;
import com.example.kafkaviz.model.vo.TopicActionResult;
import com.example.kafkaviz.model.vo.TopicDetail;
import com.example.kafkaviz.service.TopicService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Topic 端点。所有方法只做参数转发:resolver 注入的 {@link ClusterConnection}
 * 透传给服务层。
 */
@RestController
@RequestMapping("/api/c/{clusterId}/topics")
public class TopicController {

    private final TopicService topicService;

    public TopicController(TopicService topicService) {
        this.topicService = topicService;
    }

    /** topic 名列表;{@code includeInternal=true} 时包含内部 topic。 */
    @GetMapping
    public ApiResponse<List<String>> listTopics(@PathVariable long clusterId,
                                                ClusterConnection connection,
                                                @RequestParam(defaultValue = "false") boolean includeInternal)
            throws ExecutionException, InterruptedException {
        return ApiResponse.ok(topicService.listTopics(connection, includeInternal));
    }

    /** 单个 topic 的详情:分区/副本布局(leader、replicas、ISR)与各分区首末 offset。 */
    @GetMapping("/{name}")
    public ApiResponse<TopicDetail> getTopicDetail(@PathVariable long clusterId,
                                                   ClusterConnection connection,
                                                   @PathVariable String name)
            throws ExecutionException, InterruptedException {
        return ApiResponse.ok(topicService.describeTopic(connection, name));
    }

    /** 创建 topic。 */
    @PostMapping
    public ApiResponse<TopicActionResult> createTopic(@PathVariable long clusterId,
                                                      ClusterConnection connection,
                                                      @Valid @RequestBody CreateTopicRequest req)
            throws ExecutionException, InterruptedException {
        return ApiResponse.ok(topicService.createTopic(connection, req));
    }

    /** 删除 topic。 */
    @DeleteMapping("/{name}")
    public ApiResponse<TopicActionResult> deleteTopic(@PathVariable long clusterId,
                                                      ClusterConnection connection,
                                                      @PathVariable String name)
            throws ExecutionException, InterruptedException {
        return ApiResponse.ok(topicService.deleteTopic(connection, name));
    }

    /** 扩容分区数(只增不减)。 */
    @PostMapping("/{name}/partitions")
    public ApiResponse<PartitionsUpdateResult> expandPartitions(@PathVariable long clusterId,
                                                                ClusterConnection connection,
                                                                @PathVariable String name,
                                                                @Valid @RequestBody ExpandPartitionsRequest req)
            throws ExecutionException, InterruptedException {
        return ApiResponse.ok(topicService.expandPartitions(connection, name, req.getPartitions()));
    }
}
