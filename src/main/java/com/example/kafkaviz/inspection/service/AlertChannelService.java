package com.example.kafkaviz.inspection.service;

import com.example.kafkaviz.inspection.alert.AlertDispatcher;
import com.example.kafkaviz.inspection.alert.AlertMessage;
import com.example.kafkaviz.inspection.alert.SendOutcome;
import com.example.kafkaviz.inspection.exception.ChannelNotFoundException;
import com.example.kafkaviz.inspection.exception.DuplicateNameException;
import com.example.kafkaviz.inspection.model.AlertChannel;
import com.example.kafkaviz.inspection.model.dto.ChannelTestResult;
import com.example.kafkaviz.inspection.model.dto.CreateAlertChannelRequest;
import com.example.kafkaviz.inspection.model.dto.UpdateAlertChannelRequest;
import com.example.kafkaviz.inspection.model.vo.AlertChannelVO;
import com.example.kafkaviz.inspection.store.AlertChannelStore;
import com.example.kafkaviz.inspection.support.ChannelConfigCodec;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 告警通道编排(全局配置)。
 *
 * <p>通道<b>不挂集群</b>:SMTP / Webhook / IM 端点天然跨集群复用(SMTP 服务器是
 * 组织级的,不会因为多一个 Kafka 集群就多一套),规则侧经 {@code rule_channel} 关联。
 */
@Service
public class AlertChannelService {

    public static final Set<String> TYPES = Set.of(
            AlertChannel.INSITE, AlertChannel.EMAIL, AlertChannel.WEBHOOK, AlertChannel.IM_BOT);

    private final AlertChannelStore channelStore;
    private final ChannelConfigCodec codec;
    private final AlertDispatcher dispatcher;

    public AlertChannelService(AlertChannelStore channelStore, ChannelConfigCodec codec,
                               AlertDispatcher dispatcher) {
        this.channelStore = channelStore;
        this.codec = codec;
        this.dispatcher = dispatcher;
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return name.trim();
    }

    private static String requireType(String type) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be blank");
        }
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(normalized)) {
            throw new IllegalArgumentException("type must be one of " + TYPES + " but was: " + type);
        }
        return normalized;
    }

    /**
     * WEBHOOK 的 url scheme 创建/更新时就校验(发送器发送时还会再校验一次):
     * {@code ftp://} 之类的非法 scheme 若拖到 test/分发才报,用户要倒回来排查配置,
     * 不如入库前就给 40001。
     */
    private static void requireValidUrlScheme(String type, java.util.Map<String, Object> config) {
        if (!AlertChannel.WEBHOOK.equals(type) || config == null) {
            return;
        }
        Object raw = config.get("url");
        String url = raw == null ? "" : String.valueOf(raw).trim();
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new IllegalArgumentException(
                    "webhook url must start with http:// or https:// but was: " + url);
        }
    }

    public List<AlertChannelVO> list() {
        return channelStore.list().stream().map(this::toVo).toList();
    }

    public AlertChannelVO detail(long id) {
        return toVo(requireChannel(id));
    }

    public AlertChannelVO create(CreateAlertChannelRequest req) {
        String name = requireName(req.getName());
        String type = requireType(req.getType());
        channelStore.findIdByName(name).ifPresent(other -> {
            throw new DuplicateNameException("Alert channel name already exists: " + name);
        });
        // 必填键校验走既有 40001(不新增错误码)
        codec.validate(type, req.getConfig());
        requireValidUrlScheme(type, req.getConfig());
        boolean enabled = req.getEnabled() == null || req.getEnabled();
        String configJson = codec.encrypt(type, req.getConfig());
        AlertChannel channel;
        try {
            channel = channelStore.insert(name, type, configJson, enabled);
        } catch (DuplicateKeyException e) {
            // 先查后插的窗口内被并发抢先:唯一键兜底,语义仍是 40903
            throw new DuplicateNameException("Alert channel name already exists: " + name);
        }
        return toVo(channel);
    }

    // ---------------------------------------------------------------
    // 内部
    // ---------------------------------------------------------------

    public AlertChannelVO update(long id, UpdateAlertChannelRequest req) {
        AlertChannel current = requireChannel(id);
        String name = current.name();
        if (req.getName() != null && !req.getName().isBlank()) {
            name = requireName(req.getName());
            final String candidate = name;
            channelStore.findIdByName(candidate).ifPresent(otherId -> {
                if (otherId != id) {
                    throw new DuplicateNameException("Alert channel name already exists: " + candidate);
                }
            });
        }
        String type = req.getType() == null ? current.type() : requireType(req.getType());
        boolean typeChanged = !type.equals(current.type());
        boolean enabled = req.getEnabled() == null ? current.enabled() : req.getEnabled();

        String configJson = current.configJson();
        if (req.getConfig() != null) {
            if (typeChanged) {
                // 换 type = 换配置模型:整体重建而不是三态合并。
                // 合并的后果 —— 旧 type 的敏感键(如 webhook 的加密 headers)在新 type 下
                // 不再被识别为敏感,明文密文会原样泄漏到 VO;旧必填键也不会被校验。
                configJson = codec.encrypt(type, req.getConfig());
            } else {
                // 三态合并:敏感键省略 = 保持原密文(避免"只改名称就把 token 清了")
                configJson = codec.merge(type, current.configJson(), req.getConfig());
            }
            codec.validate(type, parseForValidate(configJson, type));
            requireValidUrlScheme(type, parseForValidate(configJson, type));
        } else if (typeChanged) {
            // 换 type 却不带 config:合并结果必是"旧配置套新模型",必填键校验也救不了
            // (比如 WEBHOOK→EMAIL 时 host/port/to 全缺)。直接拒绝,让用户显式提交完整配置。
            throw new IllegalArgumentException(
                    "Changing channel type from " + current.type() + " to " + type + " requires the full config");
        }
        try {
            channelStore.update(id, name, type, configJson, enabled);
        } catch (DuplicateKeyException e) {
            throw new DuplicateNameException("Alert channel name already exists: " + name);
        }
        return toVo(requireChannel(id));
    }

    /**
     * 删除通道。
     *
     * <p>顺带清 {@code rule_channel} 关联(Store 内部完成):留着指向已删通道的关联行,
     * 会让分发器每轮都去查一个必然查不到的通道。告警历史不受影响。
     * 两段写事务包裹(关联清了、通道没删会留下"幽灵通道")。
     */
    @Transactional
    public void delete(long id) {
        requireChannel(id);
        channelStore.delete(id);
    }

    /**
     * 通道 test:固定内容直发一次。
     *
     * <p>不经规则、<b>不写 {@code alert_record}</b>({@code AlertMessage.alertId} 为 null,
     * 分发器据此跳过分发日志)。禁用通道直接返回明确失败原因,而不是"静默成功"。
     */
    public ChannelTestResult test(long id) {
        AlertChannel channel = requireChannel(id);
        SendOutcome outcome = dispatcher.dispatchTo(AlertMessage.testMessage(), channel);
        return ChannelTestResult.builder()
                .success(outcome.success())
                .errorMessage(outcome.errorMessage())
                .build();
    }

    private AlertChannel requireChannel(long id) {
        return channelStore.findById(id).orElseThrow(() -> new ChannelNotFoundException(id));
    }

    /**
     * 更新后校验必填键:合并结果里敏感键是<b>密文</b>,此时"必填"应判为
     * "有没有值"而不是"能不能解密",所以直接按合并后的原始 map 判空。
     */
    private java.util.Map<String, Object> parseForValidate(String configJson, String type) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        try {
            var root = ChannelConfigCodec.MAPPER.readTree(configJson);
            if (root != null) {
                root.fields().forEachRemaining(e ->
                        out.put(e.getKey(), e.getValue() == null || e.getValue().isNull()
                                ? null : e.getValue().asText()));
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("channel config is not valid JSON: " + e.getMessage());
        }
        return out;
    }

    private AlertChannelVO toVo(AlertChannel channel) {
        return AlertChannelVO.builder()
                .id(channel.id())
                .name(channel.name())
                .type(channel.type())
                .enabled(channel.enabled())
                .config(codec.mask(channel.type(), channel.configJson()))
                .build();
    }
}
