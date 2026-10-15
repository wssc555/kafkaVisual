package com.example.kafkaviz.inspection.engine;

/**
 * 单个规则子条件(引擎运行期形态)。
 *
 * <p>与 {@code rule_condition} 行的差别:不含 id / rule_id / seq / combinator ——
 * 组合子是<b>规则级</b>的(顶层单一 combinator,本期不支持条件组嵌套),
 * seq 只影响 UI 回显顺序,不进引擎。
 *
 * @param windowCount 窗口采样次数;null = 瞬时(用当前值直接判定,不写 metric_sample)
 * @param windowMatch {@code ALL} 全部满足 / {@code ANY} 至少一次;仅 windowCount 非 null 时有意义
 */
public record RuleCondition(
        String metricKey,
        String operator,
        String threshold,
        Integer windowCount,
        String windowMatch) {

    public static final String ALL = "ALL";
    public static final String ANY = "ANY";

    /** 是否带时间窗口(决定要不要落采样行)。 */
    public boolean hasWindow() {
        return windowCount != null && windowCount > 0;
    }
}
