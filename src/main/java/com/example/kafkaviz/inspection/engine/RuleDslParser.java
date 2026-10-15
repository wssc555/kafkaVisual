package com.example.kafkaviz.inspection.engine;

import com.example.kafkaviz.inspection.exception.InvalidRuleException;
import com.example.kafkaviz.inspection.metric.MetricCatalog;
import com.example.kafkaviz.inspection.metric.MetricScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code condition_json} → {@link RuleDefinition}。
 *
 * <p>DSL 形态(计划 §5.1):
 * <pre>{@code
 * {
 *   "combinator": "AND",
 *   "conditions": [
 *     { "metric": "consumer_group_lag_total", "operator": ">", "threshold": 10000,
 *       "window": { "count": 3, "match": "ALL" } }
 *   ]
 * }
 * }</pre>
 *
 * <p>用 {@code JsonNode} 手工解析而不是绑定到 DTO:条件里 {@code threshold} 既可能是
 * 数字也可能是字符串,绑定成 {@code Object} 后又要二次判定;手工解析一次到位,
 * 且能把"哪个字段错"精确写进异常消息(40005 的 msg 就是要给人看的)。
 *
 * <p>校验项:combinator 合法 / conditions 非空 / 指标键在目录内 / 指标 scope 与规则
 * scope 一致 / 算子合法 / 阈值非空 / 窗口参数(count ≥ 1、match ∈ ALL|ANY、两者成对出现)。
 */
@Component
public class RuleDslParser {

    /** 单规则的条件数上限:解析期挡住"误粘贴出一千条条件"这类输入。 */
    private static final int MAX_CONDITIONS = 20;

    private final ObjectMapper objectMapper;

    public RuleDslParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? fallback : text.trim();
    }

    /**
     * 解析并校验。
     *
     * @throws InvalidRuleException 任一校验项不通过 → 40005
     */
    public RuleDefinition parse(long ruleId, long clusterId, MetricScope scope, String scopeFilter,
                                String conditionJson) {
        if (conditionJson == null || conditionJson.isBlank()) {
            throw new InvalidRuleException("condition must not be blank");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(conditionJson);
        } catch (Exception e) {
            throw new InvalidRuleException("condition is not valid JSON: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new InvalidRuleException("condition must be a JSON object");
        }

        String combinator = ThresholdEvaluator.normalizeToken(text(root, "combinator", "AND"));
        if (!RuleDefinition.AND.equals(combinator) && !RuleDefinition.OR.equals(combinator)) {
            throw new InvalidRuleException("combinator must be AND or OR but was: " + combinator);
        }

        JsonNode conditionsNode = root.get("conditions");
        if (conditionsNode == null || !conditionsNode.isArray() || conditionsNode.isEmpty()) {
            throw new InvalidRuleException("conditions must be a non-empty array");
        }
        if (conditionsNode.size() > MAX_CONDITIONS) {
            throw new InvalidRuleException("too many conditions: " + conditionsNode.size()
                    + " (max " + MAX_CONDITIONS + ")");
        }

        List<RuleCondition> conditions = new ArrayList<>(conditionsNode.size());
        for (int i = 0; i < conditionsNode.size(); i++) {
            conditions.add(parseCondition(conditionsNode.get(i), i, scope));
        }
        return new RuleDefinition(ruleId, clusterId, combinator, conditions, scope, scopeFilter);
    }

    private RuleCondition parseCondition(JsonNode node, int index, MetricScope scope) {
        if (node == null || !node.isObject()) {
            throw new InvalidRuleException("conditions[" + index + "] must be an object");
        }
        String metricKey = text(node, "metric", null);
        if (metricKey == null || metricKey.isBlank()) {
            throw new InvalidRuleException("conditions[" + index + "].metric must not be blank");
        }
        if (!MetricCatalog.contains(metricKey)) {
            throw new InvalidRuleException("Unknown metric key at conditions[" + index + "]: " + metricKey);
        }
        var item = MetricCatalog.get(metricKey);
        if (scope == null || !scope.value().equals(item.getScope())) {
            throw new InvalidRuleException("Metric '" + metricKey + "' belongs to scope " + item.getScope()
                    + " but the rule scope is " + (scope == null ? "null" : scope.value()));
        }

        String operator = text(node, "operator", null);
        if (!ThresholdEvaluator.isOperatorValid(operator)) {
            throw new InvalidRuleException("Invalid operator at conditions[" + index + "]: " + operator
                    + " (allowed: > < >= <= == !=)");
        }
        // 非数值指标(BOOL/STRING)只支持 ==/!=:比较算子在评估期恒"无法判定"
        //(ThresholdEvaluator 对非数值指标的比较算子返回 null),创建期就拦住,
        // 别让用户配出一条永远"样本不足"、永不触发的规则
        if (!MetricCatalog.isNumber(metricKey)
                && !"==".equals(operator.trim()) && !"!=".equals(operator.trim())) {
            throw new InvalidRuleException("Operator '" + operator.trim()
                    + "' is not applicable to non-numeric metric '" + metricKey
                    + "' (only == and != are allowed for BOOL/STRING metrics)");
        }

        JsonNode thresholdNode = node.get("threshold");
        String threshold = thresholdNode == null || thresholdNode.isNull()
                ? null
                : thresholdNode.asText();
        if (threshold == null || threshold.isBlank()) {
            throw new InvalidRuleException("conditions[" + index + "].threshold must not be blank");
        }

        JsonNode window = node.get("window");
        Integer windowCount = null;
        String windowMatch = null;
        if (window != null && !window.isNull()) {
            if (!window.isObject()) {
                throw new InvalidRuleException("conditions[" + index + "].window must be an object");
            }
            JsonNode countNode = window.get("count");
            if (countNode == null || countNode.isNull() || !countNode.isIntegralNumber()
                    || countNode.asInt() < 1) {
                throw new InvalidRuleException("conditions[" + index
                        + "].window.count must be a positive integer");
            }
            windowCount = countNode.asInt();
            windowMatch = ThresholdEvaluator.normalizeToken(text(window, "match", null));
            if (!RuleCondition.ALL.equals(windowMatch) && !RuleCondition.ANY.equals(windowMatch)) {
                throw new InvalidRuleException("conditions[" + index
                        + "].window.match must be ALL or ANY but was: " + windowMatch);
            }
        }

        return new RuleCondition(metricKey.trim(), operator.trim(), threshold.trim(),
                windowCount, windowMatch);
    }
}
