package com.example.kafkaviz.storage;

import com.example.kafkaviz.model.vo.FavoriteItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 收藏(topic / 消费组星标)。
 *
 * <p>收藏是<b>每集群</b>的:同一个 topic 名在两个集群下是两条记录
 * ({@code UNIQUE(cluster_id, item_type, item_name)})。
 */
@Component
public class FavoriteStore {

    /** 允许的收藏类型;越界 → 40001。 */
    public static final Set<String> ALLOWED_TYPES = Set.of("topic", "group");
    private static final String TABLE = "favorite";
    private static final List<String> COLUMNS = List.of("cluster_id", "item_type", "item_name");
    private final JdbcTemplate jdbcTemplate;
    private final StorageDialect dialect;

    public FavoriteStore(JdbcTemplate jdbcTemplate, StorageDialect dialect) {
        this.jdbcTemplate = jdbcTemplate;
        this.dialect = dialect;
    }

    private static String requireType(String itemType) {
        if (itemType == null || !ALLOWED_TYPES.contains(itemType.trim())) {
            throw new IllegalArgumentException(
                    "itemType must be one of " + ALLOWED_TYPES + " but was: " + itemType);
        }
        return itemType.trim();
    }

    private static String requireName(String itemName) {
        if (itemName == null || itemName.isBlank()) {
            throw new IllegalArgumentException("itemName must not be blank");
        }
        return itemName.trim();
    }

    public List<FavoriteItem> list(long clusterId) {
        return jdbcTemplate.query(
                "SELECT " + q("cluster_id") + ", " + q("item_type") + ", " + q("item_name")
                        + " FROM " + q(TABLE) + " WHERE " + q("cluster_id") + " = ?"
                        + " ORDER BY " + q("item_type") + ", " + q("item_name"),
                (rs, rowNum) -> FavoriteItem.builder()
                        .clusterId(rs.getLong(1))
                        .itemType(rs.getString(2))
                        .itemName(rs.getString(3))
                        .build(),
                clusterId);
    }

    /**
     * 添加收藏(幂等:重复添加不报错、不产生重复行)。
     *
     * @throws IllegalArgumentException itemType 不是 topic/group,或 name 为空
     */
    public void add(long clusterId, String itemType, String itemName) {
        String type = requireType(itemType);
        String name = requireName(itemName);
        jdbcTemplate.update(dialect.insertIgnoreSql(TABLE, COLUMNS, 1), clusterId, type, name);
    }

    /**
     * 移除收藏(幂等:不存在时静默成功 —— 前端的乐观更新会重复调用)。
     *
     * @return 实际删除的行数
     */
    public int remove(long clusterId, String itemType, String itemName) {
        String type = requireType(itemType);
        String name = requireName(itemName);
        return jdbcTemplate.update(
                "DELETE FROM " + q(TABLE) + " WHERE " + q("cluster_id") + " = ? AND "
                        + q("item_type") + " = ? AND " + q("item_name") + " = ?",
                clusterId, type, name);
    }

    /** 集群删除时清理其收藏(避免留下查不到的孤儿行)。 */
    public int removeAllOfCluster(long clusterId) {
        return jdbcTemplate.update(dialect.deleteWhereSql(TABLE, "cluster_id"), clusterId);
    }

    private String q(String identifier) {
        return dialect.quoteIdent(identifier);
    }
}
