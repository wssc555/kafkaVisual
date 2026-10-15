package com.example.kafkaviz.inspection.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 规则列表分页结果({@code GET /api/c/{clusterId}/inspection/rules})。
 *
 * <p>{@code page} 从 1 起(与前端 el-pagination 的 current-page 一致),
 * 后端内部再转成 SQL 的 {@code OFFSET}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InspectionRulePageVO {

    private long total;

    private int page;

    private int size;

    private List<RuleListItemVO> items;
}
