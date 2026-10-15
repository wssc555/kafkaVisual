package com.example.kafkaviz.inspection.schedule;

import org.springframework.scheduling.support.CronExpression;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * Cron 校验与下次触发时间计算。
 *
 * <p>用 spring-context 自带的 {@link CronExpression}(<b>6 段:秒 分 时 日 月 周</b>,
 * 日/周字段可用 {@code ?}),不引 Quartz —— 后者需要 7 段表达式与 11 张
 * {@code QRTZ_*} 表,且默认锁语句 {@code SELECT ... FOR UPDATE} 不被 SQLite 支持。
 *
 * <p>时间一律以 <b>UTC Instant</b> 的 ISO-8601 文本(秒级精度)落库:cron 按
 * 服务器本地时区解释(用户填的"每天 9 点"就是本地 9 点),算出触发时刻后换算成
 * UTC —— 与 {@code created_at} / {@code last_evaluated_at} / {@code fired_at} 等
 * 时间列同一口径,字典序 = 时间序,ticker 的 {@code <= ?} 直接字符串比较,
 * 前端拿到带 Z 的串也能直接转本地时间显示。
 */
public final class RuleCron {

    private RuleCron() {
    }

    /**
     * 校验 cron 合法性。
     *
     * @throws IllegalArgumentException 非法表达式(由调用方转 40005)
     */
    public static void validate(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("cronExpr must not be blank");
        }
        if (!CronExpression.isValidExpression(expression.trim())) {
            throw new IllegalArgumentException(
                    "Invalid cron expression: '" + expression + "' (expected 6 fields: second minute hour "
                            + "day-of-month month day-of-week, e.g. '0 */1 * * * ?')");
        }
    }

    /**
     * 校验最小触发间隔:取相邻两次触发时间之差,小于阈值即拒。
     *
     * <p>为什么用"相邻两次差值"而不是解析字段:cron 的真实间隔无法从字段字面读出
     * ({@code 0 0 0 1 1 ?} 是一年一次),只有 {@code next()} 两次才能给出事实。
     */
    public static void validateMinInterval(String expression, long minIntervalSeconds) {
        validate(expression);
        if (minIntervalSeconds <= 0) {
            return;
        }
        CronExpression cron = CronExpression.parse(expression.trim());
        LocalDateTime first = cron.next(LocalDateTime.now());
        if (first == null) {
            throw new IllegalArgumentException("Cron expression never fires: '" + expression + "'");
        }
        LocalDateTime second = cron.next(first);
        if (second == null) {
            // 只触发一次的表达式(如固定日期):间隔视为无穷大,合法
            return;
        }
        long gapSeconds = ChronoUnit.SECONDS.between(first, second);
        if (gapSeconds < minIntervalSeconds) {
            throw new IllegalArgumentException("Cron interval too small: " + gapSeconds
                    + "s < " + minIntervalSeconds + "s (expression '" + expression + "')");
        }
    }

    /**
     * 计算下次触发时间(ISO-8601 文本)。
     *
     * @return 下次触发时刻;表达式不再触发时返回 null(调用方按"不再排期"处理)
     */
    public static String nextFireAt(String expression) {
        return nextFireAt(expression, LocalDateTime.now());
    }

    public static String nextFireAt(String expression, LocalDateTime from) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        CronExpression cron = CronExpression.parse(expression.trim());
        LocalDateTime next = cron.next(from);
        return next == null ? null : format(next);
    }

    /** 当前时刻的 ISO-8601 文本(UTC,秒级),供 ticker 的到期比较使用。 */
    public static String now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /**
     * 本地时区算出的触发时刻 → UTC Instant 文本(与库里其他时间列同口径)。
     */
    private static String format(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant()
                .truncatedTo(ChronoUnit.SECONDS).toString();
    }
}
