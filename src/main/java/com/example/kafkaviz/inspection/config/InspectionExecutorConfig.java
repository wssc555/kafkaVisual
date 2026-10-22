package com.example.kafkaviz.inspection.config;

import com.example.kafkaviz.inspection.InspectionProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 巡检专用的两个有界线程池。
 *
 * <p>与 {@code KafkaOpsExecutorConfig} 的三点差别(差别本身就是存在的理由):
 * <ul>
 *   <li>分开两个池:采集评估(受 Kafka 延迟影响)与告警发送(受外部 HTTP/SMTP
 *       延迟影响)互不拖累 —— 一个慢通道不该让其他规则的评估排队;</li>
 *   <li>都用 {@code AbortPolicy}:巡检是"本轮跳过即可"的后台任务,
 *       CallerRuns 会把发送/采集动作倒灌回 ticker 线程,直接毁掉调度;</li>
 *   <li>线程名带语义前缀({@code inspection-} / {@code alert-sender-}),
 *       慢任务能直接从线程栈定位是哪一类。</li>
 * </ul>
 *
 * <p><b>本类刻意不加 {@code @ConditionalOnProperty("inspection.enabled")}</b>:
 * 引擎与分发器(它们没有条件)依赖这两个池,巡检总开关关掉时若池也不注册,
 * 整个上下文会因缺 Bean 起不来。两个空转的守护线程池开销可忽略,
 * 而"关掉一个功能导致应用无法启动"是不可接受的。
 */
@Configuration
public class InspectionExecutorConfig {

    private static ExecutorService pool(String namePrefix, InspectionProperties.Executor config) {
        AtomicInteger sequence = new AtomicInteger(0);
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, namePrefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        // 口径:core=2 / max=4 / queue=128。注意 ThreadPoolExecutor 的扩容规则:
        // 只有队列满之后才会超过 core 扩到 maximum —— 有界队列在这里的角色是背压
        //(积压即拒绝 → 派发侧记 warn 跳过本轮),而非"用满 4 条线程"。
        int core = Math.max(1, config.getCorePoolSize());
        int max = Math.max(core, config.getMaxPoolSize());
        return new ThreadPoolExecutor(
                core, max, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(1, config.getQueueCapacity())),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** 采集评估线程池。 */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService inspectionExecutor(InspectionProperties properties) {
        InspectionProperties.Executor config = properties.getExecutor();
        return pool("inspection-", config);
    }

    /** 告警发送线程池。 */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService alertSenderExecutor(InspectionProperties properties) {
        InspectionProperties.Executor config = properties.getAlert().getExecutor();
        return pool("alert-sender-", config);
    }
}
