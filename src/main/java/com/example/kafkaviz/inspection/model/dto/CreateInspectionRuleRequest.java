package com.example.kafkaviz.inspection.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * {@code POST /api/c/{clusterId}/inspection/rules} 请求体。
 *
 * <p>校验分两层(错误码不同,别混):
 * <ul>
 *   <li>Bean Validation(本类)—— 结构性问题 → 40001;</li>
 *   <li>Service 语义校验 —— 未知指标键 / cron 非法或过密 / 通道不存在 /
 *       ZK scope 用于 KRaft 集群 / 同集群重名 → 40005 / 40903。</li>
 * </ul>
 *
 * <p>类级 {@code @AssertTrue} 负责"跨字段形状":单条件的必填项能各自表达,
 * 但"conditions 不能为空"必须看列表本身。
 */
@Data
public class CreateInspectionRuleRequest {

    @NotBlank(message = "name must not be blank")
    @Size(max = 128, message = "name must be at most 128 characters")
    private String name;

    /** {@code INFO} / {@code WARN} / {@code CRITICAL};越界由 Service 转 40005。 */
    @NotBlank(message = "severity must not be blank")
    private String severity;

    /** {@code CLUSTER} / {@code TOPIC} / {@code CONSUMER_GROUP} / {@code ZK}。 */
    @NotBlank(message = "metricScope must not be blank")
    private String metricScope;

    @Size(max = 256, message = "scopeFilter must be at most 256 characters")
    private String scopeFilter;

    private String combinator = "AND";

    @Valid
    @NotEmpty(message = "conditions must not be empty")
    private List<RuleConditionSpec> conditions;

    /** 6 段 Spring CronExpression(如 {@code 0 0/1 * * * ?} 每分钟);最小间隔由配置决定。 */
    @NotBlank(message = "cronExpr must not be blank")
    private String cronExpr;

    /**
     * 至少一个通道;空 / 指向不存在的通道 → <b>40005</b>。
     *
     * <p>刻意<b>不加</b> {@code @NotEmpty}:Bean Validation 失败会走 40001,
     * 而"没配通道"是规则定义不完整(与未知指标键、非法 cron 同类),统一走 40005
     * 才能让用户从错误码一眼看出"这是规则本身的问题"。
     */
    private List<Long> channelIds;

    @Size(max = 512, message = "description must be at most 512 characters")
    private String description;

    /** 缺省 true。 */
    private Boolean enabled;

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @AssertTrue(message = "each condition must have metric/operator/threshold and a well-formed window")
    public boolean isConditionShapeValid() {
        if (conditions == null) {
            return false;
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
