package com.example.kafkaviz.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartitionsUpdateResult {

    private String name;

    /** 新的总分区数。 */
    private int partitions;

    private Boolean updated;
}