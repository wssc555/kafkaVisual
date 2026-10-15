package com.example.kafkaviz.inspection.store;

import com.example.kafkaviz.inspection.model.RuleConditionRow;
import com.example.kafkaviz.storage.StorageDialect;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code rule_condition} 读写(按 rule_id 批量替换)。
 *
 * <p>写策略是"整删整插"而不是逐条 diff:条件条数是个位数,一次 DELETE + 一次批量
 * INSERT 比维护增删改三态简单得多,也避免了残留孤儿行。
 */
@Component
public class RuleConditionStore {

    public static final String TABLE = "rule_condition";

    private final JdbcTemplate jdbcTemplate;
    private final StorageDialect dialect;

    public RuleConditionStore(JdbcTemplate jdbcTemplate, StorageDialect dialect) {
        this.jdbcTemplate = jdbcTemplate;
        this.dialect = dialect;
    }

    /** 按 seq 升序遍历(决定 UI 回显与摘要生成顺序)。 */
    public List<RuleConditionRow> listByRule(long ruleId) {
        return jdbcTemplate.query(
                "SELECT " + columns() + " FROM " + q(TABLE) + " WHERE " + q("rule_id") + " = ?"
                        + " ORDER BY " + q("seq"),
                this::mapRow, ruleId);
    }

    /** 批量替换:先删该规则全部条件,再按给定顺序整批插入。 */
    public void replaceAll(long ruleId, List<RuleConditionRow> conditions) {
        jdbcTemplate.update(dialect.deleteWhereSql(TABLE, "rule_id"), ruleId);
        if (conditions == null || conditions.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO " + q(TABLE) + " (" + q("rule_id") + ", " + q("seq") + ", "
                + q("metric_key") + ", " + q("operator") + ", " + q("threshold") + ", "
                + q("window_count") + ", " + q("window_match") + ", " + q("combinator") + ")"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        List<Object[]> batch = new ArrayList<>(conditions.size());
        int seq = 0;
        for (RuleConditionRow c : conditions) {
            batch.add(new Object[]{ruleId, seq++, c.metricKey(), c.operator(), c.threshold(),
                    c.windowCount(), c.windowMatch(), c.combinator()});
        }
        jdbcTemplate.batchUpdate(sql, batch);
    }

    private String columns() {
        return q("id") + ", " + q("rule_id") + ", " + q("seq") + ", " + q("metric_key") + ", "
                + q("operator") + ", " + q("threshold") + ", " + q("window_count") + ", "
                + q("window_match") + ", " + q("combinator");
    }

    private RuleConditionRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        // wasNull() 只反映"最后一次读取的列":必须在 getInt 之后立刻捕获,
        // 否则后续 getter 会把它冲掉,window_count 的 NULL 会被读成 0(瞬时条件被静默改成窗口 0)
        int windowCount = rs.getInt("window_count");
        boolean windowCountNull = rs.wasNull();
        return new RuleConditionRow(
                rs.getLong("id"),
                rs.getLong("rule_id"),
                rs.getInt("seq"),
                rs.getString("metric_key"),
                rs.getString("operator"),
                rs.getString("threshold"),
                windowCountNull ? null : windowCount,
                rs.getString("window_match"),
                rs.getString("combinator"));
    }

    private String q(String identifier) {
        return dialect.quoteIdent(identifier);
    }
}
