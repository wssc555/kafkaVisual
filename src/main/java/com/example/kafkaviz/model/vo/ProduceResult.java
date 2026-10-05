package com.example.kafkaviz.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 消息生产结果,来自 {@link org.apache.kafka.clients.producer.RecordMetadata}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProduceResult {

    private String topic;

    /** 实际写入分区。 */
    private int partition;

    /** 写入 offset。 */
    private long offset;

    /** 实际消息时间戳。 */
    private long timestamp;
}