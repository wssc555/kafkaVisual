package com.example.kafkaviz.web;

import com.example.kafkaviz.kafka.ClusterConnection;
import com.example.kafkaviz.kafka.ClusterConnectionManager;
import com.example.kafkaviz.kafka.ClusterDefinition;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

/**
 * 把 {@code /api/c/{clusterId}/...} 里的集群段解析成已经连上的
 * {@link ClusterConnection}。
 *
 * <p>解析顺序(先具体后宽松):
 * <ol>
 *   <li>URI 模板变量 {@code clusterId}(Spring 由 {@code HandlerMapping} 放进请求属性);</li>
 *   <li>请求属性 {@code clusterId}(供过滤器/测试直接设置,不必依赖路径模板)。</li>
 * </ol>
 *
 * <p>按<b>声明的参数类型</b>决定产出什么:
 * <table border="1">
 *   <tr><th>参数类型</th><th>行为</th></tr>
 *   <tr><td>{@link ClusterConnection}</td><td>{@code getOrConnect(id)} —— 惰性建连;
 *       配置不存在 → 40404;未连上/连接中 → 50302</td></tr>
 *   <tr><td>{@link ClusterDefinition}</td><td>{@code requireExists(id)} —— 只查配置表,
 *       <b>不</b>触碰 Kafka,供离线可用的端点(归档/收藏)使用</td></tr>
 * </table>
 *
 * <p>因为不支持的类型不会被本 resolver 接管,所以不会与 Spring 内置的
 * {@code @PathVariable} / {@code @RequestBody} 等解析器冲突。
 */
public class ClusterArgumentResolver implements HandlerMethodArgumentResolver {

    /** 请求属性名,与路径变量同名。 */
    public static final String CLUSTER_ID_ATTRIBUTE = "clusterId";

    private final ClusterConnectionManager connectionManager;

    public ClusterArgumentResolver(ClusterConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    private static long parse(String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid clusterId: '" + raw + "' (expected a numeric id)");
        }
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        if (parameter.hasParameterAnnotation(ClusterId.class)) {
            return true;
        }
        Class<?> type = parameter.getParameterType();
        return ClusterConnection.class.isAssignableFrom(type)
                || ClusterDefinition.class.isAssignableFrom(type);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        long clusterId = extractClusterId(webRequest);
        ClusterId annotation = parameter.getParameterAnnotation(ClusterId.class);
        Class<?> type = parameter.getParameterType();

        // existenceOnly 优先于类型:显式说了"只校验存在性"就不建连
        boolean existenceOnly = (annotation != null && annotation.existenceOnly())
                || ClusterDefinition.class.isAssignableFrom(type);

        if (existenceOnly) {
            return connectionManager.requireExists(clusterId);
        }
        if (ClusterConnection.class.isAssignableFrom(type)) {
            return connectionManager.getOrConnect(clusterId);
        }
        // @ClusterId 标在了普通类型(long/String)上:只做存在性校验,返回 id
        if (annotation != null) {
            connectionManager.requireExists(clusterId);
            return clusterId;
        }
        throw new IllegalStateException(
                "Unsupported @ClusterId parameter type: " + parameter.getParameterType().getName());
    }

    /**
     * 取集群 id。
     *
     * <p>URI 模板变量由 Spring 放在 {@code HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE}
     * 这个请求属性里;找不到时再退到同名请求属性。
     *
     * @throws IllegalArgumentException 两边都取不到 → 400/40001(端点定义错误,不该静默)
     */
    private long extractClusterId(NativeWebRequest webRequest) {
        Object templateVars = webRequest.getAttribute(
                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (templateVars instanceof Map<?, ?> map) {
            Object raw = map.get(CLUSTER_ID_ATTRIBUTE);
            if (raw != null) {
                return parse(raw.toString());
            }
        }
        Object attribute = webRequest.getAttribute(CLUSTER_ID_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (attribute != null) {
            return parse(attribute.toString());
        }
        throw new IllegalArgumentException(
                "Missing '{" + CLUSTER_ID_ATTRIBUTE + "}' path variable — is the endpoint mapped under /api/c/{"
                        + CLUSTER_ID_ATTRIBUTE + "}/... ?");
    }
}
