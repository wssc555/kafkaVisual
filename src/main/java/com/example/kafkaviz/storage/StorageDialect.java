package com.example.kafkaviz.storage;

import javax.sql.DataSource;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 三方言差异的统一收口。
 *
 * <p>Service 层只用 {@link org.springframework.jdbc.core.JdbcTemplate} + 本接口,
 * <b>不写任何方言 SQL</b>。所有差异点(自增主键、幂等写入、布尔、大对象、时间列、
 * 标识符引号、库级维护)都收敛在实现类与 V1 脚本里。
 *
 * <p>能共享的语句用接口 {@code default} 方法实现,实现类只提供少量"方言钩子"
 * (引号字符、幂等插入前缀/后缀、DDL 文本)。这样三份实现不会各自漂移出
 * 第四种写法 —— 漂移是这套设计里最贵的 bug。
 */
public interface StorageDialect {

    /** 归档表列顺序(所有语句共用)。列名固定,`offset` 统一改名 `offset_val` 避开 SQLite 关键字。 */
    List<String> ARCHIVE_COLUMNS = List.of(
            "topic", "partition_id", "offset_val", "timestamp_ms",
            "timestamp_type", "key", "value", "headers", "archived_at");

    // ---------------------------------------------------------------
    // 方言基本属性
    // ---------------------------------------------------------------

    StorageType type();

    /** JDBC URL 由后端拼装,不让用户手写。 */
    String jdbcUrl(StorageConfig config);

    /** 连接端口缺省值(用户未填 port 时用)。 */
    int defaultPort();

    String driverClassName();

    /** V1 建表脚本的 classpath 路径。 */
    String v1ScriptPath();

    /**
     * V2 多认证方式扩展脚本的 classpath 路径。
     *
     * <p>由 {@code SchemaInitializer} 在 {@code schema_version < 2} 时执行:
     * 给 {@code cluster_config} 补认证相关列,并按既有 {@code security_protocol}
     * 回填 {@code auth_type} / {@code tls_enabled}。
     */
    String v2ScriptPath();

    /**
     * 标识符引用。SQLite/PG 用双引号,MySQL 用反引号。
     *
     * <p>表名只可能是 {@code msg_<数字>} 或固定基础表名,注入风险极低;
     * 仍需引号是因为 MySQL 里 {@code key} 是保留字。
     */
    String quoteIdent(String identifier);

    /**
     * 是否支持 WAL 式的并发读写。
     *
     * <p>SQLite(单写者)为 false —— 归档批量写必须走独立单线程队列串行化;
     * PG/MySQL 由服务端 MVCC 保证,为 true。
     */
    boolean supportsConcurrentWriters();

    /**
     * 库级维护(仅 SQLite 有活干:增量 vacuum)。
     *
     * <p>PG 的 autovacuum 内置、MySQL 由用户侧维护,实现为空。
     */
    void applyMaintenance(DataSource dataSource);

    // ---------------------------------------------------------------
    // 归档表(运行期动态 DDL,每集群一张 msg_<clusterId>)
    // ---------------------------------------------------------------

    default String archiveTable(long clusterId) {
        return "msg_" + clusterId;
    }

    /**
     * 归档表 DDL。返回<b>多条</b>语句(索引可能是独立语句,也可能内联在 CREATE TABLE 里)。
     *
     * <p>为什么返回列表:MySQL 的 {@code CREATE INDEX} <b>不支持</b> {@code IF NOT EXISTS},
     * 重复执行会报「Duplicate key name」。因此 MySQL 实现把索引内联进 CREATE TABLE
     * (配合 {@code IF NOT EXISTS},表已存在时整条语句为 no-op),其余方言拆成两条。
     */
    List<String> createArchiveTableSql(long clusterId);

    /** 删集群时瞬时回收(整表 DROP,不逐行 DELETE)。 */
    default String dropArchiveTableSql(long clusterId) {
        return "DROP TABLE IF EXISTS " + quoteIdent(archiveTable(clusterId));
    }

    /** 幂等插入的前缀:{@code INSERT OR IGNORE INTO} / {@code INSERT INTO} / {@code INSERT IGNORE INTO}。 */
    String insertIgnorePrefix();

    /** 幂等插入的后缀:PG 需要 {@code ON CONFLICT DO NOTHING},其余为空串。 */
    String insertIgnoreSuffix();

    /** 批量幂等插入(主键 {@code (topic, partition_id, offset_val)} 去重,offset 回放不产生重复行)。 */
    default String insertArchiveIgnoreSql(long clusterId, int rowCount) {
        return insertIgnoreSql(archiveTable(clusterId), ARCHIVE_COLUMNS, rowCount);
    }

    /**
     * 通用幂等插入:命中唯一键的行被忽略(三种方言语义见各自实现)。
     *
     * <p>抽成通用方法的理由:收藏表({@code favorite})也需要"重复收藏不报错"，
     * 没必要为每张表各写一份方言分支 —— 方言差异只应该出现在<b>一处</b>。
     *
     * @param table    表名(未引用,内部按方言加引号)
     * @param columns  列名(未引用)
     * @param rowCount 一次插入的行数
     */
    default String insertIgnoreSql(String table, List<String> columns, int rowCount) {
        if (rowCount < 1) {
            throw new IllegalArgumentException("rowCount must be >= 1");
        }
        String columnList = columns.stream()
                .map(this::quoteIdent)
                .collect(Collectors.joining(", "));
        String placeholders = columns.stream().map(c -> "?").collect(Collectors.joining(", "));
        String tuples = java.util.stream.IntStream.range(0, rowCount)
                .mapToObj(i -> "(" + placeholders + ")")
                .collect(Collectors.joining(", "));
        return insertIgnorePrefix() + " " + quoteIdent(table)
                + " (" + columnList + ") VALUES " + tuples + insertIgnoreSuffix();
    }

    /** 删除语句:{@code DELETE FROM <table> WHERE <column> = ?}。 */
    default String deleteWhereSql(String table, String column) {
        return "DELETE FROM " + quoteIdent(table) + " WHERE " + quoteIdent(column) + " = ?";
    }

    /** retention 清理:删该集群归档表中 {@code archived_at} 早于给定 ISO 文本的行。 */
    default String deleteArchiveBeforeSql(long clusterId) {
        return "DELETE FROM " + quoteIdent(archiveTable(clusterId))
                + " WHERE " + quoteIdent("archived_at") + " < ?";
    }

    /** 清理归档:删单 topic 的全部归档行。 */
    default String deleteArchiveByTopicSql(long clusterId) {
        return "DELETE FROM " + quoteIdent(archiveTable(clusterId))
                + " WHERE " + quoteIdent("topic") + " = ?";
    }

    /** 清理归档:清空整表(集群保留时的"清空归档")。 */
    default String deleteArchiveAllSql(long clusterId) {
        return "DELETE FROM " + quoteIdent(archiveTable(clusterId));
    }

    default String countArchiveSql(long clusterId) {
        return "SELECT COUNT(*) FROM " + quoteIdent(archiveTable(clusterId));
    }

    /** 离线 Dashboard 卡片用:某 topic 的归档条数。 */
    default String countArchiveByTopicSql(long clusterId) {
        return "SELECT COUNT(*) FROM " + quoteIdent(archiveTable(clusterId))
                + " WHERE " + quoteIdent("topic") + " = ?";
    }

    /**
     * 历史消息查询。WHERE 子句按需拼装(topic 必填,以下可选条件按参数顺序),恒定带占位符。
     *
     * <p>排序 {@code (topic, partition_id, offset_val)} 保证分页稳定;
     * 时间列是 ISO-8601 文本,字典序 = 时间序,三方言一致。
     *
     * <p><b>翻页游标是复合键</b> {@code (partition_id, offset_val)}:未指定分区时
     * 只按 {@code offset_val >= ?} 过滤会在多分区 topic 上永久跳行 —— 第一页止于
     * partition 0 中部时,下一页的全局 offset 下界会排除掉高分区里 offset 更小的行。
     * 三种形态(与参数绑定顺序严格一致):
     * <ol>
     *   <li>{@code filterPartition=true}:
     *       {@code AND partition_id = ? ... AND offset_val >= ?}(分区已定,单值游标等价);</li>
     *   <li>{@code filterPartition=false, filterCursorPartition=true}:
     *       {@code AND (partition_id > ? OR (partition_id = ? AND offset_val >= ?))};</li>
     *   <li>{@code filterCursorPartition=false}:无 offset 过滤(第一页)。</li>
     * </ol>
     *
     * @param filterPartition        是否带 partition_id 等值条件
     * @param filterTimeRange        是否带 fromTime/toTime 条件
     * @param filterCursorPartition  是否带复合游标条件(仅在未指定分区时使用)
     */
    default String selectArchiveSql(long clusterId, boolean filterPartition, boolean filterTimeRange,
                                    boolean filterCursorPartition) {
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(ARCHIVE_COLUMNS.stream().map(this::quoteIdent).collect(Collectors.joining(", ")))
                .append(" FROM ").append(quoteIdent(archiveTable(clusterId)))
                .append(" WHERE ").append(quoteIdent("topic")).append(" = ?");
        if (filterPartition) {
            sql.append(" AND ").append(quoteIdent("partition_id")).append(" = ?");
        }
        if (filterCursorPartition) {
            sql.append(" AND (").append(quoteIdent("partition_id")).append(" > ?")
                    .append(" OR (").append(quoteIdent("partition_id")).append(" = ?")
                    .append(" AND ").append(quoteIdent("offset_val")).append(" >= ?))");
        }
        if (filterTimeRange) {
            sql.append(" AND ").append(quoteIdent("timestamp_ms")).append(" >= ?")
                    .append(" AND ").append(quoteIdent("timestamp_ms")).append(" <= ?");
        }
        if (filterPartition) {
            sql.append(" AND ").append(quoteIdent("offset_val")).append(" >= ?");
        }
        sql.append(" ORDER BY ").append(quoteIdent("topic")).append(", ")
                .append(quoteIdent("partition_id")).append(", ")
                .append(quoteIdent("offset_val"));
        sql.append(" LIMIT ?");
        return sql.toString();
    }

    /** 归档 topic 台账全量(含已删除),给"归档表还不存在"时的降级路径用。 */
    default String selectTopicRegistrySql() {
        return "SELECT " + quoteIdent("cluster_id") + ", " + quoteIdent("topic_name") + ", "
                + quoteIdent("first_seen_at") + ", " + quoteIdent("last_seen_at") + ", "
                + quoteIdent("last_partition_count") + ", " + quoteIdent("deleted")
                + " FROM " + quoteIdent("topic_registry") + " WHERE " + quoteIdent("cluster_id") + " = ?"
                + " ORDER BY " + quoteIdent("topic_name");
    }

    /**
     * 归档 topic 台账 + 归档条数(LEFT JOIN 聚合子查询)。
     *
     * <p>表不存在时该语句必然报错 —— 调用方应捕获后降级到 {@link #selectTopicRegistrySql()}
     * (用"查询失败"而非 {@code information_schema} 判表存在,是三方言唯一的通用做法)。
     *
     * <p>列读取契约:<b>调用方必须按列名读取</b>({@code message_count} 为聚合列的显式别名),
     * 不要按索引 —— 索引顺序曾与 RowMapper 错位(messageCount 读到 last_partition_count)。
     */
    default String selectArchiveTopicSummarySql(long clusterId) {
        String table = quoteIdent(archiveTable(clusterId));
        return "SELECT r." + quoteIdent("topic_name") + ", r." + quoteIdent("deleted") + ", r."
                + quoteIdent("first_seen_at") + ", r." + quoteIdent("last_seen_at") + ", r."
                + quoteIdent("last_partition_count") + ", COALESCE(m." + quoteIdent("cnt") + ", 0) AS "
                + quoteIdent("message_count")
                + " FROM " + quoteIdent("topic_registry") + " r"
                + " LEFT JOIN (SELECT " + quoteIdent("topic") + ", COUNT(*) AS " + quoteIdent("cnt")
                + " FROM " + table + " GROUP BY " + quoteIdent("topic") + ") m"
                + " ON m." + quoteIdent("topic") + " = r." + quoteIdent("topic_name")
                + " WHERE r." + quoteIdent("cluster_id") + " = ?"
                + " ORDER BY r." + quoteIdent("topic_name");
    }

    // ---------------------------------------------------------------
    // upsert(收敛进方言对象,不散落到 Service)
    // ---------------------------------------------------------------

    /** topic 台账 upsert:命中 {@code (cluster_id, topic_name)} 时刷新 last_seen / 分区数并清 deleted。 */
    String upsertTopicRegistrySql();

    /** 偏好 KV upsert:{@code key} 冲突时覆盖 value。 */
    String upsertPreferenceSql();

    /** 收藏存在性判断/删除用的列名(便于 Store 复用引号口径)。 */
    default String quotedColumn(String column) {
        return quoteIdent(column);
    }

    // ---------------------------------------------------------------
    // JDBC URL 拼装辅助
    // ---------------------------------------------------------------

    /**
     * 拒绝会破坏 JDBC URL 结构的字符(host/database 直接拼进 URL)。
     *
     * <p>拒绝列表:{@code / ? # @ %} 与控制字符、空白 —— 分别会引入额外的 path 分段、
     * query 起点、fragment、userinfo、百分号编码歧义。数据库标识符里这些字符即使
     * 服务端合法,也无法安全地经 URL 传达;确有此类需求的用户应改用「高级参数」
     * 或换一个标识符。失败抛 {@link IllegalArgumentException} → 40001,给出明确指认。
     *
     * @param what 字段名(错误消息用),如 "host" / "database"
     */
    default String requireUrlSafe(String value, String what) {
        if (value == null) {
            return null;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '/' || c == '?' || c == '#' || c == '@' || c == '%'
                    || c == ' ' || Character.isISOControl(c)) {
                throw new IllegalArgumentException(what + " contains a character not allowed in a "
                        + "JDBC URL (one of / ? # @ % or whitespace): '" + value + "'");
            }
        }
        return value;
    }
}
