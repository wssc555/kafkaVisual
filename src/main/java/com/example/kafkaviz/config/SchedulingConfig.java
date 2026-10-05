package com.example.kafkaviz.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务开关({@code @EnableScheduling})。
 *
 * <p>独立成类而不是塞进 {@code KafkaOpsExecutorConfig}:后者管的是"并行 AdminClient
 * 调用的线程池",与"定时任务"是两个关注点,混在一起会让读者以为必须同时改。
 *
 * <p>当前有两类定时任务:
 * <ul>
 *   <li>{@code ClusterConnectionManager.evictIdleConnections} —— 每分钟回收空闲连接;</li>
 *   <li>{@code ClusterArchiveService.cleanupExpired} —— 每日 retention 清理。</li>
 * </ul>
 *
 * <p>注意:Spring Boot 的 scheduling 与 {@code KafkaOpsExecutorConfig} 的
 * {@code kafkaOpsExecutor} 用的是各自的线程池(默认单线程调度器),
 * 所以"每日清理"不会占用 Kafka 聚合线程。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
