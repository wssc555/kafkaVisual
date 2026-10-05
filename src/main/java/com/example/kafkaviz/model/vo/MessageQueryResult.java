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
public class MessageQueryResult {
    private String topic;
    private int partition;
    private long startOffset;
    private List<MessageRecord> records;
    private int totalReturned;
    private long endOffset;
    private boolean hasMore;
}