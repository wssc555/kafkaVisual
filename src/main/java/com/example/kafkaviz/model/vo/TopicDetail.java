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
public class TopicDetail {
    private String name;
    private List<PartitionInfo> partitions;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PartitionInfo {
        private int partition;
        private int leader;
        private List<Integer> replicas;
        private List<Integer> isr;
        private long beginningOffset;
        private long endOffset;
    }
}