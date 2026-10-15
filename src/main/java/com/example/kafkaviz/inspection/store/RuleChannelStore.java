package com.example.kafkaviz.inspection.store;

import com.example.kafkaviz.storage.StorageDialect;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code rule_channel} 读写 —— 规则与通道的多对多关联。
 *
 * <p>写策略同 {@link RuleConditionStore}:整删整插。关联条数是个位数,
 * 且"哪些通道"是一次性提交的整体,逐条 diff 没有收益。
 */
@Component
public class RuleChannelStore {

    public static final String TABLE = "rule_channel";

    private final JdbcTemplate jdbcTemplate;
    private final StorageDialect dialect;

    public RuleChannelStore(JdbcTemplate jdbcTemplate, StorageDialect dialect) {
        this.jdbcTemplate = jdbcTemplate;
        this.dialect = dialect;
    }

    /** 该规则关联的通道 id(按插入顺序,id 升序便于稳定输出)。 */
    public List<Long> channelIdsOf(long ruleId) {
        return jdbcTemplate.query(
                "SELECT " + q("channel_id") + " FROM " + q(TABLE) + " WHERE " + q("rule_id") + " = ?"
                        + " ORDER BY " + q("channel_id"),
                (rs, rowNum) -> rs.getLong("channel_id"), ruleId);
    }

    /** 批量替换关联。传入空列表 = 解除全部关联(创建规则前的校验会挡住空关联)。 */
    public void replaceAll(long ruleId, List<Long> channelIds) {
        jdbcTemplate.update(dialect.deleteWhereSql(TABLE, "rule_id"), ruleId);
        if (channelIds == null || channelIds.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO " + q(TABLE) + " (" + q("rule_id") + ", " + q("channel_id") + ")"
                + " VALUES (?, ?)";
        List<Object[]> batch = new ArrayList<>(channelIds.size());
        for (Long channelId : channelIds) {
            batch.add(new Object[]{ruleId, channelId});
        }
        jdbcTemplate.batchUpdate(sql, batch);
    }

    private String q(String identifier) {
        return dialect.quoteIdent(identifier);
    }
}
