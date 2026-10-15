package com.example.kafkaviz.inspection.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 规则试运行结果({@code POST /api/c/{clusterId}/inspection/rules/{id}/test})。
 *
 * <p><b>只读</b>:不发告警、不写状态机、不写采样行。窗口规则在预热期会返回
 * {@code insufficientSamples=true} —— 这是正常状态(历史样本不足 N 条),
 * 前端必须如实展示,否则用户会以为规则配错了。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleTestResult {

    /** 至少一个 scope 满足条件。 */
    private boolean fired;

    /** 存在无法判定的条件(窗口样本不足 / 值解析失败)。 */
    private boolean insufficientSamples;

    private List<String> firedScopes;

    /** {@code scopeId → (metricKey → 当前值)};取不到值的 scope 不出现。 */
    private Map<String, Map<String, String>> metricValues;
}
