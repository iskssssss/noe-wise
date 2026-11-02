package com.skillforge.runtime.session;

import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.media.ModelContent;
import com.skillforge.runtime.api.port.ModelPort;
import com.skillforge.runtime.api.port.ModelPort.ModelMessage;
import com.skillforge.runtime.api.port.ModelPort.ModelRole;
import com.skillforge.runtime.api.port.ModelPort.ModelTool;
import com.skillforge.runtime.api.port.ToolPortal;
import com.skillforge.runtime.session.AgentExecutionProgram.Configuration;
import com.skillforge.runtime.session.AgentExecutionProgram.Sources;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 工具正文的有界安全预览；完整闭合组与原生协议另存为同次准备的原文材料。 */
public final class AgentExecutionToolPreviews {
    private static final String SCHEMA = "skillforge.runtime.execution.tool-preview.v2";
    private static final String SOURCE_SCHEMA = "skillforge.runtime.execution.tool-preview-source.v1";
    private static final String MARKER = "{\"schemaId\":\"" + SCHEMA + "\"";
    private AgentExecutionToolPreviews() { }

    public record Projection(List<ModelMessage> messages, List<ExecutionStore.Event> events) {
        public Projection { messages = List.copyOf(messages); events = List.copyOf(events); }
    }

    public static Projection prepare(List<ModelMessage> originals, ResultRef context, Configuration config,
            Sources sources, List<ModelTool> tools, long ordinal, AgentExecutionCodec codec) {
        var reader = AgentExecutionContextViews.referenceReaderWireName(config, tools);
        if (reader.isEmpty()) return new Projection(originals, List.of());
        ModelPort.validateConversation(originals);
        var measurement = sources.measure(config, originals, tools);
        var policy = config.profile().contextPolicy();
        boolean pressure = measurement.totalTokens() >= config.profile().maxTokens() * policy.pressurePercent() / 100;
        int latestGroup = java.util.stream.IntStream.range(0, originals.size())
                .filter(index -> !originals.get(index).toolCalls().isEmpty()).max().orElse(-1);
        var messages = new ArrayList<>(originals);
        var events = new ArrayList<ExecutionStore.Event>();
        for (int start = 0; start < originals.size(); start++) {
            var assistant = originals.get(start);
            if (assistant.toolCalls().isEmpty()) continue;
            int end = start + 1 + assistant.toolCalls().size();
            var group = originals.subList(start, end);
            ExecutionStore.Event material = null;
            boolean changed = false;
            for (int index = start + 1; index < end; index++) {
                var original = originals.get(index);
                // 图像不经过文字截断；原文回读至少完整进入下一请求，不能读取后立即再外置。
                if (original.contents().stream().anyMatch(part -> !(part instanceof ModelContent.Text))) continue;
                boolean readback = readback(config, assistant.toolCalls().get(index - start - 1).wireName());
                boolean immediate = measurement.tokensForMessage(index) > policy.immediateToolTokens();
                int length = characters(original.textContent());
                if (readback && start == latestGroup
                        || !immediate && (!pressure || length <= policy.pressureToolCharacters())) continue;
                int head = policy.previewHeadCharacters(), tail = policy.previewTailCharacters();
                if (immediate) {
                    head = (head + tail) / 2; tail = head;
                    // 小窗口的 token 阈值可能低于默认预览长度，先留出可裁剪区间。
                    if ((long) head + tail >= length) head = tail = length / 4;
                }
                if (material == null) material = material(group, context, codec);
                Optional<ModelMessage> preview = preview(original, material.id(), ordinal, head, tail,
                        policy.pressureToolCharacters(), reader.orElseThrow(), codec);
                // 实际 tokenizer 连同包装计量；只在完整闭合组内计量，不能构造孤立 TOOL 请求。
                while (immediate && preview.isPresent()) {
                    var candidate = new ArrayList<>(group); candidate.set(index - start, preview.orElseThrow());
                    if (sources.measure(config, candidate, List.of()).tokensForMessage(index - start) <= policy.immediateToolTokens()) break;
                    if (head == 0 && tail == 0) { preview = Optional.empty(); break; }
                    head /= 2; tail /= 2;
                    preview = preview(original, material.id(), ordinal, head, tail, policy.pressureToolCharacters(), reader.orElseThrow(), codec);
                }
                if (preview.isPresent()) { messages.set(index, preview.orElseThrow()); changed = true; }
            }
            if (changed) events.add(material);
            start = end - 1;
        }
        return new Projection(messages, events);
    }

    /** 硬窗口补救只继续缩小既有预览；原文事件已经随prepare提交，不产生新材料或摘要。 */
    public static Projection shrinkToFit(List<ModelMessage> originals, List<ModelMessage> projected,
            ResultRef context, Configuration config, Sources sources, List<ModelTool> tools,
            long ordinal, AgentExecutionCodec codec) {
        var reader = AgentExecutionContextViews.referenceReaderWireName(config, tools);
        if (reader.isEmpty()) return new Projection(projected, List.of());
        var messages = new ArrayList<>(projected);
        int latestGroup = java.util.stream.IntStream.range(0, originals.size())
                .filter(index -> !originals.get(index).toolCalls().isEmpty()).max().orElse(-1);
        while (sources.measure(config, messages, tools).totalTokens() > config.profile().maxTokens()) {
            boolean changed = false;
            for (int index = 0; index < messages.size(); index++) {
                var current = messages.get(index);
                if (current.role() != ModelRole.TOOL || !current.textContent().startsWith(MARKER)) continue;
                var matching = java.util.stream.IntStream.range(0, originals.size())
                        .filter(source -> originals.get(source).messageId().equals(current.messageId())).toArray();
                if (matching.length != 1) throw invalid();
                int sourceIndex = matching[0];
                var original = originals.get(sourceIndex);
                if (original.equals(current)) continue;
                var value = codec.parse(new TypedPayload(SCHEMA, current.textContent()), SCHEMA);
                if (AgentExecutionCodec.number(value, "ordinal") != ordinal) throw invalid();
                int head = Math.toIntExact(AgentExecutionCodec.number(value, "headCharacters"));
                int tail = Math.toIntExact(AgentExecutionCodec.number(value, "tailCharacters"));
                int limit = Math.toIntExact(AgentExecutionCodec.number(value, "maxCharacters"));
                if (head < 0 || tail < 0 || limit <= 0) throw invalid();
                if (head == 0 && tail == 0) continue;
                int start = sourceIndex - 1;
                while (start >= 0 && originals.get(start).role() == ModelRole.TOOL) start--;
                if (start < 0 || originals.get(start).toolCalls().isEmpty()) throw invalid();
                var assistant = originals.get(start);
                if (start == latestGroup && readback(config, assistant.toolCalls().get(sourceIndex - start - 1).wireName())) continue;
                int end = start + 1 + assistant.toolCalls().size();
                var material = material(originals.subList(start, end), context, codec);
                var smaller = preview(original, material.id(), ordinal, head / 2, tail / 2, limit, reader.orElseThrow(), codec).orElseThrow(AgentExecutionToolPreviews::invalid);
                messages.set(index, smaller); changed = true;
                if (sources.measure(config, messages, tools).totalTokens() <= config.profile().maxTokens()) return new Projection(messages, List.of());
            }
            if (!changed) break;
        }
        return new Projection(messages, List.of());
    }

    /** 恢复只使用冻结的实际预览长度重建，不查询计量器、当前配置或原文存储。 */
    public static List<ModelMessage> restore(List<ModelMessage> originals, List<ModelMessage> frozen,
            ResultRef context, long ordinal, AgentExecutionCodec codec) {
        ModelPort.validateConversation(originals);
        var messages = new ArrayList<>(originals);
        for (var projected : frozen) {
            if (projected.role() != ModelRole.TOOL || !projected.textContent().startsWith(MARKER)) continue;
            var matching = java.util.stream.IntStream.range(0, originals.size())
                    .filter(index -> originals.get(index).messageId().equals(projected.messageId())).toArray();
            if (matching.length != 1) throw invalid();
            int index = matching[0];
            if (originals.get(index).equals(projected)) continue;
            var value = codec.parse(new TypedPayload(SCHEMA, projected.textContent()), SCHEMA);
            if (AgentExecutionCodec.number(value, "ordinal") != ordinal) throw invalid();
            int start = index - 1;
            while (start >= 0 && originals.get(start).role() == ModelRole.TOOL) start--;
            if (start < 0 || originals.get(start).toolCalls().isEmpty()) throw invalid();
            int end = start + 1 + originals.get(start).toolCalls().size();
            var material = material(originals.subList(start, end), context, codec);
            int head = Math.toIntExact(AgentExecutionCodec.number(value, "headCharacters"));
            int tail = Math.toIntExact(AgentExecutionCodec.number(value, "tailCharacters"));
            int limit = Math.toIntExact(AgentExecutionCodec.number(value, "maxCharacters"));
            if (head < 0 || tail < 0 || limit <= 0) throw invalid();
            var rebuilt = preview(originals.get(index), material.id(), ordinal, head, tail, limit, AgentExecutionCodec.string(value, "readToolWireName"), codec);
            if (rebuilt.isEmpty() || !rebuilt.orElseThrow().equals(projected) || !messages.get(index).equals(originals.get(index))) throw invalid();
            messages.set(index, rebuilt.orElseThrow());
        }
        return List.copyOf(messages);
    }

    private static ExecutionStore.Event material(List<ModelMessage> group, ResultRef context, AgentExecutionCodec codec) {
        var body = AgentExecutionCodec.object(); body.put("sourceContext", context.value());
        body.set("messages", AgentExecutionCodec.payload(codec.encodeInput(group, new TypedPayload(SOURCE_SCHEMA, "{}"))));
        var payload = codec.encode(AgentExecutionContextViews.SCHEMA, body);
        return new ExecutionStore.Event(new EventId("execution-context-material:" + AgentExecutionCodec.digest(payload.canonicalJson())),
                AgentExecutionContextViews.EVENT, payload);
    }

    private static Optional<ModelMessage> preview(ModelMessage original, EventId source, long ordinal,
            int requestedHead, int requestedTail, int limit, String readToolWireName, AgentExecutionCodec codec) {
        String text = original.textContent(); int count = characters(text);
        int head = Math.min(requestedHead, count), tail = Math.min(requestedTail, count - head);
        if (head + tail >= count) return Optional.empty();
        String rendered;
        for (;;) {
            var value = AgentExecutionCodec.object(); value.put("schemaId", SCHEMA);
            value.put("ordinal", Long.toString(ordinal)); value.put("source_event_id", source.value());
            value.put("maxCharacters", Integer.toString(limit)); value.put("characters", Integer.toString(count));
            value.put("headCharacters", Integer.toString(head)); value.put("tailCharacters", Integer.toString(tail));
            value.put("omittedStart", Integer.toString(head)); value.put("omittedEnd", Integer.toString(count - tail));
            value.put("previewComplete", false); value.putNull("sourceComplete");
            value.put("readToolWireName", readToolWireName);
            value.put("notice", "正文预览已省略，原结果状态与完整性不作推断；通过 " + readToolWireName + " 的 source_event_id 回读完整安全材料。");
            value.put("head", text.substring(0, text.offsetByCodePoints(0, head)));
            value.put("tail", text.substring(text.offsetByCodePoints(0, count - tail)));
            rendered = codec.encode(SCHEMA, value).canonicalJson();
            int excess = characters(rendered) - limit;
            if (excess <= 0) break;
            if (head == 0 && tail == 0) return Optional.empty();
            int retained = head + tail, next = Math.max(0, retained - excess);
            int nextHead = (int) ((long) next * head / retained);
            head = nextHead; tail = next - nextHead;
        }
        if (characters(rendered) >= count) return Optional.empty();
        return Optional.of(new ModelMessage(original.messageId(), original.role(), rendered, original.toolCalls(),
                original.toolCallId(), original.reasoningContent(), original.protocolState()));
    }

    private static int characters(String value) { return value.codePointCount(0, value.length()); }
    private static boolean readback(Configuration configuration, String wireName) {
        return configuration.modelTools().stream().filter(tool -> tool.wireName().equals(wireName))
                .anyMatch(tool -> configuration.tools().stream().anyMatch(entry -> entry.target().handle().value().equals(tool.toolHandle())
                        && entry.binding().providerType() == ToolPortal.ToolBinding.ProviderType.PLATFORM
                        && entry.binding().toolName().equals("read_context_reference")));
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("execution_tool_preview_invalid"); }
}
