package com.example.kafkaviz.model.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class QueryMessageRequest {

    @NotBlank(message = "topic must not be empty")
    private String topic;

    @NotNull
    @Min(0)
    private Integer partition;

    @NotNull
    @Min(0)
    private Long offset;

    @NotNull
    @Min(1)
    private Integer count;

    /**
     * 是否返回格式化后的 value(valueFormatted)。默认 false —— 列表不需要,
     * 消息详情由前端本地格式化(见 MessageDetail.vue),避免千条消息响应体翻倍。
     */
    private Boolean includeFormatted = false;
}