package com.skillforge.runtime.session;

import com.skillforge.runtime.api.port.ToolPortal.ToolBinding;
import com.skillforge.runtime.session.spi.SessionToolDirectory;
import java.util.*;

/** 当前已授权冻结目录的纯搜索；不读旧checkpoint、准备树或历史结果。 */
public final class SessionToolDiscovery {
    public static final String SEARCH="search_tools";
    /** v1仅在已授权目录内按名称/描述子串搜索；未来改权重或分词须另立版本。 */
    static final String POLICY_ID = "authorized-tool-name-description-substring";
    static final int POLICY_VERSION = 1;
    private static final int NAME_MATCH_WEIGHT = 4;
    private static final int DESCRIPTION_MATCH_WEIGHT = 1;
    private SessionToolDiscovery() { }
    /** 只在确有额外匹配时声明更多；短目录仍保留原冻结条目与排序。 */
    public record SearchPage(List<SessionToolDirectory.Entry> matches, boolean hasMore) {
        public SearchPage { matches = List.copyOf(matches); }
    }
    /** 搜索输入已经过当前目录授权；仅返回短目录，不把完整 Schema 或无关条目送入上下文。 */
    public static List<SessionToolDirectory.Entry> search(List<SessionToolDirectory.Entry> tools, String query, int limit) {
        return searchPage(tools, query, limit).matches();
    }

    /** 多取一个候选判断遗漏，不以返回数恰好达到 limit 推断还有匹配。 */
    public static SearchPage searchPage(List<SessionToolDirectory.Entry> tools, String query, int limit) {
        if (query == null || query.isBlank() || query.codePointCount(0, query.length()) > 200 || limit < 1 || limit > 12)
            throw new IllegalArgumentException("tool_search_arguments_rejected");
        var normalized = query.strip();
        var stableOrder = Comparator.comparing((SessionToolDirectory.Entry tool) -> tool.discovery().qualifiedName())
                .thenComparing(tool -> tool.target().handle().value());
        // 精确名称只在当前授权目录内选中原条目；名称不转换为执行身份，也不补查目录外工具。
        var exact = tools.stream().filter(tool -> !platform(tool, SEARCH))
                .filter(tool -> tool.discovery().qualifiedName().equalsIgnoreCase(normalized))
                .sorted(stableOrder).limit(limit + 1L).toList();
        if (!exact.isEmpty()) return page(exact, limit);
        var terms = Arrays.stream(normalized.toLowerCase(Locale.ROOT).split("[\\s,，;；]+"))
                .filter(term -> !term.isEmpty()).distinct().toList();
        var matches = tools.stream().filter(tool -> !platform(tool, SEARCH)).filter(tool -> score(tool, terms) > 0)
                .sorted(Comparator.<SessionToolDirectory.Entry>comparingInt(tool -> score(tool, terms)).reversed()
                        .thenComparing(stableOrder))
                .limit(limit + 1L).toList();
        return page(matches, limit);
    }

    private static SearchPage page(List<SessionToolDirectory.Entry> matches, int limit) {
        return new SearchPage(matches.subList(0, Math.min(matches.size(), limit)), matches.size() > limit);
    }

    private static int score(SessionToolDirectory.Entry tool, List<String> terms) {
        var name = tool.discovery().qualifiedName().toLowerCase(Locale.ROOT);
        var description = (tool.discovery().title().orElse("") + " " + tool.discovery().description()).toLowerCase(Locale.ROOT);
        int score = 0;
        for (var term : terms) {
            if (name.contains(term)) score += NAME_MATCH_WEIGHT;
            if (description.contains(term)) score += DESCRIPTION_MATCH_WEIGHT;
        }
        return score;
    }

    private static boolean platform(SessionToolDirectory.Entry tool, String name) {
        return tool.binding().providerType() == ToolBinding.ProviderType.PLATFORM && name.equals(tool.binding().toolName());
    }

}
