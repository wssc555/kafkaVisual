package com.example.kafkaviz.inspection.engine;

import java.util.List;
import java.util.Map;

/**
 * 一次规则评估的结果(试运行端点与状态机共用)。
 *
 * @param skipped            集群离线(或 ZK scope 跑在 KRaft 集群)→ 本轮未评估,
 *                           状态机与告警表一概不动
 * @param fired              至少一个 scope 实例满足条件(试运行展示用,不代表已发告警)
 * @param firedScopes        命中的 scope 实例列表
 * @param metricValues       {@code scopeId → (metricKey → 当前值)};取不到值的 scope 不出现
 * @param insufficientSamples 存在"样本不足 / 口径不符"而无法判定的条件
 *                            (窗口规则预热期的正常状态,前端要如实展示)
 */
public record EvaluationResult(
        boolean skipped,
        boolean fired,
        List<String> firedScopes,
        Map<String, Map<String, String>> metricValues,
        boolean insufficientSamples) {

    /**
     * 跳过本轮(集群离线 / ZK scope 跑在 KRaft 集群)的结果。
     *
     * <p>方法名刻意不叫 {@code skipped()}:记录组件的存取方法就是 {@code skipped()},
     * 同名的静态工厂会与它构成"同名同参数列表"的重复方法(静态与否不参与重载判定),
     * 编译器会报「存取方法返回类型与记录组件类型不匹配」。
     */
    public static EvaluationResult skip() {
        return new EvaluationResult(true, false, List.of(), Map.of(), false);
    }
}
