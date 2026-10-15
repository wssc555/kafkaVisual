package com.example.kafkaviz.inspection.collector;

import com.example.kafkaviz.inspection.metric.MetricScope;
import com.example.kafkaviz.kafka.ClusterConnection;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 采集器注册表:按 scope 路由。
 *
 * <p><b>合并去重的落点</b>:引擎把"一条规则里全部条件所需的指标键"合成一个
 * {@code metricSet}、把"全部命中的 scope 实例"合成一个列表,一次性交给本注册表。
 * 因此 {@code partition_count + log_size_bytes} 这种共享底层调用的组合只会触发
 * <b>一次</b> describeLogDirs —— 逐条件采集才是写放大与打挂集群的真正来源。
 *
 * <p>新增指标只加 {@code MetricCollector} 实现({@code @Component} 自动入列),
 * 本类与引擎都不用改。
 */
@Component
public class CollectorRegistry {

    private final Map<MetricScope, MetricCollector> byScope = new LinkedHashMap<>();

    public CollectorRegistry(List<MetricCollector> collectors) {
        for (MetricCollector collector : collectors) {
            byScope.put(collector.scope(), collector);
        }
    }

    /**
     * 采集一批实例的指标。
     *
     * @return {@code scopeId → (metricKey → value)};该 scope 无采集器(理论上不会发生,
     *         目录与采集器一一对应)或无数据时返回空 Map
     */
    public Map<String, Map<String, String>> collect(MetricScope scope, ClusterConnection connection,
                                                    Collection<String> scopeInstances, Set<String> metricKeys)
            throws Exception {
        if (scope == null || scopeInstances == null || scopeInstances.isEmpty() || metricKeys.isEmpty()) {
            return Map.of();
        }
        MetricCollector collector = byScope.get(scope);
        if (collector == null) {
            return Map.of();
        }
        return collector.collect(connection, scopeInstances, metricKeys);
    }
}
