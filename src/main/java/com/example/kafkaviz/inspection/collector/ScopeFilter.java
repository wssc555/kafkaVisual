package com.example.kafkaviz.inspection.collector;

import java.util.List;
import java.util.Set;

/**
 * {@code scope_filter} 的三态解析。
 *
 * <p>刻意<b>只支持三态</b>(不支持正则):
 * <ul>
 *   <li>{@code *} 或空 —— 全部实例;</li>
 *   <li>{@code pay-*} —— 前缀匹配(单 {@code *} 且必须在末尾);</li>
 *   <li>{@code a,b,c} —— 精确名列表(逗号分隔,两侧空白被裁掉)。</li>
 * </ul>
 *
 * <p>不支持正则的理由是防 ReDoS:过滤串由用户在 UI 里填,回溯型正则会给出
 * 一个"输入即卡死后端"的低成本攻击面,而三态已覆盖全部真实需求。
 */
public final class ScopeFilter {

    public static final ScopeFilter ALL = new ScopeFilter(Kind.ALL, null, Set.of());
    private final Kind kind;
    private final String prefix;
    private final Set<String> names;
    private ScopeFilter(Kind kind, String prefix, Set<String> names) {
        this.kind = kind;
        this.prefix = prefix;
        this.names = names;
    }

    /**
     * 解析过滤串。
     *
     * <p>null / 空白按"全部"处理(集群级规则的列本就为 null)。
     * 无法判定的输入(如中间带 {@code *})一律按精确名处理 —— 宁可匹配不到,
     * 也不能静默扩大匹配范围(那会让规则意外覆盖不该管的 topic)。
     */
    public static ScopeFilter parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ALL;
        }
        String value = raw.trim();
        if ("*".equals(value)) {
            return ALL;
        }
        if (value.endsWith("*") && value.indexOf('*') == value.length() - 1 && value.length() > 1) {
            return new ScopeFilter(Kind.PREFIX, value.substring(0, value.length() - 1), Set.of());
        }
        Set<String> names = java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        if (names.isEmpty()) {
            return ALL;
        }
        return new ScopeFilter(Kind.EXACT, null, names);
    }

    public Kind kind() {
        return kind;
    }

    public boolean isAll() {
        return kind == Kind.ALL;
    }

    public boolean matches(String name) {
        if (name == null) {
            return false;
        }
        return switch (kind) {
            case ALL -> true;
            case PREFIX -> name.startsWith(prefix);
            case EXACT -> names.contains(name);
        };
    }

    /**
     * 精确名列表(ZK scope 的"显式 path 列表"语义)。
     *
     * <p>非 EXACT 时返回空列表 —— ZK 级不支持通配遍历(ZK 树可以极深,
     * 通配展开等于一次全树扫描),路径必须由用户显式列出。
     */
    public List<String> exactNames() {
        return kind == Kind.EXACT ? List.copyOf(names) : List.of();
    }

    /** 过滤给定实例集合(保持输入顺序)。 */
    public List<String> apply(List<String> candidates) {
        return candidates.stream().filter(this::matches).toList();
    }

    /** 回显原过滤串(日志/摘要用);Kafka 名称大小写敏感,这里不做任何折叠。 */
    public String describe() {
        return switch (kind) {
            case ALL -> "*";
            case PREFIX -> prefix + "*";
            case EXACT -> String.join(",", names);
        };
    }

    public enum Kind { ALL, PREFIX, EXACT }
}
