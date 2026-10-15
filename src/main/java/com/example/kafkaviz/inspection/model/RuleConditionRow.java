package com.example.kafkaviz.inspection.model;

/**
 * {@code rule_condition} 行 —— {@code condition_json} 的规范化拆表。
 *
 * <p>拆表的目的不是给引擎用(引擎直接读 {@code condition_json}),而是:
 * <ul>
 *   <li>创建/更新时能按列做存在性与合法性校验(指标键须在目录内);</li>
 *   <li>UI 编辑规则时能按 {@code seq} 稳定回显条件顺序。</li>
 * </ul>
 *
 * @param windowCount 采样窗口次数;null = 瞬时条件(不写 metric_sample)
 * @param windowMatch {@code ALL} / {@code ANY};仅当 windowCount 非 null 时有意义
 * @param combinator  与<b>下一</b>条件的逻辑;末条件为 {@code END}
 */
public record RuleConditionRow(
        long id,
        long ruleId,
        int seq,
        String metricKey,
        String operator,
        String threshold,
        Integer windowCount,
        String windowMatch,
        String combinator) {
}
