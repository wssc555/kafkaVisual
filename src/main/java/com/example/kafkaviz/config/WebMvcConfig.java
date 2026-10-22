package com.example.kafkaviz.config;

import com.example.kafkaviz.kafka.ClusterConnectionManager;
import com.example.kafkaviz.web.ClusterArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * MVC 扩展点注册。
 *
 * <p>注册两个:
 * <ul>
 *   <li>{@link ClusterArgumentResolver} —— 把 {@code /api/c/{clusterId}/...} 的集群段
 *       解析成 {@code ClusterConnection} / {@code ClusterDefinition} 注入控制器方法参数;</li>
 *   <li>CORS 白名单 —— Electron 桌面模式下,前端由主进程的本地静态服务器托管
 *       (origin 为 {@code http://127.0.0.1:<随机端口>}),请求直连 sidecar 的
 *       {@code http://127.0.0.1:<另一个随机端口>},因此必须放行回环地址的任意端口。
 *       {@code localhost:*} 同时覆盖浏览器开发态(vite 5173,本走 proxy)与直连调试。</li>
 * </ul>
 *
 * <p>只放行回环地址、不使用 {@code *} 通配:桌面壳不暴露到外网,但后端可能被部署在
 * 开发机上,过宽的 CORS 会让任意网页读到集群数据。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ClusterConnectionManager connectionManager;

    public WebMvcConfig(ClusterConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new ClusterArgumentResolver(connectionManager));
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://127.0.0.1:*", "http://localhost:*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
