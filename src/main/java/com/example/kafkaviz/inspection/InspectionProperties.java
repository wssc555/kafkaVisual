package com.example.kafkaviz.inspection;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 定时巡检配置({@code inspection.*})。
 *
 * <p>七项配置(计划 §10)。注意 {@code inspection.enabled} <b>不在这里</b> ——
 * 它只被 {@code @ConditionalOnProperty} 从 Environment 直接消费(决定 ticker /
 * reconciler / 三个控制器是否注册),绑成字段反而会让"关掉开关但 Bean 还在"的
 * 组合有可能被写出来。
 *
 * <p>两个线程池都<b>独立于</b> {@code kafkaOpsExecutor}:后者是给用户请求用的,
 * 它的 AbortPolicy 一旦被巡检占满,用户请求就会吃 50302 —— 那是把后台任务的
 * 背压错误地转嫁给了前台。
 */
@Getter
@Setter
@ConfigurationProperties("inspection")
public class InspectionProperties {

    private Scheduler scheduler = new Scheduler();

    private Sample sample = new Sample();

    /** 采集评估线程池。 */
    private Executor executor = new Executor();

    private Alert alert = new Alert();

    /**
     * 配置下限校验(启动即失败,带着明确原因,而不是带病运行):
     * tick-ms <= 0 会让 {@code @Scheduled(fixedDelay=0)} 变成 CPU 空转 +
     * 到期查询风暴;retention-buffer < 0 会让采样滚动删除的保留预算为负。
     */
    @PostConstruct
    void validateRanges() {
        if (scheduler.tickMs <= 0) {
            throw new IllegalStateException("inspection.scheduler.tick-ms must be positive, got: "
                    + scheduler.tickMs + " (0/negative makes the ticker busy-spin)");
        }
        if (scheduler.minIntervalSeconds < 0) {
            throw new IllegalStateException("inspection.scheduler.min-interval-seconds must be >= 0, got: "
                    + scheduler.minIntervalSeconds);
        }
        if (sample.retentionBuffer < 0) {
            throw new IllegalStateException("inspection.sample.retention-buffer must be >= 0, got: "
                    + sample.retentionBuffer);
        }
        if (alert.sendTimeoutMs <= 0) {
            throw new IllegalStateException("inspection.alert.send-timeout-ms must be positive, got: "
                    + alert.sendTimeoutMs);
        }
    }

    @Getter
    @Setter
    public static class Scheduler {

        /** ticker 轮询周期(ms)。1s 只是"看得见的粒度",扫描走索引、开销可忽略。 */
        private long tickMs = 1000L;

        /** Cron 最小触发间隔(秒);低于此值的 cron 在创建/更新时被拒(40005)。 */
        private long minIntervalSeconds = 30L;
    }

    @Getter
    @Setter
    public static class Sample {

        /**
         * 采样保留冗余:每个 (rule, scope) 保留 {@code max(window_count) + buffer} 行。
         *
         * <p>多留几行是为了让"窗口查询"与"滚动删除"不必在写入的同一瞬间严格对齐,
         * 也让 UI 排查时能多看几条历史。
         */
        private int retentionBuffer = 5;
    }

    @Getter
    @Setter
    public static class Executor {

        private int corePoolSize = 2;

        private int maxPoolSize = 4;

        private int queueCapacity = 128;
    }

    @Getter
    @Setter
    public static class Alert {

        /** 告警发送线程池(与采集池分开:慢通道不能拖慢评估)。 */
        private Executor executor = new Executor();

        /** 单通道发送超时(ms):Webhook / IM / SMTP 共用。 */
        private long sendTimeoutMs = 5000L;
    }
}
