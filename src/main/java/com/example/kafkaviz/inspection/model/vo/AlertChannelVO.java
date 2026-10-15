package com.example.kafkaviz.inspection.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 告警通道 VO。
 *
 * <p><b>敏感字段一律 {@code ******}</b>:{@code config} 由 {@code ChannelConfigCodec.mask}
 * 生成,密文绝不出库。前端把它回填进表单再提交时,掩码值经三态合并被识别为
 * "没改"({@code ******} = 保持原密文),空串才是清空。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertChannelVO {

    private long id;

    private String name;

    /** {@code INSITE} / {@code EMAIL} / {@code WEBHOOK} / {@code IM_BOT}。 */
    private String type;

    private boolean enabled;

    /** 已脱敏的配置(敏感键 = {@code ******})。 */
    private Map<String, Object> config;
}
