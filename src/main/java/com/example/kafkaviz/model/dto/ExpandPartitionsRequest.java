package com.example.kafkaviz.model.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ExpandPartitionsRequest {

    /** 扩容后的总分区数(非增量)。 */
    @NotNull
    @Min(1)
    private Integer partitions;
}