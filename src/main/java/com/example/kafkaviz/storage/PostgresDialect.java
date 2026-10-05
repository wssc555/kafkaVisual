package com.example.kafkaviz.storage;

import javax.sql.DataSource;
import java.util.List;

/**
 * PostgreSQL 方言(外部库,用户手动配置连接)。
 *
 * <p>布尔列在这里同样用 {@code INTEGER 0/1} 而非 {@code BOOLEAN} ——
 * Java 侧统一按 int 读写可避免三方言的布尔映射差异,保持三方言口径一致。
 */
public class PostgresDialect implements StorageDialect {

    @Override
    public StorageType type() {
        return StorageType.POSTGRESQL;
    }

    @Override
    public String jdbcUrl(StorageConfig config) {
        int port = config.getPort() > 0 ? config.getPort() : defaultPort();
        StringBuilder url = new StringBuilder("jdbc:postgresql://")
                .append(requireUrlSafe(config.getHost(), "host")).append(':').append(port)
                .append('/').append(requireUrlSafe(config.getDatabase(), "database"));
        String extra = config.getExtraParams();
        if (extra != null && !extra.isBlank()) {
            url.append(extra.trim().startsWith("?") ? "" : "?").append(extra.trim());
        }
        return url.toString();
    }

    @Override
    public int defaultPort() {
        return 5432;
    }

    @Override
    public String driverClassName() {
        return "org.postgresql.Driver";
    }

    @Override
    public String v1ScriptPath() {
        return "schema/V1__postgres.sql";
    }

    @Override
    public String v2ScriptPath() {
        return "schema/V2__auth-methods.postgres.sql";
    }

    @Override
    public String quoteIdent(String identifier) {
        return "\"" + identifier + "\"";
    }

    @Override
    public boolean supportsConcurrentWriters() {
        return true;
    }

    @Override
    public void applyMaintenance(DataSource dataSource) {
        // autovacuum 由服务端内置,客户端无需做任何事
    }

    @Override
    public List<String> createArchiveTableSql(long clusterId) {
        String table = quoteIdent(archiveTable(clusterId));
        String create = "CREATE TABLE IF NOT EXISTS " + table + " ("
                + "\"topic\" TEXT NOT NULL, "
                + "\"partition_id\" INTEGER NOT NULL, "
                + "\"offset_val\" BIGINT NOT NULL, "
                + "\"timestamp_ms\" BIGINT, "
                + "\"timestamp_type\" TEXT, "
                + "\"key\" BYTEA, "
                + "\"value\" BYTEA, "
                + "\"headers\" TEXT, "
                + "\"archived_at\" TEXT NOT NULL, "
                + "PRIMARY KEY (\"topic\", \"partition_id\", \"offset_val\"))";
        // PG 支持 CREATE INDEX IF NOT EXISTS,索引可独立成句
        String createIndex = "CREATE INDEX IF NOT EXISTS " + quoteIdent("idx_" + archiveTable(clusterId) + "_ts")
                + " ON " + table + "(\"topic\", \"timestamp_ms\")";
        return List.of(create, createIndex);
    }

    @Override
    public String insertIgnorePrefix() {
        // PG 没有 INSERT IGNORE 语法,幂等靠 ON CONFLICT 后缀
        return "INSERT INTO";
    }

    @Override
    public String insertIgnoreSuffix() {
        return " ON CONFLICT DO NOTHING";
    }

    @Override
    public String upsertTopicRegistrySql() {
        return "INSERT INTO " + quoteIdent("topic_registry")
                + " (" + quoteIdent("cluster_id") + ", " + quoteIdent("topic_name") + ", "
                + quoteIdent("first_seen_at") + ", " + quoteIdent("last_seen_at") + ", "
                + quoteIdent("last_partition_count") + ", " + quoteIdent("deleted") + ")"
                + " VALUES (?, ?, ?, ?, ?, 0)"
                + " ON CONFLICT (" + quoteIdent("cluster_id") + ", " + quoteIdent("topic_name") + ")"
                + " DO UPDATE SET " + quoteIdent("last_seen_at") + " = EXCLUDED." + quoteIdent("last_seen_at")
                + ", " + quoteIdent("last_partition_count") + " = EXCLUDED." + quoteIdent("last_partition_count")
                + ", " + quoteIdent("deleted") + " = 0";
    }

    @Override
    public String upsertPreferenceSql() {
        return "INSERT INTO " + quoteIdent("ui_preference")
                + " (" + quoteIdent("key") + ", " + quoteIdent("value") + ") VALUES (?, ?)"
                + " ON CONFLICT (" + quoteIdent("key") + ")"
                + " DO UPDATE SET " + quoteIdent("value") + " = EXCLUDED." + quoteIdent("value");
    }
}
