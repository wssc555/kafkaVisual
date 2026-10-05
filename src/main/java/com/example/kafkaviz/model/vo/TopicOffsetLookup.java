package com.example.kafkaviz.model.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 按时间戳定位消息的结果:各分区中"时间戳 ≥ 目标值"的首条消息 offset。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TopicOffsetLookup {

    private String topic;

    /** 请求的目标时间戳。 */
    private long timestamp;

    private List<PartitionOffset> partitions;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PartitionOffset {
        private int partition;
        /** 命中消息 offset;-1 表示该分区在目标时间之后无消息。 */
        private long offset;
        /** 命中消息的时间戳;offset=-1 时为 null 并被 NON_NULL 省略。 */
        private Long matchTimestamp;
    }
}