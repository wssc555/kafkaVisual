package com.example.kafkaviz.inspection.service;

import com.example.kafkaviz.inspection.InspectionProperties;
import com.example.kafkaviz.inspection.engine.RuleDefinition;
import com.example.kafkaviz.inspection.engine.RuleDslParser;
import com.example.kafkaviz.inspection.engine.RuleEngine;
import com.example.kafkaviz.inspection.exception.DuplicateNameException;
import com.example.kafkaviz.inspection.exception.InvalidRuleException;
import com.example.kafkaviz.inspection.exception.RuleNotFoundException;
import com.example.kafkaviz.inspection.metric.MetricScope;
import com.example.kafkaviz.inspection.model.InspectionRule;
import com.example.kafkaviz.inspection.model.dto.CreateInspectionRuleRequest;
import com.example.kafkaviz.inspection.model.dto.RuleConditionSpec;
import com.example.kafkaviz.inspection.model.dto.UpdateInspectionRuleRequest;
import com.example.kafkaviz.inspection.model.vo.InspectionRulePageVO;
import com.example.kafkaviz.inspection.model.vo.InspectionRuleVO;
import com.example.kafkaviz.inspection.model.vo.RuleListItemVO;
import com.example.kafkaviz.inspection.model.vo.RuleTestResult;
import com.example.kafkaviz.inspection.schedule.RuleCron;
import com.example.kafkaviz.inspection.store.AlertChannelStore;
import com.example.kafkaviz.inspection.store.InspectionRuleStore;
import com.example.kafkaviz.inspection.store.RuleChannelStore;
import com.example.kafkaviz.inspection.store.RuleConditionStore;
import com.example.kafkaviz.inspection.store.RuleStateStore;
import com.example.kafkaviz.kafka.ClusterConnection;
import com.example.kafkaviz.kafka.ClusterDefinition;
import com.example.kafkaviz.storage.ClusterConfigStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 巡检规则编排:校验 → Store 读写 → 条件/通道关联 → ticker 联动。
 *
 * <p><b>连接语义分工</b>(与归档/收藏同哲学):
 * <ul>
 *   <li>CRUD / 启停 / 删除 —— 只做集群<b>存在性</b>校验,集群离线也照样能管规则
 *       (否则集群一挂,用户连"把误报的规则停掉"都做不到);</li>
 *   <li>试运行 —— 需要 {@link ClusterConnection},由 {@code ClusterArgumentResolver}
 *       惰性建连,连不上先得到 50302 / 40404,到不了 Service。</li>
 * </ul>
 */
@Service
public class InspectionRuleService {

    public static final Set<String> SEVERITIES = Set.of("INFO", "WARN", "CRITICAL");

    /** 分页 size 上限:与归档 limit 同思路,超上限直接拒而不是静默截断。 */
    private static final int MAX_PAGE_SIZE = 200;

    private final InspectionRuleStore ruleStore;
    private final RuleConditionStore conditionStore;
    private final RuleChannelStore ruleChannelStore;
    private final AlertChannelStore channelStore;
    private final RuleStateStore stateStore;
    private final RuleDslParser parser;
    private final RuleEngine engine;
    private final ClusterConfigStore clusterConfigStore;
    private final InspectionProperties properties;
    private final ObjectMapper objectMapper;

    public InspectionRuleService(InspectionRuleStore ruleStore,
                                 RuleConditionStore conditionStore,
                                 RuleChannelStore ruleChannelStore,
                                 AlertChannelStore channelStore,
                                 RuleStateStore stateStore,
                                 RuleDslParser parser,
                                 RuleEngine engine,
                                 ClusterConfigStore clusterConfigStore,
                                 InspectionProperties properties,
                                 ObjectMapper objectMapper) {
        this.ruleStore = ruleStore;
        this.conditionStore = conditionStore;
        this.ruleChannelStore = ruleChannelStore;
        this.channelStore = channelStore;
        this.stateStore = stateStore;
        this.parser = parser;
        this.engine = engine;
        this.clusterConfigStore = clusterConfigStore;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------
    // 读
    // ---------------------------------------------------------------

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return name.trim();
    }

    private static String requireSeverity(String severity) {
        if (severity == null) {
            throw new InvalidRuleException("severity must not be blank");
        }
        String normalized = severity.trim().toUpperCase(Locale.ROOT);
        if (!SEVERITIES.contains(normalized)) {
            throw new InvalidRuleException("severity must be one of " + SEVERITIES + " but was: " + severity);
        }
        return normalized;
    }

    // ---------------------------------------------------------------
    // 写
    // ---------------------------------------------------------------

    private static MetricScope requireScope(String raw) {
        MetricScope scope = MetricScope.of(raw);
        if (scope == null) {
            throw new InvalidRuleException("metricScope must be one of CLUSTER, TOPIC, CONSUMER_GROUP, ZK"
                    + " but was: " + raw);
        }
        return scope;
    }

    private static List<com.example.kafkaviz.inspection.model.RuleConditionRow> toConditionRows(
            long ruleId, RuleDefinition def) {
        List<com.example.kafkaviz.inspection.model.RuleConditionRow> rows =
                new ArrayList<>(def.conditions().size());
        int seq = 0;
        for (var condition : def.conditions()) {
            rows.add(new com.example.kafkaviz.inspection.model.RuleConditionRow(
                    0L, ruleId, seq++, condition.metricKey(), condition.operator(), condition.threshold(),
                    condition.windowCount(), condition.windowMatch(),
                    def.combinator()));
        }
        return rows;
    }

    private static void throwDuplicate(String name) {
        throw new DuplicateNameException("Inspection rule name already exists in this cluster: " + name);
    }

    public InspectionRulePageVO list(long clusterId, Boolean enabled, String scope, String name,
                                     int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        // long 运算:page 无上界,(page-1)*size 用 int 会溢出成负 offset
        long offset = (long) (safePage - 1) * safeSize;

        List<InspectionRule> rows = ruleStore.list(clusterId, enabled, scope, name, safeSize, offset);
        long total = ruleStore.count(clusterId, enabled, scope, name);

        List<RuleListItemVO> items = new ArrayList<>(rows.size());
        for (InspectionRule rule : rows) {
            var state = stateStore.latestOfRule(rule.id());
            items.add(RuleListItemVO.builder()
                    .id(rule.id())
                    .clusterId(rule.clusterId())
                    .name(rule.name())
                    .enabled(rule.enabled())
                    .severity(rule.severity())
                    .metricScope(rule.metricScope())
                    .scopeFilter(rule.scopeFilter())
                    .cronExpr(rule.cronExpr())
                    .description(rule.description())
                    .conditionSummary(conditionSummary(rule))
                    .channelNames(channelNames(rule.id()))
                    .lastState(state.map(s -> s.currentState()).orElse(null))
                    .lastEvaluatedAt(state.map(s -> s.lastEvaluatedAt()).orElse(null))
                    .nextFireAt(rule.nextFireAt())
                    .createdAt(rule.createdAt())
                    .updatedAt(rule.updatedAt())
                    .build());
        }
        return InspectionRulePageVO.builder()
                .total(total).page(safePage).size(safeSize).items(items).build();
    }

    public InspectionRuleVO detail(long clusterId, long id) {
        return toVo(requireRule(clusterId, id));
    }

    /**
     * 创建规则。
     *
     * <p>{@code @Transactional}:规则行 + 条件 + 通道关联是三段写,任何一段失败
     * 都不该留下"有规则行但没有条件/关联"的半成品(引擎靠 condition_json 还能跑,
     * 但展示与分发全空,极难察觉)。SQLite 单写者下事务开销可忽略。
     */
    @Transactional
    public InspectionRuleVO create(long clusterId, CreateInspectionRuleRequest req) {
        String name = requireName(req.getName());
        String severity = requireSeverity(req.getSeverity());
        MetricScope scope = requireScope(req.getMetricScope());

        ruleStore.findIdByName(clusterId, name).ifPresent(other ->
                throwDuplicate(name));
        // ZK scope 只在该集群配了 zk_connect_string 时才有意义 —— 创建时即拒,
        // 不留到运行期才发现"永远采不到值"
        requireZkAvailable(clusterId, scope);

        String cronExpr = requireCron(req.getCronExpr());
        List<Long> channelIds = requireChannels(req.getChannelIds());

        String conditionJson = toConditionJson(req.getCombinator(), req.getConditions());
        // 解析即校验:未知指标键 / 算子非法 / 窗口参数非法 / scope 与指标不匹配 → 40005
        RuleDefinition def = parser.parse(0L, clusterId, scope, req.getScopeFilter(), conditionJson);

        boolean enabled = req.getEnabled() == null || req.getEnabled();
        String nextFireAt = enabled ? RuleCron.nextFireAt(cronExpr) : null;

        InspectionRule rule;
        try {
            rule = ruleStore.insert(clusterId, name, enabled, severity, scope.value(),
                    req.getScopeFilter(), conditionJson, cronExpr, nextFireAt, req.getDescription());
        } catch (DuplicateKeyException e) {
            // 先查后插的窗口内被并发抢先:库里 (cluster_id, name) 唯一键兜底,
            // 语义仍是 40903 而不是落进 50000 兜底
            throw new DuplicateNameException("Inspection rule name already exists in this cluster: " + name);
        }
        conditionStore.replaceAll(rule.id(), toConditionRows(rule.id(), def));
        ruleChannelStore.replaceAll(rule.id(), channelIds);
        return toVo(rule);
    }

    // ---------------------------------------------------------------
    // 内部:校验
    // ---------------------------------------------------------------

    /** 同 create:多段写,事务包裹;名字唯一性由先查后插 + 库唯一键双保险。 */
    @Transactional
    public InspectionRuleVO update(long clusterId, long id, UpdateInspectionRuleRequest req) {
        InspectionRule current = requireRule(clusterId, id);

        String name = current.name();
        if (req.getName() != null && !req.getName().isBlank()) {
            name = requireName(req.getName());
            final String candidate = name;
            ruleStore.findIdByName(clusterId, candidate).ifPresent(otherId -> {
                if (otherId != id) {
                    throwDuplicate(candidate);
                }
            });
        }
        String severity = req.getSeverity() == null ? current.severity() : requireSeverity(req.getSeverity());
        MetricScope scope = req.getMetricScope() == null
                ? MetricScope.of(current.metricScope())
                : requireScope(req.getMetricScope());
        // 只在显式改 scope 时校验 ZK 可用性:集群 ZK 配置被摘掉后,残留的 ZK 规则
        // 连"改名/停用"都被 40005 挡死反而把用户锁死(运行期由引擎跳过兜底)
        if (req.getMetricScope() != null) {
            requireZkAvailable(clusterId, scope);
        }

        String cronExpr = req.getCronExpr() == null ? current.cronExpr() : requireCron(req.getCronExpr());
        List<Long> channelIds = req.getChannelIds() == null
                ? ruleChannelStore.channelIdsOf(id)
                : requireChannels(req.getChannelIds());

        String conditionJson = current.conditionJson();
        String scopeFilter = req.getScopeFilter() == null ? current.scopeFilter() : req.getScopeFilter();
        if (req.getConditions() != null) {
            // 只换条件、没给组合子时沿用原组合子(不重置成 AND —— 那会静默改变规则语义)
            String combinator = req.getCombinator() == null
                    ? existingCombinator(current.conditionJson())
                    : req.getCombinator();
            conditionJson = toConditionJson(combinator, req.getConditions());
        } else if (req.getCombinator() != null) {
            conditionJson = replaceCombinator(current.conditionJson(), req.getCombinator());
        }
        RuleDefinition def = parser.parse(id, clusterId, scope, scopeFilter, conditionJson);

        boolean enabled = req.getEnabled() == null ? current.enabled() : req.getEnabled();
        // cron 变更 / 启停变更都要重排;禁用时清空(ticker 自然不再命中)。
        // cronExpr 可能来自库中旧值(本次没给 cron):损坏时按 40005 而不是 40001/50000
        String nextFireAt = enabled ? requireParsableCron(cronExpr) : null;

        try {
            ruleStore.update(id, name, enabled, severity, scope.value(), scopeFilter, conditionJson,
                    cronExpr, nextFireAt,
                    req.getDescription() == null ? current.description() : req.getDescription());
        } catch (DuplicateKeyException e) {
            throw new DuplicateNameException("Inspection rule name already exists in this cluster: " + name);
        }
        if (req.getConditions() != null || req.getCombinator() != null) {
            conditionStore.replaceAll(id, toConditionRows(id, def));
        }
        if (req.getChannelIds() != null) {
            ruleChannelStore.replaceAll(id, channelIds);
        }
        return toVo(requireRule(clusterId, id));
    }

    public InspectionRuleVO toggle(long clusterId, long id, boolean enabled) {
        InspectionRule rule = requireRule(clusterId, id);
        String nextFireAt = enabled ? requireParsableCron(rule.cronExpr()) : null;
        ruleStore.setEnabled(id, enabled, nextFireAt);
        return toVo(requireRule(clusterId, id));
    }

    /** 库中的 cron(可能被手工改坏)也要能算 next_fire_at:非法按 40005,不落 40001。 */
    private String requireParsableCron(String cronExpr) {
        try {
            return RuleCron.nextFireAt(cronExpr);
        } catch (IllegalArgumentException e) {
            throw new InvalidRuleException("Stored cron expression is invalid: '" + cronExpr + "'", e);
        }
    }

    /**
     * 删除规则。
     *
     * <p>级联清 {@code rule_condition} / {@code rule_state} / {@code metric_sample} /
     * {@code rule_channel};<b>保留</b> {@code alert_record} 与 {@code alert_dispatch_log}
     * —— 告警是审计记录,规则删了历史仍要可查。
     */
    @Transactional
    public void delete(long clusterId, long id) {
        requireRule(clusterId, id);
        ruleStore.cascadeDelete(id);
        ruleStore.delete(id);
    }

    /**
     * 试运行:立即评估一次,<b>不发告警、不写状态机、不写采样</b>。
     *
     * <p>窗口规则会读已有采样(能反映"按历史样本现在会不会触发"),但不新增样本 ——
     * 否则用户点几次试运行就把窗口"喂"满了,反而看不真实行为。
     */
    public RuleTestResult test(long clusterId, long id, ClusterConnection connection) {
        InspectionRule rule = requireRule(clusterId, id);
        MetricScope scope = MetricScope.of(rule.metricScope());
        if (scope == null) {
            throw new InvalidRuleException("Rule " + id + " has unknown metric_scope: " + rule.metricScope());
        }
        RuleDefinition def = parser.parse(id, clusterId, scope, rule.scopeFilter(), rule.conditionJson());
        var result = engine.evaluateDryRun(def, connection);
        return RuleTestResult.builder()
                .fired(result.fired())
                .insufficientSamples(result.insufficientSamples())
                .firedScopes(result.firedScopes())
                .metricValues(result.metricValues())
                .build();
    }

    private InspectionRule requireRule(long clusterId, long id) {
        InspectionRule rule = ruleStore.findById(id).orElseThrow(() -> new RuleNotFoundException(id));
        if (rule.clusterId() != clusterId) {
            // 规则存在但不属于路径里的集群:对调用方来说就是"这个集群下没有这条规则"
            throw new RuleNotFoundException(id);
        }
        return rule;
    }

    private String requireCron(String cronExpr) {
        if (cronExpr == null || cronExpr.isBlank()) {
            throw new InvalidRuleException("cronExpr must not be blank");
        }
        try {
            RuleCron.validateMinInterval(cronExpr, properties.getScheduler().getMinIntervalSeconds());
        } catch (IllegalArgumentException e) {
            throw new InvalidRuleException(e.getMessage(), e);
        }
        return cronExpr.trim();
    }

    // ---------------------------------------------------------------
    // 内部:DSL 序列化 / 摘要
    // ---------------------------------------------------------------

    private void requireZkAvailable(long clusterId, MetricScope scope) {
        if (scope != MetricScope.ZK) {
            return;
        }
        ClusterDefinition definition = clusterConfigStore.findById(clusterId).orElse(null);
        boolean zkAvailable = definition != null && definition.zkConfigured();
        if (!zkAvailable) {
            throw new InvalidRuleException(
                    "ZK-scoped rules require a cluster with zk.connect-string configured (cluster "
                            + clusterId + " is KRaft or has no ZooKeeper)");
        }
    }

    /** 通道必须全部存在:指向不存在通道的规则会在分发时静默什么都不发。 */
    private List<Long> requireChannels(List<Long> channelIds) {
        if (channelIds == null || channelIds.isEmpty()) {
            throw new InvalidRuleException("channelIds must not be empty");
        }
        List<Long> distinct = new ArrayList<>(new LinkedHashSet<>(channelIds));
        var found = channelStore.findByIds(distinct);
        if (found.size() != distinct.size()) {
            Set<Long> foundIds = new java.util.HashSet<>();
            for (var channel : found) {
                foundIds.add(channel.id());
            }
            List<Long> missing = distinct.stream().filter(id -> !foundIds.contains(id)).toList();
            throw new InvalidRuleException("Alert channel(s) do not exist: " + missing);
        }
        return distinct;
    }

    /** 规范化成引擎可读的 {@code condition_json}(阈值统一以字符串落库)。 */
    private String toConditionJson(String combinator, List<RuleConditionSpec> specs) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("combinator", combinator == null || combinator.isBlank() ? "AND" : combinator.trim());
        ArrayNode array = root.putArray("conditions");
        for (RuleConditionSpec spec : specs) {
            ObjectNode node = array.addObject();
            node.put("metric", spec.getMetric().trim());
            node.put("operator", spec.getOperator().trim());
            node.put("threshold", spec.getThreshold().trim());
            RuleConditionSpec.RuleWindowSpec window = spec.getWindow();
            if (window != null && window.getCount() != null) {
                ObjectNode windowNode = node.putObject("window");
                windowNode.put("count", window.getCount());
                windowNode.put("match", window.getMatch() == null ? "ALL" : window.getMatch().trim());
            }
        }
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new InvalidRuleException("Failed to serialize rule condition: " + e.getMessage(), e);
        }
    }

    /** 从既有 condition_json 里读出组合子;损坏的 JSON 按 40005 拒绝 —— 静默回退 AND 会悄悄改掉规则语义。 */
    private String existingCombinator(String conditionJson) {
        try {
            var root = objectMapper.readTree(conditionJson);
            var node = root == null ? null : root.get("combinator");
            if (node != null && !node.isNull() && !node.asText().isBlank()) {
                return node.asText().trim();
            }
        } catch (Exception e) {
            throw new InvalidRuleException(
                    "Existing condition_json is corrupt, cannot derive combinator: " + e.getMessage(), e);
        }
        return RuleDefinition.AND;
    }

    /** 只改组合子(条件本身没变)时,在既有 JSON 上原地替换,避免重建丢失未知字段。 */
    private String replaceCombinator(String conditionJson, String combinator) {
        try {
            ObjectNode root = (ObjectNode) objectMapper.readTree(conditionJson);
            root.put("combinator", combinator.trim());
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new InvalidRuleException("Failed to update combinator: " + e.getMessage(), e);
        }
    }

    /** 人话摘要:{@code under_replicated_partitions > 0};带窗口时补 {@code [ALL/3]}。 */
    private String conditionSummary(InspectionRule rule) {
        MetricScope scope = MetricScope.of(rule.metricScope());
        if (scope == null) {
            return rule.conditionJson();
        }
        try {
            RuleDefinition def = parser.parse(rule.id(), rule.clusterId(), scope,
                    rule.scopeFilter(), rule.conditionJson());
            List<String> parts = new ArrayList<>();
            for (var condition : def.conditions()) {
                StringBuilder sb = new StringBuilder()
                        .append(condition.metricKey()).append(' ')
                        .append(condition.operator()).append(' ')
                        .append(condition.threshold());
                if (condition.hasWindow()) {
                    sb.append(" [").append(condition.windowMatch()).append('/')
                            .append(condition.windowCount()).append(']');
                }
                parts.add(sb.toString());
            }
            return String.join(" " + def.combinator() + " ", parts);
        } catch (RuntimeException e) {
            // 摘要是展示用:解析失败(规则被手工改坏)时退回原始 JSON,不让列表页挂掉
            return rule.conditionJson();
        }
    }

    private List<String> channelNames(long ruleId) {
        List<Long> ids = ruleChannelStore.channelIdsOf(ruleId);
        if (ids.isEmpty()) {
            return List.of();
        }
        return channelStore.findByIds(ids).stream()
                .map(com.example.kafkaviz.inspection.model.AlertChannel::name)
                .toList();
    }

    private InspectionRuleVO toVo(InspectionRule rule) {
        List<Long> channelIds = ruleChannelStore.channelIdsOf(rule.id());
        return InspectionRuleVO.builder()
                .id(rule.id())
                .clusterId(rule.clusterId())
                .name(rule.name())
                .enabled(rule.enabled())
                .severity(rule.severity())
                .metricScope(rule.metricScope())
                .scopeFilter(rule.scopeFilter())
                .conditionJson(rule.conditionJson())
                .cronExpr(rule.cronExpr())
                .nextFireAt(rule.nextFireAt())
                .description(rule.description())
                .channelIds(channelIds)
                .channelNames(channelNames(rule.id()))
                .conditionSummary(conditionSummary(rule))
                .createdAt(rule.createdAt())
                .updatedAt(rule.updatedAt())
                .build();
    }
}
