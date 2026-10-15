package com.example.kafkaviz.inspection.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 规则列表项。
 *
 * <p>{@code lastState} / {@code lastEvaluatedAt} 的口径(避免歧义,写在这里):
 * 取该规则<b>任意 scope</b> 中 {@code last_evaluated_at} 最大的一条 rule_state。
 * 它是"这条规则最近跑过一次,当时最活跃的那个 scope 是 X 态"的概览,
 * <b>不等于</b>"全部 scope 都是 X 态" —— 多 scope 规则的详情要看规则详情或告警中心。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleListItemVO {

    private long id;

    private long clusterId;

    private String name;

    private boolean enabled;

    private String severity;

    private String metricScope;

    private String scopeFilter;

    private String cronExpr;

    private String description;

    /** 人话摘要,如 {@code under_replicated_partitions > 0};多条件用组合子连接。 */
    private String conditionSummary;

    private List<String> channelNames;

    /** 概览口径的最新状态;从未评估过为 null(JSON 中显式输出)。 */
    private String lastState;

    private String lastEvaluatedAt;

    private String nextFireAt;

    private String createdAt;

    private String updatedAt;
}
