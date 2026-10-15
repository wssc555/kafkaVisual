package com.example.kafkaviz.inspection.alert;

import com.example.kafkaviz.inspection.model.AlertChannel;
import com.example.kafkaviz.inspection.support.ChannelConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * IM 机器人通道:钉钉 / 飞书 / 企业微信。
 *
 * <p>三种平台的差异只在"URL 怎么拼"和"body 长什么样",加签只有钉钉有:
 * {@code timestamp + "\n" + secret} 的 HMacSHA256 → Base64 → URL 编码
 * ({@code javax.crypto},零新增依赖)。
 *
 * <p>平台默认地址内置,配置里的 {@code url} 可整体覆盖(私有化部署 / 代理场景)。
 */
@Component
public class ImBotAlertSender implements AlertSender {

    private static final Logger log = LoggerFactory.getLogger(ImBotAlertSender.class);

    private static final String DINGTALK = "DINGTALK";
    private static final String FEISHU = "FEISHU";
    private static final String WECOM = "WECOM";

    private static final String DINGTALK_URL = "https://oapi.dingtalk.com/robot/send";
    private static final String FEISHU_URL = "https://open.feishu.cn/open-apis/bot/v2/hook/";
    private static final String WECOM_URL = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send";

    private final RestClient restClient;

    public ImBotAlertSender(RestClient restClient) {
        this.restClient = restClient;
    }

    private static String defaultUrl(String platform, String token) {
        return switch (platform) {
            case DINGTALK -> DINGTALK_URL;
            case FEISHU -> FEISHU_URL + enc(token);
            case WECOM -> WECOM_URL + "?key=" + enc(token);
            default -> throw new IllegalArgumentException("Unsupported IM platform: " + platform);
        };
    }

    /** 钉钉加签:timestamp + "\n" + secret → HMacSHA256 → Base64 → URL 编码。 */
    private static String dingtalkSignedUrl(String base, String token, String secret) throws Exception {
        return base + "?access_token=" + enc(token) + "&" + dingtalkSignParams(secret);
    }

    /** 只算加签参数(自定义地址场景:token 已内嵌在 url 里,仅补 timestamp/sign)。 */
    private static String dingtalkSignParams(String secret) throws Exception {
        long timestamp = System.currentTimeMillis();
        String toSign = timestamp + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signBytes = mac.doFinal(toSign.getBytes(StandardCharsets.UTF_8));
        String sign = enc(Base64.getEncoder().encodeToString(signBytes));
        return "timestamp=" + timestamp + "&sign=" + sign;
    }

    private static Map<String, Object> body(String platform, AlertMessage message) {
        String text = message.title() + "\n" + (message.message() == null ? "" : message.message());
        Map<String, Object> body = new LinkedHashMap<>();
        switch (platform) {
            case DINGTALK -> {
                body.put("msgtype", "text");
                body.put("text", Map.of("content", text));
            }
            case FEISHU -> {
                body.put("msg_type", "text");
                body.put("content", Map.of("text", text));
            }
            case WECOM -> {
                body.put("msgtype", "text");
                body.put("text", Map.of("content", text));
            }
            default -> throw new IllegalArgumentException("Unsupported IM platform: " + platform);
        }
        return body;
    }

    private static String enc(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }

    private static String normalize(String raw) {
        return raw == null ? null : raw.trim().toUpperCase(Locale.ROOT);
    }

    @Override
    public String type() {
        return AlertChannel.IM_BOT;
    }

    @Override
    public SendOutcome send(AlertMessage message, ChannelConfig config) {
        String platform = normalize(config.get("platform"));
        String token = config.get("token");
        if (platform == null) {
            return SendOutcome.failed("IM platform is not configured (DINGTALK / FEISHU / WECOM)");
        }
        if (token == null || token.isBlank()) {
            return SendOutcome.failed("IM bot token is not configured");
        }

        String url = config.get("url");
        // 自定义 url(私有化部署/代理)与默认 url 都要带上凭据:
        // 默认地址由这里拼 token/加签;自定义地址的 token 视为已内嵌,但钉钉加签仍要补
        boolean useDefault = url == null || url.isBlank();
        try {
            String target = useDefault ? defaultUrl(platform, token) : url.trim();
            if (platform.equals(DINGTALK)) {
                String secret = config.get("secret");
                boolean hasSecret = secret != null && !secret.isBlank();
                if (useDefault) {
                    target = hasSecret
                            ? dingtalkSignedUrl(target, token, secret)
                            : target + "?access_token=" + enc(token);
                } else if (hasSecret) {
                    String sep = target.contains("?") ? "&" : "?";
                    target = target + sep + dingtalkSignParams(secret);
                }
            }
            var response = restClient.post()
                    .uri(target)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body(platform, message))
                    .retrieve()
                    .toEntity(String.class);
            int status = response.getStatusCode().value();
            if (status >= 200 && status < 300) {
                return SendOutcome.ok();
            }
            return SendOutcome.failed("IM bot responded with HTTP " + status);
        } catch (RestClientResponseException e) {
            // 只记状态码:异常消息可能携带对端响应体,不能经 errorMessage 进日志/落库
            log.warn("IM bot alert send failed (platform={}): HTTP {}", platform, e.getStatusCode().value());
            return SendOutcome.failed("IM bot responded with HTTP " + e.getStatusCode().value());
        } catch (Exception e) {
            log.warn("IM bot alert send failed (platform={}): {}", platform, e.getMessage());
            return SendOutcome.failed(e.getMessage());
        }
    }
}
