package com.example.kafkaviz.inspection.support;

import com.example.kafkaviz.inspection.InspectionProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 项目首个 {@code RestClient} Bean(告警外发用)。
 *
 * <p>用 spring-web 自带的 {@code RestClient} 而不是再引 WebClient/OkHttp:
 * 告警外发只是"发一个 JSON POST",零新增依赖比响应式 API 更重要 ——
 * 依赖表每多一项,就多一份需要长期维护与审计的面。
 *
 * <p><b>超时必须设</b>:慢通道(对端挂起、防火墙黑洞)会一直占住发送线程,
 * 而 {@code alertSenderExecutor} 只有 2–4 条线程 —— 不设超时等于让一个坏通道
 * 拖垮全部告警。读超时与连接超时取同一个 {@code inspection.alert.send-timeout-ms}。
 *
 * <p>与 {@code InspectionExecutorConfig} 同理:不加 {@code @ConditionalOnProperty}
 * —— Webhook/IM sender 是无条件的 Bean,巡检总开关关掉时仍要保证上下文能起来。
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient inspectionRestClient(InspectionProperties properties) {
        long timeoutMs = properties.getAlert().getSendTimeoutMs();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(Math.max(1, timeoutMs)));
        factory.setReadTimeout(Duration.ofMillis(Math.max(1, timeoutMs)));
        return RestClient.builder().requestFactory(factory).build();
    }
}
