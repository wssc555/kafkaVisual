package com.example.kafkaviz.inspection.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code POST /api/inspection/channels/{id}/test} 结果。
 *
 * <p>失败时必须给出<b>原因</b>(对端 HTTP 状态 / 连接失败 / 通道禁用),而不是
 * 一句"发送失败" —— 通道配置的排查成本几乎全花在"为什么没收到"上。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChannelTestResult {

    private boolean success;

    /** 失败原因;成功时为 null(JSON 中显式输出)。 */
    private String errorMessage;
}
