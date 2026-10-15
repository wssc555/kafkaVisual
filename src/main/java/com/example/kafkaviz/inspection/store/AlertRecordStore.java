package com.example.kafkaviz.inspection.store;

import com.example.kafkaviz.inspection.model.AlertRecord;
import com.example.kafkaviz.storage.StorageDialect;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code alert_record} 读写 —— 站内信即本表(读即见)。
 *
 * <p>告警是<b>历史留存</b>:不提供删除,规则被删也不连带清理。
 */
@Component
public class AlertRecordStore {

    public static final String TABLE = "alert_record";

    private final JdbcTemplate jdbcTemplate;
    private final StorageDialect dialect;

    public AlertRecordStore(JdbcTemplate jdbcTemplate, StorageDialect dialect) {
        this.jdbcTemplate = jdbcTemplate;
        this.dialect = dialect;
    }

    private static void appendEquals(List<String> conditions, List<Object> args, String column, String value) {
        if (value != null && !value.isBlank()) {
            conditions.add(column + " = ?");
            args.add(value.trim());
        }
    }

    private static void appendEquals(List<String> conditions, List<Object> args, String column, Long value) {
        if (value != null) {
            conditions.add(column + " = ?");
            args.add(value);
        }
    }

    private static String now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /**
     * 多条件过滤分页(全局告警中心)。
     *
     * <p>时间范围用 {@code fired_at} 的字典序比较(ISO-8601 文本,三方言一致);
     * 排序固定 {@code fired_at DESC, id DESC},保证翻页稳定。
     */
    public List<AlertRecord> search(String state, String severity, Long ruleId, Long clusterId,
                                    Boolean acknowledged, String firedFrom, String firedTo,
                                    int limit, long offset) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(state, severity, ruleId, clusterId, acknowledged, firedFrom, firedTo, args);
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(
                "SELECT " + columns() + " FROM " + q(TABLE) + where
                        + " ORDER BY " + q("fired_at") + " DESC, " + q("id") + " DESC LIMIT ? OFFSET ?",
                this::mapRow, args.toArray());
    }

    public int countSearch(String state, String severity, Long ruleId, Long clusterId,
                           Boolean acknowledged, String firedFrom, String firedTo) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(state, severity, ruleId, clusterId, acknowledged, firedFrom, firedTo, args);
        Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + q(TABLE) + where, Integer.class, args.toArray());
        return total == null ? 0 : total;
    }

    /**
     * 拼装 WHERE 子句。
     *
     * <p>条件全部可选,因此用"条件列表 + 非空才加 {@code WHERE}"而不是"恒真前缀"
     * —— 无条件时的 {@code SELECT COUNT(*) FROM alert_record} 能干净地走全表,
     * 而不是带一个 {@code 1 = 1} 让优化器多做一次无谓判定。
     */
    private String buildWhere(String state, String severity, Long ruleId, Long clusterId,
                              Boolean acknowledged, String firedFrom, String firedTo, List<Object> args) {
        List<String> conditions = new ArrayList<>();
        appendEquals(conditions, args, q("state"), state);
        appendEquals(conditions, args, q("severity"), severity);
        appendEquals(conditions, args, q("rule_id"), ruleId);
        appendEquals(conditions, args, q("cluster_id"), clusterId);
        if (acknowledged != null) {
            conditions.add(q("acknowledged_at") + (acknowledged ? " IS NOT NULL" : " IS NULL"));
        }
        if (firedFrom != null && !firedFrom.isBlank()) {
            conditions.add(q("fired_at") + " >= ?");
            args.add(firedFrom.trim());
        }
        if (firedTo != null && !firedTo.isBlank()) {
            conditions.add(q("fired_at") + " <= ?");
            args.add(firedTo.trim());
        }
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }

    public Optional<AlertRecord> findById(long id) {
        List<AlertRecord> rows = jdbcTemplate.query(
                "SELECT " + columns() + " FROM " + q(TABLE) + " WHERE " + q("id") + " = ?",
                this::mapRow, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 最近 N 条(概览页用,全局不分页)。 */
    public List<AlertRecord> recent(int limit) {
        return jdbcTemplate.query(
                "SELECT " + columns() + " FROM " + q(TABLE)
                        + " ORDER BY " + q("fired_at") + " DESC, " + q("id") + " DESC LIMIT ?",
                this::mapRow, limit);
    }

    /**
     * 未读数:{@code state = 'FIRING' AND acknowledged_at IS NULL AND recovered_at IS NULL}。
     *
     * <p>为什么排除已恢复行:状态机的恢复是"回填旧 FIRING 行的 recovered_at"(state 仍是
     * FIRING),不排除的话,自动恢复但没人 ack 的告警会<b>永久</b>挂在未读数里,
     * 顶部徽标只增不减。"待处理"的语义是"还在响且没人认领",已经恢复的问题不算。
     *
     * <p>不额外建索引:FIRING 集合天然很小(状态机去重后 ≈ 当前真实故障数),
     * 复用 023 的 {@code (state, fired_at)} 索引即可,为这个小集合再建一个索引
     * 只会拖慢写路径(SQLite 单写者下尤其明显)。
     */
    public int countUnread() {
        Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + q(TABLE) + " WHERE " + q("state") + " = ?"
                        + " AND " + q("acknowledged_at") + " IS NULL"
                        + " AND " + q("recovered_at") + " IS NULL",
                Integer.class, AlertRecord.FIRING);
        return total == null ? 0 : total;
    }

    /**
     * 当前仍 FIRING 的告警数(未恢复)。
     *
     * <p>与 {@link #countUnread} 的差别:未读数 = FIRING 且<b>未 ack</b>;
     * 这里只看"还没恢复"。两个数字都进概览,用途不同(一个是待处理,一个是故障面)。
     */
    public int countFiring() {
        Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + q(TABLE) + " WHERE " + q("state") + " = ?"
                        + " AND " + q("recovered_at") + " IS NULL",
                Integer.class, AlertRecord.FIRING);
        return total == null ? 0 : total;
    }

    /** 当前仍 FIRING 的告警按严重度分桶(概览 bySeverity)。 */
    public Map<String, Integer> countFiringBySeverity() {
        Map<String, Integer> out = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT " + q("severity") + ", COUNT(*) AS " + q("cnt") + " FROM " + q(TABLE)
                        + " WHERE " + q("state") + " = ? AND " + q("recovered_at") + " IS NULL"
                        + " GROUP BY " + q("severity"),
                (RowCallbackHandler) rs -> out.put(rs.getString("severity"), rs.getInt("cnt")),
                AlertRecord.FIRING);
        return out;
    }

    /**
     * 今日恢复数(概览页 {@code recoveredToday});按 {@code recovered_at} 字典序当天下界统计。
     *
     * <p><b>刻意不过滤 state</b>:状态机的恢复 = 回填旧 FIRING 行的 recovered_at
     * (state 仍是 FIRING)+ 另插一条 RECOVERED 通知行(其 recovered_at 为 null)。
     * 若按 state='RECOVERED' 统计,状态机路径的恢复<b>一行都数不到</b>(恒 0);
     * 按 recovered_at 统计恰好一次恢复计一行(通知行无 recovered_at,不会重复计数)。
     */
    public int countRecoveredSince(String since) {
        Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + q(TABLE)
                        + " WHERE " + q("recovered_at") + " >= ?",
                Integer.class, since);
        return total == null ? 0 : total;
    }

    /** 写入一条告警(FIRING 或 RECOVERED),返回带 id 的行。 */
    public AlertRecord insert(long ruleId, long clusterId, String scopeId, String severity, String state,
                              String title, String message) {
        String now = now();
        String sql = "INSERT INTO " + q(TABLE) + " (" + q("rule_id") + ", " + q("cluster_id") + ", "
                + q("scope_id") + ", " + q("severity") + ", " + q("state") + ", " + q("title") + ", "
                + q("message") + ", " + q("fired_at") + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            ps.setLong(i++, ruleId);
            ps.setLong(i++, clusterId);
            ps.setString(i++, scopeId);
            ps.setString(i++, severity);
            ps.setString(i++, state);
            ps.setString(i++, title);
            ps.setString(i++, message);
            ps.setString(i, now);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Insert succeeded but no generated key returned for alert of rule " + ruleId);
        }
        return findById(key.longValue())
                .orElseThrow(() -> new IllegalStateException("Alert " + key.longValue() + " disappeared after insert"));
    }

    /**
     * 恢复回填:找该 (rule, scope) 最近一条未回填的 FIRING 告警,写入 recovered_at。
     *
     * <p>只回填<b>最近一条</b>(状态机去重下同一 (rule, scope) 同时最多一条未回填),
     * 回填失败(已被手工处理)返回 false,不抛异常 —— 恢复通知本身不该卡住状态机。
     */
    public boolean markRecovered(long ruleId, String scopeId) {
        List<Long> ids = jdbcTemplate.query(
                "SELECT " + q("id") + " FROM " + q(TABLE)
                        + " WHERE " + q("rule_id") + " = ? AND " + q("scope_id") + " = ?"
                        + " AND " + q("state") + " = ? AND " + q("recovered_at") + " IS NULL"
                        + " ORDER BY " + q("fired_at") + " DESC, " + q("id") + " DESC LIMIT 1",
                (rs, rowNum) -> rs.getLong("id"), ruleId, scopeId, AlertRecord.FIRING);
        if (ids.isEmpty()) {
            return false;
        }
        return jdbcTemplate.update(
                "UPDATE " + q(TABLE) + " SET " + q("recovered_at") + " = ? WHERE " + q("id") + " = ?",
                now(), ids.get(0)) > 0;
    }

    /** 确认(ack);不改变 state。 */
    public boolean acknowledge(long id, String by) {
        return jdbcTemplate.update(
                "UPDATE " + q(TABLE) + " SET " + q("acknowledged_at") + " = ?, "
                        + q("acknowledged_by") + " = ?"
                        + " WHERE " + q("id") + " = ? AND " + q("acknowledged_at") + " IS NULL",
                now(), by, id) > 0;
    }

    private String columns() {
        return q("id") + ", " + q("rule_id") + ", " + q("cluster_id") + ", " + q("scope_id") + ", "
                + q("severity") + ", " + q("state") + ", " + q("title") + ", " + q("message") + ", "
                + q("fired_at") + ", " + q("recovered_at") + ", " + q("acknowledged_at") + ", "
                + q("acknowledged_by");
    }

    private AlertRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new AlertRecord(
                rs.getLong("id"),
                rs.getLong("rule_id"),
                rs.getLong("cluster_id"),
                rs.getString("scope_id"),
                rs.getString("severity"),
                rs.getString("state"),
                rs.getString("title"),
                rs.getString("message"),
                rs.getString("fired_at"),
                rs.getString("recovered_at"),
                rs.getString("acknowledged_at"),
                rs.getString("acknowledged_by"));
    }

    private String q(String identifier) {
        return dialect.quoteIdent(identifier);
    }
}
