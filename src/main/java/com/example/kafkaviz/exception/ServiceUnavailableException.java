package com.example.kafkaviz.exception;

/**
 * 服务暂时不可用,映射到 503/50302。
 *
 * <p>语义:
 * <ul>
 *   <li><b>Kafka 调用超时/中断</b>({@code KafkaFutures.await} 等) —— 带 cause;</li>
 *   <li><b>集群连接类不可用</b>:正在连接中、探活失败后的冷却期、该集群未配置
 *       ZooKeeper —— 这些是<b>业务状态</b>而非底层异常,通常没有 cause,
 *       用单参构造器,由调用方把"为什么不可用"写进 msg。</li>
 * </ul>
 *
 * <p>与 50001(Kafka error)的边界:可重试、预期会恢复的 → 50302;
 * 操作本身失败(配置错、协议错)→ 50001。
 */
public class ServiceUnavailableException extends RuntimeException {

    /** 业务性不可用(无底层 cause):连接中 / 冷却期 / 该集群无 ZK 等。 */
    public ServiceUnavailableException(String msg) {
        super(msg);
    }

    /** 带 cause 的不可用:Kafka 调用超时、线程被中断等,根因链用于日志排查。 */
    public ServiceUnavailableException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
