package com.example.kafkaviz.inspection.model;

/**
 * {@code alert_channel} 行 —— 全局通道(不挂集群)。
 *
 * <p>{@code configJson} 里存的是<b>密文</b>:敏感字段(token / secret / SMTP 口令 /
 * 自定义 header 值)在入库前经 {@code CryptoService} 加密,读取侧解密;
 * 对外输出一律经 {@code ChannelConfigCodec} 掩码。
 */
public record AlertChannel(
        long id,
        String name,
        String type,
        String configJson,
        boolean enabled) {

    public static final String INSITE = "INSITE";
    public static final String EMAIL = "EMAIL";
    public static final String WEBHOOK = "WEBHOOK";
    public static final String IM_BOT = "IM_BOT";
}
