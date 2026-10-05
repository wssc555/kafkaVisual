package com.example.kafkaviz.model.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class OffsetForTimesRequest {

    @NotBlank(message = "topic must not be empty")
    private String topic;

    /** 目标时间戳(ms)。 */
    @NotNull
    @Min(0)
    private Long timestamp;

    /** 指定分区;缺省 = 全部分区。 */
    @Min(0)
    private Integer partition;
}