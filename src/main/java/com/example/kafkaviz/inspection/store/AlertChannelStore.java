package com.example.kafkaviz.inspection.store;

import com.example.kafkaviz.inspection.model.AlertChannel;
import com.example.kafkaviz.storage.StorageDialect;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

/**
 * {@code alert_channel} 读写 —— <b>全局</b>通道(不挂集群)。
 *
 * <p>{@code config_json} 存的是密文 JSON(敏感字段已加密),本 Store 只当不透明
 * 字符串搬运,加解密与掩码一律在 {@code ChannelConfigCodec} 里做 —— 避免"某条路径
 * 忘了脱敏"这类泄漏。
 */
@Component
public class AlertChannelStore {

    public static final String TABLE = "alert_channel";

    private final JdbcTemplate jdbcTemplate;
    private final StorageDialect dialect;

    public AlertChannelStore(JdbcTemplate jdbcTemplate, StorageDialect dialect) {
        this.jdbcTemplate = jdbcTemplate;
        this.dialect = dialect;
    }

    public List<AlertChannel> list() {
        return jdbcTemplate.query(
                "SELECT " + columns() + " FROM " + q(TABLE) + " ORDER BY " + q("id"), this::mapRow);
    }

    public List<AlertChannel> listEnabled() {
        return jdbcTemplate.query(
                "SELECT " + columns() + " FROM " + q(TABLE) + " WHERE " + q("enabled") + " = 1"
                        + " ORDER BY " + q("id"), this::mapRow);
    }

    public Optional<AlertChannel> findById(long id) {
        List<AlertChannel> rows = jdbcTemplate.query(
                "SELECT " + columns() + " FROM " + q(TABLE) + " WHERE " + q("id") + " = ?",
                this::mapRow, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 名称全局唯一性检查(跨集群)。 */
    public Optional<Long> findIdByName(String name) {
        List<Long> ids = jdbcTemplate.query(
                "SELECT " + q("id") + " FROM " + q(TABLE) + " WHERE " + q("name") + " = ?",
                (rs, rowNum) -> rs.getLong("id"), name);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    /** 批量按 id 取通道(规则关联通道时用,不存在/被删的 id 直接跳过)。 */
    public List<AlertChannel> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        String placeholders = ids.stream().map(id -> "?").reduce((a, b) -> a + ", " + b).orElse("");
        return jdbcTemplate.query(
                "SELECT " + columns() + " FROM " + q(TABLE) + " WHERE " + q("id") + " IN ("
                        + placeholders + ") ORDER BY " + q("id"),
                this::mapRow, ids.toArray());
    }

    public AlertChannel insert(String name, String type, String configJson, boolean enabled) {
        String sql = "INSERT INTO " + q(TABLE) + " (" + q("name") + ", " + q("type") + ", "
                + q("config_json") + ", " + q("enabled") + ") VALUES (?, ?, ?, ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, type);
            ps.setString(3, configJson);
            ps.setInt(4, enabled ? 1 : 0);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Insert succeeded but no generated key returned for channel " + name);
        }
        return findById(key.longValue())
                .orElseThrow(() -> new IllegalStateException("Channel " + key.longValue() + " disappeared after insert"));
    }

    public void update(long id, String name, String type, String configJson, boolean enabled) {
        jdbcTemplate.update(
                "UPDATE " + q(TABLE) + " SET " + q("name") + " = ?, " + q("type") + " = ?, "
                        + q("config_json") + " = ?, " + q("enabled") + " = ? WHERE " + q("id") + " = ?",
                name, type, configJson, enabled ? 1 : 0, id);
    }

    /**
     * 删除通道。
     *
     * <p>同时清 {@code rule_channel} 关联:留着指向已删通道的关联行会让分发器
     * 每轮都去查一个必然不存在的通道。告警历史不受影响。
     */
    public boolean delete(long id) {
        jdbcTemplate.update(dialect.deleteWhereSql("rule_channel", "channel_id"), id);
        return jdbcTemplate.update(dialect.deleteWhereSql(TABLE, "id"), id) > 0;
    }

    private String columns() {
        return q("id") + ", " + q("name") + ", " + q("type") + ", " + q("config_json") + ", " + q("enabled");
    }

    private AlertChannel mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new AlertChannel(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("type"),
                rs.getString("config_json"),
                rs.getInt("enabled") != 0);
    }

    private String q(String identifier) {
        return dialect.quoteIdent(identifier);
    }
}
