package com.example.kafkaviz.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 建表与结构升级。
 *
 * <p>不引 Flyway:脚本由本类按当前方言路径直接执行,V1 全部建表语句都是
 * {@code CREATE TABLE IF NOT EXISTS}(MySQL 的归档索引内联,见 {@code MySqlDialect}),
 * 因此"重复执行"天然幂等。版本登记在 {@code schema_version}。
 *
 * <p>按 {@code schema_version} 版本号感知迁移:读 {@code MAX(schema_version.version)},
 * 低于目标版本则依次执行对应脚本。
 * <ul>
 *   <li>V1 仍然每次都执行(幂等,代价是一次 no-op DDL);</li>
 *   <li>V2 是 {@code ALTER TABLE ADD COLUMN} —— SQLite 与 MySQL 8 都<b>不支持</b>
 *       {@code ADD COLUMN IF NOT EXISTS},所以 V2 的幂等性<b>完全依赖版本守卫</b>,
 *       脚本内不做列级判断;</li>
 *   <li>半执行风险:V2 中途崩溃会留下"部分列已加、版本未登记"的状态,重跑报
 *       duplicate column —— 按报错列名手工补齐即可。这是"不引 Flyway"的既定代价,
 *       桌面单机场景可接受。</li>
 * </ul>
 *
 * <p>顺序:必须早于 {@link com.example.kafkaviz.config.ClusterSeedImporter}
 * (种子导入要往 cluster_config 写) —— 靠 {@code @Order} 保证。
 */
@Component
@Order(SchemaInitializer.ORDER)
public class SchemaInitializer implements ApplicationRunner {

    /** 早于 ClusterSeedImporter。 */
    public static final int ORDER = 0;

    /** V1:基础表。 */
    private static final int V1 = 1;

    /** V2:cluster_config 认证相关列。 */
    private static final int V2 = 2;

    /** 当前目标版本 —— 新脚本落地时同步 +1。 */
    private static final int CURRENT_VERSION = V2;

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);

    private final DataSource dataSource;
    private final StorageDialect dialect;
    private final JdbcTemplate jdbcTemplate;

    public SchemaInitializer(DataSource dataSource, StorageDialect dialect, JdbcTemplate jdbcTemplate) {
        this.dataSource = dataSource;
        this.dialect = dialect;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        // V1 恒执行:全部语句幂等,顺便兜住"表被手工删掉"的极端情况
        executeScript(dialect.v1ScriptPath());

        int applied = appliedVersion();
        if (applied < V1) {
            recordVersion(V1);
            applied = V1;
        }
        if (applied < V2) {
            executeScript(dialect.v2ScriptPath());
            recordVersion(V2);
            applied = V2;
        }
        log.debug("Schema up to date (appliedVersion={}, target={})", applied, CURRENT_VERSION);
    }

    /** 按当前方言执行一个 classpath 下的 SQL 脚本。 */
    private void executeScript(String script) {
        try (Connection conn = dataSource.getConnection()) {
            log.info("Applying schema script {} for storage type {}", script, dialect.type().configValue());
            ScriptUtils.executeSqlScript(conn, new ClassPathResource(script));
        } catch (Exception e) {
            // 建表/改表失败必须让启动失败:后续所有读写都会以"列不存在"的形式不断报错,
            // 而那种错误远不如这里一条明确的 DDL 失败好排查。
            throw new IllegalStateException("Failed to apply schema script " + script
                    + " on " + dialect.type().configValue() + ": " + e.getMessage(), e);
        }
    }

    /**
     * 已应用的最高版本;空库(尚无任何登记)返回 0。
     *
     * <p>用 {@code MAX} 而非逐版本 {@code COUNT}:新增 V3 时不必再补一堆存在性查询。
     * {@code cluster_config} 等表由 V1 建立,而 {@code schema_version} 表本身在 V1
     * 脚本里,所以本方法只会在 V1 执行之后被调用。
     */
    private int appliedVersion() {
        Integer max = jdbcTemplate.queryForObject("SELECT MAX(version) FROM schema_version", Integer.class);
        return max == null ? 0 : max;
    }

    private void recordVersion(int version) {
        jdbcTemplate.update("INSERT INTO schema_version (version, applied_at) VALUES (?, ?)",
                version, Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        log.info("Recorded schema_version={}", version);
    }
}
