package com.example.kafkaviz.controller;

import com.example.kafkaviz.kafka.ClusterConnection;
import com.example.kafkaviz.model.dto.OffsetForTimesRequest;
import com.example.kafkaviz.model.dto.ProduceMessageRequest;
import com.example.kafkaviz.model.dto.QueryMessageRequest;
import com.example.kafkaviz.model.vo.ApiResponse;
import com.example.kafkaviz.model.vo.MessageQueryResult;
import com.example.kafkaviz.model.vo.ProduceResult;
import com.example.kafkaviz.model.vo.TopicOffsetLookup;
import com.example.kafkaviz.service.MessageService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.ExecutionException;

/**
 * 消息端点。
 */
@RestController
@RequestMapping("/api/c/{clusterId}/messages")
public class MessageController {

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    /**
     * 查询实时消息。
     *
     * <p>用 {@code @ModelAttribute + @Valid} 触发 GET 查询参数的 Bean 校验:
     * 裸 bean 参数 + {@code @Valid} 在 Spring 6 上可能不触发,校验失败会落 50000
     * 而不是 40001(BindException handler 已存在)。
     */
    @GetMapping
    public ApiResponse<MessageQueryResult> queryMessages(@PathVariable long clusterId,
                                                         ClusterConnection connection,
                                                         @Valid @ModelAttribute QueryMessageRequest req) {
        return ApiResponse.ok(messageService.query(connection, req));
    }

    /**
     * 按时间戳定位各分区首条消息 offset。
     */
    @GetMapping("/offsets-for-times")
    public ApiResponse<TopicOffsetLookup> offsetsForTimes(@PathVariable long clusterId,
                                                          ClusterConnection connection,
                                                          @Valid @ModelAttribute OffsetForTimesRequest req)
            throws ExecutionException, InterruptedException {
        return ApiResponse.ok(messageService.offsetsForTimes(connection, req));
    }

    /**
     * 向指定 topic 同步发送单条消息。
     */
    @PostMapping
    public ApiResponse<ProduceResult> produceMessage(@PathVariable long clusterId,
                                                     ClusterConnection connection,
                                                     @Valid @RequestBody ProduceMessageRequest req) {
        return ApiResponse.ok(messageService.produce(connection, req));
    }
}
