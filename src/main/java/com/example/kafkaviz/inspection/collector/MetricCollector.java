package com.example.kafkaviz.inspection.collector;

import com.example.kafkaviz.inspection.metric.MetricScope;
import com.example.kafkaviz.kafka.ClusterConnection;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * 指标采集器 SPI。
 *
 * <p>契约要点:
 * <ul>
 *   <li><b>批量</b>:一次调用收下"多个实例 × 多个指标",实现方自行合并底层 RPC
 *       —— 禁止引擎逐条件调用(那样同一条规则会打出 N 次 describeTopics);</li>
 *   <li><b>只读</b>:全部指标经 {@code AdminClient} / {@code Curator} 取得,
 *       不借 {@code ConsumerPool}、不 poll、不提交 offset,对线上消费组零影响;</li>
 *   <li><b>值统一为字符串</b>:数值与枚举一样串存(与 {@code metric_sample.value}
 *       的 TEXT 口径一致),由 {@code ThresholdEvaluator} 按目录的 valueType 解释。</li>
 * </ul>
 */
public interface MetricCollector {

    /** 本采集器负责的 scope。 */
    MetricScope scope();

    /**
     * 采集指定实例集合的指定指标。
     *
     * @param scopeInstances scope 实例(集群级恒为 {@code CLUSTER};Topic 级=topic 名;组级=group id;ZK 级=path)
     * @param metricKeys     本次需要的指标键(已按目录校验过)
     * @return {@code scopeId → (metricKey → value)};取不到值的实例/指标<b>不出现</b>在结果里
     *         (引擎按"缺值 = 样本不足"处理,不会误判为 0)
     * @throws Exception 采集失败原样上抛,由引擎包成 {@code InspectionExecutionException}
     */
    Map<String, Map<String, String>> collect(ClusterConnection connection,
                                             Collection<String> scopeInstances,
                                             Set<String> metricKeys) throws Exception;
}
