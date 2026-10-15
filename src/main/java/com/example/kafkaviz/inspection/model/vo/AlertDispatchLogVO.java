package com.example.kafkaviz.inspection.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单通道的发送结果(告警详情里的分发日志行)。
 *
 * <p>失败原因是<b>脱敏后</b>的(换行被压平、长度被截断、URL query 不进库)——
 * 对端返回的错误体可能包含 HTTP 响应片段,不该原样留存在用户库里。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertDispatchLogVO {

    private long id;

    private long alertId;

    private long channelId;

    private String channelName;

    /** {@code SUCCESS} / {@code FAILED}。 */
    private String status;

    private String errorMessage;

    private String createdAt;
}
