package com.example.kafkaviz.inspection.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 规则 DSL 的单个子条件(请求体形态)。
 *
 * <p>与引擎侧 {@code RuleCondition} 的差别:这里是"用户填的东西"(threshold 是字符串、
 * window 可缺省),由 Service 规范化成 {@code condition_json} 后再交给
 * {@code RuleDslParser} 做语义校验(未知指标键 / 算子非法 → 40005)。
 *
 * <p>类级结构校验放在 {@link CreateInspectionRuleRequest} 的 {@code @AssertTrue} 上,
 * 因为它要跨字段(conditions 非空 + 每个子条件的必填项)。
 */
@Data
public class RuleConditionSpec {

    @NotBlank(message = "metric must not be blank")
    private String metric;

    @NotBlank(message = "operator must not be blank")
    private String operator;

    @NotBlank(message = "threshold must not be blank")
    private String threshold;

    /** 省略 = 瞬时条件(用当前值直接判定,不写采样)。 */
    private RuleWindowSpec window;

    @Data
    public static class RuleWindowSpec {

        /** 窗口采样次数;≥ 1。 */
        private Integer count;

        /** {@code ALL} 窗口内全部满足 / {@code ANY} 至少一次。 */
        private String match;
    }
}
