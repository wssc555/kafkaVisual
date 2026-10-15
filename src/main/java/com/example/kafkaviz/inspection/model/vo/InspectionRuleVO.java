package com.example.kafkaviz.inspection.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 规则详情({@code GET /api/c/{clusterId}/inspection/rules/{id}})与创建/更新响应。
 *
 * <p>创建/更新响应<b>带回 {@code nextFireAt}</b>:前端的"下次执行时间预览"直接用它,
 * 不需要在浏览器里再引一个 cron 解析库(前端解析与后端 Spring CronExpression 的
 * 语义差异会变成"预览和实际不一致"的 bug)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InspectionRuleVO {

    private long id;

    private long clusterId;

    private String name;

    private boolean enabled;

    private String severity;

    private String metricScope;

    private String scopeFilter;

    /** 规则 DSL(原始 JSON 文本),供规则构建器回显。 */
    private String conditionJson;

    private String cronExpr;

    /** 下次触发时间;禁用 / 不会再触发时为 null。 */
    private String nextFireAt;

    private String description;

    private List<Long> channelIds;

    private List<String> channelNames;

    private String conditionSummary;

    private String createdAt;

    private String updatedAt;
}
