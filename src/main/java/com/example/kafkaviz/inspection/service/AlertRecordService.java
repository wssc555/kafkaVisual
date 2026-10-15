package com.example.kafkaviz.inspection.service;

import com.example.kafkaviz.inspection.exception.AlertNotFoundException;
import com.example.kafkaviz.inspection.exception.AlreadyAcknowledgedException;
import com.example.kafkaviz.inspection.model.AlertDispatchLog;
import com.example.kafkaviz.inspection.model.AlertRecord;
import com.example.kafkaviz.inspection.model.vo.AlertDetailVO;
import com.example.kafkaviz.inspection.model.vo.AlertDispatchLogVO;
import com.example.kafkaviz.inspection.model.vo.AlertRecordVO;
import com.example.kafkaviz.inspection.store.AlertChannelStore;
import com.example.kafkaviz.inspection.store.AlertDispatchLogStore;
import com.example.kafkaviz.inspection.store.AlertRecordStore;
import com.example.kafkaviz.inspection.store.InspectionRuleStore;
import com.example.kafkaviz.kafka.ClusterDefinition;
import com.example.kafkaviz.storage.ClusterConfigStore;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 告警中心编排:列表过滤 / 详情 / ack / 未读数。
 *
 * <p>全部只读 + 一次 ack 写,<b>不提供删除</b>:告警是审计记录,规则被删、
 * 集群被删之后它仍要可查 —— 这也是 {@code alert_record} 里那份集群快照存在的理由。
 */
@Service
public class AlertRecordService {

    /** 概览的"最近告警"条数。 */
    private static final int RECENT_LIMIT = 10;

    private static final int MAX_PAGE_SIZE = 200;

    /** firedFrom/firedTo 接受日期或完整日期时间(字典序比较,格式错了会静默给出错结果,必须拦)。 */
    private static final Pattern ISO_DATE_OR_DATETIME = Pattern.compile("\\d{4}-\\d{2}-\\d{2}(T.*)?");

    private final AlertRecordStore alertStore;
    private final AlertDispatchLogStore dispatchLogStore;
    private final AlertChannelStore channelStore;
    private final InspectionRuleStore ruleStore;
    private final ClusterConfigStore clusterConfigStore;

    public AlertRecordService(AlertRecordStore alertStore, AlertDispatchLogStore dispatchLogStore,
                              AlertChannelStore channelStore, InspectionRuleStore ruleStore,
                              ClusterConfigStore clusterConfigStore) {
        this.alertStore = alertStore;
        this.dispatchLogStore = dispatchLogStore;
        this.channelStore = channelStore;
        this.ruleStore = ruleStore;
        this.clusterConfigStore = clusterConfigStore;
    }

    private static String requireIsoTime(String value, String what) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (!ISO_DATE_OR_DATETIME.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(what + " must be an ISO-8601 date or date-time"
                    + " (e.g. 2026-10-14 or 2026-10-14T00:00:00Z) but was: " + value);
        }
        return trimmed;
    }

    private static AlertRecordVO toVo(AlertRecord record, Map<Long, String> clusterNames) {
        return AlertRecordVO.builder()
                .id(record.id())
                .ruleId(record.ruleId())
                .clusterId(record.clusterId())
                .clusterName(clusterNames.get(record.clusterId()))
                .scopeId(record.scopeId())
                .severity(record.severity())
                .state(record.state())
                .title(record.title())
                .message(record.message())
                .firedAt(record.firedAt())
                .recoveredAt(record.recoveredAt())
                .acknowledgedAt(record.acknowledgedAt())
                .acknowledgedBy(record.acknowledgedBy())
                .build();
    }

    private static AlertDispatchLogVO toLogVo(AlertDispatchLog log, Map<Long, String> channelNames) {
        return AlertDispatchLogVO.builder()
                .id(log.id())
                .alertId(log.alertId())
                .channelId(log.channelId())
                .channelName(channelNames.get(log.channelId()))
                .status(log.status())
                .errorMessage(log.errorMessage())
                .createdAt(log.createdAt())
                .build();
    }

    /** 告警行是否存在(供站内信 sender 做投递确认)。 */
    public boolean exists(long alertId) {
        return alertStore.findById(alertId).isPresent();
    }

    public List<AlertRecordVO> search(String state, String severity, Long ruleId, Long clusterId,
                                      Boolean acknowledged, String firedFrom, String firedTo,
                                      int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        // long 运算:page 无上界,(page-1)*size 用 int 会溢出成负 offset
        long offset = (long) (safePage - 1) * safeSize;
        List<AlertRecord> rows = alertStore.search(state, severity, ruleId, clusterId, acknowledged,
                requireIsoTime(firedFrom, "firedFrom"), requireIsoTime(firedTo, "firedTo"),
                safeSize, offset);
        Map<Long, String> clusterNames = clusterNames();
        return rows.stream().map(row -> toVo(row, clusterNames)).toList();
    }

    public long count(String state, String severity, Long ruleId, Long clusterId, Boolean acknowledged,
                      String firedFrom, String firedTo) {
        return alertStore.countSearch(state, severity, ruleId, clusterId, acknowledged, firedFrom, firedTo);
    }

    // ---------------------------------------------------------------
    // 概览(跨集群聚合)
    // ---------------------------------------------------------------

    public AlertDetailVO detail(long id) {
        AlertRecord record = alertStore.findById(id).orElseThrow(() -> new AlertNotFoundException(id));
        Map<Long, String> channelNames = new HashMap<>();
        for (var channel : channelStore.list()) {
            channelNames.put(channel.id(), channel.name());
        }
        List<AlertDispatchLogVO> logs = dispatchLogStore.listByAlert(id).stream()
                .map(log -> toLogVo(log, channelNames))
                .toList();
        return AlertDetailVO.builder()
                .alert(toVo(record, clusterNames()))
                .dispatchLogs(logs)
                .build();
    }

    // ---------------------------------------------------------------
    // 内部
    // ---------------------------------------------------------------

    /**
     * 确认(ack)。
     *
     * <p><b>不改变 state</b>:FIRING 仍是 FIRING,只是"人已认领"。把 ack 做成
     * "关掉告警"会让运维失去"这个问题还在不在"的判断依据。
     *
     * <p>幂等性完全靠 UPDATE 的 {@code WHERE acknowledged_at IS NULL} 条件行数判定:
     * 先查后改的写法在并发下会有"双双通过预查"的窗口,行数为 0 一律按重复 ack 处理。
     */
    public AlertRecordVO acknowledge(long id, String by) {
        AlertRecord record = alertStore.findById(id).orElseThrow(() -> new AlertNotFoundException(id));
        // 操作人由前端传入(本期无登录态),钳到列宽 —— MySQL 的 acknowledged_by 是 VARCHAR(64)
        String operator = by == null || by.isBlank() ? null : by.trim();
        if (operator != null && operator.length() > 64) {
            operator = operator.substring(0, 64);
        }
        if (!alertStore.acknowledge(id, operator)) {
            throw new AlreadyAcknowledgedException(id);
        }
        return toVo(alertStore.findById(id).orElse(record), clusterNames());
    }

    /** 未读数:{@code FIRING 且未 ack}(顶部徽标轮询用)。 */
    public long unreadCount() {
        return alertStore.countUnread();
    }

    public com.example.kafkaviz.inspection.model.vo.InspectionOverviewVO overview() {
        Map<Long, String> clusterNames = clusterNames();
        List<AlertRecordVO> recent = alertStore.recent(RECENT_LIMIT).stream()
                .map(row -> toVo(row, clusterNames))
                .toList();
        // "今日"用当日 00:00 的 ISO 文本下界(时间列是文本,字典序 = 时间序)
        String todayStart = LocalDate.now().atTime(LocalTime.MIDNIGHT).toString();
        return com.example.kafkaviz.inspection.model.vo.InspectionOverviewVO.builder()
                .totalRules(ruleStore.countAll())
                .enabledRules(ruleStore.countEnabled())
                .firingCount(alertStore.countFiring())
                .unreadCount(alertStore.countUnread())
                .recoveredToday(alertStore.countRecoveredSince(todayStart))
                .bySeverity(alertStore.countFiringBySeverity())
                .recentAlerts(recent)
                .build();
    }

    private Map<Long, String> clusterNames() {
        Map<Long, String> names = new HashMap<>();
        for (ClusterDefinition definition : clusterConfigStore.list()) {
            names.put(definition.id(), definition.name());
        }
        return names;
    }
}
