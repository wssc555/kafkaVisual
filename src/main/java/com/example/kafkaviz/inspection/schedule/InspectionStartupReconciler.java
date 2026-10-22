package com.example.kafkaviz.inspection.schedule;

import com.example.kafkaviz.config.ClusterSeedImporter;
import com.example.kafkaviz.inspection.engine.RuleEngine;
import com.example.kafkaviz.inspection.model.InspectionRule;
import com.example.kafkaviz.inspection.store.InspectionRuleStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动对账(DB 是唯一调度事实源)。
 *
 * <p>停机期间没有 ticker,{@code next_fire_at} 会停在过去的某个时刻。启动时:
 * <ul>
 *   <li>{@code next_fire_at} 为空 —— 按 cron 现算(新建/被手工清空的规则);</li>
 *   <li>{@code next_fire_at} 在过去 —— <b>立即补评一次</b>,然后重排到未来。</li>
 * </ul>
 *
 * <p>"补评会不会刷屏":不会 —— 状态机去重保证停机期间持续 FIRING 的问题
 * 不会产生第二条告警,恢复态也不会重复发恢复通知。这正是把去重放在状态机
 * 而不是调度层的好处。
 *
 * <p><b>为什么本类不带 {@code @ConditionalOnProperty}</b>(与 ticker / 控制器不同):
 * 集成测试上下文注入了 {@code inspection.enabled=false}(避免常驻 ticker 污染
 * 共享上下文),而"重启续跑"用例必须直接调用 {@link #reconcile()} —— 条件注册
 * 会让测试上下文里根本没有这个 Bean。故无条件注册 + {@link #run} 内检查开关:
 * 开关关闭时启动对账直接空转,行为与"不注册"等价。补评走
 * {@code RuleEngine#evaluate(InspectionRule)},与 ticker 共用 in-flight 守卫,
 * 不会和首轮 tick 并发重评同一条规则。
 *
 * <p>{@code @Order} 必须晚于 {@link ClusterSeedImporter}:种子导入之后集群数才是
 * 最终值(与 {@code StartupValidator} 取 {@code ORDER + 10} 同一思路)。
 */
@Component
@Order(ClusterSeedImporter.ORDER + 20)
public class InspectionStartupReconciler implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InspectionStartupReconciler.class);

    private final InspectionRuleStore ruleStore;
    private final RuleEngine engine;
    /** 巡检总开关(与 ticker/控制器的 @ConditionalOnProperty 同一属性)。 */
    private final boolean inspectionEnabled;

    public InspectionStartupReconciler(InspectionRuleStore ruleStore, RuleEngine engine,
                                       @Value("${inspection.enabled:true}") boolean inspectionEnabled) {
        this.ruleStore = ruleStore;
        this.engine = engine;
        this.inspectionEnabled = inspectionEnabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!inspectionEnabled) {
            return;
        }
        int reconciled = reconcile();
        if (reconciled > 0) {
            log.info("Inspection startup reconciliation done for {} enabled rule(s)", reconciled);
        }
    }

    /**
     * 对账全部启用规则。
     *
     * <p>公开入口主要给测试用:集成测试不重启上下文(共享 Spring 上下文是硬约定),
     * 直接调用本方法即可验证"重启续跑"语义。单条规则的异常(如库中 cron 被手工
     * 改坏)只跳过该规则,不让对账失败拖垮应用启动。
     *
     * @return 被补评(立即评估)的规则数
     */
    public int reconcile() {
        List<InspectionRule> rules;
        try {
            rules = ruleStore.listEnabled();
        } catch (RuntimeException e) {
            log.warn("Inspection startup reconciliation skipped: {}", e.getMessage());
            return 0;
        }
        String now = RuleCron.now();
        int catchUp = 0;
        for (InspectionRule rule : rules) {
            try {
                if (rule.nextFireAt() == null || rule.nextFireAt().isBlank()) {
                    String next = RuleCron.nextFireAt(rule.cronExpr());
                    if (next == null) {
                        log.warn("Rule {} cron '{}' never fires again — next_fire_at stays empty",
                                rule.id(), rule.cronExpr());
                    }
                    ruleStore.updateNextFireAt(rule.id(), next);
                    continue;
                }
                if (rule.nextFireAt().compareTo(now) > 0) {
                    continue; // 未到期,保持原排期
                }
                // 过期:先重排(避免补评期间被 ticker 再命中一次),再补评
                String next = RuleCron.nextFireAt(rule.cronExpr());
                if (next == null) {
                    log.warn("Rule {} cron '{}' never fires again — next_fire_at cleared",
                            rule.id(), rule.cronExpr());
                }
                ruleStore.updateNextFireAt(rule.id(), next);
                if (engine.evaluate(rule)) {
                    catchUp++;
                }
            } catch (RuntimeException e) {
                // 单条规则坏数据(手工改库等)不能让整个应用起不来
                log.error("Catch-up reconciliation failed for rule {} ({}): {}",
                        rule.id(), rule.name(), e.getMessage());
            }
        }
        return catchUp;
    }
}
