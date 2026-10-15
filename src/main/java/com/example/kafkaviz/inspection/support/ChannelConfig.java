package com.example.kafkaviz.inspection.support;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通道配置的类型安全视图(<b>敏感字段已解密</b>,仅内存)。
 *
 * <p>存在理由:让各 {@code AlertSender} 读配置时不用关心"这个字段是密文还是明文"、
 * "缺失时默认值是什么" —— 解密与缺键处理统一收在 {@link ChannelConfigCodec},
 * 发送器里只有 {@code config.get("host")} 这种直读。
 *
 * <p><b>不要在日志/响应里打印本对象</b>:它持有明文 token / SMTP 口令。
 */
public final class ChannelConfig {

    private final String type;
    private final Map<String, String> values;

    public ChannelConfig(String type, Map<String, String> values) {
        this.type = type;
        this.values = values == null ? Map.of() : new LinkedHashMap<>(values);
    }

    public String type() {
        return type;
    }

    public String get(String key) {
        return values.get(key);
    }

    public String getOrDefault(String key, String fallback) {
        String value = values.get(key);
        return value == null ? fallback : value;
    }

    public int getInt(String key, int fallback) {
        String raw = values.get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** {@code true} / {@code 1} / {@code yes} 视为真;其余(含缺失)为假。 */
    public boolean getBool(String key, boolean fallback) {
        String raw = values.get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String normalized = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return "true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized);
    }

    /**
     * 逗号分隔的列表(收件人 / cc)。
     *
     * <p>支持"用户填了一行多地址"的常见写法,空白项被剔除。
     */
    public List<String> getList(String key) {
        String raw = values.get(key);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new java.util.ArrayList<>();
        for (String part : raw.split("[,;]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    /** 自定义 headers(webhook);无时为 null 而不是空 Map —— 让调用方能区分"没配"与"配了空"。 */
    public Map<String, String> getHeaders() {
        String raw = values.get("headers");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            var node = ChannelConfigCodec.MAPPER.readTree(raw);
            Map<String, String> out = new LinkedHashMap<>();
            node.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
            return out;
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }
}
