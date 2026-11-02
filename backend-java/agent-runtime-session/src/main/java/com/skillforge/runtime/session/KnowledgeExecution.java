package com.skillforge.runtime.session;

import com.skillforge.runtime.api.EvidenceBundle;
import com.skillforge.runtime.api.EvidenceItem;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.media.ModelContent;
import com.skillforge.runtime.api.port.ModelPort;
import com.skillforge.runtime.api.port.ModelPort.ModelMessage;
import com.skillforge.runtime.api.port.ModelPort.ModelRole;
import com.skillforge.runtime.api.port.ModelPort.ModelToolCall;
import com.skillforge.runtime.core.AgentAction;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.core.ModelConversation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Knowledge的纯消费投影：原bundle不裁剪保存，模型只消费真实预算内的选中证据。 */
final class KnowledgeExecution {
    private KnowledgeExecution() { }

    static TypedPayload arguments(AgentAction.KnowledgeAccess query, Optional<String> commentary, AgentExecutionCodec codec) {
        var value = AgentExecutionCodec.object(); value.put("knowledge_handle", query.knowledgeHandle());
        switch (query.operation()) {
            case com.skillforge.runtime.api.KnowledgeOperation.Search search -> value.put("query", search.query());
            case com.skillforge.runtime.api.KnowledgeOperation.ListDocuments list -> {
                value.put("title_filter", list.titleFilter()); value.put("page_cursor", list.cursor().orElse("")); value.put("page_size", list.pageSize());
            }
            case com.skillforge.runtime.api.KnowledgeOperation.ReadDocument read -> {
                value.put("source_id", read.sourceId()); value.put("document_id", read.documentId()); value.put("source_version", read.sourceVersion());
                value.put("offset", read.offset()); value.put("max_characters", read.maxCharacters());
            }
        }
        commentary.ifPresent(text -> value.put("commentary", text));
        return codec.encode("application/json", value);
    }

    /** 只按实际selected/source版本闭包、排除与真实tokenizer计量决定注入，不估算字符为token。 */
    static List<String> select(RunRef run, AgentExecutionProgram.Materials before, AgentExecutionCodec.KnowledgeCallRequest request,
            EvidenceBundle bundle, AgentExecutionProgram.Sources sources, CodecLimits limits) {
        var candidates = candidates(bundle); var used = new HashSet<String>();
        before.knowledge().forEach(observation -> used.addAll(observation.injected()));
        var selected = new ArrayList<String>();
        var baselineMessages = append(run, before, request, bundle, List.of(), limits);
        var baseline = sources.measure(before.configuration(), baselineMessages, before.configuration().modelTools());
        if (!baseline.messages().equals(baselineMessages) || !baseline.tools().equals(before.configuration().modelTools())
                || !baseline.profile().target().equals(before.configuration().target())) throw invalid();
        if (bundle.documentResult().isPresent()) {
            var original = sources.measure(before.configuration(), before.messages(), before.configuration().modelTools());
            long allCharacters = baselineMessages.stream().mapToLong(message -> message.accountingText().codePointCount(0, message.accountingText().length())).sum()
                    + before.configuration().modelTools().stream().mapToLong(tool -> tool.accountingText().codePointCount(0, tool.accountingText().length())).sum();
            if (Math.max(0, baseline.totalTokens() - original.totalTokens()) > request.request().budget().maxTokens()
                    || baseline.totalTokens() > before.configuration().profile().maxTokens()
                    || allCharacters > before.configuration().profile().maxCharacters()) throw new BudgetExceeded();
        }
        for (var item : bundle.items()) {
            if (!candidates.contains(item.itemId()) || used.contains(item.itemId())
                    && !(request.request().operation() instanceof com.skillforge.runtime.api.KnowledgeOperation.ReadDocument)) continue;
            var next = new ArrayList<>(selected); next.add(item.itemId());
            long characters = bundle.items().stream().filter(value -> next.contains(value.itemId())).mapToLong(value -> text(value, limits).codePointCount(0, text(value, limits).length())).sum();
            if (characters > request.request().budget().maxCharacters()) continue;
            var messages = append(run, before, request, bundle, next, limits); var measured = sources.measure(before.configuration(), messages, before.configuration().modelTools());
            if (!measured.messages().equals(messages) || !measured.tools().equals(before.configuration().modelTools())
                    || !measured.profile().target().equals(before.configuration().target())) throw invalid();
            long evidenceTokens = Math.max(0, measured.totalTokens() - (bundle.documentResult().isPresent()
                    ? sources.measure(before.configuration(), before.messages(), before.configuration().modelTools()).totalTokens() : baseline.totalTokens()));
            long allCharacters = messages.stream().mapToLong(message -> message.accountingText().codePointCount(0, message.accountingText().length())).sum()
                    + before.configuration().modelTools().stream().mapToLong(tool -> tool.accountingText().codePointCount(0, tool.accountingText().length())).sum();
            if (evidenceTokens > request.request().budget().maxTokens() || measured.totalTokens() > before.configuration().profile().maxTokens()
                    || allCharacters > before.configuration().profile().maxCharacters()) continue;
            selected.add(item.itemId());
        }
        if (bundle.documentResult().orElse(null) instanceof com.skillforge.runtime.api.KnowledgeDocumentResult.DocumentSlice slice
                && slice.endOffset() > slice.startOffset() && selected.isEmpty()) throw new BudgetExceeded();
        return List.copyOf(selected);
    }

    /** 恢复仅核验原已保存选择，不根据当前tokenizer重新选择或扩大可引用集合。 */
    static void requireSelection(EvidenceBundle bundle, List<String> injected) {
        if (new HashSet<>(injected).size() != injected.size() || !candidates(bundle).containsAll(injected)
                || !bundle.items().stream().map(EvidenceItem::itemId).filter(injected::contains).toList().equals(injected)) throw invalid();
    }

    static List<ModelMessage> append(RunRef run, AgentExecutionProgram.Materials before, AgentExecutionCodec.KnowledgeCallRequest request,
            EvidenceBundle bundle, List<String> injected, CodecLimits limits) {
        requireSelection(bundle, injected);
        var value = AgentExecutionCodec.object(); value.put("bundleId", bundle.bundleId()); value.put("version", Long.toString(bundle.version()));
        value.put("sufficiency", bundle.sufficiency().name());
        value.put("citationInstructions", bundle.documentResult().orElse(null) instanceof com.skillforge.runtime.api.KnowledgeDocumentResult.CatalogPage
                ? "本结果仅为文档目录，可据目录回答名称与可用范围；不含正文证据，不能编造正文结论或citationId。需要正文时按返回的精确身份读取。"
                : "实际使用下列证据的结论在正文相邻位置写[[cite:<citationId>]]，仅使用本结果提供的citationId；代码、链接、引用示例中的标记不算引用。JSON或纯代码输出与强制引用冲突时明确说明，不能编造或自动引用全部结果。");
        bundle.documentResult().ifPresent(result -> value.set("document_access", SessionRuntimePayloadJson.parse(
                com.skillforge.runtime.core.CoreRuntime.knowledgeDocuments(limits).encode(result).canonicalJson(), limits)));
        var items = value.putArray("items");
        for (var item : bundle.items()) if (injected.contains(item.itemId())) {
            var entry = items.addObject(); entry.put("citationId",NativeCitations.citationId(run,item)); entry.put("itemId", item.itemId()); entry.put("sourceId", item.sourceId()); entry.put("sourceVersion", item.sourceVersion());
            entry.put("text", text(item, limits)); entry.put("trustLevel", item.trustLevel());
            var provenance = entry.putArray("provenanceRefs"); item.provenanceRefs().forEach(provenance::add);
            var path = entry.putArray("graphPath"); item.graphPath().forEach(path::add);
        }
        return appendContents(before, request, List.of(new ModelContent.Text(SessionRuntimePayloadJson.write(value))));
    }

    static List<ModelMessage> failure(AgentExecutionProgram.Materials before, AgentExecutionCodec.KnowledgeCallRequest request, String reason) {
        var message = switch (reason) {
            case "knowledge_document_budget_exceeded" -> "知识访问已返回结果，但文档内容超出本次上下文预算，未向模型提供正文证据。";
            case "knowledge_document_unavailable" -> "本次请求未取得可用文档；文档可能已不可用，不能据此断言整个知识库没有相关内容。";
            default -> "本次知识访问收到失败回执，未取得可用于回答的新证据；这不表示知识库没有相关内容。";
        };
        var nextStep = switch (reason) {
            case "knowledge_document_budget_exceeded" -> "缩小 max_characters 分段读取，沿用原 source_id、document_id、source_version 和明确的 offset；仅引用实际返回的正文证据。";
            case "knowledge_document_unavailable" -> "通过可用的文档目录核对当前精确身份与版本；没有可用正文时向用户说明证据缺口，不要编造结论或引用。";
            default -> "根据原因码说明访问限制或失败，请用户或管理员核对知识源；仅使用已有证据，不要编造本次检索内容或引用。";
        };
        return appendContents(before, request, List.of(new ModelContent.Text(
                "知识访问未取得可用证据。\n原因码：" + reason + "\n" + message + "\n下一步：" + nextStep)));
    }

    private static List<ModelMessage> appendContents(AgentExecutionProgram.Materials before, AgentExecutionCodec.KnowledgeCallRequest request,
            List<ModelContent> contents) {
        var assistant = SessionNativeResponses.assistant(before,List.of(new ModelToolCall(request.callId(),request.wireName(),request.arguments())));
        var feedback = new ModelMessage("execution-knowledge-" + before.modelOrdinal(), ModelRole.TOOL, contents, List.of(), Optional.of(request.callId()), Optional.empty());
        return ModelConversation.append(before.messages(), assistant, List.of(feedback));
    }

    private static Set<String> candidates(EvidenceBundle bundle) {
        var selected = new HashSet<String>(); bundle.trace().forEach(trace -> selected.addAll(trace.selectedItemIds())); return Set.copyOf(selected);
    }

    private static String text(EvidenceItem item, CodecLimits limits) {
        var value = SessionRuntimePayloadJson.parse(item.content().canonicalJson(), limits);
        if (!Set.of("skillforge.runtime.knowledge.chunk.v1", "skillforge.runtime.knowledge.link.v1", "skillforge.runtime.knowledge.document-slice.v1").contains(item.content().schemaId())
                || !value.isObject() || !value.path("text").isTextual()) throw invalid();
        return value.get("text").textValue();
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("execution_knowledge_projection_invalid"); }
    static final class BudgetExceeded extends RuntimeException { }
}
