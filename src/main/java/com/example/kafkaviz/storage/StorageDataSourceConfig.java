package com.example.kafkaviz.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;

/**
 * 按 {@code storage.json} 装配数据源。
 *
 * <p>装配口径:
 * <ul>
 *   <li><b>SQLite</b> —— WAL / foreign_keys / busy_timeout 由 {@link SQLiteConfig} 注入每条新连接;
 *       auto_vacuum 不在该驱动的 Pragma 枚举内,经 Hikari {@code connectionInitSql} 下发;
 *       Hikari 池固定 {@code maximumPoolSize=1},
 *       强制单写者语义。</li>
 *   <li><b>PG / MySQL</b> —— 标准 Hikari 小池 {@code maximumPoolSize=4}
 *       (归档写 1 + 查询 3),{@code connectionTimeout=5000},避免外部库网络抖动时
 *       请求线程长时间挂住。</li>
 * </ul>
 *
 * <p>切换数据源<b>不热生效</b>:本配置类在启动时读一次 {@code storage.json} 即定,
 * {@code /api/storage} 保存后需重启(不做存储间数据迁移)。
 *
 * <p>关于 Spring Boot 自动配置:应用自己声明了 {@code DataSource} Bean,
 * {@code DataSourceAutoConfiguration} / {@code HikariDataSourceAutoConfiguration}
 * 均带 {@code @ConditionalOnMissingBean(DataSource.class)},会自动退让,
 * 因此不需要 exclude。
 */
@Configuration
public class StorageDataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(StorageDataSourceConfig.class);

    /** SQLite 池固定 1:所有写经同一连接串行执行。 */
    private static final int SQLITE_POOL_SIZE = 1;
    /** PG/MySQL 小池:归档写 1 + 查询 3。 */
    private static final int EXTERNAL_POOL_SIZE = 4;

    /** 供 {@code /api/storage/test} 复用同一套 URL 口径。 */
    public static StorageDialect dialectFor(StorageType type, AppPaths appPaths) {
        return switch (type) {
            case SQLITE -> new SqliteDialect(appPaths);
            case POSTGRESQL -> new PostgresDialect();
            case MYSQL -> new MySqlDialect();
        };
    }

    private static boolean driverAvailable(String driverClassName) {
        try {
            Class.forName(driverClassName);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Bean
    public StorageDialect storageDialect(AppPaths appPaths, StorageConfigFile configFile) {
        StorageConfig config = configFile.read();
        return dialectFor(config.getType(), appPaths);
    }

    @Bean(destroyMethod = "close")
    public DataSource dataSource(StorageDialect dialect, StorageConfigFile configFile) {
        StorageConfig config = configFile.read();
        if (config.getType() != dialect.type()) {
            // 理论上不可达(storageDialect 由同一个文件推导);真出现说明有并发改文件的竞态
            throw new IllegalStateException("Storage dialect/type mismatch: dialect=" + dialect.type()
                    + " but storage.json now says " + config.getType());
        }
        if (dialect.type() == StorageType.SQLITE) {
            return sqliteDataSource(dialect, config);
        }
        return externalDataSource(dialect, config, configFile);
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    private DataSource sqliteDataSource(StorageDialect dialect, StorageConfig config) {
        SQLiteConfig sqliteConfig = new SQLiteConfig();
        sqliteConfig.setPragma(SQLiteConfig.Pragma.JOURNAL_MODE, "WAL");
        sqliteConfig.setPragma(SQLiteConfig.Pragma.FOREIGN_KEYS, "ON");
        sqliteConfig.setPragma(SQLiteConfig.Pragma.BUSY_TIMEOUT, "5000");

        // INCREMENTAL:配合每日 PRAGMA incremental_vacuum,归档删除后文件能回缩。
        // 注意 1:auto_vacuum 只在建库(VACUUM)时写入文件头,对已存在的库本次不生效 —— 属已知限制。
        // 注意 2:AUTO_VACUUM 不在 sqlite-jdbc 的 Pragma 枚举里(3.53.4.0 实测),
        // 故不走 SQLiteConfig,改由 Hikari 在每条新连接上执行原生 PRAGMA,不依赖驱动 API 面。
        SQLiteDataSource sqlite = new SQLiteDataSource(sqliteConfig);
        sqlite.setUrl(dialect.jdbcUrl(config));

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("storage-sqlite");
        hikari.setDataSource(sqlite);
        hikari.setConnectionInitSql("PRAGMA auto_vacuum=INCREMENTAL");
        hikari.setMaximumPoolSize(SQLITE_POOL_SIZE);
        // 单连接池:并发请求会排队等连接。超时给 10s —— 大于 busy_timeout(5s),
        // 让"库忙"优先由 SQLite 自己的 busy 重试消化,而不是由池抛超时。
        hikari.setConnectionTimeout(10_000);
        log.info("Storage backend: SQLite (single-writer pool, WAL) at {}", dialect.jdbcUrl(config));
        return new HikariDataSource(hikari);
    }

    private DataSource externalDataSource(StorageDialect dialect, StorageConfig config,
                                          StorageConfigFile configFile) {
        if (!driverAvailable(dialect.driverClassName())) {
            throw new IllegalStateException("JDBC driver " + dialect.driverClassName()
                    + " not found on classpath for storage type " + dialect.type());
        }
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("storage-" + dialect.type().configValue());
        hikari.setDriverClassName(dialect.driverClassName());
        hikari.setJdbcUrl(dialect.jdbcUrl(config));
        hikari.setUsername(config.getUsername());
        // 解不开(secret.key 被换 / 文件被改坏)时直接启动失败并说明原因,
        // 绝不能拿空口令去连 —— 那会变成误导性的"认证失败"。
        hikari.setPassword(configFile.decryptPassword(config.getPasswordCipher()));
        hikari.setMaximumPoolSize(EXTERNAL_POOL_SIZE);
        hikari.setConnectionTimeout(5_000);
        log.info("Storage backend: {} at {} (pool={})", dialect.type().configValue(),
                dialect.jdbcUrl(config), EXTERNAL_POOL_SIZE);
        return new HikariDataSource(hikari);
    }
}
