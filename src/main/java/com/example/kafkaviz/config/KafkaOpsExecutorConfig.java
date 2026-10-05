package com.example.kafkaviz.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Kafka 聚合类操作专用线程池。
 *
 * <p>仅供需要并行发起多路 AdminClient 调用的场景使用(当前是 Dashboard 概览聚合)。
 * 不用 {@code ForkJoinPool.commonPool}:公共池会被其他并行流共用,慢 Kafka 请求会
 * 拖累无关任务,也无法通过线程名定位日志。
 *
 * <p>过载时走 {@code AbortPolicy} 快速失败,由调用方转成 50302(见
 * {@code DashboardService}),而不是让请求在队列里无限堆积。
 */
@Configuration
public class KafkaOpsExecutorConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService kafkaOpsExecutor() {
        AtomicInteger sequence = new AtomicInteger(0);
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "kafka-ops-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        // 核心 8 / 上限 8:ThreadPoolExecutor 只有在队列满之后才会把线程扩到 maximum,
        // 若 core < max,队列几乎不可能填满,实际并发恒为 core,maximumPoolSize 形同虚设
        // (Dashboard 单请求峰值 3 路,第 2 个并发请求就开始排队)。
        // 取 core=max=8 让并发上限真实可达;队列 64 仅作缓冲,填满后 AbortPolicy 快速失败转 50302。
        return new ThreadPoolExecutor(
                8, 8, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }
}
