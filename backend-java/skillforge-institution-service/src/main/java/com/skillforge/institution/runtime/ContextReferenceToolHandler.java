package com.skillforge.institution.runtime;

import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.chat.contract.ContextReferenceLimits;
import com.skillforge.core.capability.connector.PlatformToolHandler;
import com.skillforge.runtime.session.spi.SessionContextReferenceReader;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 参数只定位内部证据；tenant/user/Agent/Session 全部来自已授权的工具调用。 */
@Component
public final class ContextReferenceToolHandler implements PlatformToolHandler {
    private static final int MAX_HISTORY_RECORDS = 100;
    private static final int MAX_HISTORY_WINDOW = 50;
    private static final Set<String> HISTORY_FIELDS = Set.of("mode", "history_run_id", "after_sequence", "limit", "query",
            "from_time", "to_time", "around_source_event_id", "window");
    private static final Set<String> REFERENCE_FIELDS = Set.of("reference_id", "source_event_id", "offset", "limit", "find");

    /** 参数校验阶段的确定拒绝，尚未调用 Reader，不应进入外部效果未知的恢复流程。 */
    public static final class ArgumentsRejected extends IllegalArgumentException {
        private final Map<String, String> fieldErrors;
        public ArgumentsRejected() { this(Map.of("$", "参数必须匹配当前 read_context_reference 工具 schema；历史查询显式传 mode=history，原文读取省略 mode")); }
        private ArgumentsRejected(Map<String, String> fieldErrors) {
            super("context_reference_arguments_rejected");
            this.fieldErrors = Map.copyOf(fieldErrors);
        }
        /** 固定字段指引，不回显用户参数或 Reader 异常，不复制目录 schema。 */
        public Map<String, Object> feedback() {
            return Map.of("success", false, "error", getMessage(), "field_errors", fieldErrors);
        }
    }

    private final ExecutionContextReferenceReader readers;

    public ContextReferenceToolHandler(ExecutionContextReferenceReader readers) { this.readers = readers; }

    @Override public String toolName() { return "read_context_reference"; }

    @Override public Map<String, Object> execute(Map<String, Object> arguments, PlatformToolExecutionContext context) {
        if (context.cancellationSignal().isCancellationRequested() || !Instant.now().isBefore(context.deadlineAt()))
            throw new IllegalStateException("context_reference_read_cancelled");
        if (arguments == null) throw new ArgumentsRejected();
        if (arguments.containsKey("mode") && !"history".equals(arguments.get("mode")))
            throw new ArgumentsRejected(Map.of("mode", "仅支持 mode=history；使用 reference_id/source_event_id 读取原文时省略 mode"));
        if (!arguments.containsKey("mode") && arguments.keySet().stream()
                .anyMatch(field -> HISTORY_FIELDS.contains(field) && !REFERENCE_FIELDS.contains(field)))
            throw new ArgumentsRejected(Map.of("mode", "历史查询及 around_source_event_id/window 必须显式提供 mode=history"));
        if ("history".equals(arguments.get("mode"))) return history(arguments, context);
        if (!REFERENCE_FIELDS.containsAll(arguments.keySet())
                || arguments.containsKey("reference_id") == arguments.containsKey("source_event_id"))
            throw new ArgumentsRejected();
        String referenceId = arguments.get("reference_id") instanceof String value ? value : null;
        String sourceId = arguments.get("source_event_id") instanceof String value ? value : null;
        long offset = integer(arguments.getOrDefault("offset", 0));
        long requestedLimit = integer(arguments.getOrDefault("limit", ContextReferenceLimits.DEFAULT_PAGE_CHARACTERS));
        String literal = arguments.get("find") instanceof String value ? value : null;
        if (arguments.containsKey("find") && (literal == null || literal.isBlank()
                || literal.codePointCount(0, literal.length()) > 200
                || literal.codePoints().anyMatch(point -> point >= 0xD800 && point <= 0xDFFF))) throw new ArgumentsRejected();
        if (offset < 0 || requestedLimit < 1 || requestedLimit > ContextReferenceLimits.MAX_PAGE_CHARACTERS
                || (arguments.containsKey("reference_id") ? referenceId == null || !referenceId.matches("context:[a-f0-9]{64}")
                        : sourceId == null || sourceId.isBlank() || sourceId.length() > 512))
            throw new ArgumentsRejected();
        // limit 是请求上界；首次回读必须完整进入下一冻结请求，不能依赖立刻再次外置救场。
        // Handler 没有本次冻结 tokenizer/窗口，不读取变化中的模型配置；沿用既有安全单页预算。
        int limit = (int) Math.min(requestedLimit, ContextReferenceLimits.MAX_MODEL_PAGE_CHARACTERS);
        var reader = readers.forCall(context);
        SessionContextReferenceReader.LocatedPage located = literal == null ? null : referenceId != null
                ? reader.find(context.run(), referenceId, literal, offset, limit)
                : reader.findSource(context.run(), sourceId, literal, offset, limit);
        if (literal != null && located == null) throw rejected();
        var page = located != null ? located.page() : referenceId != null ? reader.read(context.run(), referenceId, offset, limit)
                : reader.readSource(context.run(), sourceId, offset, limit);
        long actualOffset = located == null ? offset : located.offset();
        if (actualOffset < offset) throw rejected();
        if (sourceId != null && (page == null || page.reference() == null
                || !sourceId.equals(page.reference().sourceEventId().value()))) throw rejected();
        validatePage(context, referenceId == null ? page.reference().referenceId() : referenceId, actualOffset, limit, page);
        if (located != null && (located.matched() ? page.content().isEmpty()
                || !(page.content().startsWith(literal) || literal.startsWith(page.content()))
                : !page.content().isEmpty() || actualOffset != page.reference().characters() || !page.end())) throw rejected();
        var result = new java.util.LinkedHashMap<String, Object>(Map.of("success", true, "reference_id", page.reference().referenceId(), "content", page.content(),
                "next_offset", page.nextOffset(), "end", page.end(), "source_complete", page.reference().sourceComplete(),
                "source_characters", page.reference().characters()));
        result.put("offset", actualOffset);
        if (located != null) result.put("match_found", located.matched());
        readers.forCall(context);
        if (context.cancellationSignal().isCancellationRequested() || !Instant.now().isBefore(context.deadlineAt()))
            throw new IllegalStateException("context_reference_read_cancelled");
        return Map.copyOf(result);
    }

    private Map<String, Object> history(Map<String, Object> arguments, PlatformToolExecutionContext context) {
        if (!HISTORY_FIELDS.containsAll(arguments.keySet())
                || arguments.containsKey("history_run_id") && (!(arguments.get("history_run_id") instanceof String)
                || ((String) arguments.get("history_run_id")).isBlank()
                || ((String) arguments.get("history_run_id")).length() > 512)
                || arguments.containsKey("query") && (!(arguments.get("query") instanceof String)
                || ((String) arguments.get("query")).isBlank()
                || ((String) arguments.get("query")).codePointCount(
                        0, ((String) arguments.get("query")).length()) > 200)
                || arguments.containsKey("from_time") && !(arguments.get("from_time") instanceof String)
                || arguments.containsKey("to_time") && !(arguments.get("to_time") instanceof String)
                || arguments.containsKey("around_source_event_id")
                && (!(arguments.get("around_source_event_id") instanceof String)
                || ((String) arguments.get("around_source_event_id")).isBlank()
                || ((String) arguments.get("around_source_event_id")).length() > 512))
            throw new ArgumentsRejected();
        var runId = java.util.Optional.ofNullable((String) arguments.get("history_run_id"));
        var queryText = java.util.Optional.ofNullable((String) arguments.get("query"));
        var fromTime = instant(arguments.get("from_time"));
        var toTime = instant(arguments.get("to_time"));
        var aroundSourceEventId = java.util.Optional.ofNullable((String) arguments.get("around_source_event_id"));
        long after = integer(arguments.getOrDefault("after_sequence", 0));
        long limit = integer(arguments.getOrDefault("limit", 20));
        long window = integer(arguments.getOrDefault("window", 20));
        if (limit < 1 || limit > MAX_HISTORY_RECORDS)
            throw new ArgumentsRejected(Map.of("limit", "mode=history 的 limit 按扫描原事件数，合法范围为 1 至 " + MAX_HISTORY_RECORDS
                    + "；原文字符分页请按当前工具 schema 使用 reference_id/source_event_id，允许请求上界为 "
                    + ContextReferenceLimits.MAX_PAGE_CHARACTERS + " Unicode字符"));
        if (after < 0 || window < 1 || window > MAX_HISTORY_WINDOW
                || fromTime.isPresent() && toTime.isPresent() && fromTime.get().isAfter(toTime.get())
                || aroundSourceEventId.isPresent()
                && (arguments.containsKey("after_sequence") || arguments.containsKey("limit") || queryText.isPresent()
                || fromTime.isPresent() || toTime.isPresent())
                || aroundSourceEventId.isEmpty() && arguments.containsKey("window"))
            throw new ArgumentsRejected();
        var historyQuery = new SessionContextReferenceReader.HistoryQuery(runId, after, (int) limit,
                queryText, fromTime, toTime, aroundSourceEventId, (int) window);
        var page = readers.forCall(context).history(context.run(), historyQuery);
        long maxRecords = aroundSourceEventId.isPresent() ? 2L * window + 1 : limit;
        if (page == null || page.records().size() > maxRecords) throw rejected();
        long previous;
        long upper = Long.MAX_VALUE;
        if (aroundSourceEventId.isPresent()) {
            var anchor = page.records().stream()
                    .filter(entry -> aroundSourceEventId.orElseThrow().equals(entry.sourceEventId()))
                    .findFirst().orElseThrow(ContextReferenceToolHandler::rejected);
            previous = Math.max(0, anchor.sequence() - window - 1);
            try { upper = Math.addExact(anchor.sequence(), window); }
            catch (ArithmeticException overflow) { throw rejected(); }
            if (!page.end() || page.nextSequence() < anchor.sequence() || page.nextSequence() > upper) throw rejected();
        } else {
            previous = after;
            if (page.nextSequence() < after || !page.end() && page.nextSequence() == after) throw rejected();
        }
        for (var entry : page.records()) {
            if (entry.sequence() <= previous || entry.sequence() > page.nextSequence()
                    || entry.sequence() > upper || entry.sourceEventId() == null || entry.sourceEventId().isBlank()
                    || entry.runId() == null || entry.runId().isBlank() || entry.occurredAt() == null || entry.content() == null
                    || runId.isPresent() && !runId.orElseThrow().equals(entry.runId())
                    || fromTime.filter(bound -> entry.occurredAt().isBefore(bound)).isPresent()
                    || toTime.filter(bound -> entry.occurredAt().isAfter(bound)).isPresent()
                    || queryText.isPresent() && !containsIgnoreCase(entry.content(), queryText.orElseThrow()))
                throw rejected();
            previous = entry.sequence();
        }
        int recordLimit = Math.min(ContextReferenceLimits.MAX_HISTORY_RECORD_CHARACTERS,
                ContextReferenceLimits.MAX_HISTORY_PAGE_CHARACTERS / Math.max(1, page.records().size()));
        var result = Map.of("success", true, "records", page.records().stream().map(entry -> {
            int characters = entry.content().codePointCount(0, entry.content().length());
            boolean truncated = characters > recordLimit;
            String content = truncated ? entry.content().substring(0, entry.content().offsetByCodePoints(
                    0, recordLimit)) : entry.content();
            var record = new java.util.LinkedHashMap<String, Object>(Map.of(
                    "source_event_id", entry.sourceEventId(), "run_id", entry.runId(), "sequence", entry.sequence(),
                    "occurred_at", entry.occurredAt().toString(), "content", content, "content_truncated", truncated));
            queryText.ifPresent(query -> record.put("query_match_in_content", containsIgnoreCase(content, query)));
            return Map.copyOf(record);
        }).toList(),
                "next_sequence", page.nextSequence(), "end", page.end());
        readers.forCall(context);
        if (context.cancellationSignal().isCancellationRequested() || !Instant.now().isBefore(context.deadlineAt()))
            throw new IllegalStateException("context_reference_read_cancelled");
        return result;
    }

    private static java.util.Optional<Instant> instant(Object value) {
        if (value == null) return java.util.Optional.empty();
        try {
            return java.util.Optional.of(Instant.parse((String) value));
        } catch (java.time.format.DateTimeParseException rejected) {
            throw new ArgumentsRejected();
        }
    }

    private static boolean containsIgnoreCase(String content, String query) {
        return content.toLowerCase(java.util.Locale.ROOT).contains(query.toLowerCase(java.util.Locale.ROOT));
    }

    /** Reader 负责实时授权；工具出口仍拒绝归属漂移和无法证明连续性的分页结果。 */
    private static void validatePage(PlatformToolExecutionContext context, String requestedReferenceId,
                                     long offset, int limit, SessionContextReferenceReader.Page page) {
        if (page == null || page.reference() == null || page.content() == null) throw rejected();
        var reference = page.reference();
        long contentCharacters = page.content().codePointCount(0, page.content().length());
        long expectedNext;
        try { expectedNext = Math.addExact(offset, contentCharacters); }
        catch (ArithmeticException overflow) { throw rejected(); }
        if (!Objects.equals(reference.owner(), context.run().owner())
                || !requestedReferenceId.equals(reference.referenceId())
                || contentCharacters > limit || contentCharacters == 0 && !page.end()
                || page.nextOffset() != expectedNext
                || page.nextOffset() > reference.characters()
                || page.end() != (page.nextOffset() == reference.characters())) throw rejected();
    }

    private static long integer(Object value) {
        if (!(value instanceof Number number)) throw new ArgumentsRejected();
        try { return new BigDecimal(number.toString()).longValueExact(); }
        catch (ArithmeticException | NumberFormatException failure) {
            throw new ArgumentsRejected();
        }
    }

    private static IllegalStateException rejected() {
        return new IllegalStateException("context_reference_read_rejected");
    }
}
