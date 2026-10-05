package com.example.kafkaviz.kafka;

import com.example.kafkaviz.exception.ServiceUnavailableException;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.KafkaFuture;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Kafka 异步调用的统一收口:超时等待 + 并行编排 + 异常还原。
 *
 * <p>存在两个动机:
 * <ol>
 *   <li><b>超时</b>:无参 {@code .get()} 的阻塞上限由客户端默认 60s 决定,
 *       慢/失联集群下会占满 Tomcat 线程池,连不碰 Kafka 的端点也一起无响应。
 *       统一走 {@link #await} 后,超时转 {@link ServiceUnavailableException}(50302)。</li>
 *   <li><b>异常还原</b>:{@code CompletableFuture} 会把受检异常包成 {@code CompletionException},
 *       若不做还原,{@code GlobalExceptionHandler} 中 {@code ExecutionException} → 40401/40901/40004
 *       的映射会全部失效、退化成 50000。{@link #join} 负责逐层解包。</li>
 * </ol>
 *
 * <p>本类只做工具,保持静态、无状态,不注册为 Spring Bean。
 */
public final class KafkaFutures {

    private KafkaFutures() {
    }

    /**
     * 带超时等待 future;超时抛 {@link ServiceUnavailableException}(50302)。
     *
     * @param op 用于定位具体 RPC 的操作名,如 {@code describeTopics:my-topic}
     */
    public static <T> T await(KafkaFuture<T> future, long timeoutMs, String op)
            throws ExecutionException, InterruptedException {
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 不用 KafkaException:那会被映射成 50001,语义错误 —— 50302 才表达"后端在等 Kafka,可重试"。
            // TimeoutException 作为 cause 保留:503 响应体只有 msg,根因链留作日志排查用。
            throw new ServiceUnavailableException("Kafka call timed out after " + timeoutMs + "ms: " + op, e);
        }
    }

    /**
     * 把可抛受检异常的任务塞进 {@code CompletableFuture},由 {@link #join} 统一还原。
     *
     * <p>{@code RuntimeException} 原样抛出,不做额外包装(如 {@code TopicNotFoundException} 等
     * 自定义业务异常在 {@code join} 后可零损失还原)。
     */
    public static <T> CompletableFuture<T> supply(CheckedSupplier<T> task, Executor executor) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.get();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    /**
     * 等待并行任务完成,并把 {@link #supply} 中包装的异常还原为原始类型,
     * 保持服务方法原有的 {@code throws ExecutionException, InterruptedException} 语义。
     */
    public static <T> T join(CompletableFuture<T> future)
            throws ExecutionException, InterruptedException {
        try {
            // 用 join() 而非 get():无参 get() 会再包一层 ExecutionException,还原层次更乱
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ExecutionException executionException) {
                throw executionException;
            }
            if (cause instanceof InterruptedException interruptedException) {
                throw interruptedException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new KafkaException("Kafka async call failed", cause);
        } catch (CancellationException e) {
            throw new KafkaException("Kafka async call cancelled", e);
        }
    }

    /** 可抛受检异常的任务,供 {@link #supply} 使用。 */
    @FunctionalInterface
    public interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}
