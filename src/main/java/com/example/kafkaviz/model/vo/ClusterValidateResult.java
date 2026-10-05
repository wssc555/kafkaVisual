package com.example.kafkaviz.model.vo;

import lombok.Builder;
import lombok.Data;

/**
 * {@code POST /api/clusters/validate} 的成功结果。
 *
 * <p><b>为什么失败不进这个对象</b>:与 {@code /api/storage/test} 的
 * {@code success=false} 风格不同,本端点沿用"错误码 + HTTP 状态"的口径
 * (校验失败 40001 / 连接超时 50302 / 认证或握手失败 50001):前端拦截器
 * 已把 code 挂到 {@code ApiError} 上,组件按码精确提示。失败时本对象不出现。
 */
@Data
@Builder
public class ClusterValidateResult {

    /** 恒为 true(失败会抛异常而不是回 false)。 */
    private boolean ok;

    /** 探测到的 broker 数量 —— 比"成功了"更能说明认证与网络确实通了。 */
    private int brokerCount;

    /** 建连 + describeCluster 的总耗时(ms)。 */
    private long elapsedMs;
}
