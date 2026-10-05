package com.example.kafkaviz.archive;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 消息归档配置。
 *
 * <p>这三个值决定归档的"防线":
 * <ul>
 *   <li>{@link #enabled} —— 全局开关。关掉后 <b>不再启动任何归档器</b>
 *       (已落库的归档仍可查询)。测试基建也用它把归档从断言路径里排除。</li>
 *   <li>{@link #maxMessageBytes} —— 单条 value 上限,超限只存占位(见
 *       {@code ClusterArchiver}),保证 offset 连续性不断档。</li>
 *   <li>{@link #maxHeaderBytes} —— headers JSON 上限,超限截断标记。</li>
 * </ul>
 */
@Getter
@Setter
@ConfigurationProperties("archive")
public class ArchiveProperties {

    /** 全局归档开关;false 时所有集群都不启动归档器。 */
    private boolean enabled = true;

    /** 单条消息 value 的入库上限(字节)。超限:value 置 NULL + headers 里加 {@code __truncated:true} 标记。 */
    private int maxMessageBytes = 1048576;

    /** 单条消息 headers 序列化后的上限(字节)。超限:替换为 {@code {"__truncated":true}}。 */
    private int maxHeaderBytes = 4096;
}
