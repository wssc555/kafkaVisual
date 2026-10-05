package com.example.kafkaviz.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 消费组 offset 重置结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResetOffsetsResult {

    private String group;

    private String topic;

    /** 实际写入的 partition → offset 列表(TO_TIMESTAMP 无命中的分区被跳过)。 */
    private List<PartitionReset> reset;

    /** 写入分区数。 */
    private int resetCount;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PartitionReset {
        private int partition;
        private long offset;
    }
}