package com.example.kafkaviz.inspection.schedule;

import com.example.kafkaviz.inspection.engine.RuleEngine;
import com.example.kafkaviz.inspection.model.InspectionRule;
import com.example.kafkaviz.inspection.store.InspectionRuleStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * 巡检调度器:1s 单线程 tick,扫到期规则 → 异步评估。
 *
 * <p>为什么不是 Quartz / Spring {@code @Scheduled(cron)}:
 * 后两者的 cron 是<b>编译期注解</b>里的常量,而这里的周期是用户在 UI 里填的、
 * 存在 {@code inspection_rule.cron_expr} 里的运行期数据;规则还能随时增删改。
 * 因此调度事实源只能是库里的 {@code next_fire_at} —— ticker 只负责"到期就派发"。
 *
 * <p>三个关键设计:
 * <ol>
 *   <li><b>同 tick 内先落新的 next_fire_at 再派发</b>:评估比周期慢时(大集群、
 *       慢 Kafka)不会重复触发同一条规则;in-flight 的规则也会被重排 ——
 *       长评估期间的到期只会顺延、不积累,评估结束后从下一个周期继续;</li>
 *   <li><b>in-flight 守卫在引擎里</b>({@code RuleEngine#evaluate(InspectionRule)}):
 *       ticker 派发与启动对账的补评共用同一把守卫,不会并发重评同一条规则;</li>
 *   <li><b>池满即跳过 + 单条异常不炸整轮</b>:{@code AbortPolicy} 的拒绝转成 warn;
 *       某条规则的 next_fire_at 计算/落库失败也只影响它自己,其余到期规则照常派发。</li>
 * </ol>
 */
@Component
@ConditionalOnProperty(name = "inspection.enabled", havingValue = "true", matchIfMissing = true)
public class InspectionTicker {

    /** 单轮最多派发多少条到期规则(防止一次 tick 把池打满后长时间空转)。 */
    private static final int MAX_PER_TICK = 64;

    private static final Logger log = LoggerFactory.getLogger(InspectionTicker.class);

    private final InspectionRuleStore ruleStore;
    private final RuleEngine engine;
    private final ExecutorService inspectionExecutor;

    public InspectionTicker(InspectionRuleStore ruleStore, RuleEngine engine,
                            @Qualifier("inspectionExecutor") ExecutorService inspectionExecutor) {
        this.ruleStore = ruleStore;
        this.engine = engine;
        this.inspectionExecutor = inspectionExecutor;
    }

    /**
     * 一轮 tick。
     *
     * <p>{@code fixedDelay} 而非 {@code fixedRate}:慢 tick 会推迟下一轮,而不是
     * 让多轮叠在一起 —— 巡检不需要"补偿式狂奔"。
     */
    @Scheduled(fixedDelayString = "${inspection.scheduler.tick-ms:1000}")
    public void tick() {
        List<InspectionRule> due;
        try {
            due = ruleStore.findDue(RuleCron.now(), MAX_PER_TICK);
        } catch (RuntimeException e) {
            // 到期查询失败(库瞬时不可达)不能让调度线程死掉:下一轮再试。
            log.warn("Inspection due-rule query failed: {}", e.getMessage());
            return;
        }
        if (due.isEmpty()) {
            return;
        }

        for (InspectionRule rule : due) {
            // 单条失败(如库中 cron 被手工改坏)只跳过该规则,不中断本轮其余派发
            try {
                // 1) 先排下次触发时间 —— 即使本轮评估失败/超慢,也不会重复触发;
                //    表达式不再触发(next=null)时按"不再排期"落库,记 warn 提示
                String next = RuleCron.nextFireAt(rule.cronExpr());
                if (next == null) {
                    log.warn("Rule {} cron '{}' never fires again — next_fire_at cleared", rule.id(), rule.cronExpr());
                }
                ruleStore.updateNextFireAt(rule.id(), next);

                // 2) 异步评估;in-flight / 池满的取舍见类注释
                inspectionExecutor.execute(() -> engine.evaluate(rule));
            } catch (RejectedExecutionException e) {
                log.warn("Inspection executor queue full — rule {} skipped this round", rule.id());
            } catch (RuntimeException e) {
                log.warn("Inspection dispatch failed for rule {}: {}", rule.id(), e.getMessage());
            }
        }
    }
}
