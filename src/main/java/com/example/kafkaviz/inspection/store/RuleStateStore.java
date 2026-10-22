package com.example.kafkaviz.inspection.store;

import com.example.kafkaviz.inspection.model.RuleStateRow;
import com.example.kafkaviz.storage.StorageDialect;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * {@code rule_state} 读写 —— 状态去重的落地点。
 *
 * <p>写放大控制:
 * <ul>
 *   <li>状态<b>转移</b>才写行({@link #markFiring} / {@link #markRecovered});</li>
 *   <li>持续 FIRING / 持续 OK 只走 {@link #touchEvaluated},且由调用方按分钟节流。</li>
 * </ul>
 */
@Component
public class RuleStateStore {

    public static final String TABLE = "rule_state";

    private final JdbcTemplate jdbcTemplate;
    private final StorageDialect dialect;

    public RuleStateStore(JdbcTemplate jdbcTemplate, StorageDialect dialect) {
        this.jdbcTemplate = jdbcTemplate;
        this.dialect = dialect;
    }

    private static String now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    public Optional<RuleStateRow> find(long ruleId, String scopeId) {
        List<RuleStateRow> rows = jdbcTemplate.query(
                "SELECT " + columns() + " FROM " + q(TABLE)
                        + " WHERE " + q("rule_id") + " = ? AND " + q("scope_id") + " = ?",
                this::mapRow, ruleId, scopeId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 该规则下全部 scope 的状态(引擎批量评估前一次性读入,避免逐 scope 查询)。 */
    public List<RuleStateRow> listByRule(long ruleId) {
        return jdbcTemplate.query(
                "SELECT " + columns() + " FROM " + q(TABLE) + " WHERE " + q("rule_id") + " = ?",
                this::mapRow, ruleId);
    }

    /**
     * 规则级"最新一条"状态(列表 VO 的 {@code lastState} / {@code lastEvaluatedAt})。
     *
     * <p>口径:该规则<b>任意 scope</b> 中 {@code last_evaluated_at} 最大的一条。
     * 多 scope 规则下它不代表"全部 scope 都 OK",只是一句概览 —— 详情要点进规则看。
     */
    public Optional<RuleStateRow> latestOfRule(long ruleId) {
        List<RuleStateRow> rows = jdbcTemplate.query(
                "SELECT " + columns() + " FROM " + q(TABLE) + " WHERE " + q("rule_id") + " = ?"
                        + " ORDER BY " + q("last_evaluated_at") + " DESC, " + q("id") + " DESC LIMIT 1",
                this::mapRow, ruleId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** OK → FIRING:upsert(不存在则插入 FIRING 行)。 */
    public void markFiring(long ruleId, String scopeId) {
        String now = now();
        jdbcTemplate.update(dialect.upsertRuleStateFiringSql(), ruleId, scopeId, now, now);
    }

    /** FIRING → OK:upsert(不存在则插入 OK 行)。 */
    public void markRecovered(long ruleId, String scopeId) {
        String now = now();
        jdbcTemplate.update(dialect.upsertRuleStateRecoveredSql(), ruleId, scopeId, now, now);
    }

    /**
     * 只推进 {@code last_evaluated_at}(抑制分支的节流写)。
     *
     * <p>先 UPDATE 再按"零行则插入"补行:行不存在时说明从未转移过,插入一条 OK 行即可,
     * 不需要为这条低频路径再引入一个方言 upsert 钩子。
     */
    public void touchEvaluated(long ruleId, String scopeId) {
        String now = now();
        int updated = jdbcTemplate.update(
                "UPDATE " + q(TABLE) + " SET " + q("last_evaluated_at") + " = ?"
                        + " WHERE " + q("rule_id") + " = ? AND " + q("scope_id") + " = ?",
                now, ruleId, scopeId);
        if (updated == 0) {
            jdbcTemplate.update(
                    dialect.insertIgnoreSql(TABLE,
                            List.of("rule_id", "scope_id", "current_state", "last_evaluated_at"), 1),
                    ruleId, scopeId, RuleStateRow.OK, now);
        }
    }

    private String columns() {
        return q("id") + ", " + q("rule_id") + ", " + q("scope_id") + ", " + q("current_state") + ", "
                + q("last_evaluated_at") + ", " + q("last_fired_at") + ", " + q("last_recovered_at");
    }

    private RuleStateRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new RuleStateRow(
                rs.getLong("id"),
                rs.getLong("rule_id"),
                rs.getString("scope_id"),
                rs.getString("current_state"),
                rs.getString("last_evaluated_at"),
                rs.getString("last_fired_at"),
                rs.getString("last_recovered_at"));
    }

    private String q(String identifier) {
        return dialect.quoteIdent(identifier);
    }
}
