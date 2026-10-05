package com.example.kafkaviz.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.List;

@Data
public class ResetOffsetsRequest {

    @NotBlank
    private String topic;

    /** 目标分区列表;缺省 = 该 topic 全部分区。 */
    private List<@Min(0) Integer> partitions;

    /** EARLIEST / LATEST / TO_OFFSET / TO_TIMESTAMP。 */
    @NotBlank
    @Pattern(regexp = "EARLIEST|LATEST|TO_OFFSET|TO_TIMESTAMP",
             message = "strategy must be one of EARLIEST/LATEST/TO_OFFSET/TO_TIMESTAMP")
    private String strategy;

    /** strategy=TO_OFFSET 时必填。 */
    @Min(0)
    private Long offset;

    /** strategy=TO_TIMESTAMP 时必填。 */
    @Min(0)
    private Long timestamp;

    @AssertTrue(message = "strategy=TO_OFFSET requires an offset")
    @JsonIgnore
    public boolean isToOffsetValid() {
        return !"TO_OFFSET".equals(strategy) || offset != null;
    }

    @AssertTrue(message = "strategy=TO_TIMESTAMP requires a timestamp")
    @JsonIgnore
    public boolean isToTimestampValid() {
        return !"TO_TIMESTAMP".equals(strategy) || timestamp != null;
    }
}