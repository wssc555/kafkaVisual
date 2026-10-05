package com.example.kafkaviz.storage;

import javax.sql.DataSource;
import java.util.List;

/**
 * MySQL 方言(外部库,用户手动配置连接)。
 *
 * <p>两个必须记住的差异:
 * <ol>
 *   <li>{@code key} 是 MySQL 保留字 —— {@code ui_preference.key} 与归档表 {@code key} 列
 *       必须用反引号,全部经 {@link #quoteIdent(String)} 收口;</li>
 *   <li>{@code CREATE INDEX} 没有 {@code IF NOT EXISTS} —— 索引内联进
 *       {@code CREATE TABLE}(表已存在时整句 no-op),因此 {@link #createArchiveTableSql(long)}
 *       只返回一条语句。</li>
 * </ol>
 *
 * <p>布尔同样用 {@code INT 0/1}(与 SQLite/PG 口径一致)。
 */
public class MySqlDialect implements StorageDialect {

    @Override
    public StorageType type() {
        return StorageType.MYSQL;
    }

    @Override
    public String jdbcUrl(StorageConfig config) {
        int port = config.getPort() > 0 ? config.getPort() : defaultPort();
        StringBuilder url = new StringBuilder("jdbc:mysql://")
                .append(requireUrlSafe(config.getHost(), "host")).append(':').append(port)
                .append('/').append(requireUrlSafe(config.getDatabase(), "database"))
                // 默认参数只解决"连得上":UTF-8 通道、时区明确、以及 caching_sha2_password
                // 在非 SSL 通道下需要 allowPublicKeyRetrieval。SSL 是否强制由用户在
                // 「高级参数」里写 sslMode=REQUIRED/VERIFY_IDENTITY 决定,这里不擅自关闭或打开。
                .append("?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&allowPublicKeyRetrieval=true");
        String extra = config.getExtraParams();
        if (extra != null && !extra.isBlank()) {
            url.append('&').append(extra.trim().replaceFirst("^[?&]", ""));
        }
        return url.toString();
    }

    @Override
    public int defaultPort() {
        return 3306;
    }

    @Override
    public String driverClassName() {
        return "com.mysql.cj.jdbc.Driver";
    }

    @Override
    public String v1ScriptPath() {
        return "schema/V1__mysql.sql";
    }

    @Override
    public String v2ScriptPath() {
        return "schema/V2__auth-methods.mysql.sql";
    }

    @Override
    public String quoteIdent(String identifier) {
        return "`" + identifier + "`";
    }

    @Override
    public boolean supportsConcurrentWriters() {
        return true;
    }

    @Override
    public void applyMaintenance(DataSource dataSource) {
        // MySQL 的空间回收由用户侧维护(OPTIMIZE TABLE 会锁表,不适合后台自动跑)
    }

    @Override
    public List<String> createArchiveTableSql(long clusterId) {
        // 索引内联:MySQL 的 CREATE INDEX 无 IF NOT EXISTS,独立成句时重跑必然报
        // "Duplicate key name"。内联后由 CREATE TABLE IF NOT EXISTS 天然保证幂等。
        String ddl = "CREATE TABLE IF NOT EXISTS " + quoteIdent(archiveTable(clusterId)) + " ("
                + "`topic` VARCHAR(255) NOT NULL, "
                + "`partition_id` INT NOT NULL, "
                + "`offset_val` BIGINT NOT NULL, "
                + "`timestamp_ms` BIGINT NULL, "
                + "`timestamp_type` VARCHAR(32) NULL, "
                + "`key` LONGBLOB NULL, "
                + "`value` LONGBLOB NULL, "
                + "`headers` LONGTEXT NULL, "
                + "`archived_at` VARCHAR(32) NOT NULL, "
                + "PRIMARY KEY (`topic`, `partition_id`, `offset_val`), "
                + "INDEX " + quoteIdent("idx_" + archiveTable(clusterId) + "_ts")
                + " (`topic`, `timestamp_ms`))";
        return List.of(ddl);
    }

    @Override
    public String insertIgnorePrefix() {
        // 注意语义差异:MySQL 的 INSERT IGNORE 会忽略所有可忽略错误(不只主键冲突),
        // 含数据截断等。归档写入路径的列宽足够,且失败行会由下批重试补齐,可接受。
        return "INSERT IGNORE INTO";
    }

    @Override
    public String insertIgnoreSuffix() {
        return "";
    }

    @Override
    public String upsertTopicRegistrySql() {
        return "INSERT INTO " + quoteIdent("topic_registry")
                + " (`cluster_id`, `topic_name`, `first_seen_at`, `last_seen_at`, "
                + "`last_partition_count`, `deleted`) VALUES (?, ?, ?, ?, ?, 0)"
                + " ON DUPLICATE KEY UPDATE "
                + "`last_seen_at` = VALUES(`last_seen_at`), "
                + "`last_partition_count` = VALUES(`last_partition_count`), "
                + "`deleted` = 0";
    }

    @Override
    public String upsertPreferenceSql() {
        return "INSERT INTO " + quoteIdent("ui_preference")
                + " (`key`, `value`) VALUES (?, ?)"
                + " ON DUPLICATE KEY UPDATE `value` = VALUES(`value`)";
    }
}
