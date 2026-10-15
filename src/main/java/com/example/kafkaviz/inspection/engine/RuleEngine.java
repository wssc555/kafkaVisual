package com.example.kafkaviz.inspection.engine;

import com.example.kafkaviz.inspection.collector.CollectorRegistry;
import com.example.kafkaviz.inspection.collector.ScopeFilter;
import com.example.kafkaviz.inspection.exception.InspectionExecutionException;
import com.example.kafkaviz.inspection.metric.MetricCatalog;
import com.example.kafkaviz.inspection.metric.MetricScope;
import com.example.kafkaviz.inspection.model.InspectionRule;
import com.example.kafkaviz.inspection.store.InspectionRuleStore;
import com.example.kafkaviz.inspection.store.MetricSampleStore;
import com.example.kafkaviz.inspection.store.RuleChannelStore;
import com.example.kafkaviz.kafka.ClusterConnection;
import com.example.kafkaviz.kafka.ClusterConnectionManager;
import com.example.kafkaviz.kafka.KafkaFutures;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 规则引擎:解析 → 取连接 → 解析 scope 实例 → <b>批量采集</b> → 条件评估 → 状态机。
 *
 * <p>两条入口:
 * <ul>
 *   <li>{@link #evaluate(RuleDefinition)} —— 引擎本体。集群连不上时返回
 *       {@code skipped}(不进状态机:不触发、不恢复、不写任何行 —— 避免离线抖动
 *       造成"假恢复"刷屏);</li>
 *   <li>{@link #evaluateDryRun(RuleDefinition, ClusterConnection)} —— 试运行路径。
 *       连接由 {@code ClusterArgumentResolver} 预先解析(离线会先得到 50302,
 *       到不了这里);全程只读:不写采样、不写状态机、不发告警。</li>
 *   <li>{@link #evaluate(InspectionRule)} —— 调度入口(ticker 与启动对账共用)。
 *       内置 in-flight 守卫:同一规则同时至多一个评估在跑,守卫下沉到引擎后,
 *       ticker 派发与对账补评天然互斥,不会再出现"首轮 tick 与补评并发重评"。</li>
 * </ul>
 *
 * <p><b>并发</b>:除 in-flight 守卫外引擎自身不加锁。
 */
@Component
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    private final ClusterConnectionManager connectionManager;
    private final CollectorRegistry registry;
    private final MetricSampleStore sampleStore;
    private final RuleStateMachine stateMachine;
    private final InspectionRuleStore ruleStore;
    private final RuleChannelStore ruleChannelStore;
    private final RuleDslParser parser;
    private final long timeoutMs;
    private final int sampleRetentionBuffer;

    /** ruleId → 是否正在评估中(in-flight 守卫)。 */
    private final ConcurrentHashMap<Long, Boolean> inFlight = new ConcurrentHashMap<>();

    public RuleEngine(ClusterConnectionManager connectionManager,
                      CollectorRegistry registry,
                      MetricSampleStore sampleStore,
                      RuleStateMachine stateMachine,
                      InspectionRuleStore ruleStore,
                      RuleChannelStore ruleChannelStore,
                      RuleDslParser parser,
                      com.example.kafkaviz.config.KafkaProperties kafkaProperties,
                      com.example.kafkaviz.inspection.InspectionProperties inspectionProperties) {
        this.connectionManager = connectionManager;
        this.registry = registry;
        this.sampleStore = sampleStore;
        this.stateMachine = stateMachine;
        this.ruleStore = ruleStore;
        this.ruleChannelStore = ruleChannelStore;
        this.parser = parser;
        this.timeoutMs = kafkaProperties.getAdmin().getDefaultApiTimeoutMs();
        this.sampleRetentionBuffer = inspectionProperties.getSample().getRetentionBuffer();
    }

    /**
     * 调度入口(ticker 派发与启动对账补评共用):解析 + 评估 + in-flight 守卫。
     *
     * <p>本方法不向上抛业务异常:调度是后台任务,采集失败记日志即可(下一轮再试)。
     *
     * @return true = 确实发起了一次评估(含评估中途失败);false = 跳过
     *         (in-flight / metric_scope 未知)
     */
    public boolean evaluate(InspectionRule rule) {
        MetricScope scope = MetricScope.of(rule.metricScope());
        if (scope == null) {
            log.warn("Rule {} has unknown metric_scope '{}' — skipped", rule.id(), rule.metricScope());
            return false;
        }
        if (inFlight.putIfAbsent(rule.id(), Boolean.TRUE) != null) {
            log.debug("Rule {} is still evaluating — skip this round", rule.id());
            return false;
        }
        try {
            RuleDefinition def = parser.parse(rule.id(), rule.clusterId(), scope,
                    rule.scopeFilter(), rule.conditionJson());
            evaluate(def);
            return true;
        } catch (InspectionExecutionException e) {
            // 采集失败:后台任务没有可返回的调用方,记日志即可(下轮再试)
            log.error("Inspection execution failed for rule {} ({}): {}",
                    rule.id(), rule.name(), e.getMessage());
            return true;
        } catch (RuntimeException e) {
            log.error("Inspection evaluation failed for rule {} ({}): {}",
                    rule.id(), rule.name(), e.getMessage(), e);
            return true;
        } finally {
            inFlight.remove(rule.id());
        }
    }

    /**
     * ticker 路径:惰性取连接,离线则跳过。
     *
     * <p>这里刻意<b>吞掉</b>取连接的异常:集群不可达是正常业务状态(不是 bug),
     * 让它在 ticker 线程里冒出去只会污染日志。
     */
    public EvaluationResult evaluate(RuleDefinition def) {
        ClusterConnection connection;
        try {
            connection = connectionManager.getOrConnect(def.clusterId());
        } catch (RuntimeException e) {
            log.debug("Skip rule {} evaluation: cluster {} is not reachable ({})",
                    def.ruleId(), def.clusterId(), e.getMessage());
            return EvaluationResult.skip();
        }
        return evaluate(def, connection, true);
    }

    /** 试运行:全程只读(不写采样、不写状态机、不发告警)。 */
    public EvaluationResult evaluateDryRun(RuleDefinition def, ClusterConnection connection) {
        return evaluate(def, connection, false);
    }

    private EvaluationResult evaluate(RuleDefinition def, ClusterConnection connection, boolean persist) {
        if (def.scope() == MetricScope.ZK && !connection.zkAvailable()) {
            // 残留的 ZK 规则(集群 ZK 配置被摘掉):创建时已拦 40005,运行期只能跳过
            log.debug("Skip rule {} : ZK scope on a cluster without ZooKeeper", def.ruleId());
            return EvaluationResult.skip();
        }

        List<String> instances;
        try {
            instances = resolveScopeInstances(def, connection);
        } catch (Exception e) {
            throw new InspectionExecutionException(
                    "Failed to resolve scope instances for rule " + def.ruleId() + ": " + e.getMessage(), e);
        }
        if (instances.isEmpty()) {
            return new EvaluationResult(false, false, List.of(), Map.of(), false);
        }

        Map<String, Map<String, String>> values;
        try {
            values = registry.collect(def.scope(), connection, instances, def.metricKeys());
        } catch (Exception e) {
            throw new InspectionExecutionException(
                    "Metric collection failed for rule " + def.ruleId() + ": " + e.getMessage(), e);
        }

        // 规则行与通道关联和 scope 无关 —— 循环外取一次,避免 50 scope = 100 次重复查询
        InspectionRule rule = null;
        List<Long> channelIds = List.of();
        if (persist) {
            rule = ruleStore.findById(def.ruleId()).orElse(null);
            if (rule == null) {
                log.debug("Rule {} disappeared during evaluation — skipping state transitions", def.ruleId());
            } else {
                channelIds = ruleChannelStore.channelIdsOf(def.ruleId());
            }
        }

        List<String> firedScopes = new ArrayList<>();
        boolean insufficient = false;
        // 保留预算 = 全部窗口条件的 count 之和 + buffer:滚动删除按 (rule, scope) 计数,
        // 而窗口查询按 (rule, metric, scope) 各要 count 条 —— 预算必须装下每个指标的窗口
        int keep = def.totalWindowCount() + sampleRetentionBuffer;

        try {
            for (String scopeId : instances) {
                Map<String, String> scopeValues = values.getOrDefault(scopeId, Map.of());
                boolean anyInsufficient = false;
                boolean fired = RuleDefinition.AND.equals(def.combinator());

                for (RuleCondition condition : def.conditions()) {
                    String current = scopeValues.get(condition.metricKey());
                    Boolean result;
                    if (condition.hasWindow()) {
                        if (persist && current != null) {
                            sampleStore.insertNow(def.ruleId(), condition.metricKey(), scopeId, current);
                        }
                        result = evaluateWindow(def, condition, scopeId, current);
                    } else {
                        result = ThresholdEvaluator.evaluate(
                                condition.metricKey(), condition.operator(), condition.threshold(), current);
                    }
                    if (result == null) {
                        anyInsufficient = true;
                        continue;
                    }
                    if (RuleDefinition.AND.equals(def.combinator())) {
                        fired = fired && result;
                    } else {
                        fired = fired || result;
                    }
                }

                // 全瞬时规则没有采样行,滚动删除是纯空转,跳过
                if (persist && def.totalWindowCount() > 0) {
                    sampleStore.keepLatest(def.ruleId(), scopeId, keep);
                }

                if (anyInsufficient) {
                    insufficient = true;
                    // 窗口预热期 / 值口径不符:本轮<b>不可判定</b> → 不进状态机
                    //(不触发、也不恢复 —— FIRING 中的规则不能因为一次取不到值就"假恢复")。
                    // 宁可漏报不误报:把"样本还没攒够"当成"条件不满足"会漏报,
                    // 当成"条件满足"又会在零数据的第一轮就告警。
                    log.warn("Rule {} scope {} : insufficient samples — no transition this round",
                            def.ruleId(), scopeId);
                    continue;
                }

                if (fired) {
                    firedScopes.add(scopeId);
                }
                if (persist && rule != null) {
                    // 规则行的 name/severity 与通道关联已在循环外取出(引擎定义里刻意不带这些)
                    stateMachine.transit(def.ruleId(), def.clusterId(), rule.name(), rule.severity(),
                            scopeId, fired, scopeValues, channelIds);
                }
            }
        } catch (DataAccessException e) {
            // 采样/状态落库失败:与采集失败同一出口(50004 语义),由调用方决定怎么记
            throw new InspectionExecutionException(
                    "Inspection persistence failed for rule " + def.ruleId() + ": " + e.getMessage(), e);
        }

        return new EvaluationResult(false, !firedScopes.isEmpty(), firedScopes, values, insufficient);
    }

    /**
     * 窗口评估:{@code ALL} = 最近 N 条全部满足;{@code ANY} = 至少一条满足。
     *
     * <p>样本不足 N 条 → 返回 {@code null}(不可判定),由调用方标记 insufficient。
     */
    private Boolean evaluateWindow(RuleDefinition def, RuleCondition condition, String scopeId,
                                   String current) {
        int count = condition.windowCount();
        var samples = sampleStore.latest(def.ruleId(), condition.metricKey(), scopeId, count);
        if (samples == null || samples.size() < count) {
            return null;
        }
        if (RuleCondition.ANY.equals(condition.windowMatch())) {
            for (var sample : samples) {
                Boolean r = ThresholdEvaluator.evaluate(
                        condition.metricKey(), condition.operator(), condition.threshold(), sample.value());
                if (Boolean.TRUE.equals(r)) {
                    return true;
                }
            }
            return false;
        }
        for (var sample : samples) {
            Boolean r = ThresholdEvaluator.evaluate(
                    condition.metricKey(), condition.operator(), condition.threshold(), sample.value());
            if (!Boolean.TRUE.equals(r)) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------
    // scope 实例解析
    // ---------------------------------------------------------------

    private List<String> resolveScopeInstances(RuleDefinition def, ClusterConnection connection)
            throws Exception {
        var filter = ScopeFilter.parse(def.scopeFilter());
        return switch (def.scope()) {
            case CLUSTER -> List.of(MetricCatalog.CLUSTER_SCOPE_ID);
            case TOPIC -> filter.apply(listTopicNames(connection));
            case CONSUMER_GROUP -> filter.apply(listGroupIds(connection));
            case ZK -> filter.exactNames();
        };
    }

    /** 与 Dashboard 口径一致:排除 {@code _} 前缀内部 topic。 */
    private List<String> listTopicNames(ClusterConnection connection) throws Exception {
        Set<String> names = KafkaFutures.await(connection.getAdminClient().listTopics().names(),
                timeoutMs, "listTopics:inspection-scope");
        return new ArrayList<>(new TreeSet<>(names.stream().filter(n -> !n.startsWith("_")).toList()));
    }

    private List<String> listGroupIds(ClusterConnection connection) throws Exception {
        var listings = KafkaFutures.await(connection.getAdminClient().listConsumerGroups().all(),
                timeoutMs, "listConsumerGroups:inspection-scope");
        // 归档组是本应用自己的常驻消费者:监控它的 lag 属于自噪声,
        // 与 topic 侧排除 "_" 前缀同哲学
        return listings.stream()
                .map(ConsumerGroupListing::groupId)
                .filter(g -> !g.startsWith("kafkaviz-archive-"))
                .sorted()
                .toList();
    }
}
