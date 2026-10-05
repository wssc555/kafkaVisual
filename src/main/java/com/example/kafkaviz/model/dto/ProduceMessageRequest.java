package com.example.kafkaviz.model.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class ProduceMessageRequest {

    @NotBlank
    private String topic;

    /** 指定分区;缺省 = 按 key 哈希(无 key 则轮询)。 */
    @Min(0)
    private Integer partition;

    /** 消息 key(UTF-8)。 */
    private String key;

    /** 消息体(UTF-8,空串合法;墓碑消息 null value 为后续扩展)。 */
    @NotNull
    private String value;

    /** 消息头。 */
    private Map<String, String> headers;

    /** 显式时间戳;缺省 = producer 端 CreateTime。 */
    @Min(0)
    private Long timestamp;
}