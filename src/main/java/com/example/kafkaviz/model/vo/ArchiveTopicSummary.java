package com.example.kafkaviz.model.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 归档 topic 列表项({@code GET /api/c/{id}/archive/topics})。
 *
 * <p>{@link #deleted} 是这套能力的核心:topic 已从 Kafka 删除,但归档行还在,
 * 因此"查曾经存在过的 topic"成为可能。
 */
@Data
@Builder
public class ArchiveTopicSummary {

    /** topic 名。 */
    private String topicName;

    /** 1 = 已从 Kafka 消失(仅归档可查);0 = 仍在 Kafka topic 列表中。 */
    private int deleted;

    /** 首次归档时间(ISO-8601)。 */
    private String firstSeenAt;

    /** 最近一次"仍在 Kafka 列表中"的时间(ISO-8601);被删后不再更新。 */
    private String lastSeenAt;

    /** 已归档消息条数(该 topic 在 {@code msg_<clusterId>} 里的行数)。 */
    private long messageCount;

    /** 最近一次记录的分区数;可能为 NULL(首见时未采集)。 */
    private Integer lastPartitionCount;
}
