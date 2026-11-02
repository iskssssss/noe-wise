package com.skillforge.runtime.session;

import com.skillforge.runtime.api.EvidenceItem;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.json.CodecLimits;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

/** 原生回答的正文引用只解析真实普通文本，来源由同 Run 已注入知识事实提供。 */
final class NativeCitations {
    private static final String PREFIX = "[[cite:";
    private static final String SUFFIX = "]]";
    private static final String INVALID = "knowledge_citation_invalid";
    private static final int ID_LENGTH = 17;
    private static final Parser MARKDOWN = Parser.builder()
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build();

    private NativeCitations() { }

    /** 相同 Run 与证据身份始终生成同一短标识，重启或重复查询不改变它。 */
    static String citationId(RunRef run, EvidenceItem item) {
        Objects.requireNonNull(run); Objects.requireNonNull(item);
        var identity = AgentExecutionCodec.object();
        identity.put("tenantId", run.tenantId().value()); identity.put("runId", run.runId());
        identity.put("itemId", item.itemId()); identity.put("sourceId", item.sourceId());
        identity.put("sourceVersion", item.sourceVersion());
        return "k" + AgentExecutionCodec.digest("native-citation.v1\n" + SessionRuntimePayloadJson.write(identity)).substring(0, 16);
    }

    sealed interface Resolution permits Resolved, Rejected { }

    /** 公开正文只投影已核验标记；原模型正文仍由 AssistantTurn 持有。 */
    record Resolved(String output, List<SelectedEvidence> evidence, List<Marker> markers) implements Resolution {
        Resolved { Objects.requireNonNull(output); evidence = List.copyOf(evidence); markers = List.copyOf(markers); }
        List<String> evidenceItemIds() { return evidence.stream().map(value -> value.item().itemId()).toList(); }
        @Override public String toString() { return "NativeCitationResolution[REDACTED]"; }
    }

    /** 保留完整注入观测身份，后续不能仅靠相同 itemId 选择另一个 bundle。 */
    record SelectedEvidence(String citationId, AgentExecutionProgram.KnowledgeObservation observation, EvidenceItem item) {
        SelectedEvidence { Objects.requireNonNull(citationId); Objects.requireNonNull(observation); Objects.requireNonNull(item); }
        @Override public String toString() { return "NativeSelectedEvidence[REDACTED]"; }
    }

    /** start/end 是完整原始正文的 UTF-16 下标；ordinal 与首次引用顺序一致。 */
    record Marker(int start, int end, int ordinal, String citationId) {
        Marker { if (start < 0 || end <= start || ordinal <= 0) throw new IllegalArgumentException(INVALID); Objects.requireNonNull(citationId); }
    }

    record Rejected(String code) implements Resolution {
        Rejected { if (!INVALID.equals(code)) throw new IllegalArgumentException(INVALID); }
    }

    /** 只读取原已采用注入事实；来源不唯一、未知或正文语法非法均明确拒绝。 */
    static Resolution resolve(RunRef run, String output,
            List<AgentExecutionProgram.KnowledgeObservation> observations, CodecLimits limits) {
        Objects.requireNonNull(run); Objects.requireNonNull(output); Objects.requireNonNull(observations); Objects.requireNonNull(limits);
        if (output.length() > limits.maxCodeUnits()) return new Rejected(INVALID);
        // 严格 JSON 的字符串、对象与数组都是用户正文，不在其内部偷取引用语法。
        if (json(output, limits)) return new Resolved(output, List.of(), List.of());
        var visible = new LinkedHashMap<String, List<SelectedEvidence>>();
        for (var observation : observations) for (var item : observation.bundle().items()) {
            if (!observation.injected().contains(item.itemId())) continue;
            var id = citationId(run, item);
            visible.computeIfAbsent(id, ignored -> new ArrayList<>()).add(new SelectedEvidence(id, observation, item));
        }
        var eligible = eligible(output); var selected = new LinkedHashMap<String, SelectedEvidence>();
        var markers = new ArrayList<Marker>();
        for (int start = output.indexOf(PREFIX); start >= 0; start = output.indexOf(PREFIX, start + PREFIX.length())) {
            if (!eligible[start] || escaped(output, start)) continue;
            int idStart = start + PREFIX.length(); int idEnd = idStart + ID_LENGTH; int end = idEnd + SUFFIX.length();
            if (end > output.length() || !output.startsWith(SUFFIX, idEnd) || !validId(output.substring(idStart, idEnd)))
                return new Rejected(INVALID);
            for (int index = start; index < end; index++) if (!eligible[index]) return new Rejected(INVALID);
            var id = output.substring(idStart, idEnd); var matches = visible.get(id);
            if (matches == null || matches.size() != 1) return new Rejected(INVALID);
            selected.putIfAbsent(id, matches.getFirst());
            int ordinal = new ArrayList<>(selected.keySet()).indexOf(id) + 1;
            markers.add(new Marker(start, end, ordinal, id));
        }
        if (selected.values().stream().map(value -> value.item().itemId()).distinct().count() != selected.size())
            return new Rejected(INVALID);
        return new Resolved(render(output, markers), List.copyOf(selected.values()), markers);
    }

    private static String render(String output, List<Marker> markers) {
        var rendered = new StringBuilder(); int position = 0;
        for (var marker : markers) {
            rendered.append(output, position, marker.start()).append("[知识依据 ").append(marker.ordinal())
                    .append("](#citation-").append(marker.citationId()).append(')');
            position = marker.end();
        }
        return rendered.append(output, position, output.length()).toString();
    }

    /** AST 将代码、链接、图片与引用块排除；源区间避免把转义后的展示文本误认为协议。 */
    private static boolean[] eligible(String output) {
        var eligible = new boolean[output.length()]; var document = MARKDOWN.parse(output);
        Node current = document;
        while (current != null) {
            if (current instanceof Text && ordinary(current)) for (var span : current.getSourceSpans()) {
                int start = span.getInputIndex(); int end = Math.addExact(start, span.getLength());
                java.util.Arrays.fill(eligible, start, end, true);
            }
            if (current.getFirstChild() != null) current = current.getFirstChild();
            else {
                while (current != null && current.getNext() == null) current = current.getParent();
                if (current != null) current = current.getNext();
            }
        }
        return eligible;
    }

    private static boolean ordinary(Node node) {
        for (Node ancestor = node.getParent(); ancestor != null; ancestor = ancestor.getParent())
            if (ancestor instanceof BlockQuote || ancestor instanceof Link || ancestor instanceof Image) return false;
        return true;
    }

    private static boolean escaped(String output, int start) {
        int slashes = 0;
        for (int index = start - 1; index >= 0 && output.charAt(index) == '\\'; index--) slashes++;
        return slashes % 2 != 0;
    }

    private static boolean validId(String value) {
        if (value.length() != ID_LENGTH || value.charAt(0) != 'k') return false;
        for (int index = 1; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character >= '0' && character <= '9' || character >= 'a' && character <= 'f')) return false;
        }
        return true;
    }

    private static boolean json(String output, CodecLimits limits) {
        try { SessionRuntimePayloadJson.parse(output, limits); return true; }
        catch (IllegalArgumentException invalid) { return false; }
    }
}
