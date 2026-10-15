package com.example.kafkaviz.inspection.alert;

import com.example.kafkaviz.inspection.InspectionProperties;
import com.example.kafkaviz.inspection.model.AlertChannel;
import com.example.kafkaviz.inspection.support.ChannelConfig;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Properties;

/**
 * SMTP 邮件通道。
 *
 * <p><b>每通道动态构建 {@code JavaMailSenderImpl}</b>:SMTP 主机/账号/口令是该通道
 * 自己的配置(密文存在 {@code config_json}),不是全局 {@code spring.mail.*} ——
 * 后者会把口令逼回明文配置文件。
 *
 * <p><b>可测性</b>:传输与构造分离 —— {@link MimeMessage} 构造完交给
 * {@link #mailTransport}(默认 {@code Transport::send}),集成测试用记录型替身替换它,
 * 既不连真实 SMTP 也不需要引 GreenMail。字段刻意是包级可见(非 private):
 * 测试要与生产代码同包才能换掉它,而把它做成 public setter 会留下一个
 * "生产代码也能随手改传输方式"的口子。
 */
@Component
public class EmailAlertSender implements AlertSender {

    private static final Logger log = LoggerFactory.getLogger(EmailAlertSender.class);
    /** 默认 SMTP 端口(配置缺失时)。 */
    private static final int DEFAULT_PORT = 25;
    private final long sendTimeoutMs;
    /**
     * 包级可见:IT 可替换成记录型替身。{@code volatile} 保证替换对发送线程可见
     * (test 路径虽是同线程直发,分发路径经 alertSenderExecutor 是跨线程的);
     * 替换方负责在结束时还原 —— 它是单例上的共享状态。
     */
    volatile MailTransport mailTransport = Transport::send;

    public EmailAlertSender(InspectionProperties properties) {
        this.sendTimeoutMs = properties.getAlert().getSendTimeoutMs();
    }

    private static InternetAddress[] addresses(List<String> raw) throws Exception {
        InternetAddress[] out = new InternetAddress[raw.size()];
        for (int i = 0; i < raw.size(); i++) {
            out[i] = new InternetAddress(raw.get(i));
        }
        return out;
    }

    private static String subject(ChannelConfig config, AlertMessage message) {
        String prefix = config.getOrDefault("subjectPrefix", "[Kafka告警]");
        return prefix + " " + message.title();
    }

    private static String body(AlertMessage message) {
        StringBuilder sb = new StringBuilder();
        sb.append(message.title()).append('\n').append('\n');
        if (message.message() != null) {
            sb.append(message.message()).append('\n');
        }
        if (message.metricValues() != null && !message.metricValues().isEmpty()) {
            sb.append('\n');
            message.metricValues().forEach((k, v) -> sb.append(k).append(" = ").append(v).append('\n'));
        }
        return sb.toString();
    }

    @Override
    public String type() {
        return AlertChannel.EMAIL;
    }

    @Override
    public SendOutcome send(AlertMessage message, ChannelConfig config) {
        String host = config.get("host");
        List<String> to = config.getList("to");
        if (host == null || host.isBlank()) {
            return SendOutcome.failed("SMTP host is not configured");
        }
        if (to.isEmpty()) {
            return SendOutcome.failed("SMTP recipient (to) is not configured");
        }
        try {
            JavaMailSenderImpl sender = buildSender(config);
            MimeMessage mime = sender.createMimeMessage();
            String from = config.getOrDefault("username", "kafkaviz@localhost");
            mime.setFrom(new InternetAddress(from));
            mime.setRecipients(Message.RecipientType.TO, addresses(to));
            List<String> cc = config.getList("cc");
            if (!cc.isEmpty()) {
                mime.setRecipients(Message.RecipientType.CC, addresses(cc));
            }
            mime.setSubject(subject(config, message), "UTF-8");
            mime.setText(body(message), "UTF-8");
            mailTransport.send(mime);
            return SendOutcome.ok();
        } catch (Exception e) {
            log.warn("Email alert send failed: {}", e.getMessage());
            return SendOutcome.failed(e.getMessage());
        }
    }

    private JavaMailSenderImpl buildSender(ChannelConfig config) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(config.get("host"));
        sender.setPort(config.getInt("port", DEFAULT_PORT));
        String username = config.get("username");
        if (username != null && !username.isBlank()) {
            sender.setUsername(username);
        }
        String password = config.get("password");
        if (password != null && !password.isBlank()) {
            sender.setPassword(password);
        }
        Properties props = new Properties();
        props.put("mail.smtp.timeout", String.valueOf(sendTimeoutMs));
        props.put("mail.smtp.connectiontimeout", String.valueOf(sendTimeoutMs));
        props.put("mail.smtp.writetimeout", String.valueOf(sendTimeoutMs));
        // starttls / ssl 由通道配置决定(端口 465 通常要 ssl、587 通常要 starttls)
        if (config.getBool("ssl", false)) {
            props.put("mail.smtp.ssl.enable", "true");
        }
        if (config.getBool("starttls", false)) {
            props.put("mail.smtp.starttls.enable", "true");
        }
        sender.setJavaMailProperties(props);
        return sender;
    }

    /** 发送动作的最小契约(仅用于替换真实 SMTP 传输)。 */
    @FunctionalInterface
    public interface MailTransport {
        void send(MimeMessage message) throws MessagingException;
    }
}
