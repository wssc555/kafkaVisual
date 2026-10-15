package com.example.kafkaviz.inspection.controller;

import com.example.kafkaviz.inspection.model.dto.ChannelTestResult;
import com.example.kafkaviz.inspection.model.dto.CreateAlertChannelRequest;
import com.example.kafkaviz.inspection.model.dto.UpdateAlertChannelRequest;
import com.example.kafkaviz.inspection.model.vo.AlertChannelVO;
import com.example.kafkaviz.inspection.service.AlertChannelService;
import com.example.kafkaviz.model.vo.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 告警通道 REST(<b>全局</b> {@code /api/inspection/channels})。
 *
 * <p>与 {@code /api/storage}、{@code /api/preferences} 同级:通道是跨集群复用的
 * 全局配置,挂到任何集群段下都会变成"每个集群配一遍同一套 SMTP"。
 */
@RestController
@RequestMapping("/api/inspection/channels")
@ConditionalOnProperty(name = "inspection.enabled", havingValue = "true", matchIfMissing = true)
public class InspectionChannelController {

    private final AlertChannelService channelService;

    public InspectionChannelController(AlertChannelService channelService) {
        this.channelService = channelService;
    }

    @GetMapping
    public ApiResponse<List<AlertChannelVO>> listChannels() {
        return ApiResponse.ok(channelService.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<AlertChannelVO> getChannel(@PathVariable long id) {
        return ApiResponse.ok(channelService.detail(id));
    }

    @PostMapping
    public ApiResponse<AlertChannelVO> createChannel(@Valid @RequestBody CreateAlertChannelRequest req) {
        return ApiResponse.ok(channelService.create(req));
    }

    @PutMapping("/{id}")
    public ApiResponse<AlertChannelVO> updateChannel(@PathVariable long id,
                                                     @Valid @RequestBody UpdateAlertChannelRequest req) {
        return ApiResponse.ok(channelService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteChannel(@PathVariable long id) {
        channelService.delete(id);
        return ApiResponse.ok();
    }

    /** 用固定内容发一次,返回成功/失败原因(便于排查通道配置)。 */
    @PostMapping("/{id}/test")
    public ApiResponse<ChannelTestResult> testChannel(@PathVariable long id) {
        return ApiResponse.ok(channelService.test(id));
    }
}
