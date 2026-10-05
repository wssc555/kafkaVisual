package com.example.kafkaviz.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

/**
 * SQLite 方言(默认数据源,零配置)。
 *
 * <p>专项约束:
 * <ul>
 *   <li>WAL + foreign_keys=ON + busy_timeout —— 归档批量写与前端查询可并发;</li>
 *   <li>单写者:Hikari 池固定 1 条连接(见 {@code StorageDataSourceConfig}),所有写串行;</li>
 *   <li>auto_vacuum=INCREMENTAL + 每日增量清理,避免归档删除后文件不回缩。</li>
 * </ul>
 */
public class SqliteDialect implements StorageDialect {

    private static final Logger log = LoggerFactory.getLogger(SqliteDialect.class);

    private final AppPaths appPaths;

    public SqliteDialect(AppPaths appPaths) {
        this.appPaths = appPaths;
    }

    @Override
    public StorageType type() {
        return StorageType.SQLITE;
    }

    @Override
    public String jdbcUrl(StorageConfig config) {
        // 库文件位置由启动参数 --app.db-path 决定,与 StorageConfig 无关(type=sqlite 时外部库字段被忽略)
        return "jdbc:sqlite:" + appPaths.sqliteDbFile().toString();
    }

    @Override
    public int defaultPort() {
        // SQLite 无端口概念
        return 0;
    }

    @Override
    public String driverClassName() {
        return "org.sqlite.JDBC";
    }

    @Override
    public String v1ScriptPath() {
        return "schema/V1__sqlite.sql";
    }

    @Override
    public String v2ScriptPath() {
        return "schema/V2__auth-methods.sqlite.sql";
    }

    @Override
    public String quoteIdent(String identifier) {
        return "\"" + identifier + "\"";
    }

    @Override
    public boolean supportsConcurrentWriters() {
        // WAL 下读写不互斥,但写者仍唯一
        return false;
    }

    @Override
    public void applyMaintenance(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute("PRAGMA incremental_vacuum");
            // optimize:让 SQLite 依据实际负载决策是否重建统计信息/索引,开销远小于 ANALYZE
            st.execute("PRAGMA optimize");
            log.debug("SQLite maintenance done (incremental_vacuum + optimize)");
        } catch (Exception e) {
            // 维护失败不影响业务读写,记 WARN 即可(下次每日任务会再试)
            log.warn("SQLite maintenance failed: {}", e.getMessage());
        }
    }

    @Override
    public List<String> createArchiveTableSql(long clusterId) {
        String table = quoteIdent(archiveTable(clusterId));
        String index = quoteIdent("idx_" + archiveTable(clusterId) + "_ts");
        // WITHOUT ROWID:主键即聚簇索引,(topic, partition_id, offset_val) 前缀查询免回表
        String create = "CREATE TABLE IF NOT EXISTS " + table + " ("
                + "\"topic\" TEXT NOT NULL, "
                + "\"partition_id\" INTEGER NOT NULL, "
                + "\"offset_val\" INTEGER NOT NULL, "
                + "\"timestamp_ms\" INTEGER, "
                + "\"timestamp_type\" TEXT, "
                + "\"key\" BLOB, "
                + "\"value\" BLOB, "
                + "\"headers\" TEXT, "
                + "\"archived_at\" TEXT NOT NULL, "
                + "PRIMARY KEY (\"topic\", \"partition_id\", \"offset_val\")) WITHOUT ROWID";
        String createIndex = "CREATE INDEX IF NOT EXISTS " + index + " ON " + table
                + "(\"topic\", \"timestamp_ms\")";
        return List.of(create, createIndex);
    }

    @Override
    public String insertIgnorePrefix() {
        return "INSERT OR IGNORE INTO";
    }

    @Override
    public String insertIgnoreSuffix() {
        return "";
    }

    @Override
    public String upsertTopicRegistrySql() {
        return "INSERT INTO " + quoteIdent("topic_registry")
                + " (" + quoteIdent("cluster_id") + ", " + quoteIdent("topic_name") + ", "
                + quoteIdent("first_seen_at") + ", " + quoteIdent("last_seen_at") + ", "
                + quoteIdent("last_partition_count") + ", " + quoteIdent("deleted") + ")"
                + " VALUES (?, ?, ?, ?, ?, 0)"
                + " ON CONFLICT(" + quoteIdent("cluster_id") + ", " + quoteIdent("topic_name") + ")"
                + " DO UPDATE SET " + quoteIdent("last_seen_at") + " = excluded." + quoteIdent("last_seen_at")
                + ", " + quoteIdent("last_partition_count") + " = excluded." + quoteIdent("last_partition_count")
                + ", " + quoteIdent("deleted") + " = 0";
    }

    @Override
    public String upsertPreferenceSql() {
        return "INSERT INTO " + quoteIdent("ui_preference")
                + " (" + quoteIdent("key") + ", " + quoteIdent("value") + ") VALUES (?, ?)"
                + " ON CONFLICT(" + quoteIdent("key") + ")"
                + " DO UPDATE SET " + quoteIdent("value") + " = excluded." + quoteIdent("value");
    }
}
