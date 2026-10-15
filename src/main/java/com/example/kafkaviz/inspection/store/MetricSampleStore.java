package com.example.kafkaviz.inspection.store;

import com.example.kafkaviz.inspection.model.MetricSample;
import com.example.kafkaviz.storage.StorageDialect;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * {@code metric_sample} 读写:插入 + 窗口查询(最近 N 条)+ 滚动删除。
 *
 * <p><b>只服务带窗口的条件</b>:瞬时条件不产生采样行(引擎侧保证),因此本表行数
 * 与"窗口规则数 × scope 数 × (windowCount + buffer)"同量级,不会无界增长。
 */
@Component
public class MetricSampleStore {

    public static final String TABLE = "metric_sample";

    private final JdbcTemplate jdbcTemplate;
    private final StorageDialect dialect;

    public MetricSampleStore(JdbcTemplate jdbcTemplate, StorageDialect dialect) {
        this.jdbcTemplate = jdbcTemplate;
        this.dialect = dialect;
    }

    private static String now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /** 写入一条采样。 */
    public void insert(long ruleId, String metricKey, String scopeId, String value, String sampledAt) {
        jdbcTemplate.update(
                "INSERT INTO " + q(TABLE) + " (" + q("rule_id") + ", " + q("metric_key") + ", "
                        + q("scope_id") + ", " + q("value") + ", " + q("sampled_at") + ")"
                        + " VALUES (?, ?, ?, ?, ?)",
                ruleId, metricKey, scopeId, value, sampledAt);
    }

    /** 以当前时刻写入一条采样(常规路径)。 */
    public void insertNow(long ruleId, String metricKey, String scopeId, String value) {
        insert(ruleId, metricKey, scopeId, value, now());
    }

    /**
     * 窗口查询:该 (rule, metric, scope) 下<b>最近 count 条</b>采样,按时间<b>倒序</b>
     * (索引 (rule_id, scope_id, sampled_at) 覆盖)。
     *
     * <p>返回为空即"样本不足 → 窗口规则不触发"(宁可漏报不误报)。
     */
    public List<MetricSample> latest(long ruleId, String metricKey, String scopeId, int count) {
        if (count <= 0) {
            return List.of();
        }
        return jdbcTemplate.query(
                "SELECT " + columns() + " FROM " + q(TABLE)
                        + " WHERE " + q("rule_id") + " = ? AND " + q("metric_key") + " = ?"
                        + " AND " + q("scope_id") + " = ?"
                        + " ORDER BY " + q("sampled_at") + " DESC, " + q("id") + " DESC LIMIT ?",
                this::mapRow, ruleId, metricKey, scopeId, count);
    }

    /**
     * 滚动删除:保留该 (rule_id, scope_id) 下最新 {@code keep} 行。
     *
     * <p>删除语句本身是方言敏感的(MySQL 不允许 DELETE 的目标表出现在子查询 FROM 里),
     * 因此收敛进 {@link StorageDialect#deleteSamplesKeepLatestSql()} —— 本类零方言痕迹。
     *
     * <p>{@code keep <= 0} 时<b>不删任何行</b>(调用方传错预算不该演变成"清空整个规则的
     * 采样" —— 那会连其他 scope 的样本一起抹掉);全瞬时规则本就不产生采样行,
     * 引擎侧也会跳过这次调用。
     */
    public int keepLatest(long ruleId, String scopeId, int keep) {
        if (keep <= 0) {
            return 0;
        }
        return jdbcTemplate.update(dialect.deleteSamplesKeepLatestSql(), ruleId, scopeId, ruleId, scopeId, keep);
    }

    private String columns() {
        return q("id") + ", " + q("rule_id") + ", " + q("metric_key") + ", " + q("scope_id") + ", "
                + q("value") + ", " + q("sampled_at");
    }

    private MetricSample mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new MetricSample(
                rs.getLong("id"),
                rs.getLong("rule_id"),
                rs.getString("metric_key"),
                rs.getString("scope_id"),
                rs.getString("value"),
                rs.getString("sampled_at"));
    }

    private String q(String identifier) {
        return dialect.quoteIdent(identifier);
    }
}
