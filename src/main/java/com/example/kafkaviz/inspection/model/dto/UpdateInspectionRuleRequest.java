package com.example.kafkaviz.inspection.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * {@code PUT /api/c/{clusterId}/inspection/rules/{id}} 请求体。
 *
 * <p><b>省略 = 不变</b>(与集群配置的"秘密字段三态"不同:这里没有敏感字段,
 * 语义就统一为"没给就别动")。{@code conditions} / {@code channelIds} 给出即整体替换。
 */
@Data
public class UpdateInspectionRuleRequest {

    @Size(max = 128, message = "name must be at most 128 characters")
    private String name;

    private String severity;

    private String metricScope;

    @Size(max = 256, message = "scopeFilter must be at most 256 characters")
    private String scopeFilter;

    private String combinator;

    /** 给出即整体替换(整删整插);null = 不动。 */
    @Valid
    private List<RuleConditionSpec> conditions;

    /** 变更后重算 next_fire_at。 */
    private String cronExpr;

    private List<Long> channelIds;

    @Size(max = 512, message = "description must be at most 512 characters")
    private String description;

    private Boolean enabled;

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @AssertTrue(message = "each condition must have metric/operator/threshold and a well-formed window")
    public boolean isConditionShapeValid() {
        if (conditions == null) {
            return true;
        }
        for (RuleConditionSpec spec : conditions) {
            if (spec == null) {
                return false;
            }
            if (isBlank(spec.getMetric()) || isBlank(spec.getOperator()) || isBlank(spec.getThreshold())) {
                return false;
            }
            RuleConditionSpec.RuleWindowSpec window = spec.getWindow();
            if (window != null && (window.getCount() == null || isBlank(window.getMatch()))) {
                return false;
            }
        }
        return true;
    }
}
