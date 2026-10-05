package com.example.kafkaviz.archive;

import com.example.kafkaviz.kafka.ClusterDefinition;
import com.example.kafkaviz.model.vo.ArchiveMessageQueryResult;
import com.example.kafkaviz.model.vo.ArchiveTopicSummary;
import com.example.kafkaviz.model.vo.MessageRecord;
import com.example.kafkaviz.storage.ClusterConfigStore;
import com.example.kafkaviz.storage.StorageDialect;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;

/**
 * 每集群归档单元。
 *
 * <p>这是归档能力的<b>存储侧</b>唯一入口:动态建/删表、topic 台账、批量幂等写、
 * 历史查询、retention 清理。写入方是 {@code ClusterArchiver},
 * 读取方是 {@code ArchiveController}。
 *
 * <p>两条贯穿全类的口径:
 * <ol>
 *   <li><b>所有方言差异经 {@link StorageDialect}</b> —— 本类不出现
 *       {@code INSERT OR IGNORE} / {@code ON CONFLICT} / 反引号 这类方言痕迹。
 *       唯一例外是标准 SQL 的 {@code SELECT COUNT(*)} 与
 *       {@code UPDATE ... SET deleted=1}(参数化、三方言同形),这两条也走
 *       {@code quoteIdent} 引用标识符。</li>
 *   <li><b>读前必先 ensureArchiveTable</b> —— 否则"从未归档过的集群"查询会撞上
 *       "表不存在"。建一张空表是无害的(集群删除时会被 DROP),换来的是彻底消失
 *       一整类错误路径。</li>
 * </ol>
 */
@Service
public class ClusterArchiveService {

    private static final Logger log = LoggerFactory.getLogger(ClusterArchiveService.class);

    /**
     * 单条 INSERT 的最大行数。
     *
     * <p>9 列 × 200 = 1800 个绑定参数,三方言都在安全区内(SQLite 3.32+ 上限 32766,
     * MySQL 65535,PgJDBC 无硬限)。再大只是让失败时的重试代价更高。
     */
    private static final int MAX_ROWS_PER_STATEMENT = 200;

    private final JdbcTemplate jdbcTemplate;
    private final StorageDialect dialect;
    private final DataSource dataSource;
    private final ClusterConfigStore clusterConfigStore;
    private final ObjectMapper objectMapper;

    /** 已确保存在的归档表(避免每批写都跑一次 DDL)。进程内缓存;{@code IF NOT EXISTS} 保证跨进程也安全。 */
    private final Set<Long> ensuredTables = ConcurrentHashMap.newKeySet();

    public ClusterArchiveService(JdbcTemplate jdbcTemplate,
                                StorageDialect dialect,
                                DataSource dataSource,
                                ClusterConfigStore clusterConfigStore,
                                ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.dialect = dialect;
        this.dataSource = dataSource;
        this.clusterConfigStore = clusterConfigStore;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------
    // 动态 DDL
    // ---------------------------------------------------------------

    private static String now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /**
     * 确保该集群的归档表存在(首次调用执行 DDL,之后走内存标记)。
     *
     * <p>注意:<b>不</b>动 {@code topic_registry} —— 台账只由
     * {@link #syncTopicRegistry} 维护,建表和"这个 topic 曾经存在"是两件事。
     */
    public void ensureArchiveTable(long clusterId) {
        if (ensuredTables.contains(clusterId)) {
            return;
        }
        for (String ddl : dialect.createArchiveTableSql(clusterId)) {
            jdbcTemplate.execute(ddl);
        }
        ensuredTables.add(clusterId);
        log.info("Ensured archive table {} for cluster {}", dialect.archiveTable(clusterId), clusterId);
    }

    /**
     * 删除该集群的归档表(整表 DROP,瞬时回收,不逐行 DELETE)。
     *
     * <p>由集群删除流程调用。
     */
    public void dropArchiveTable(long clusterId) {
        jdbcTemplate.execute(dialect.dropArchiveTableSql(clusterId));
        ensuredTables.remove(clusterId);
        log.info("Dropped archive table {} of cluster {}", dialect.archiveTable(clusterId), clusterId);
    }

    /** 该集群归档表名(供诊断/日志)。 */
    public String archiveTableName(long clusterId) {
        return dialect.archiveTable(clusterId);
    }

    // ---------------------------------------------------------------
    // topic 台账
    // ---------------------------------------------------------------

    /**
     * 彻底清掉该集群的归档痕迹(集群删除时调用)。
     *
     * <p>比 {@link #dropArchiveTable} 多做一件事:删 {@code topic_registry} 里属于该集群的
     * 台账行。少了这一步会留下<b>永远查不到、也永远删不掉</b>的孤儿行(集群 id 不会被复用,
     * 所以它们不会被误读,只是白占空间)。
     *
     * @return 删掉的台账行数
     */
    public int purgeClusterArchive(long clusterId) {
        dropArchiveTable(clusterId);
        int registryRows = jdbcTemplate.update(
                "DELETE FROM " + dialect.quoteIdent("topic_registry") + " WHERE "
                        + dialect.quoteIdent("cluster_id") + " = ?", clusterId);
        log.info("Purged archive metadata of cluster {} ({} topic registry row(s) removed)",
                clusterId, registryRows);
        return registryRows;
    }

    // ---------------------------------------------------------------
    // 写入路径(供 ClusterArchiver 调用)
    // ---------------------------------------------------------------

    /**
     * 同步 {@code topic_registry}。
     *
     * <p>差异计算放在 Java 侧而不是一条"NOT IN (...) 批量置 deleted"的 SQL 里:
     * 后者要拼动态 IN 列表,是三方言漂移最容易发生的地方(空集合、超长列表、
     * 引号规则各不同)。这里只用到"一个集群的 topic 名"这一份内存数据。
     *
     * @param liveTopics      当前 Kafka 上真实存在的 topic 名(调用方已过滤内部 topic)
     * @param partitionCountOf 取某 topic 当前分区数,用于记录规模(可返回 0)
     * @return 被置为 deleted 的 topic 数
     */
    @Transactional
    public int syncTopicRegistry(long clusterId, Collection<String> liveTopics,
                                 ToIntFunction<String> partitionCountOf) {
        String now = now();
        Set<String> live = new HashSet<>(liveTopics);

        List<String> known = jdbcTemplate.query(
                "SELECT " + dialect.quoteIdent("topic_name") + " FROM " + dialect.quoteIdent("topic_registry")
                        + " WHERE " + dialect.quoteIdent("cluster_id") + " = ? AND "
                        + dialect.quoteIdent("deleted") + " = 0",
                (rs, rowNum) -> rs.getString(1), clusterId);

        List<String> disappeared = new ArrayList<>();
        for (String topic : known) {
            if (!live.contains(topic)) {
                disappeared.add(topic);
            }
        }
        String markDeletedSql = "UPDATE " + dialect.quoteIdent("topic_registry")
                + " SET " + dialect.quoteIdent("deleted") + " = 1 WHERE "
                + dialect.quoteIdent("cluster_id") + " = ? AND " + dialect.quoteIdent("topic_name") + " = ?";
        for (String topic : disappeared) {
            jdbcTemplate.update(markDeletedSql, clusterId, topic);
        }

        // upsert:命中 (cluster_id, topic_name) 时刷新 last_seen / 分区数并把 deleted 清回 0
        String upsertSql = dialect.upsertTopicRegistrySql();
        for (String topic : live) {
            jdbcTemplate.update(upsertSql, clusterId, topic, now, now, partitionCountOf.applyAsInt(topic));
        }

        if (!disappeared.isEmpty()) {
            log.info("Cluster {}: marked {} topic(s) as deleted (kept their archived messages): {}",
                    clusterId, disappeared.size(), disappeared);
        }
        return disappeared.size();
    }

    /**
     * 批量幂等写入(单事务)。
     *
     * <p>幂等靠主键 {@code (topic, partition_id, offset_val)} + 方言的
     * {@code INSERT OR IGNORE / ON CONFLICT DO NOTHING / INSERT IGNORE}:
     * 归档消费组 offset 回退重放时不会产生重复行,这是"可以无脑重试"的前提。
     *
     * @return 实际插入行数(含被忽略的重复行会小于 batch.size();不同方言返回语义
     *         略有差异,MySQL 的 INSERT IGNORE 返回"尝试插入"的行数,
     *         因此该值仅用于日志,不做断言依据)
     */
    @Transactional
    public int appendMessages(long clusterId, List<ArchivedMessage> batch) {
        if (batch == null || batch.isEmpty()) {
            return 0;
        }
        ensureArchiveTable(clusterId);
        String archivedAt = now();
        int total = 0;
        for (int from = 0; from < batch.size(); from += MAX_ROWS_PER_STATEMENT) {
            int to = Math.min(from + MAX_ROWS_PER_STATEMENT, batch.size());
            total += insertChunk(clusterId, batch.subList(from, to), archivedAt);
        }
        return total;
    }

    private int insertChunk(long clusterId, List<ArchivedMessage> chunk, String archivedAt) {
        String sql = dialect.insertArchiveIgnoreSql(clusterId, chunk.size());
        List<Object> args = new ArrayList<>(chunk.size() * StorageDialect.ARCHIVE_COLUMNS.size());
        for (ArchivedMessage m : chunk) {
            // 顺序必须与 StorageDialect.ARCHIVE_COLUMNS 完全一致
            args.add(m.topic());
            args.add(m.partition());
            args.add(m.offset());
            args.add(m.timestampMs());
            args.add(m.timestampType());
            args.add(m.key());
            args.add(m.value());
            args.add(m.headersJson());
            args.add(archivedAt);
        }
        return jdbcTemplate.update(sql, args.toArray());
    }

    // ---------------------------------------------------------------
    // 查询路径(供 ArchiveController 调用)
    // ---------------------------------------------------------------

    /** 归档 topic 列表(含已删除标记与归档条数);离线集群可用。 */
    public List<ArchiveTopicSummary> listArchivedTopics(long clusterId) {
        ensureArchiveTable(clusterId);
        // 按列名读取(与 toMessageRecord 同口径):此前的索引读取曾与摘要 SQL 的列序
        // 错位 —— messageCount(第5列)读到 last_partition_count、真实条数进了
        // lastPartitionCount,台账接口把分区数当成了归档条数。名字读取取消顺序契约。
        return jdbcTemplate.query(dialect.selectArchiveTopicSummarySql(clusterId), (rs, rowNum) -> {
            Integer partitionCount = rs.getInt("last_partition_count");
            if (rs.wasNull()) {
                partitionCount = null;
            }
            return ArchiveTopicSummary.builder()
                    .topicName(rs.getString("topic_name"))
                    .deleted(rs.getInt("deleted"))
                    .firstSeenAt(rs.getString("first_seen_at"))
                    .lastSeenAt(rs.getString("last_seen_at"))
                    .lastPartitionCount(partitionCount)
                    .messageCount(rs.getLong("message_count"))
                    .build();
        }, clusterId);
    }

    /**
     * 历史消息查询。
     *
     * <p>翻页游标是复合键 {@code (partition_id, offset_val)}(设计见
     * {@link StorageDialect#selectArchiveSql}):未指定分区时只按全局 offset
     * 过滤会在多分区 topic 上跳行,前端翻页必须把上一页返回的
     * {@code nextPartition + nextOffset} 原样带回。
     *
     * @param partition           可选;null = 全部分区
     * @param offsetFromPartition 可选;游标的分区分量(未指定分区且翻页时必传)
     * @param fromTimeMs          可选;record timestamp 下界(含)
     * @param toTimeMs            可选;record timestamp 上界(含)
     * @param offsetFrom          可选;游标的 offset 分量(分区内 offset 下界,含)
     * @param limit               返回条数上限(调用方负责钳制上限)
     */
    public ArchiveMessageQueryResult queryMessages(long clusterId, String topic, Integer partition,
                                                  Integer offsetFromPartition,
                                                  Long fromTimeMs, Long toTimeMs,
                                                  Long offsetFrom, int limit) {
        ensureArchiveTable(clusterId);
        boolean filterPartition = partition != null;
        boolean filterTimeRange = fromTimeMs != null || toTimeMs != null;
        // 复合游标只在"未指定分区"时生效:指定了分区时 offsetFrom 单值即可表达游标
        boolean filterCursorPartition = !filterPartition && offsetFromPartition != null;
        String sql = dialect.selectArchiveSql(clusterId, filterPartition, filterTimeRange, filterCursorPartition);

        // 参数顺序必须与 selectArchiveSql 的占位符拼装顺序严格一致
        long effectiveOffsetFrom = offsetFrom == null ? 0L : offsetFrom;
        List<Object> args = new ArrayList<>();
        args.add(topic);
        if (filterPartition) {
            args.add(partition);
        }
        if (filterCursorPartition) {
            args.add(offsetFromPartition);
            args.add(offsetFromPartition);
            args.add(effectiveOffsetFrom);
        }
        if (filterTimeRange) {
            // 单边给定时用类型边界补齐另一侧,保持 SQL 形状固定(少一个方言分支)
            args.add(fromTimeMs == null ? Long.MIN_VALUE : fromTimeMs);
            args.add(toTimeMs == null ? Long.MAX_VALUE : toTimeMs);
        }
        if (filterPartition) {
            args.add(effectiveOffsetFrom);
        }
        args.add(limit);

        List<MessageRecord> records = jdbcTemplate.query(sql, this::toMessageRecord, args.toArray());

        long nextOffset = offsetFrom == null ? 0L : offsetFrom;
        if (!records.isEmpty()) {
            nextOffset = records.get(records.size() - 1).getOffset() + 1;
        }
        // 游标的分区分量:指定分区时恒为该分区;全局模式取末条记录的分区(空结果回传请求值)
        Integer nextPartition;
        if (filterPartition) {
            nextPartition = partition;
        } else if (!records.isEmpty()) {
            nextPartition = records.get(records.size() - 1).getPartition();
        } else {
            nextPartition = offsetFromPartition;
        }
        return ArchiveMessageQueryResult.builder()
                .topic(topic)
                .partition(partition)
                .totalReturned(records.size())
                .records(records)
                .hasMore(records.size() >= limit)
                .nextOffset(nextOffset)
                .nextPartition(nextPartition)
                .build();
    }

    /**
     * 清理归档。
     *
     * @param topicOrNull 指定 topic → 只删该 topic 的行;null / 空白 → 整集群归档(DROP + 重建)
     * @return 清理掉的归档行数
     */
    public long deleteArchive(long clusterId, String topicOrNull) {
        if (topicOrNull == null || topicOrNull.isBlank()) {
            long total = countMessages(clusterId);
            // 整集群清理走 DROP + 重建:百万行级 DELETE 慢且 SQLite 文件不回缩
            dropArchiveTable(clusterId);
            ensureArchiveTable(clusterId);
            log.info("Cleared all {} archived message(s) of cluster {} (table dropped and recreated)",
                    total, clusterId);
            return total;
        }
        ensureArchiveTable(clusterId);
        int deleted = jdbcTemplate.update(dialect.deleteArchiveByTopicSql(clusterId), topicOrNull);
        log.info("Deleted {} archived message(s) of topic {} in cluster {}", deleted, topicOrNull, clusterId);
        return deleted;
    }

    /**
     * 该集群已归档的消息总数(离线 Dashboard 卡片用)。
     *
     * <p>失败不抛出:调用方是聚合视图,一张卡片的归档量查不出来不该拖垮整页。
     */
    public long countMessages(long clusterId) {
        try {
            ensureArchiveTable(clusterId);
            Long count = jdbcTemplate.queryForObject(dialect.countArchiveSql(clusterId), Long.class);
            return count == null ? 0L : count;
        } catch (RuntimeException e) {
            log.warn("Failed to count archived messages of cluster {}: {}", clusterId, e.getMessage());
            return -1L;
        }
    }

    // ---------------------------------------------------------------
    // retention 清理
    // ---------------------------------------------------------------

    /**
     * 每日 retention 清理。
     *
     * <p>{@code archived_at} 是 ISO-8601 文本,字典序 = 时间序,因此用字符串
     * {@code <} 比较即可,三方言一致(不引入时区/字面量差异)。
     *
     * <p>逐集群 try/catch:一个集群的表出问题(比如被外部删了)不能阻断其它集群。
     */
    @Scheduled(cron = "0 30 2 * * *")
    public void cleanupExpiredArchive() {
        List<ClusterDefinition> clusters = clusterConfigStore.list();
        int touched = 0;
        for (ClusterDefinition cluster : clusters) {
            int retentionDays = cluster.archiveRetentionDays();
            if (retentionDays <= 0) {
                continue;
            }
            try {
                String cutoff = Instant.now()
                        .minus(retentionDays, ChronoUnit.DAYS)
                        .truncatedTo(ChronoUnit.SECONDS)
                        .toString();
                ensureArchiveTable(cluster.id());
                int deleted = jdbcTemplate.update(dialect.deleteArchiveBeforeSql(cluster.id()), cutoff);
                if (deleted > 0) {
                    touched += deleted;
                    log.info("Retention cleanup for cluster {}: removed {} archived message(s) older than {} ({} days)",
                            cluster.id(), deleted, cutoff, retentionDays);
                }
            } catch (RuntimeException e) {
                log.warn("Retention cleanup failed for cluster {}: {}", cluster.id(), e.getMessage());
            }
        }
        // SQLite:删除后文件不会自动回缩,必须显式增量 vacuum(其余方言为空实现)
        if (!clusters.isEmpty()) {
            dialect.applyMaintenance(dataSource);
        }
        log.info("Daily archive retention cleanup finished ({} row(s) removed across {} cluster(s))",
                touched, clusters.size());
    }

    // ---------------------------------------------------------------
    // 内部
    // ---------------------------------------------------------------

    /** 归档行 → {@link MessageRecord}(与实时消息查询同一 VO,前端零适配)。 */
    private MessageRecord toMessageRecord(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        byte[] keyBytes = rs.getBytes("key");
        byte[] valueBytes = rs.getBytes("value");
        long timestampMs = rs.getLong("timestamp_ms");
        boolean timestampNull = rs.wasNull();

        return MessageRecord.builder()
                .topic(rs.getString("topic"))
                .partition(rs.getInt("partition_id"))
                .offset(rs.getLong("offset_val"))
                // MessageRecord.timestamp 是基本类型:归档行的 timestamp_ms 为 NULL(消息无时间戳)
                // 时落 0,前端时间列会显示 1970 —— 这是可接受的信息损失,好过为归档单开一个 VO
                .timestamp(timestampNull ? 0L : timestampMs)
                .timestampType(rs.getString("timestamp_type"))
                .key(keyBytes == null ? null : new String(keyBytes, StandardCharsets.UTF_8))
                .value(valueBytes == null ? null : new String(valueBytes, StandardCharsets.UTF_8))
                // valueFormatted 恒 null:归档浏览由前端本地格式化(与实时列表同一约定)
                .valueFormatted(null)
                .headers(parseHeaders(rs.getString("headers")))
                .build();
    }

    /** headers JSON → Map;解析失败返回空 map(不因一条脏数据打断整页查询)。 */
    private Map<String, String> parseHeaders(String headersJson) {
        if (headersJson == null || headersJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, String> parsed = objectMapper.readValue(headersJson,
                    objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, String.class));
            return parsed == null ? new LinkedHashMap<>() : parsed;
        } catch (Exception e) {
            log.debug("Unparsable archived headers, returning empty map: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /**
     * 归档消息(单条记录)。
     *
     * <p>{@code archived_at} 取<b>落库时刻</b>而不是消息自带时间:retention 语义是
     * "归档保留 N 天",与消息的业务时间无关(否则历史消息一入库就该被清掉)。
     */
    public record ArchivedMessage(
            String topic,
            int partition,
            long offset,
            Long timestampMs,
            String timestampType,
            byte[] key,
            byte[] value,
            String headersJson) {
    }
}
