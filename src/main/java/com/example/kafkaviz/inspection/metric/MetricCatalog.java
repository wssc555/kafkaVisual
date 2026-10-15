package com.example.kafkaviz.inspection.metric;

import com.example.kafkaviz.inspection.model.vo.MetricCatalogItemVO;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 指标目录(静态注册表)。
 *
 * <p>目录是<b>唯一事实源</b>:规则创建时用它校验指标键(未知键 → 40005),
 * 引擎用它决定阈值比较方式(NUMBER 走 double、STRING/BOOL 走字符串相等),
 * 前端用它渲染下拉。新增指标只在 {@link #ITEMS} 里加一行 + 在对应 Collector 里
 * 实现取值,不改引擎与分发器。
 *
 * <p>{@code valueType} 三态:
 * <ul>
 *   <li>{@code NUMBER} —— 阈值按 double 比较(解析失败视为"样本不足");</li>
 *   <li>{@code BOOL} —— 采样值是 {@code true}/{@code false} 字符串,阈值同形态;</li>
 *   <li>{@code STRING} —— 枚举串(如消费组 state),只支持 {@code ==}/{@code !=}。</li>
 * </ul>
 */
public final class MetricCatalog {

    public static final String NUMBER = "NUMBER";
    public static final String STRING = "STRING";
    public static final String BOOL = "BOOL";

    /** 集群级 scope 的固定实例 id(集群级规则只有一个 scope)。 */
    public static final String CLUSTER_SCOPE_ID = "CLUSTER";
    private static final List<MetricCatalogItemVO> ITEMS = List.of(
            // ---- CLUSTER ----
            item("broker_count", MetricScope.CLUSTER, NUMBER, "个", "Broker 总数"),
            item("controller_alive", MetricScope.CLUSTER, BOOL, null, "Controller 是否存活"),
            item("under_replicated_partitions", MetricScope.CLUSTER, NUMBER, "个", "ISR 收缩分区数"),
            item("offline_partitions", MetricScope.CLUSTER, NUMBER, "个", "无 Leader 分区数"),
            item("online_broker_count", MetricScope.CLUSTER, NUMBER, "个", "承载 Leader 分区的 Broker 数"),

            // ---- TOPIC ----
            item("partition_count", MetricScope.TOPIC, NUMBER, "个", "分区数"),
            item("replication_factor", MetricScope.TOPIC, NUMBER, "个", "副本数"),
            item("message_count_total", MetricScope.TOPIC, NUMBER, "条", "该 topic 消息总量(listOffsets endOffset 之和)"),
            item("log_size_bytes", MetricScope.TOPIC, NUMBER, "B", "该 topic 日志字节(describeLogDirs)"),
            item("under_replicated", MetricScope.TOPIC, BOOL, null, "该 topic 是否存在 ISR 收缩分区"),

            // ---- CONSUMER_GROUP ----
            item("state", MetricScope.CONSUMER_GROUP, STRING, null, "组状态(STABLE/REBALANCING/PREPARING_REBALANCE/DEAD/EMPTY)"),
            item("lag_total", MetricScope.CONSUMER_GROUP, NUMBER, "条", "组总 lag(endOffset − committed 之和)"),
            item("member_count", MetricScope.CONSUMER_GROUP, NUMBER, "个", "活跃成员数"),
            item("dead", MetricScope.CONSUMER_GROUP, BOOL, null, "是否 DEAD"),

            // ---- ZK ----
            item("node_exists", MetricScope.ZK, BOOL, null, "节点是否存在"),
            item("child_count", MetricScope.ZK, NUMBER, "个", "子节点数"),
            item("node_value_hash", MetricScope.ZK, NUMBER, null, "节点值 hash(相邻两次不同即变更检测)"));
    private static final Map<String, MetricCatalogItemVO> BY_KEY = ITEMS.stream()
            .collect(Collectors.toMap(MetricCatalogItemVO::getKey, item -> item));

    private MetricCatalog() {
    }

    private static MetricCatalogItemVO item(String key, MetricScope scope, String valueType,
                                            String unit, String description) {
        return MetricCatalogItemVO.builder()
                .key(key)
                .scope(scope.value())
                .valueType(valueType)
                .unit(unit)
                .description(description)
                .build();
    }

    /**
     * 按集群模式过滤后的目录。
     *
     * <p>{@code zkAvailable=false}(KRaft 集群)时剔除 ZK 级指标 —— 前端下拉里
     * 根本不出现,配合创建时的 40005 校验形成双保险。
     */
    public static List<MetricCatalogItemVO> listFor(boolean zkAvailable) {
        return ITEMS.stream()
                .filter(item -> zkAvailable || !MetricScope.ZK.value().equals(item.getScope()))
                .collect(Collectors.toList());
    }

    /** 全量目录(服务端内部校验用,不过滤)。 */
    public static List<MetricCatalogItemVO> all() {
        return ITEMS;
    }

    /** 指标键是否在目录内(创建/更新规则的校验)。 */
    public static boolean contains(String key) {
        return key != null && BY_KEY.containsKey(key.trim());
    }

    /** 指标定义;未知键返回 null。 */
    public static MetricCatalogItemVO get(String key) {
        return key == null ? null : BY_KEY.get(key.trim());
    }

    /** 该指标是否数值型(决定阈值比较走 double 还是字符串)。 */
    public static boolean isNumber(String key) {
        MetricCatalogItemVO item = get(key);
        return item != null && NUMBER.equals(item.getValueType());
    }

    /** 按 scope 取出该 scope 下的全部合法指标键(规则校验用)。 */
    public static Set<String> keysOf(MetricScope scope) {
        return ITEMS.stream()
                .filter(item -> scope.value().equals(item.getScope()))
                .map(MetricCatalogItemVO::getKey)
                .collect(Collectors.toSet());
    }
}
