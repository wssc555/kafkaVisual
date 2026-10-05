package com.example.kafkaviz.exception;

import com.example.kafkaviz.kafka.ConsumerPoolExhaustedException;
import com.example.kafkaviz.model.vo.ApiResponse;
import org.apache.kafka.common.errors.InvalidConfigurationException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.TopicExistsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.concurrent.ExecutionException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ---- 参数校验失败 ----
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(40001, msg));
    }

    // ---- @ModelAttribute 绑定校验失败 —— Spring 6 对 @ModelAttribute + @Valid
    // 抛 BindException(MethodArgumentNotValidException 的父类),必须单独映射,否则落 50000 兜底
    @ExceptionHandler(org.springframework.validation.BindException.class)
    public ResponseEntity<ApiResponse<Void>> handleBind(org.springframework.validation.BindException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(40001, msg));
    }

    // ---- 线程中断(服务端关闭 / 请求取消 / Kafka poll 被中断) ----
    // 服务方法普遍声明 throws InterruptedException,若不显式映射会落 50000 兜底,
    // 把"服务端正在关闭"这种正常状态报成 Internal error。
    @ExceptionHandler(InterruptedException.class)
    public ResponseEntity<ApiResponse<Void>> handleInterrupted(InterruptedException ex) {
        // 恢复中断标志:调用方线程池后续仍可能被中断,必须保留语义
        Thread.currentThread().interrupt();
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error(50302, "Request interrupted, please retry later"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(40001, ex.getMessage()));
    }

    // ---- Topic 不存在 ----
    @ExceptionHandler(TopicNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleTopicNotFound(TopicNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(40401, ex.getMessage()));
    }

    // ---- 集群配置不存在 ----
    // 404/40404:避开 ZK 节点已占用的 40403。
    // 注意边界:配置不存在 → 40404;配置存在但连不上/正在连 → 50302(ServiceUnavailableException)。
    @ExceptionHandler(ClusterNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleClusterNotFound(ClusterNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(40404, ex.getMessage()));
    }

    // ---- 消费组不存在 ----
    @ExceptionHandler(ConsumerGroupNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleGroupNotFound(ConsumerGroupNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(40402, ex.getMessage()));
    }

    // ---- 消费组活跃成员,禁止重置/删除 ----
    @ExceptionHandler(GroupActiveException.class)
    public ResponseEntity<ApiResponse<Void>> handleGroupActive(GroupActiveException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(40902, ex.getMessage()));
    }

    // ---- Topic 已存在 ----
    @ExceptionHandler(TopicExistsException.class)
    public ResponseEntity<ApiResponse<Void>> handleTopicExists(TopicExistsException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(40901, "Topic already exists: " + ex.getMessage()));
    }

    // ---- 消费者池耗尽 ----
    @ExceptionHandler(ConsumerPoolExhaustedException.class)
    public ResponseEntity<ApiResponse<Void>> handlePoolExhausted(ConsumerPoolExhaustedException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error(50301, ex.getMessage()));
    }

    // ---- 服务暂时不可用(线程中断等,服务端关闭/请求取消场景) ----
    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> handleServiceUnavailable(ServiceUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error(50302, ex.getMessage()));
    }

    // ---- ExecutionException (Kafka Admin 调用失败) ----
    @ExceptionHandler(ExecutionException.class)
    public ResponseEntity<ApiResponse<Void>> handleExecution(ExecutionException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof TopicExistsException) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error(40901, "Topic already exists: " + cause.getMessage()));
        }
        // .all().get() 会把配置错误 INVALID_CONFIG 包进 ExecutionException,必须解包映射 40004
        if (cause instanceof InvalidConfigurationException) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(40004, "Invalid topic config: " + cause.getMessage()));
        }
        log.error("ExecutionException", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(50001, "Kafka operation failed: " + ex.getMessage()));
    }

    // ---- Kafka 配置键值非法(直接抛出时) ----
    @ExceptionHandler(InvalidConfigurationException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidConfig(InvalidConfigurationException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(40004, "Invalid topic config: " + ex.getMessage()));
    }

    // ---- 消息过大(客户端输入问题归 4xx)----
    // RecordTooLargeException 继承自 KafkaException,必须显式声明,否则被 handleKafka 吞成 50001
    @ExceptionHandler(RecordTooLargeException.class)
    public ResponseEntity<ApiResponse<Void>> handleRecordTooLarge(RecordTooLargeException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(40001, "Message too large: " + ex.getMessage()));
    }

    // ---- Kafka 通用异常 ----
    @ExceptionHandler(org.apache.kafka.common.KafkaException.class)
    public ResponseEntity<ApiResponse<Void>> handleKafka(org.apache.kafka.common.KafkaException ex) {
        log.error("KafkaException", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(50001, "Kafka error: " + ex.getMessage()));
    }

    // ---- 兜底 ----
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneral(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(50000, "Internal error: " + ex.getMessage()));
    }
}