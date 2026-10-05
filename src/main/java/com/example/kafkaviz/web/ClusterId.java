package com.example.kafkaviz.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个由 {@link ClusterArgumentResolver} 注入的集群参数。
 *
 * <p>用法说明:参数类型本身已经足够表达意图,注解主要用于
 * <b>显式标注</b>("这个参数来自集群段,不要当成普通 bean 绑定")与将来扩展。
 *
 * <pre>{@code
 * @GetMapping("/info")
 * public ApiResponse<ClusterInfo> info(@PathVariable long clusterId, ClusterConnection connection) { ... }
 *
 * // 只校验存在性、不求建连(归档端点对离线集群必须可用)
 * @GetMapping("/topics")
 * public ApiResponse<...> topics(@ClusterId(existenceOnly = true) ClusterDefinition cluster) { ... }
 * }</pre>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ClusterId {

    /**
     * 只校验集群配置存在(40404),<b>不</b>触发建连。
     *
     * <p>归档查询、收藏等"离线可用"的端点必须用这个模式 ——
     * 否则集群离线时归档也查不了,而那恰恰是归档存在的意义。
     */
    boolean existenceOnly() default false;
}
