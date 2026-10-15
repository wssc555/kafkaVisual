package com.example.kafkaviz.inspection.metric;

/**
 * 巡检指标的 scope(决定"规则要遍历哪些实例")。
 *
 * <p>scope 同时决定 {@code scope_id} 的取值口径:
 * <ul>
 *   <li>{@link #CLUSTER} —— 固定单实例 {@code CLUSTER};</li>
 *   <li>{@link #TOPIC} —— topic 名;</li>
 *   <li>{@link #CONSUMER_GROUP} —— group id;</li>
 *   <li>{@link #ZK} —— ZK 节点 path(须在 scope_filter 里显式列出)。</li>
 * </ul>
 */
public enum MetricScope {

    CLUSTER("CLUSTER"),
    TOPIC("TOPIC"),
    CONSUMER_GROUP("CONSUMER_GROUP"),
    ZK("ZK");

    private final String value;

    MetricScope(String value) {
        this.value = value;
    }

    /** 按库中 {@code metric_scope} 列的值解析;未知值返回 null(由调用方转 40005)。 */
    public static MetricScope of(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(java.util.Locale.ROOT);
        for (MetricScope scope : values()) {
            if (scope.value.equals(normalized)) {
                return scope;
            }
        }
        return null;
    }

    public String value() {
        return value;
    }
}
