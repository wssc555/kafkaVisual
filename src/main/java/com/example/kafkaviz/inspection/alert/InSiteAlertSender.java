package com.example.kafkaviz.inspection.alert;

import com.example.kafkaviz.inspection.model.AlertChannel;
import com.example.kafkaviz.inspection.service.AlertRecordService;
import com.example.kafkaviz.inspection.support.ChannelConfig;
import org.springframework.stereotype.Component;

/**
 * 站内信:告警中心本身就是 {@code alert_record} 的视图,"投递"= 该行已存在。
 *
 * <p>为什么本 sender <b>不负责创建</b>告警行:行由 {@code RuleStateMachine} 在状态
 * 转移时经 {@link com.example.kafkaviz.inspection.store.AlertRecordStore} 写入 —— 它是
 * 审计记录,无论规则配了什么通道都必须存在(否则未读数、恢复回填、按集群过滤
 * 全都无从谈起)。本 sender 只做"行在不在"的确认,这样通道 test 那条路径
 * ({@code alertId == null},按 030 的约定不写 alert_record)与规则路径可以共用
 * 同一份实现而不产生脏行。
 */
@Component
public class InSiteAlertSender implements AlertSender {

    private final AlertRecordService alertRecordService;

    public InSiteAlertSender(AlertRecordService alertRecordService) {
        this.alertRecordService = alertRecordService;
    }

    @Override
    public String type() {
        return AlertChannel.INSITE;
    }

    @Override
    public SendOutcome send(AlertMessage message, ChannelConfig config) {
        if (message.alertId() == null) {
            // 通道 test 的直发路径:不经规则,不写 alert_record
            return SendOutcome.ok();
        }
        if (alertRecordService.exists(message.alertId())) {
            return SendOutcome.ok();
        }
        return SendOutcome.failed("alert record not found: " + message.alertId());
    }
}
