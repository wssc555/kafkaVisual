package com.example.kafkaviz.inspection.engine;

import com.example.kafkaviz.inspection.metric.MetricCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * 阈值比较。
 *
 * <p>两类口径:
 * <ul>
 *   <li><b>NUMBER</b>(目录 {@code valueType=NUMBER}):按 double 比较。采样值或阈值
 *       解析失败 → 返回 {@code null}(语义:样本不足/口径不符,<b>不是 false</b>);
 *       宁可漏报不误报 —— 把"值解析不出来"当成"条件不满足"会静默吞掉真实故障。</li>
 *   <li><b>STRING / BOOL</b>:字符串相等比较,只认 {@code ==} / {@code !=}。
 *       BOOL 的采样值是 {@code true}/{@code false},{@code == false} 这种写法可读
 *       且不需要特殊语法。对非数值型指标用 {@code >} 之类算子时返回 null(口径不符)。</li>
 * </ul>
 */
public final class ThresholdEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ThresholdEvaluator.class);

    private ThresholdEvaluator() {
    }

    /**
     * @return {@code TRUE} 满足 / {@code FALSE} 不满足 / {@code null} 无法判定(样本不足或口径不符)
     */
    public static Boolean evaluate(String metricKey, String operator, String threshold, String actual) {
        if (threshold == null || operator == null) {
            return null;
        }
        String op = operator.trim();
        boolean numeric = MetricCatalog.isNumber(metricKey);

        if (numeric) {
            try {
                double actualValue = Double.parseDouble(actual == null ? "" : actual.trim());
                double expected = Double.parseDouble(threshold.trim());
                return compareNumeric(op, actualValue, expected);
            } catch (NumberFormatException e) {
                log.debug("Metric {} sample/threshold not numeric (sample='{}', threshold='{}') — "
                        + "treated as insufficient samples", metricKey, actual, threshold);
                return null;
            }
        }

        if ("==".equals(op) || "!=".equals(op)) {
            String expected = threshold.trim();
            boolean equal = expected.equals(actual == null ? "" : actual.trim());
            return "==".equals(op) ? equal : !equal;
        }
        log.debug("Operator '{}' is not applicable to non-numeric metric {} — treated as insufficient",
                op, metricKey);
        return null;
    }

    private static boolean compareNumeric(String op, double actual, double expected) {
        return switch (op) {
            case ">" -> actual > expected;
            case "<" -> actual < expected;
            case ">=" -> actual >= expected;
            case "<=" -> actual <= expected;
            case "==" -> actual == expected;
            case "!=" -> actual != expected;
            default -> throw new IllegalArgumentException("Unsupported operator: " + op);
        };
    }

    /** 算子是否合法(解析期校验用)。 */
    public static boolean isOperatorValid(String operator) {
        if (operator == null) {
            return false;
        }
        return switch (operator.trim()) {
            case ">", "<", ">=", "<=", "==", "!=" -> true;
            default -> false;
        };
    }

    /** 归一化:去空白;组合子/窗口匹配模式统一大写,避免大小写变种漏过校验。 */
    public static String normalizeToken(String raw) {
        return raw == null ? null : raw.trim().toUpperCase(Locale.ROOT);
    }
}
