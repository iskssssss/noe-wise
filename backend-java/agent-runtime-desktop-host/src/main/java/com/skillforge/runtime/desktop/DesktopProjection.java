package com.skillforge.runtime.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 仅裁剪桌面展示副本，模型输入、正式结果与持久材料始终保留原内容。 */
final class DesktopProjection {
    static final int MAX_STATE_BYTES = 1024 * 1024;
    private static final int METADATA_RESERVE_BYTES = 1024;
    static final int RECENT_MESSAGES = 40;
    static final int RECENT_RUNS = 100;
    static final int RECENT_PROCESS_EVENTS = 80;
    static final int RECENT_TOOL_EVENTS = 40;
    private static final int ENTRY_CHARACTERS = 6000;
    private static final int FIELD_CHARACTERS = 2048;
    private static final int ARRAY_ITEMS = 20;
    private static final int MAX_DEPTH = 8;
    private static final Set<String> STATUS_FIELDS = Set.of("status", "isError", "exitCode", "code", "processTreeStopped", "outputComplete");
    private DesktopProjection() { }

    static ObjectNode bound(ObjectNode state) {
        return bound(state, state.required("messages").size(), state.required("runs").size(), state.required("events").size());
    }

    /** 数据库已省略的记录和最后字节裁剪相加；窗口候选不是完整历史。 */
    static ObjectNode bound(ObjectNode state, long totalMessages, long totalRuns, long totalEvents) {
        var messages = (ArrayNode) state.required("messages"); var runs = (ArrayNode) state.required("runs");
        var originalEvents = (ArrayNode) state.required("events");
        keepLast(messages, RECENT_MESSAGES); keepLast(runs, RECENT_RUNS);
        long omittedMessages = totalMessages - messages.size(); long omittedRuns = totalRuns - runs.size();
        var process = new ArrayList<JsonNode>(); var tools = new ArrayList<JsonNode>();
        originalEvents.forEach(event -> (event.path("type").asText().equals("tool") ? tools : process).add(event));
        var events = state.putArray("events");
        process.stream().skip(Math.max(0, process.size() - RECENT_PROCESS_EVENTS)).forEach(events::add);
        tools.stream().skip(Math.max(0, tools.size() - RECENT_TOOL_EVENTS)).forEach(events::add);
        long omittedEvents = totalEvents - events.size();
        boolean truncated = omittedMessages > 0 || omittedRuns > 0 || omittedEvents > 0;
        for (var collection : List.of(messages, events)) for (int index = 0; index < collection.size(); index++) {
            var budget = new PreviewBudget(); var entry = (ObjectNode) preview(collection.get(index), "", budget, 0);
            if (!budget.fields.isEmpty()) {
                truncated = true; var fields = entry.putArray("fieldsTruncated"); budget.fields.forEach(fields::add);
            }
            collection.set(index, entry);
        }
        state.put("projectionTruncated", truncated);
        // JSON转义会放大字节数，最终以真实UTF-8线协议大小复核，而不是按字符数猜测。
        while (bytes(state) > MAX_STATE_BYTES - METADATA_RESERVE_BYTES && !events.isEmpty()) { events.remove(0); omittedEvents++; truncated = true; }
        while (bytes(state) > MAX_STATE_BYTES - METADATA_RESERVE_BYTES && messages.size() > 1) { messages.remove(0); omittedMessages++; truncated = true; }
        while (bytes(state) > MAX_STATE_BYTES - METADATA_RESERVE_BYTES && runs.size() > 1) { runs.remove(0); omittedRuns++; truncated = true; }
        state.put("projectionTruncated", truncated).put("omittedMessages", omittedMessages).put("omittedEvents", omittedEvents).put("omittedRuns", omittedRuns);
        if (bytes(state) > MAX_STATE_BYTES) throw new IllegalStateException("desktop_projection_capacity_exceeded");
        return state;
    }

    private static void keepLast(ArrayNode values, int maximum) {
        int omitted = Math.max(0, values.size() - maximum);
        for (int index = 0; index < omitted; index++) values.remove(0);
    }

    private static int bytes(JsonNode value) { return DesktopJson.write(value).getBytes(StandardCharsets.UTF_8).length; }

    private static JsonNode preview(JsonNode source, String path, PreviewBudget budget, int depth) {
        if (depth > MAX_DEPTH) { budget.fields.add(path); return TextNode.valueOf("（已省略，原记录保留在本机）"); }
        if (source.isTextual()) {
            String text = source.textValue(); int allowed = Math.max(0, Math.min(FIELD_CHARACTERS, budget.remaining));
            if (text.length() > allowed) {
                int end = allowed;
                if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
                budget.remaining -= end; budget.fields.add(path);
                return TextNode.valueOf(text.substring(0, end) + "…（已省略）");
            }
            budget.remaining -= text.length(); return source;
        }
        if (source.isArray()) {
            var result = DesktopJson.JSON.createArrayNode(); int index = 0;
            for (var item : source) {
                if (index >= ARRAY_ITEMS) { budget.fields.add(path); break; }
                result.add(preview(item, path + "[" + index + "]", budget, depth + 1)); index++;
            }
            return result;
        }
        if (source.isObject()) {
            var result = DesktopJson.object(); var fields = new ArrayList<String>(); source.fieldNames().forEachRemaining(fields::add);
            fields.sort(Comparator.comparingInt(field -> STATUS_FIELDS.contains(field) ? 0 : 1));
            for (var field : fields) result.set(field, preview(source.get(field), path.isEmpty() ? field : path + "." + field, budget, depth + 1));
            return result;
        }
        return source;
    }

    private static final class PreviewBudget {
        int remaining = ENTRY_CHARACTERS;
        final Set<String> fields = new LinkedHashSet<>();
    }
}
