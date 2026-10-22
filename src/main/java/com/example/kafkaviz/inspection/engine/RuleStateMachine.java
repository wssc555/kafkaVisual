package com.example.kafkaviz.inspection.engine;

import com.example.kafkaviz.inspection.alert.AlertDispatcher;
import com.example.kafkaviz.inspection.alert.AlertMessage;
import com.example.kafkaviz.inspection.model.AlertRecord;
import com.example.kafkaviz.inspection.model.RuleStateRow;
import com.example.kafkaviz.inspection.store.AlertRecordStore;
import com.example.kafkaviz.inspection.store.RuleStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * (rule, scope) 状态机 —— 告警去重的落点。
 *
 * <table border="1">
 *   <tr><th>当前</th><th>本轮 fired</th><th>动作</th></tr>
 *   <tr><td>OK</td><td>true</td><td>→ FIRING:写 alert_record(FIRING)、发告警</td></tr>
 *   <tr><td>FIRING</td><td>true</td><td>抑制:不重发,仅节流更新 last_evaluated_at</td></tr>
 *   <tr><td>FIRING</td><td>false</td><td>→ OK:回填 recovered_at、写 alert_record(RECOVERED)、发恢复</td></tr>
 *   <tr><td>OK</td><td>false</td><td>no-op(节流更新 last_evaluated_at)</td></tr>
 * </table>
 *
 * <p>写放大控制:{@code last_evaluated_at} 每规则至多每分钟写一次(内存记上次写时间,
 * 重启丢精度可接受 —— 它只用于 UI 展示"最近评估时刻",不参与任何判定)。
 * 100 规则 × 50 scope × 30s 周期下,这条节流把 SQLite 单写者的写入量降了两个数量级。
 */
@Component
public class RuleStateMachine {

    /** last_evaluated_at 的最小写入间隔。 */
    private static final long THROTTLE_MS = 60_000L;

    private static final Logger log = LoggerFactory.getLogger(RuleStateMachine.class);

    private final RuleStateStore stateStore;
    private final AlertRecordStore alertStore;
    private final AlertDispatcher dispatcher;
    /**
     * 状态行与告警行的原子写:markFiring 与 alert_record 插入要么都成功要么都不落库。
     * 不做原子化的后果 —— 状态已写、告警插入失败 → rule_state=FIRING 但无告警行,
     * 此后每轮都走抑制分支,该故障<b>永远</b>不会再产生告警(且无任何告警行可 ack)。
     */
    private final TransactionTemplate transactionTemplate;
    /** ruleId → 上次写 last_evaluated_at 的 epoch millis(纯内存,重启即丢)。 */
    private final ConcurrentHashMap<Long, Long> lastTouchAt = new ConcurrentHashMap<>();

    public RuleStateMachine(RuleStateStore stateStore, AlertRecordStore alertStore,
                            AlertDispatcher dispatcher, TransactionTemplate transactionTemplate) {
        this.stateStore = stateStore;
        this.alertStore = alertStore;
        this.dispatcher = dispatcher;
        this.transactionTemplate = transactionTemplate;
    }

    private static String title(String ruleName, String scopeId, boolean firing) {
        return (firing ? "[FIRING] " : "[RECOVERED] ") + ruleName + " @ " + scopeId;
    }

    private static String message(String ruleName, String severity, String scopeId,
                                  Map<String, String> metricValues) {
        StringBuilder sb = new StringBuilder();
        sb.append("rule=").append(ruleName)
                .append(", severity=").append(severity)
                .append(", scope=").append(scopeId);
        if (metricValues != null && !metricValues.isEmpty()) {
            List<String> pairs = new ArrayList<>();
            for (Map.Entry<String, String> e : metricValues.entrySet()) {
                pairs.add(e.getKey() + "=" + e.getValue());
            }
            sb.append(", metrics=[").append(String.join(", ", pairs)).append("]");
        }
        return sb.toString();
    }

    /**
     * 处理一次状态转移。
     *
     * <p>本方法不抛业务异常:告警是后台任务,状态机挂了不该把 ticker 线程带崩;
     * 采集异常在更上游(引擎)就已经转成 {@code InspectionExecutionException} 了。
     */
    public void transit(long ruleId, long clusterId, String ruleName, String severity, String scopeId,
                        boolean fired, Map<String, String> metricValues, List<Long> channelIds) {
        Optional<RuleStateRow> state = stateStore.find(ruleId, scopeId);
        boolean firing = state.map(RuleStateRow::firing).orElse(false);

        try {
            if (fired && !firing) {
                // 状态行 + 告警行原子写:任何一步失败整体回滚,下一轮重试
                AlertRecord record = transactionTemplate.execute(tx -> {
                    stateStore.markFiring(ruleId, scopeId);
                    return alertStore.insert(ruleId, clusterId, scopeId, severity,
                            AlertRecord.FIRING, title(ruleName, scopeId, true),
                            message(ruleName, severity, scopeId, metricValues));
                });
                lastTouchAt.put(ruleId, System.currentTimeMillis());
                log.info("Rule {} ({}) fired on scope {}", ruleId, ruleName, scopeId);
                dispatch(record, ruleName, severity, scopeId, metricValues, channelIds);
                return;
            }
            if (fired) {
                throttleTouch(ruleId, scopeId);
                return;
            }
            if (firing) {
                // 恢复三写原子化:状态回 OK + 回填旧 FIRING 行 recovered_at + 写 RECOVERED 行。
                // RECOVERED 行本身不带 recovered_at(恢复时刻以回填的旧行为准),
                // 这样"今日恢复数"按 recovered_at 统计才不会一次恢复计两行。
                AlertRecord record = transactionTemplate.execute(tx -> {
                    stateStore.markRecovered(ruleId, scopeId);
                    alertStore.markRecovered(ruleId, scopeId);
                    return alertStore.insert(ruleId, clusterId, scopeId, severity,
                            AlertRecord.RECOVERED, title(ruleName, scopeId, false),
                            message(ruleName, severity, scopeId, metricValues));
                });
                lastTouchAt.put(ruleId, System.currentTimeMillis());
                log.info("Rule {} ({}) recovered on scope {}", ruleId, ruleName, scopeId);
                dispatch(record, ruleName, severity, scopeId, metricValues, channelIds);
                return;
            }
            throttleTouch(ruleId, scopeId);
        } catch (RuntimeException e) {
            log.error("Rule {} state transition failed on scope {}: {}", ruleId, scopeId, e.getMessage(), e);
        }
    }

    /** 持续态(抑制 / 持续 OK):只节流推进 last_evaluated_at。 */
    private void throttleTouch(long ruleId, String scopeId) {
        long now = System.currentTimeMillis();
        Long previous = lastTouchAt.get(ruleId);
        if (previous != null && now - previous < THROTTLE_MS) {
            return;
        }
        lastTouchAt.put(ruleId, now);
        stateStore.touchEvaluated(ruleId, scopeId);
    }

    private void dispatch(AlertRecord record, String ruleName, String severity, String scopeId,
                          Map<String, String> metricValues, List<Long> channelIds) {
        if (channelIds == null || channelIds.isEmpty()) {
            return;
        }
        AlertMessage message = new AlertMessage(record.id(), record.ruleId(), record.clusterId(),
                scopeId, severity, record.state(), record.title(), record.message(), metricValues);
        dispatcher.dispatch(message, channelIds);
    }
}
