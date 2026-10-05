package com.example.kafkaviz.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 消费组跨全部 topic 的聚合消费进度与 lag。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConsumerGroupOverview {

    private String group;

    /** 组状态(Stable/Empty/Dead/PreparingRebalance/CompletingRebalance)。 */
    private String state;

    /** 活跃成员数。 */
    private int memberCount;

    private List<TopicProgress> topics;

    /** 全部 topic lag 之和。 */
    private long totalLag;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopicProgress {
        private String topic;
        /** 结构与 ConsumerGroupDetail.PartitionOffset 完全一致。 */
        private List<ConsumerGroupDetail.PartitionOffset> partitions;
        /** 该 topic 的 lag 之和。 */
        private long totalLag;
    }
}