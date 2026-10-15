package com.example.kafkaviz.inspection.alert;

import com.example.kafkaviz.inspection.support.ChannelConfig;

/**
 * 告警发送器 SPI。
 *
 * <p>实现类以 {@code @Component} 入列,{@code AlertDispatcher} 按 {@link #type()}
 * 路由。新增通道只加一个实现类,分发器与引擎都不用改。
 *
 * <p><b>实现纪律</b>:
 * <ul>
 *   <li>不抛异常 —— 一律返回 {@link SendOutcome};一个通道的失败不能影响其他通道,</li>
 *   <li>自行保证超时(HTTP 走带超时的 {@code RestClient},SMTP 走 mail 属性超时),</li>
 *   <li>不写 {@code alert_record}(那是状态机的职责),只关心"把内容送出去"。</li>
 * </ul>
 */
public interface AlertSender {

    /** 通道类型:{@code INSITE} / {@code EMAIL} / {@code WEBHOOK} / {@code IM_BOT}。 */
    String type();

    SendOutcome send(AlertMessage message, ChannelConfig config);
}
