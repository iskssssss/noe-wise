package com.skillforge.institution.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.capability.connector.PlatformToolHandler;
import com.skillforge.runtime.session.SessionToolDiscovery;
import com.skillforge.runtime.session.SessionToolNames;
import com.skillforge.runtime.session.spi.SessionToolDirectory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 目录身份只来自真实调用方；目录摘录不改变本次模型完整定义，也不执行被发现工具。 */
@Component
public final class ToolSearchToolHandler implements PlatformToolHandler {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ToolSearchToolHandler.class);
    private static final int MAX_EXCERPT_CHARACTERS = 400;
    private static final int EXCERPT_HEAD_CHARACTERS = 120;
    private static final int MATCH_LEADING_CHARACTERS = 40;
    private static final String EXCERPT_PREFIX = "摘录：";
    private static final String NO_MATCH_HINT = "当前查询在本次冻结的已授权工具目录中没有词法匹配；如仍需该能力，可改用已知工具名或更短关键词。不要重复相同查询或猜测工具句柄。";
    private static final String MORE_MATCHES_HINT = "还有匹配条目未列出；当前结果不适用时可用更具体的工具名或关键词缩小范围。";
    private static final TypeReference<Map<String, Object>> RESULT_TYPE = new TypeReference<>() { };
    private final SessionToolDirectory directory;
    private final ObjectMapper mapper;

    public ToolSearchToolHandler(SessionToolDirectory directory, ObjectMapper mapper) {
        this.directory = directory;
        this.mapper = mapper;
    }
    @Override public String toolName() { return SessionToolDiscovery.SEARCH; }

    /** 搜索冻结 Run 的完整授权目录，结果只投影稳定模型身份，不暴露输入相关执行 handle。 */
    @Override public Map<String, Object> execute(Map<String, Object> arguments, PlatformToolExecutionContext context) {
        LOG.debug("搜索冻结工具目录进入");
        try {
            if (context.cancellationSignal().isCancellationRequested() || !Instant.now().isBefore(context.deadlineAt()))
                return failure("cancelled");
            if (arguments == null || !Set.of("query", "limit").containsAll(arguments.keySet())
                    || !(arguments.get("query") instanceof String query)) return failure("invalid_arguments");
            int limit;
            try { limit = new BigDecimal(arguments.getOrDefault("limit", 5).toString()).intValueExact(); }
            catch (RuntimeException invalid) { return failure("invalid_arguments"); }
            final SessionToolDiscovery.SearchPage page;
            final Map<String, String> modelIdentities;
            try {
                var current = List.copyOf(directory.list(context.run()));
                modelIdentities = SessionToolDirectory.requireModelIdentities(current,
                        directory.modelIdentities(context.run(), current));
                page = SessionToolDiscovery.searchPage(current, query, limit);
            } catch (IllegalArgumentException invalid) {
                return failure("invalid_arguments");
            }
            if (context.cancellationSignal().isCancellationRequested() || !Instant.now().isBefore(context.deadlineAt()))
                return failure("cancelled");
            var tools = page.matches().stream().map(tool -> new Match(
                    SessionToolNames.wireName(modelIdentities.get(tool.target().handle().value()), tool.discovery().name()), tool.discovery().qualifiedName(),
                    excerpt(tool.discovery(), query))).toList();
            String hint = tools.isEmpty() ? NO_MATCH_HINT : page.hasMore() ? MORE_MATCHES_HINT : null;
            return response(new Result(true, tools, page.hasMore(), hint, null));
        } finally {
            LOG.debug("搜索冻结工具目录退出");
        }
    }

    /** 摘录保留开头限制和后段命中线索，不能替代模型当前完整定义中的参数及调用限制。 */
    private static String excerpt(SessionToolDirectory.Discovery discovery, String query) {
        String text = discovery.title().map(title -> title + "；").orElse("") + discovery.description();
        int length = text.codePointCount(0, text.length());
        if (length <= MAX_EXCERPT_CHARACTERS) return text;
        int budget = MAX_EXCERPT_CHARACTERS - EXCERPT_PREFIX.length() - 1;
        int match = firstMatch(text, query);
        int matchCharacter = text.codePointCount(0, match < 0 ? 0 : match);
        if (matchCharacter < budget) return EXCERPT_PREFIX + slice(text, 0, budget) + "…";
        int start = Math.max(EXCERPT_HEAD_CHARACTERS, matchCharacter - MATCH_LEADING_CHARACTERS);
        int end = Math.min(length, start + budget - EXCERPT_HEAD_CHARACTERS - 1);
        return EXCERPT_PREFIX + slice(text, 0, EXCERPT_HEAD_CHARACTERS) + "…" + slice(text, start, end)
                + (end < length ? "…" : "");
    }

    private static int firstMatch(String text, String query) {
        var terms = Arrays.stream(query.strip().split("[\\s,，;；]+"))
                .filter(term -> !term.isEmpty()).distinct().toList();
        for (int index = 0; index < text.length(); index += Character.charCount(text.codePointAt(index))) {
            for (String term : terms) if (text.regionMatches(true, index, term, 0, term.length())) return index;
        }
        return -1;
    }

    private static String slice(String text, int start, int end) {
        return text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end));
    }

    private Map<String, Object> failure(String error) { return response(new Result(false, List.of(), null, null, error)); }
    private Map<String, Object> response(Result result) { return mapper.convertValue(result, RESULT_TYPE); }

    private record Match(String handle, String name, String description) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record Result(boolean success, List<Match> tools, @JsonProperty("has_more") Boolean hasMore,
                          String hint, String error) { }
}
