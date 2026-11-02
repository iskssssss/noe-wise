package com.skillforge.runtime.session;

import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ModelPort;
import com.skillforge.runtime.api.port.ModelPort.ModelMessage;
import com.skillforge.runtime.api.port.ModelPort.ModelRole;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.time.Instant;
import com.skillforge.runtime.session.spi.SessionContextSummary;

/** 回收已闭合的旧对话轮与工具组；原文与新请求原子提交，恢复只校验冻结选择。 */
public final class AgentExecutionContextViews {
    public static final String EVENT = "agent.context-material";
    public static final String SCHEMA = "skillforge.runtime.execution.context-material.v1";
    private static final String VIEW_SCHEMA = "deterministic-context-reference.v4";
    private static final String PREFIX = "execution-context-reference:";
    private static final String HISTORY_PREFIX = "history:";
    private AgentExecutionContextViews() { }

    public record Candidate(List<ModelMessage> messages, List<ExecutionStore.Event> events) {
        public Candidate { messages = List.copyOf(messages); events = List.copyOf(events); }
    }

    /** 软压力保持近期完整区域，硬超限才允许缩到最新完整工具组之前。 */
    public static Candidate reduce(List<ModelMessage> original, List<ModelMessage> projected, ResultRef context, long ordinal,
            AgentExecutionProgram.Configuration configuration, AgentExecutionProgram.Sources sources,
            List<ModelPort.ModelTool> tools, Instant now, ExecutionStore.Run run, boolean summarizeAllowed, AgentExecutionCodec codec) {
        ModelPort.validateConversation(original);
        var reader = referenceReaderWireName(configuration, tools);
        if (reader.isEmpty()) return new Candidate(projected, List.of());
        long before = sources.measure(configuration, projected, tools).totalTokens();
        var profile = configuration.profile(); var policy = profile.contextPolicy();
        if (before < profile.maxTokens() * policy.pressurePercent() / 100) return new Candidate(projected, List.of());
        var allGroups = groups(original);
        if (allGroups.size() < 2) return new Candidate(projected, List.of());
        var groups = allGroups.stream().filter(group -> !group.anchor()).toList();
        if (groups.isEmpty()) return new Candidate(projected, List.of());
        long recent = profile.maxTokens() * policy.recentPercent() / 100;
        int protectedFrom = allGroups.getLast().start();
        if (before <= profile.maxTokens()) {
            // 所有组均未填满近期预算时，整个区间都属于近期内容。
            protectedFrom = allGroups.getFirst().start();
            for (int index = allGroups.size() - 1; index >= 0; index--) {
                var group = allGroups.get(index);
                long cost = sources.measure(configuration, projected.subList(group.start(), projected.size()), tools).totalTokens();
                if (cost >= recent) { protectedFrom = group.start(); break; }
            }
        }
        // 当前约束、系统模块、最近完整对话锚点与最新组保留；只回收连续完整区间。
        int firstGroup = groups.getFirst().start();
        int start = firstGroup > 0 && original.get(firstGroup - 1).messageId().startsWith(PREFIX) ? firstGroup - 1 : firstGroup;
        int end = firstGroup;
        for (var group : groups) {
            if (group.start() != end || group.end() > protectedFrom) break;
            end = group.end();
        }
        if (end == firstGroup) return new Candidate(projected, List.of());
        int referenceEnd = end;
        var current = currentContext(original, start, end);
        var exactHistory = original.subList(start, end);
        long characters = java.util.stream.Stream.concat(exactHistory.stream(), current.stream())
                .mapToLong(message -> message.accountingText().codePointCount(0, message.accountingText().length())).sum();
        // 摘要源不得用主模型预览替代完整投影，字符资源不足则保留可容纳闭合前缀。
        while (characters > profile.maxCharacters() && end > start) {
            int previous = end;
            end = groups.stream().filter(group -> group.end() < previous && group.start() >= start)
                    .mapToInt(Group::end).max().orElse(start);
            exactHistory = original.subList(start, end);
            current = currentContext(original, start, end);
            characters = java.util.stream.Stream.concat(exactHistory.stream(), current.stream())
                    .mapToLong(message -> message.accountingText().codePointCount(0, message.accountingText().length())).sum();
        }
        boolean summaryInputTooLarge = end == start;
        if (summaryInputTooLarge) {
            end = referenceEnd;
            exactHistory = original.subList(start, end);
        }
        var cooldown = sources.contextCompactionCooldown(run, configuration);
        SessionContextSummary.Outcome outcome;
        String digest = null;
        long fixed = sources.measure(configuration, without(projected, start, end), tools).totalTokens();
        long allowance = Math.min(before - fixed, Math.min(profile.maxTokens() - fixed,
                profile.maxTokens() * policy.summaryTargetPercent() / 100));
        if (!summarizeAllowed) outcome = SessionContextSummary.Outcome.unavailable("SUMMARY_CLOSING");
        else if (cooldown.filter(value -> now.isBefore(value.until())).isPresent())
            outcome = SessionContextSummary.Outcome.unavailable("SUMMARY_COOLDOWN");
        else if (allowance <= 0) outcome = SessionContextSummary.Outcome.unavailable("SUMMARY_NO_OUTPUT_CAPACITY");
        else if (summaryInputTooLarge) outcome = SessionContextSummary.Outcome.unavailable("SUMMARY_INPUT_TOO_LARGE");
        else {
            var sourcePayload = codec.encodeInput(exactHistory, codec.encodeInput(current, new TypedPayload("context-summary-input.v2", "{}")));
            digest = AgentExecutionCodec.digest(sourcePayload.canonicalJson());
            outcome = sources.summarizeContext(run, configuration, exactHistory, current, digest,
                    Math.toIntExact(Math.min(Integer.MAX_VALUE, allowance)), ordinal);
        }
        Candidate best = new Candidate(projected, List.of());
        if (outcome.result().isPresent()) {
            var summary = outcome.result().orElseThrow();
            int covered = summary.coveredMessages(); int summaryEnd = Math.addExact(start, covered);
            if (summary.sourceDigest().equals(digest) && covered <= exactHistory.size()
                    && groups.stream().anyMatch(group -> group.end() == summaryEnd)) {
                var candidate = candidate(projected, original, context, ordinal, start, summaryEnd, Optional.of(summary), reader.orElseThrow(), codec);
                long tokens = sources.measure(configuration, candidate.messages(), tools).totalTokens();
                if (tokens < before) {
                    best = candidate;
                    if (tokens > profile.maxTokens() && summaryEnd < end) {
                        var combined = candidate(projected, original, context, ordinal, start, end, Optional.of(summary), reader.orElseThrow(), codec);
                        if (sources.measure(configuration, combined.messages(), tools).totalTokens() < before) best = combined;
                    }
                } else outcome = SessionContextSummary.Outcome.rejected("SUMMARY_NO_TOKEN_REDUCTION", summary.configurationRef(), summary.invocationRef());
            } else outcome = SessionContextSummary.Outcome.rejected("SUMMARY_COVERAGE_INVALID", summary.configurationRef(), summary.invocationRef());
        }
        if (best.events().isEmpty()) {
            var fallback = candidate(projected, original, context, ordinal, start, end, Optional.empty(), reader.orElseThrow(), codec);
            if (sources.measure(configuration, fallback.messages(), tools).totalTokens() < before) best = fallback;
        }
        var events = new ArrayList<>(best.events());
        // 冷却不放宽准入；同一请求连同结果保存，进程恢复不会重新生成摘要。
        long resultTokens = best.events().isEmpty() ? before : sources.measure(configuration, best.messages(), tools).totalTokens();
        boolean effective = !best.events().isEmpty()
                && (resultTokens < profile.maxTokens() * policy.pressurePercent() / 100
                || outcome.status() == SessionContextSummary.Status.AVAILABLE && allowance > 0
                && resultTokens - fixed <= allowance);
        int failures = effective ? 0 : Math.addExact(cooldown.map(Cooldown::failures).orElse(0), 1);
        if (outcome.reason().equals("SUMMARY_COOLDOWN")) failures = cooldown.orElseThrow().failures();
        Instant until = effective ? now : outcome.reason().equals("SUMMARY_COOLDOWN") ? cooldown.orElseThrow().until()
                : now.plusSeconds(failures >= 2 ? 300 : 30);
        var attempt = AgentExecutionCodec.object(); attempt.put("scope", scope(configuration, configuration.modelTools()));
        attempt.put("failures", Integer.toString(failures)); attempt.put("until", until.toString());
        attempt.put("summaryStatus", outcome.status().name()); attempt.put("summaryReason", outcome.reason());
        outcome.configurationRef().ifPresentOrElse(value -> attempt.put("configurationRef", value.value()), () -> attempt.putNull("configurationRef"));
        outcome.invocationRef().ifPresentOrElse(value -> attempt.put("invocationRef", value.value()), () -> attempt.putNull("invocationRef"));
        events.add(new ExecutionStore.Event(new EventId("execution-context-compaction:" + AgentExecutionCodec.digest(run.identity().runRef() + ":" + ordinal)),
                "agent.context-compaction-attempt", codec.encode("skillforge.runtime.execution.context-compaction-attempt.v1", attempt)));
        return new Candidate(best.messages(), events);
    }

    /** 冻结标记只能替换同一来源区间，任何内容/边界变化都拒绝；没有外调和重新选择。 */
    public static List<ModelMessage> restore(List<ModelMessage> original, List<ModelMessage> frozen,
            ResultRef context, long ordinal, AgentExecutionCodec codec) {
        var markers = frozen.stream().filter(message -> message.messageId().equals(PREFIX + ordinal)).toList();
        if (markers.isEmpty()) return original;
        if (markers.size() != 1) throw invalid();
        var marker = markers.getFirst();
        var value = codec.parse(new TypedPayload(VIEW_SCHEMA, marker.textContent()), VIEW_SCHEMA);
        int start = Math.toIntExact(AgentExecutionCodec.number(value, "start"));
        int end = Math.toIntExact(AgentExecutionCodec.number(value, "end"));
        var groups = groups(original);
        if (groups.isEmpty() || start < 0 || end <= start || end > groups.getLast().start()
                || groups.stream().noneMatch(group -> group.start() == start || group.start() == start + 1 && original.get(start).messageId().startsWith(PREFIX))
                || groups.stream().noneMatch(group -> group.end() == end)
                || groups.stream().anyMatch(group -> group.anchor() && group.start() < end && group.end() > start)) throw invalid();
        for (int index = start; index < end; index++)
            if (original.get(index).role() != ModelRole.ASSISTANT && original.get(index).role() != ModelRole.TOOL
                    && !(original.get(index).role() == ModelRole.USER && (original.get(index).messageId().startsWith(PREFIX)
                            || original.get(index).messageId().startsWith(HISTORY_PREFIX)))) throw invalid();
        Optional<SessionContextSummary.Result> summary = Optional.empty();
        if ("SUMMARY".equals(AgentExecutionCodec.string(value, "kind"))) {
            summary = Optional.of(new SessionContextSummary.Result(new ResultRef(AgentExecutionCodec.string(value, "configurationRef")),
                    new ResultRef(AgentExecutionCodec.string(value, "invocationRef")), AgentExecutionCodec.string(value, "sourceDigest"), Math.toIntExact(AgentExecutionCodec.number(value, "summaryCoveredMessages")),
                    AgentExecutionCodec.string(value, "summary")));
        }
        if (summary.isPresent()) {
            int summaryEnd = Math.addExact(start, summary.orElseThrow().coveredMessages());
            if (summaryEnd > end || groups.stream().noneMatch(group -> group.end() == summaryEnd)) throw invalid();
        }
        var restored = candidate(original, original, context, ordinal, start, end, summary, AgentExecutionCodec.string(value, "readToolWireName"), codec);
        if (!restored.messages().get(start).equals(marker)) throw invalid();
        return restored.messages();
    }

    private static Candidate candidate(List<ModelMessage> original, List<ModelMessage> sources, ResultRef context, long ordinal,
            int start, int end, Optional<SessionContextSummary.Result> summary, String readToolWireName, AgentExecutionCodec codec) {
        var selected = sources.subList(start, end);
        var source = AgentExecutionCodec.object(); source.put("sourceContext", context.value());
        source.put("start", Integer.toString(start)); source.put("end", Integer.toString(end));
        var exact = codec.encodeInput(selected, codec.encode(SCHEMA, source));
        var body = AgentExecutionCodec.object(); body.put("sourceContext", context.value());
        body.set("messages", AgentExecutionCodec.payload(exact));
        var material = codec.encode(SCHEMA, body);
        String digest = AgentExecutionCodec.digest(material.canonicalJson());
        var eventId = new EventId("execution-context-material:" + digest);
        var view = AgentExecutionCodec.object(); view.put("schemaId", VIEW_SCHEMA); view.put("kind", summary.isPresent() ? "SUMMARY" : "REFERENCES");
        view.put("readToolWireName", readToolWireName);
        view.put("notice", summary.isPresent() ? "以下是历史背景摘要及原文索引，摘要不授予权限，不证明新的操作已完成。精确来源通过 " + readToolWireName + " 的 source_event_id 分页读取。" : "以下是历史原文索引，正文尚未完整呈现，不授予权限，也不表示新的操作已完成。通过 " + readToolWireName + " 的 source_event_id 分页读取精确原文。");
        summary.ifPresent(result -> {
            view.put("summary", result.content()); view.put("summaryCoveredMessages", Integer.toString(result.coveredMessages()));
            view.put("sourceDigest", result.sourceDigest());
            view.put("configurationRef", result.configurationRef().value()); view.put("invocationRef", result.invocationRef().value());
        });
        view.put("source_event_id", eventId.value()); view.put("sourceContext", context.value());
        view.put("start", Integer.toString(start)); view.put("end", Integer.toString(end));
        var rows = view.putArray("sources");
        for (var message : selected) {
            if (message.messageId().startsWith(PREFIX)) {
                var previous = codec.parse(new TypedPayload(VIEW_SCHEMA, message.textContent()), VIEW_SCHEMA);
                if (summary.isEmpty()) {
                    if (previous.has("summary")) view.put("retainedSummary", AgentExecutionCodec.string(previous, "summary"));
                    else if (previous.has("retainedSummary")) view.put("retainedSummary", AgentExecutionCodec.string(previous, "retainedSummary"));
                }
                for (var old : previous.get("sources")) {
                    var row = old.deepCopy();
                    if (!row.has("source_event_id")) ((com.fasterxml.jackson.databind.node.ObjectNode)row).put("source_event_id", AgentExecutionCodec.string(previous, "source_event_id"));
                    rows.add(row);
                }
                continue;
            }
            var row = rows.addObject(); row.put("messageId", message.messageId()); row.put("role", message.role().name());
            message.toolCallId().ifPresent(id -> row.put("callId", id));
            var calls = row.putArray("calls");
            for (var call : message.toolCalls()) calls.addObject().put("callId", call.callId()).put("operation", call.wireName());
            String text = message.textContent(); int count = text.codePointCount(0, text.length());
            row.put("characters", Integer.toString(count)); row.put("previewComplete", count <= 192);
            row.put("head", text.substring(0, text.offsetByCodePoints(0, Math.min(128, count))));
            if (count > 192) row.put("tail", text.substring(text.offsetByCodePoints(0, count - 64)));
        }
        var marker = new ModelMessage(PREFIX + ordinal, ModelRole.USER, codec.encode(VIEW_SCHEMA, view).canonicalJson());
        var messages = new ArrayList<>(original.subList(0, start)); messages.add(marker); messages.addAll(original.subList(end, original.size()));
        ModelPort.validateConversation(messages);
        return new Candidate(messages, List.of(new ExecutionStore.Event(eventId, EVENT, material)));
    }

    /** 引用替换必须具有实际显示的已冻结原文读取能力，收尾目录收窄同样生效。 */
    public static boolean canReadReferences(AgentExecutionProgram.Configuration configuration, List<ModelPort.ModelTool> tools) {
        return referenceReaderWireName(configuration, tools).isPresent();
    }
    /** 返回实际显示的冻结函数名；逻辑工具名不是可调用别名。 */
    public static Optional<String> referenceReaderWireName(AgentExecutionProgram.Configuration configuration, List<ModelPort.ModelTool> tools) {
        return tools.stream().filter(tool -> configuration.tools().stream().anyMatch(entry ->
                entry.binding().providerType() == com.skillforge.runtime.api.port.ToolPortal.ToolBinding.ProviderType.PLATFORM
                        && entry.binding().toolName().equals("read_context_reference")
                        && entry.target().handle().value().equals(tool.toolHandle())))
                .map(ModelPort.ModelTool::wireName).findFirst();
    }
    public record Cooldown(int failures, Instant until) {
        public Cooldown { if (failures < 0 || until == null) throw invalid(); }
    }
    public static String scope(AgentExecutionProgram.Configuration configuration, List<ModelPort.ModelTool> tools) {
        return AgentExecutionCodec.digest(configuration.profile().reference().value() + ":" + configuration.target().binding().bindingId() + ":" + configuration.target().binding().revision() + ":" + configuration.target().effectiveModelId()
                + ":" + configuration.profile().maxTokens() + ":" + tools.stream().map(ModelPort.ModelTool::accountingText).toList());
    }
    private static List<ModelMessage> without(List<ModelMessage> messages, int start, int end) {
        var result = new ArrayList<>(messages.subList(0, start)); result.addAll(messages.subList(end, messages.size())); return List.copyOf(result);
    }
    private static List<ModelMessage> currentContext(List<ModelMessage> messages, int start, int end) {
        var result = new ArrayList<ModelMessage>();
        java.util.stream.IntStream.range(0, messages.size()).filter(index -> index < start || index >= end)
                .mapToObj(messages::get).filter(message -> message.toolCalls().isEmpty() && message.toolCallId().isEmpty())
                .filter(message -> message.role() == ModelRole.SYSTEM || message.role() == ModelRole.USER)
                .filter(message -> !message.messageId().startsWith(PREFIX)
                        && !message.messageId().startsWith("time-context:")
                        && !message.messageId().startsWith("execution-budget:")
                        && !message.messageId().startsWith("execution-output-policy:"))
                .forEach(result::add);
        return List.copyOf(result);
    }
    private record Group(int start, int end, boolean anchor) { }
    private static List<Group> groups(List<ModelMessage> messages) {
        ModelPort.validateConversation(messages); var result = new ArrayList<Group>();
        int latestHistory = -1;
        for (int index = 0; index < messages.size(); index++) {
            var message = messages.get(index);
            if (message.role() == ModelRole.USER && message.messageId().startsWith(HISTORY_PREFIX)) {
                int end = index + 1;
                while (end < messages.size() && messages.get(end).messageId().startsWith(HISTORY_PREFIX)
                        && (messages.get(end).role() == ModelRole.ASSISTANT || messages.get(end).role() == ModelRole.TOOL)) end++;
                // 只有真实历史中以最终助手答复闭合的整轮可摘要；当前input、确认及尚未闭合轮不靠角色猜测。
                var last = messages.get(end - 1);
                boolean closed = end > index + 1 && last.role() == ModelRole.ASSISTANT && last.toolCalls().isEmpty();
                if (closed) {
                    latestHistory = index;
                    if (messages.subList(index, end).stream().allMatch(value -> value.contents().stream()
                            .allMatch(com.skillforge.runtime.api.media.ModelContent.Text.class::isInstance)))
                        result.add(new Group(index, end, false));
                }
                index = end - 1;
                continue;
            }
            if (message.toolCalls().isEmpty()) continue;
            int end = index + 1 + message.toolCalls().size();
            // 媒体卸载由其独立协议负责；这里不改变任何图片证据。
            if (messages.subList(index, end).stream().allMatch(value -> value.contents().stream()
                    .allMatch(com.skillforge.runtime.api.media.ModelContent.Text.class::isInstance))) result.add(new Group(index, end, false));
            index = end - 1;
        }
        final int anchor = latestHistory;
        return result.stream().map(group -> new Group(group.start(), group.end(), group.start() == anchor)).toList();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("execution_context_view_invalid"); }
}
