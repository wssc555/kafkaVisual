package com.example.kafkaviz.inspection.exception;

/**
 * 巡检执行错误(采集异常等)→ HTTP 500 / {@code 50004}。
 *
 * <p>只出现在两条路径:规则试运行端点(同步返回用户)与 ticker 的异步评估
 * (记日志,不返回给用户 —— 后台任务没有可返回的调用方)。
 */
public class InspectionExecutionException extends RuntimeException {

    public InspectionExecutionException(String message) {
        super(message);
    }

    public InspectionExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
