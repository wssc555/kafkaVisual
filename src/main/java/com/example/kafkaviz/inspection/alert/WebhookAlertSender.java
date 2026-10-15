package com.example.kafkaviz.inspection.alert;

import com.example.kafkaviz.inspection.model.AlertChannel;
import com.example.kafkaviz.inspection.support.ChannelConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Webhook 通道:POST 一个 JSON。
 *
 * <p>判定口径:<b>2xx 即成功,不解析响应体</b> —— 各系统的"业务成功"字段千奇百怪
 * (有的 {@code errcode=0} 才算成功),去猜它们只会把可运维性押在猜测上;
 * 真正的失败(4xx/5xx/连接失败)由 HTTP 状态码表达得足够清楚。
 *
 * <p>URL scheme:https 优先,http 允许但记 warn(桌面单用户工具,外发地址由用户自配,
 * 硬拒 http 会让内网自签环境的用户完全没有出路)。
 */
@Component
public class WebhookAlertSender implements AlertSender {

    private static final Logger log = LoggerFactory.getLogger(WebhookAlertSender.class);

    private final RestClient restClient;

    public WebhookAlertSender(RestClient restClient) {
        this.restClient = restClient;
    }

    /** 固定结构的告警体(不提供模板引擎:模板语言是另一套需要长期维护的面)。 */
    private static Map<String, Object> payload(AlertMessage message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", message.title());
        body.put("message", message.message());
        body.put("severity", message.severity());
        body.put("state", message.state());
        body.put("scope", message.scopeId());
        body.put("clusterId", message.clusterId());
        body.put("ruleId", message.ruleId());
        body.put("alertId", message.alertId());
        body.put("metrics", message.metricValues() == null ? Map.of() : message.metricValues());
        return body;
    }

    @Override
    public String type() {
        return AlertChannel.WEBHOOK;
    }

    @Override
    public SendOutcome send(AlertMessage message, ChannelConfig config) {
        String url = config.get("url");
        if (url == null || url.isBlank()) {
            return SendOutcome.failed("webhook url is not configured");
        }
        String target = url.trim();
        String lower = target.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("http://")) {
            log.warn("Webhook channel uses plain http ({}): credentials in custom headers will travel "
                    + "unencrypted — https is strongly recommended", target);
        } else if (!lower.startsWith("https://")) {
            return SendOutcome.failed("webhook url must start with http:// or https:// but was: " + target);
        }

        try {
            var spec = restClient.post().uri(target).contentType(MediaType.APPLICATION_JSON);
            Map<String, String> headers = config.getHeaders();
            if (headers != null) {
                headers.forEach(spec::header);
            }
            var response = spec.body(payload(message)).retrieve().toEntity(String.class);
            int status = response.getStatusCode().value();
            if (status >= 200 && status < 300) {
                return SendOutcome.ok();
            }
            return SendOutcome.failed("webhook responded with HTTP " + status);
        } catch (RestClientResponseException e) {
            // 只记状态码:框架异常消息可能携带对端响应体,万一对端回显了 token,
            // 不能让它经 errorMessage 进日志/落库(alert_dispatch_log)
            log.warn("Webhook alert send failed: HTTP {}", e.getStatusCode().value());
            return SendOutcome.failed("webhook responded with HTTP " + e.getStatusCode().value());
        } catch (Exception e) {
            log.warn("Webhook alert send failed: {}", e.getMessage());
            return SendOutcome.failed(e.getMessage());
        }
    }
}
