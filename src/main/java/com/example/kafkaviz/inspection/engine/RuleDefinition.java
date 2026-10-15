package com.example.kafkaviz.inspection.engine;

import com.example.kafkaviz.inspection.metric.MetricScope;

import java.util.List;

/**
 * 规则的引擎运行期形态({@code condition_json} 解析产物)。
 *
 * <p>刻意<b>不含</b> name / severity / channelIds —— 那是"告警呈现与投递"关注点,
 * 由状态机在转移时从规则行里取。混进来会让"规则定义"同时承担两种职责,
 * 以后加字段就会两边都动。
 *
 * @param combinator 顶层组合子:{@code AND} / {@code OR}(不支持嵌套条件组)
 * @param scopeFilter 原始过滤串({@code *} / 前缀 / 精确列表三态)
 */
public record RuleDefinition(
        long ruleId,
        long clusterId,
        String combinator,
        List<RuleCondition> conditions,
        MetricScope scope,
        String scopeFilter) {

    public static final String AND = "AND";
    public static final String OR = "OR";

    /** 本次评估需要的指标键并集 —— 交给采集器做一次批量采集的依据。 */
    public java.util.Set<String> metricKeys() {
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        for (RuleCondition c : conditions) {
            keys.add(c.metricKey());
        }
        return keys;
    }

    /**
     * 全部窗口条件的 count 之和 —— 采样滚动删除的保留行数预算;全瞬时规则为 0。
     *
     * <p>为什么是求和而不是取最大:滚动删除按 {@code (rule_id, scope_id)} 保留行数,
     * 而窗口查询按 {@code (rule_id, metric_key, scope_id)} 各取 count 条 ——
     * 保留预算必须容纳<b>每个</b>指标各自的窗口,否则多指标规则下大窗口的样本
     * 会被其他指标的采样挤掉,窗口永远凑不满(样本不足 → 永不触发)。
     */
    public int totalWindowCount() {
        int total = 0;
        for (RuleCondition c : conditions) {
            if (c.windowCount() != null) {
                total += c.windowCount();
            }
        }
        return total;
    }
}
