package com.example.kafkaviz.inspection.store;

import com.example.kafkaviz.inspection.model.AlertDispatchLog;
import com.example.kafkaviz.storage.StorageDialect;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * {@code alert_dispatch_log} 读写 —— 每告警每通道一条发送结果。
 *
 * <p>分发器对每通道独立 try/catch 后落一条 SUCCESS / FAILED,因此"哪个通道挂了"
 * 在告警详情里是可查的事实,不是日志里的猜测。
 *
 * <p><b>为什么只有单条 insert 而没有批量接口</b>(任务 024 改动清单曾写"批量插入"):
 * 各通道的发送结果在 {@code alertSenderExecutor} 的不同 worker 线程上、彼此独立的
 * 时刻产生,逐条落库才能让"先完成的通道"立即可查;攒一批反而把失败原因的可见性
 * 拖到最后一个通道结束。任务口径按此实现为准。
 */
@Component
public class AlertDispatchLogStore {

    public static final String TABLE = "alert_dispatch_log";

    private final JdbcTemplate jdbcTemplate;
    private final StorageDialect dialect;

    public AlertDispatchLogStore(JdbcTemplate jdbcTemplate, StorageDialect dialect) {
        this.jdbcTemplate = jdbcTemplate;
        this.dialect = dialect;
    }

    /**
     * 失败原因长度上限。
     *
     * <p>脱敏后的原因仍可能很长(含整段 HTTP 响应体),截断避免把 MB 级文本写进
     * 单写者的 SQLite —— 排查只需前 1000 字符。
     */
    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 1000 ? message.substring(0, 1000) + "..." : message;
    }

    private static String now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /** 按告警查(详情抽屉里展示)。 */
    public List<AlertDispatchLog> listByAlert(long alertId) {
        return jdbcTemplate.query(
                "SELECT " + columns() + " FROM " + q(TABLE) + " WHERE " + q("alert_id") + " = ?"
                        + " ORDER BY " + q("id"),
                this::mapRow, alertId);
    }

    public void insert(long alertId, long channelId, String status, String errorMessage) {
        jdbcTemplate.update(
                "INSERT INTO " + q(TABLE) + " (" + q("alert_id") + ", " + q("channel_id") + ", "
                        + q("status") + ", " + q("error_message") + ", " + q("created_at") + ")"
                        + " VALUES (?, ?, ?, ?, ?)",
                alertId, channelId, status, truncate(errorMessage), now());
    }

    private String columns() {
        return q("id") + ", " + q("alert_id") + ", " + q("channel_id") + ", " + q("status") + ", "
                + q("error_message") + ", " + q("created_at");
    }

    private AlertDispatchLog mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new AlertDispatchLog(
                rs.getLong("id"),
                rs.getLong("alert_id"),
                rs.getLong("channel_id"),
                rs.getString("status"),
                rs.getString("error_message"),
                rs.getString("created_at"));
    }

    private String q(String identifier) {
        return dialect.quoteIdent(identifier);
    }
}
