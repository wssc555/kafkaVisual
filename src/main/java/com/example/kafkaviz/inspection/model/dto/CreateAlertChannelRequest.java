package com.example.kafkaviz.inspection.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * {@code POST /api/inspection/channels} 请求体。
 *
 * <p>{@code config} 是明文提交的一次性输入:入库前由 {@code ChannelConfigCodec}
 * 对敏感键加密,响应体里再以 {@code ******} 掩码回显 —— 明文只在这一次请求的
 * 网络往返里存在(与集群凭据的提交口径一致)。
 */
@Data
public class CreateAlertChannelRequest {

    @NotBlank(message = "name must not be blank")
    @Size(max = 64, message = "name must be at most 64 characters")
    private String name;

    /** {@code INSITE} / {@code EMAIL} / {@code WEBHOOK} / {@code IM_BOT}。 */
    @NotBlank(message = "type must not be blank")
    private String type;

    /** 类型相关配置;INSITE 可为 {@code {}}。 */
    @NotNull(message = "config must not be null")
    private Map<String, Object> config;

    /** 缺省 true。 */
    private Boolean enabled;
}
