package com.example.kafkaviz.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConsumerGroupDetail {
    private String group;
    private String topic;
    private String state;
    private List<PartitionOffset> partitions;
    private long totalLag;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PartitionOffset {
        private int partition;
        private long currentOffset;
        private long logEndOffset;
        private long lag;
        private String memberId;
        private String clientId;
        private String host;
    }
}