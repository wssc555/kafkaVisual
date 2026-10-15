package com.example.kafkaviz.inspection.model.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * {@code PUT /api/inspection/channels/{id}} 请求体。
 *
 * <p>{@code config} 是<b>部分</b>配置:未出现的键保持库中现值,出现的键按三态处理
 * (见 {@code ChannelConfigCodec.merge} 与集群凭据 {@code resolveSecret} 同口径):
 * 空串 = 清空,{@code ******} = 保持,其他 = 加密覆盖。
 *
 * <p>只改名称或启停时,{@code config} 传 null 即可 —— 密文不会被"没传"误清空。
 */
@Data
public class UpdateAlertChannelRequest {

    @Size(max = 64, message = "name must be at most 64 characters")
    private String name;

    /** 一般不改(改了等于换通道类型);给出时按新 type 重新校验 config 必填键。 */
    private String type;

    private Map<String, Object> config;

    private Boolean enabled;
}
