package com.example.kafkaviz.controller;

import com.example.kafkaviz.archive.ClusterArchiveService;
import com.example.kafkaviz.kafka.ClusterDefinition;
import com.example.kafkaviz.model.vo.ApiResponse;
import com.example.kafkaviz.model.vo.ArchiveMessageQueryResult;
import com.example.kafkaviz.model.vo.ArchiveTopicSummary;
import com.example.kafkaviz.web.ClusterId;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 历史消息归档查询。
 *
 * <p><b>离线可用</b>:本控制器<strong>不</strong>走 {@code getOrConnect} ——
 * 集群参数声明为 {@link ClusterDefinition} 且标注 {@code existenceOnly},resolver
 * 只查配置表(40404 兜底不变),因此 Kafka 不可达时归档照常可查。
 * 这正是归档存在的意义。
 *
 * <p>注意:数据源为外部 PG/MySQL 时,"离线可用"的前提是该数据库可达 ——
 * Kafka 离线 ≠ 数据库离线。
 */
@RestController
@RequestMapping("/api/c/{clusterId}/archive")
public class ArchiveController {

    /** limit 缺省值与上限;超上限 → 40001(不静默截断,避免用户以为看到了全部)。 */
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 1000;

    private final ClusterArchiveService archiveService;

    public ArchiveController(ClusterArchiveService archiveService) {
        this.archiveService = archiveService;
    }

    /**
     * 归档 topic 列表:含「已删除」标记、首末归档时间、归档条数。
     *
     * <p>已删除 topic 是这个列表的核心价值:它仍在 Kafka 的 topic 列表里消失,
     * 但归档行保留,因此"曾经存在过的 topic"可被发现。
     */
    @GetMapping("/topics")
    public ApiResponse<List<ArchiveTopicSummary>> listArchivedTopics(
            @PathVariable long clusterId,
            @ClusterId(existenceOnly = true) ClusterDefinition cluster) {
        return ApiResponse.ok(archiveService.listArchivedTopics(clusterId));
    }

    /**
     * 历史消息查询。
     *
     * <p>参数全为可选(除 topic):分区 / 时间范围 / 翻页游标 / 条数上限。
     * 返回结构复用 {@code MessageRecord},前端 {@code MessageTable} 零适配。
     *
     * <p><b>翻页游标是复合键</b> {@code (partition_id, offset_val)}:未指定分区时,
     * 下一页必须同时传 {@code offsetFromPartition} 与 {@code offsetFrom}
     * (都取上一页的 {@code nextPartition} / {@code nextOffset});只传 offsetFrom
     * 会在多分区 topic 上跳行。指定分区时单传 {@code offsetFrom} 即可。
     *
     * @param fromTime            毫秒时间戳下界(含)
     * @param toTime              毫秒时间戳上界(含)
     * @param offsetFromPartition 游标的分区分量(未指定分区翻页时必传)
     * @param offsetFrom          游标的 offset 分量(含)
     */
    @GetMapping("/messages")
    public ApiResponse<ArchiveMessageQueryResult> queryArchivedMessages(
            @PathVariable long clusterId,
            @ClusterId(existenceOnly = true) ClusterDefinition cluster,
            @RequestParam String topic,
            @RequestParam(required = false) Integer partition,
            @RequestParam(required = false) Long fromTime,
            @RequestParam(required = false) Long toTime,
            @RequestParam(required = false) Integer offsetFromPartition,
            @RequestParam(required = false) Long offsetFrom,
            @RequestParam(required = false) Integer limit) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic must not be blank");
        }
        int effectiveLimit = limit == null ? DEFAULT_LIMIT : limit;
        if (effectiveLimit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        if (effectiveLimit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must not exceed " + MAX_LIMIT);
        }
        if (partition != null && partition < 0) {
            throw new IllegalArgumentException("partition must be >= 0");
        }
        if (offsetFromPartition != null && offsetFromPartition < 0) {
            throw new IllegalArgumentException("offsetFromPartition must be >= 0");
        }
        return ApiResponse.ok(archiveService.queryMessages(
                clusterId, topic, partition, offsetFromPartition,
                fromTime, toTime, offsetFrom, effectiveLimit));
    }

    /**
     * 清理归档:带 {@code topic} 只删该 topic 的行;不带则清空整集群归档
     * (整表 DROP + 重建,瞬时回收)。
     */
    @DeleteMapping
    public ApiResponse<Long> deleteArchive(@PathVariable long clusterId,
                                           @ClusterId(existenceOnly = true) ClusterDefinition cluster,
                                           @RequestParam(required = false) String topic) {
        return ApiResponse.ok(archiveService.deleteArchive(clusterId, topic));
    }
}
