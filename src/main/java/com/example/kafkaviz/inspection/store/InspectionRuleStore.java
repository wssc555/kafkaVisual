package com.example.kafkaviz.inspection.store;

import com.example.kafkaviz.inspection.model.InspectionRule;
import com.example.kafkaviz.storage.StorageDialect;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.util.List;
import java.util.Optional;

/**
 * {@code inspection_rule} 读写。
 *
 * <p>规则是<b>每集群</b>的:唯一键 {@code (cluster_id, name)},跨集群同名合法。
 * 所有 SQL 的标识符经 {@link StorageDialect#quoteIdent},本类不含方言痕迹。
 *
 * <p>RowMapper 一律<b>按列名取值</b>(不按索引)—— 索引顺序曾与 RowMapper 错位
 * (归档把分区数读成了归档条数),这里从源头禁掉索引读取。
 */
@Component
public class InspectionRuleStore {

    public static final String TABLE = "inspection_rule";

    private final JdbcTemplate jdbcTemplate;
    private final StorageDialect dialect;

    public InspectionRuleStore(JdbcTemplate jdbcTemplate, StorageDialect dialect) {
        this.jdbcTemplate = jdbcTemplate;
        this.dialect = dialect;
    }

    // ---------------------------------------------------------------
    // 读
    // ---------------------------------------------------------------

    private static String now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /**
     * 分页列表(按集群)。
     *
     * @param enabled null = 不过滤
     * @param scope   null/空白 = 不过滤
     * @param nameLike null/空白 = 不过滤(两侧 % 模糊)
     * @param offset  long:page 无上界时 (page-1)*size 会溢出 int
     */
    public List<InspectionRule> list(long clusterId, Boolean enabled, String scope, String nameLike,
                                     int limit, long offset) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(clusterId, enabled, scope, nameLike, args);
        args.add(limit);
        args.add(offset);
        // LIMIT ? OFFSET ? 三方言通用(与 selectArchiveSql 同口径)
        String sql = "SELECT " + columns() + " FROM " + q(TABLE) + where
                + " ORDER BY " + q("id") + " DESC LIMIT ? OFFSET ?";
        return jdbcTemplate.query(sql, this::mapRow, args.toArray());
    }

    /** 与 {@link #list} 完全相同的过滤条件下的总行数(分页总数)。 */
    public int count(long clusterId, Boolean enabled, String scope, String nameLike) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(clusterId, enabled, scope, nameLike, args);
        Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + q(TABLE) + where, Integer.class, args.toArray());
        return total == null ? 0 : total;
    }

    /** {@link #list} 与 {@link #count} 共用的 WHERE 拼装(两处各写一份迟早漂移出"列表与总数不一致")。 */
    private String buildWhere(long clusterId, Boolean enabled, String scope, String nameLike,
                              List<Object> args) {
        StringBuilder where = new StringBuilder(" WHERE ").append(q("cluster_id")).append(" = ?");
        args.add(clusterId);
        if (enabled != null) {
            where.append(" AND ").append(q("enabled")).append(" = ?");
            args.add(enabled ? 1 : 0);
        }
        if (scope != null && !scope.isBlank()) {
            where.append(" AND ").append(q("metric_scope")).append(" = ?");
            args.add(scope.trim());
        }
        if (nameLike != null && !nameLike.isBlank()) {
            where.append(" AND ").append(q("name")).append(" LIKE ?");
            args.add("%" + nameLike.trim() + "%");
        }
        return where.toString();
    }

    /** 全局规则总数(概览页;跨集群)。 */
    public int countAll() {
        Integer total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + q(TABLE), Integer.class);
        return total == null ? 0 : total;
    }

    /** 全局启用规则数。 */
    public int countEnabled() {
        Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + q(TABLE) + " WHERE " + q("enabled") + " = 1", Integer.class);
        return total == null ? 0 : total;
    }

    public Optional<InspectionRule> findById(long id) {
        List<InspectionRule> rows = jdbcTemplate.query(
                "SELECT " + columns() + " FROM " + q(TABLE) + " WHERE " + q("id") + " = ?",
                this::mapRow, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 同集群内的名称唯一性检查。 */
    public Optional<Long> findIdByName(long clusterId, String name) {
        List<Long> ids = jdbcTemplate.query(
                "SELECT " + q("id") + " FROM " + q(TABLE)
                        + " WHERE " + q("cluster_id") + " = ? AND " + q("name") + " = ?",
                (rs, rowNum) -> rs.getLong("id"), clusterId, name);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    /**
     * 到期规则查询(ticker 的事实源)。
     *
     * <p>走 023 建的复合索引 {@code (enabled, next_fire_at)};时间列是 ISO-8601 文本,
     * 字典序 = 时间序,因此 {@code <= ?} 直接字符串比较即可,无需日期函数。
     * {@code next_fire_at IS NULL}(待对账)的规则不会被命中 —— 由启动对账负责重排。
     */
    public List<InspectionRule> findDue(String now, int limit) {
        return jdbcTemplate.query(
                "SELECT " + columns() + " FROM " + q(TABLE)
                        + " WHERE " + q("enabled") + " = 1 AND " + q("next_fire_at") + " IS NOT NULL"
                        + " AND " + q("next_fire_at") + " <= ?"
                        + " ORDER BY " + q("next_fire_at") + " LIMIT ?",
                this::mapRow, now, limit);
    }

    // ---------------------------------------------------------------
    // 写
    // ---------------------------------------------------------------

    /** 全部启用规则(启动对账用;不分页,规则量级是十到百)。 */
    public List<InspectionRule> listEnabled() {
        return jdbcTemplate.query(
                "SELECT " + columns() + " FROM " + q(TABLE) + " WHERE " + q("enabled") + " = 1"
                        + " ORDER BY " + q("id"),
                this::mapRow);
    }

    /** 插入并返回带 id 的行。 */
    public InspectionRule insert(long clusterId, String name, boolean enabled, String severity,
                                 String metricScope, String scopeFilter, String conditionJson,
                                 String cronExpr, String nextFireAt, String description) {
        String now = now();
        String sql = "INSERT INTO " + q(TABLE) + " (" + q("cluster_id") + ", " + q("name") + ", "
                + q("enabled") + ", " + q("severity") + ", " + q("metric_scope") + ", "
                + q("scope_filter") + ", " + q("condition_json") + ", " + q("cron_expr") + ", "
                + q("next_fire_at") + ", " + q("description") + ", " + q("created_at") + ", "
                + q("updated_at") + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            ps.setLong(i++, clusterId);
            ps.setString(i++, name);
            ps.setInt(i++, enabled ? 1 : 0);
            ps.setString(i++, severity);
            ps.setString(i++, metricScope);
            ps.setString(i++, scopeFilter);
            ps.setString(i++, conditionJson);
            ps.setString(i++, cronExpr);
            ps.setString(i++, nextFireAt);
            ps.setString(i++, description);
            ps.setString(i++, now);
            ps.setString(i, now);
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Insert succeeded but no generated key returned for rule " + name);
        }
        return findById(key.longValue())
                .orElseThrow(() -> new IllegalStateException("Rule " + key.longValue() + " disappeared after insert"));
    }

    /** 全量更新(省略语义由 Service 层解析成现值后再传入)。 */
    public void update(long id, String name, boolean enabled, String severity, String metricScope,
                       String scopeFilter, String conditionJson, String cronExpr, String nextFireAt,
                       String description) {
        jdbcTemplate.update(
                "UPDATE " + q(TABLE) + " SET " + q("name") + " = ?, " + q("enabled") + " = ?, "
                        + q("severity") + " = ?, " + q("metric_scope") + " = ?, "
                        + q("scope_filter") + " = ?, " + q("condition_json") + " = ?, "
                        + q("cron_expr") + " = ?, " + q("next_fire_at") + " = ?, "
                        + q("description") + " = ?, " + q("updated_at") + " = ?"
                        + " WHERE " + q("id") + " = ?",
                name, enabled ? 1 : 0, severity, metricScope, scopeFilter, conditionJson, cronExpr,
                nextFireAt, description, now(), id);
    }

    public void setEnabled(long id, boolean enabled, String nextFireAt) {
        jdbcTemplate.update(
                "UPDATE " + q(TABLE) + " SET " + q("enabled") + " = ?, " + q("next_fire_at") + " = ?, "
                        + q("updated_at") + " = ? WHERE " + q("id") + " = ?",
                enabled ? 1 : 0, nextFireAt, now(), id);
    }

    /** 只重排下次触发时间(ticker 每轮评估前后的高频写)。 */
    public void updateNextFireAt(long id, String nextFireAt) {
        jdbcTemplate.update(
                "UPDATE " + q(TABLE) + " SET " + q("next_fire_at") + " = ? WHERE " + q("id") + " = ?",
                nextFireAt, id);
    }

    /**
     * 删除规则。
     *
     * <p>级联清理由 {@link #cascadeDelete(long)} 完成(Store 内部保证顺序),
     * 历史告警与分发日志<b>保留</b>。
     *
     * @return true = 确实删掉了一行
     */
    public boolean delete(long id) {
        return jdbcTemplate.update(dialect.deleteWhereSql(TABLE, "id"), id) > 0;
    }

    // ---------------------------------------------------------------
    // 内部
    // ---------------------------------------------------------------

    /**
     * 级联清理规则附属行。
     *
     * <p>刻意<b>不动</b> {@code alert_record} / {@code alert_dispatch_log}:
     * 规则删掉后历史告警仍需可查(告警中心是审计视图)。
     */
    public void cascadeDelete(long ruleId) {
        jdbcTemplate.update(dialect.deleteWhereSql("rule_condition", "rule_id"), ruleId);
        jdbcTemplate.update(dialect.deleteWhereSql("rule_state", "rule_id"), ruleId);
        jdbcTemplate.update(dialect.deleteWhereSql("metric_sample", "rule_id"), ruleId);
        jdbcTemplate.update(dialect.deleteWhereSql("rule_channel", "rule_id"), ruleId);
    }

    private String columns() {
        return q("id") + ", " + q("cluster_id") + ", " + q("name") + ", " + q("enabled") + ", "
                + q("severity") + ", " + q("metric_scope") + ", " + q("scope_filter") + ", "
                + q("condition_json") + ", " + q("cron_expr") + ", " + q("next_fire_at") + ", "
                + q("description") + ", " + q("created_at") + ", " + q("updated_at");
    }

    private InspectionRule mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new InspectionRule(
                rs.getLong("id"),
                rs.getLong("cluster_id"),
                rs.getString("name"),
                rs.getInt("enabled") != 0,
                rs.getString("severity"),
                rs.getString("metric_scope"),
                rs.getString("scope_filter"),
                rs.getString("condition_json"),
                rs.getString("cron_expr"),
                rs.getString("next_fire_at"),
                rs.getString("description"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }

    private String q(String identifier) {
        return dialect.quoteIdent(identifier);
    }
}
