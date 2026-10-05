package com.example.kafkaviz.model.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 单条消息记录。key/value/valueFormatted 可能为 null,统一按 NON_NULL 省略,
 * 客户端须把非 code/msg 字段视为可选(与 ApiResponse 的序列化约定一致)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageRecord {
    private String topic;
    private int partition;
    private long offset;
    private long timestamp;
    private String timestampType;
    private String key;
    private String value;
    private String valueFormatted;
    private Map<String, String> headers;
}