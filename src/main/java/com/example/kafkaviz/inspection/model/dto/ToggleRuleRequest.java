package com.example.kafkaviz.inspection.model.dto;

import lombok.Data;

/**
 * {@code POST /api/c/{clusterId}/inspection/rules/{id}/toggle} 请求体。
 *
 * <p>启用 → 重算 {@code next_fire_at};禁用 → 清空(置 null),ticker 的到期查询
 * 天然不再命中它({@code next_fire_at IS NULL} 不在结果里)。
 */
@Data
public class ToggleRuleRequest {

    private boolean enabled;
}
