package com.example.kafkaviz.model.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 归档消息查询结果({@code GET /api/c/{id}/archive/messages})。
 *
 * <p>{@link #records} 复用 {@link MessageRecord} —— 前端 {@code MessageTable} /
 * {@code MessageDetail} 组件零适配即可渲染归档结果。这也是为什么
 * 归档查询不在 VO 层引入任何"归档专用"字段。
 */
@Data
@Builder
public class ArchiveMessageQueryResult {

    private String topic;

    /** 查询时指定的分区;null = 全部分区。 */
    private Integer partition;

    private int totalReturned;

    private List<MessageRecord> records;

    /**
     * 是否可能还有更多。
     *
     * <p>判定方式 = "返回条数 == limit"(多取一条会更准,但归档表主键有序、
     * offset 连续,按 limit 判断已足够;前端翻页只需 nextOffset)。
     */
    private boolean hasMore;

    /**
     * 下一页游标的 offset 分量 = 末条 offset + 1。
     *
     * <p>无记录时等于请求的 {@code offsetFrom}(原样回传,便于前端幂等处理)。
     */
    private long nextOffset;

    /**
     * 下一页游标的 partition 分量。
     *
     * <p>翻页游标是复合键 {@code (partition_id, offset_val)}:未指定分区查询时,
     * 前端翻页必须把 {@code nextPartition + nextOffset} <b>成对</b>回传
     * ({@code offsetFromPartition} + {@code offsetFrom}),否则多分区 topic
     * 会漏行。指定分区查询时恒等于该分区;空结果时原样回传请求值。
     */
    private Integer nextPartition;
}
