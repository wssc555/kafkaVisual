package com.example.kafkaviz.inspection.support;

import com.example.kafkaviz.inspection.model.AlertChannel;
import com.example.kafkaviz.security.CryptoService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code config_json} 的加解密 / 校验 / 掩码 / 三态合并。
 *
 * <p><b>唯一收口</b>:所有"通道配置怎么变成可发送的东西""怎么变成可展示的东西"
 * 都在本类里,Controller 与 Sender 都不碰密文 —— 分散处理必然出现"某条路径忘了
 * 脱敏"或"某条路径忘了加密"的泄漏。
 *
 * <p>密钥复用 {@link CryptoService}({@code app-data/secret.key}),不引入第二套
 * 密钥配置:两套密钥意味着两套轮换与备份口径,而对单机工具没有额外收益。
 */
@Component
public class ChannelConfigCodec {

    /** 对外输出时替代敏感值的哨兵(与集群凭据 {@code credentialPresence} 同口径)。 */
    public static final String MASK = "******";

    /**
     * 序列化器。
     *
     * <p>包外可见是刻意的:{@code AlertChannelService} 在更新后要读合并结果做必填键校验,
     * 与其再把同一个 mapper 暴露成 Bean,不如共用一个静态实例(它无状态、线程安全)。
     */
    public static final ObjectMapper MAPPER = new ObjectMapper();

    /** 各 type 的必填键;缺失 → 40001(既有参数校验码,不新增错误码)。 */
    private static final Map<String, List<String>> REQUIRED_KEYS = Map.of(
            AlertChannel.INSITE, List.of(),
            AlertChannel.EMAIL, List.of("host", "port", "to"),
            AlertChannel.WEBHOOK, List.of("url"),
            AlertChannel.IM_BOT, List.of("platform", "token"));

    /** 各 type 的敏感键(token / secret / SMTP 口令 / 自定义 header 值)。 */
    private static final Map<String, Set<String>> SENSITIVE_KEYS = Map.of(
            AlertChannel.INSITE, Set.of(),
            AlertChannel.EMAIL, Set.of("password"),
            AlertChannel.WEBHOOK, Set.of("headers"),
            AlertChannel.IM_BOT, Set.of("token", "secret"));

    private final CryptoService cryptoService;

    public ChannelConfigCodec(CryptoService cryptoService) {
        this.cryptoService = cryptoService;
    }

    // ---------------------------------------------------------------
    // 校验
    // ---------------------------------------------------------------

    private static String normalizeType(String type) {
        return type == null ? "" : type.trim().toUpperCase(java.util.Locale.ROOT);
    }

    // ---------------------------------------------------------------
    // 加密 / 解密
    // ---------------------------------------------------------------

    private static Set<String> sensitiveKeysOf(String type) {
        return SENSITIVE_KEYS.getOrDefault(normalizeType(type), Set.of());
    }

    private static Map<String, Object> toMap(String json) {
        Map<String, Object> out = new LinkedHashMap<>();
        JsonNode root = readTree(json);
        if (root == null) {
            return out;
        }
        root.fields().forEachRemaining(e -> {
            JsonNode value = e.getValue();
            if (value == null || value.isNull()) {
                out.put(e.getKey(), null);
            } else if (value.isIntegralNumber()) {
                out.put(e.getKey(), value.asLong());
            } else if (value.isBoolean()) {
                out.put(e.getKey(), value.asBoolean());
            } else {
                out.put(e.getKey(), value.asText());
            }
        });
        return out;
    }

    // ---------------------------------------------------------------
    // 三态合并(更新语义)
    // ---------------------------------------------------------------

    private static JsonNode readTree(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------
    // 掩码(对外输出)
    // ---------------------------------------------------------------

    private static String write(Map<String, Object> config) {
        try {
            return MAPPER.writeValueAsString(config);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize channel config: " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------
    // 内部
    // ---------------------------------------------------------------

    /**
     * 按 type 校验必填键。
     *
     * @throws IllegalArgumentException 缺失 → 40001
     */
    public void validate(String type, Map<String, Object> config) {
        List<String> required = REQUIRED_KEYS.get(normalizeType(type));
        if (required == null) {
            throw new IllegalArgumentException("Unsupported channel type: " + type);
        }
        if (required.isEmpty()) {
            return;
        }
        for (String key : required) {
            Object value = config == null ? null : config.get(key);
            if (value == null || String.valueOf(value).isBlank()) {
                throw new IllegalArgumentException(
                        "Channel config missing required key '" + key + "' for type " + type);
            }
        }
    }

    /**
     * 入库前加密:敏感键的值经 {@code CryptoService} 加密后写入,其余原样保留。
     *
     * @return 待落库的 {@code config_json} 文本
     */
    public String encrypt(String type, Map<String, Object> config) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (config == null) {
            return "{}";
        }
        Set<String> sensitive = sensitiveKeysOf(type);
        for (Map.Entry<String, Object> e : config.entrySet()) {
            if (e.getKey() == null) {
                continue;
            }
            Object value = e.getValue();
            if (value != null && sensitive.contains(e.getKey())) {
                out.put(e.getKey(), cryptoService.encrypt(String.valueOf(value)));
            } else {
                out.put(e.getKey(), value);
            }
        }
        return write(out);
    }

    /** 解密成发送器可直接读的配置视图。 */
    public ChannelConfig decrypt(String type, String configJson) {
        Map<String, String> values = new LinkedHashMap<>();
        JsonNode root = readTree(configJson);
        Set<String> sensitive = sensitiveKeysOf(type);
        if (root != null) {
            root.fields().forEachRemaining(e -> {
                String key = e.getKey();
                JsonNode value = e.getValue();
                String raw = value == null || value.isNull() ? null : value.asText();
                if (raw != null && sensitive.contains(key)) {
                    values.put(key, cryptoService.decrypt(raw));
                } else {
                    values.put(key, raw);
                }
            });
        }
        return new ChannelConfig(normalizeType(type), values);
    }

    /**
     * 三态更新:与集群凭据 {@code resolveSecret} 同口径。
     *
     * <ul>
     *   <li>键<b>不存在</b> —— 保持库中现值(前端"没改这个字段"的语义);</li>
     *   <li>值为 {@code ""} —— 清空;</li>
     *   <li>值为 {@link #MASK} —— 保持现值(前端回填了掩码,等于没改);</li>
     *   <li>其他值 —— 加密后覆盖。</li>
     * </ul>
     *
     * <p>非敏感键一律直接覆盖(不需要三态:它们是明文,前端回显的就是真值)。
     *
     * <p>实现细节:敏感键"空串 = 清空"以<b>删键</b>落地 —— 若该键同时是必填键
     * (如 IM_BOT 的 token),随后 Service 层的必填校验会以 40001 拒绝,
     * 不会静默落库一个缺 token 的通道。
     */
    public String merge(String type, String existingJson, Map<String, Object> incoming) {
        Map<String, Object> current = new LinkedHashMap<>(toMap(existingJson));
        if (incoming == null || incoming.isEmpty()) {
            return write(current);
        }
        Set<String> sensitive = sensitiveKeysOf(type);
        for (Map.Entry<String, Object> e : incoming.entrySet()) {
            String key = e.getKey();
            if (key == null) {
                continue;
            }
            Object incomingValue = e.getValue();
            if (sensitive.contains(key)) {
                if (incomingValue == null) {
                    continue; // 未给出 → 保持密文
                }
                String text = String.valueOf(incomingValue);
                if (text.isEmpty() || MASK.equals(text)) {
                    if (text.isEmpty()) {
                        current.remove(key);
                    }
                    continue;
                }
                current.put(key, cryptoService.encrypt(text));
            } else {
                current.put(key, incomingValue);
            }
        }
        return write(current);
    }

    /**
     * 脱敏后的配置(供 VO 输出):敏感键统一替换成 {@link #MASK},
     * 密文<b>绝不出库</b> —— 密文与密钥分离才有意义,把密文发给前端等于
     * 把"可离线爆破的目标"递出去。
     */
    public Map<String, Object> mask(String type, String configJson) {
        Map<String, Object> out = new LinkedHashMap<>();
        JsonNode root = readTree(configJson);
        if (root == null) {
            return out;
        }
        Set<String> sensitive = sensitiveKeysOf(type);
        root.fields().forEachRemaining(e -> {
            String value = e.getValue() == null || e.getValue().isNull() ? null : e.getValue().asText();
            if (sensitive.contains(e.getKey()) && value != null && !value.isEmpty()) {
                out.put(e.getKey(), MASK);
            } else {
                out.put(e.getKey(), value);
            }
        });
        return out;
    }
}
