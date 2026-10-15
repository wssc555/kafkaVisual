package com.example.kafkaviz.inspection.alert;

import com.example.kafkaviz.inspection.model.AlertChannel;
import com.example.kafkaviz.inspection.model.AlertDispatchLog;
import com.example.kafkaviz.inspection.store.AlertChannelStore;
import com.example.kafkaviz.inspection.store.AlertDispatchLogStore;
import com.example.kafkaviz.inspection.support.ChannelConfigCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * 告警分发:按规则关联的通道逐个发送,成败进 {@code alert_dispatch_log}。
 *
 * <p>四条硬纪律:
 * <ol>
 *   <li><b>异步</b>:提交 {@code alertSenderExecutor},慢通道不占用 ticker 线程;</li>
 *   <li><b>逐通道独立 try/catch</b>:一个通道挂了不影响其他通道,也不回滚状态机
 *       (告警已经发生,发送失败不该让状态机以为"没发生过");</li>
 *   <li><b>池满即记为 FAILED</b>:{@code AbortPolicy} 的拒绝不抛、不阻塞、不重试 ——
 *       重试会把压力叠加到已经拥塞的池上;</li>
 *   <li><b>全失败只记后台日志(50002)</b>:告警是异步后台任务,没有调用方可返回,
 *       强行回写 HTTP 响应只会把错误发给"碰巧在刷页面的那个人"。</li>
 * </ol>
 */
@Component
public class AlertDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AlertDispatcher.class);

    /** 单条失败原因的展示上限(与 AlertDispatchLogStore 的落库截断同值)。 */
    private static final int ERROR_LIMIT = 1000;

    private final AlertChannelStore channelStore;
    private final AlertDispatchLogStore dispatchLogStore;
    private final ChannelConfigCodec codec;
    private final Map<String, AlertSender> sendersByType = new HashMap<>();
    private final ExecutorService alertSenderExecutor;

    public AlertDispatcher(AlertChannelStore channelStore,
                           AlertDispatchLogStore dispatchLogStore,
                           ChannelConfigCodec codec,
                           List<AlertSender> senders,
                           ExecutorService alertSenderExecutor) {
        this.channelStore = channelStore;
        this.dispatchLogStore = dispatchLogStore;
        this.codec = codec;
        this.alertSenderExecutor = alertSenderExecutor;
        for (AlertSender sender : senders) {
            sendersByType.put(sender.type(), sender);
        }
    }

    /** 去掉换行(防日志注入)并截断。 */
    private static String sanitize(String message) {
        if (message == null) {
            return null;
        }
        String flat = message.replaceAll("\\s+", " ").trim();
        return flat.length() > ERROR_LIMIT ? flat.substring(0, ERROR_LIMIT) + "..." : flat;
    }

    /**
     * 按通道 id 列表异步分发。
     *
     * <p>方法本身不抛异常:分发失败只体现在 {@code alert_dispatch_log} 与后台日志里。
     */
    public void dispatch(AlertMessage message, List<Long> channelIds) {
        if (channelIds == null || channelIds.isEmpty()) {
            return;
        }
        List<AlertChannel> channels;
        try {
            channels = channelStore.findByIds(channelIds);
        } catch (RuntimeException e) {
            // 查通道失败也要守住"不抛"契约:状态机的 markFiring/insert 已提交,
            // 这里抛出去只会把已发生的告警变成一条没人看见的后台堆栈
            log.error("Failed to load alert channels {} for alert {}: {}",
                    channelIds, message.alertId(), e.getMessage());
            return;
        }
        if (channels.isEmpty()) {
            return;
        }
        List<CompletableFuture<Boolean>> futures = new ArrayList<>(channels.size());
        for (AlertChannel channel : channels) {
            try {
                futures.add(CompletableFuture.supplyAsync(() -> sendOne(message, channel).success(),
                        alertSenderExecutor));
            } catch (RejectedExecutionException e) {
                // 池满:本通道记 FAILED,继续下一个(不抛、不阻塞、不回滚状态机)
                record(message, channel, SendOutcome.failed("sender queue full"));
                futures.add(CompletableFuture.completedFuture(false));
            }
        }
        // 全失败汇总(50002):只在最后一个发送线程上跑一次,不阻塞调用方(ticker)。
        // 单通道的失败原因已由 sendOne 各自落 alert_dispatch_log,这里只回答"是不是全挂了"。
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .whenComplete((ignored, throwable) -> {
                    if (throwable != null) {
                        // 正常路径到不了这里(sendOne 全捕获),防御性记录避免静默丢弃
                        log.warn("Alert dispatch aggregation failed for alert {}: {}",
                                message.alertId(), throwable.getMessage());
                        return;
                    }
                    boolean allFailed = true;
                    for (CompletableFuture<Boolean> future : futures) {
                        if (Boolean.TRUE.equals(future.join())) {
                            allFailed = false;
                            break;
                        }
                    }
                    if (allFailed) {
                        log.error("[50002] All {} alert channel(s) failed for alert {} (rule {}, scope {})",
                                futures.size(), message.alertId(), message.ruleId(), message.scopeId());
                    }
                });
    }

    /**
     * 单通道同步发送(通道 test 端点用)。
     *
     * <p>test 路径 {@code alertId == null},因此不会写 {@code alert_dispatch_log}
     * (那条日志是"某条告警发给了某通道",测试告警不是一条告警)。
     */
    public SendOutcome dispatchTo(AlertMessage message, AlertChannel channel) {
        if (!channel.enabled()) {
            return SendOutcome.failed("channel is disabled: " + channel.name());
        }
        return sendOne(message, channel, false);
    }

    private SendOutcome sendOne(AlertMessage message, AlertChannel channel) {
        return sendOne(message, channel, true);
    }

    private SendOutcome sendOne(AlertMessage message, AlertChannel channel, boolean persist) {
        if (!channel.enabled()) {
            SendOutcome outcome = SendOutcome.failed("channel is disabled: " + channel.name());
            if (persist) {
                record(message, channel, outcome);
            }
            return outcome;
        }
        AlertSender sender = sendersByType.get(channel.type());
        if (sender == null) {
            SendOutcome outcome = SendOutcome.failed("no sender registered for channel type " + channel.type());
            if (persist) {
                record(message, channel, outcome);
            }
            return outcome;
        }
        SendOutcome outcome;
        try {
            var config = codec.decrypt(channel.type(), channel.configJson());
            outcome = sender.send(message, config);
        } catch (Exception e) {
            // sender 不该抛,但配置解密失败(CryptoService)等仍可能到这里
            outcome = SendOutcome.failed(e.getMessage());
        }
        if (persist) {
            record(message, channel, outcome);
        } else if (!outcome.success()) {
            log.warn("Channel test failed for '{}' ({}): {}", channel.name(), channel.type(),
                    outcome.errorMessage());
        }
        if (!outcome.success() && persist) {
            log.error("[50002] Alert channel '{}' ({}) failed: {}", channel.name(), channel.type(),
                    outcome.errorMessage());
        }
        return outcome;
    }

    private void record(AlertMessage message, AlertChannel channel, SendOutcome outcome) {
        if (message.alertId() == null) {
            return; // 通道 test:无告警行可关联
        }
        try {
            dispatchLogStore.insert(message.alertId(), channel.id(),
                    outcome.success() ? AlertDispatchLog.SUCCESS : AlertDispatchLog.FAILED,
                    outcome.success() ? null : sanitize(outcome.errorMessage()));
        } catch (RuntimeException e) {
            // 日志写失败不能吞掉发送结果,记一行即可
            log.warn("Failed to persist dispatch log for alert {} channel {}: {}",
                    message.alertId(), channel.id(), e.getMessage());
        }
    }
}
