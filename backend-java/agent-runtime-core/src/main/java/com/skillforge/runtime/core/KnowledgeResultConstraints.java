package com.skillforge.runtime.core;

import com.skillforge.runtime.json.CodecLimits;

import com.skillforge.runtime.api.EvidenceBundle;
import com.skillforge.runtime.api.KnowledgeBudget;
import com.skillforge.runtime.api.KnowledgeMode;
import com.skillforge.runtime.api.KnowledgeSourceVersion;

/** Knowledge Retrieved 与冻结 request 的纯闭包校验；不读取存储、时钟或 active source。 */
final class KnowledgeResultConstraints {
    private KnowledgeResultConstraints() { }

    /** 新执行链直接消费原真实请求，不为纯闭包核验构造旧准备或来源图。 */
    static void requireBundle(com.skillforge.runtime.api.KnowledgeRequest request, EvidenceBundle bundle) {
        var budget = request.budget();
        if (!bundle.knowledgeRequestId().equals(request.knowledgeRequestId())
                || !bundle.plan().knowledgeRequestId().equals(request.knowledgeRequestId())
                || request.mode() == KnowledgeMode.NONE
                || request.mode() == KnowledgeMode.GROUNDING && bundle.plan().steps().size() != 1
                || request.mode() == KnowledgeMode.RESEARCH
                        && bundle.plan().steps().size() > budget.maxQueries()
                || bundle.items().size() > budget.maxItems()) {
            throw invalid();
        }
        for (var step : bundle.plan().steps()) {
            if (step.sourceIds().size() != 1 || !request.allowedSourceIds().containsAll(step.sourceIds())
                    || !request.allowedOperators().contains(step.operator())
                    || !narrower(step.budget(), budget)) { throw invalid(); }
        }
        for (var item : bundle.items()) {
            var version = new KnowledgeSourceVersion(item.sourceId(), item.sourceVersion());
            if (!request.allowedSourceIds().contains(item.sourceId())
                    || !request.allowedSourceVersions().contains(version)
                    || !request.allowedOperators().contains(item.operator())) { throw invalid(); }
        }
        documentResult(request, bundle);
        long tokens = 0;
        long elapsed = 0;
        try {
            for (var trace : bundle.trace()) {
                tokens = Math.addExact(tokens, trace.tokenCost());
                elapsed = Math.addExact(elapsed, trace.elapsedMs());
            }
        } catch (ArithmeticException exception) { throw invalid(); }
        if (tokens > budget.maxTokens() || elapsed > budget.timeoutMs()) { throw invalid(); }
    }

    private static void documentResult(com.skillforge.runtime.api.KnowledgeRequest request, EvidenceBundle bundle) {
        var result = bundle.documentResult().orElse(null);
        if (request.operation() instanceof com.skillforge.runtime.api.KnowledgeOperation.Search) {
            if (result != null) throw invalid();
            return;
        }
        if (result == null) throw invalid();
        int units = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, request.budget().maxCharacters() * 2L));
        var limits = new CodecLimits(units, Math.max(units, 128), 128);
        String metadata;
        try { metadata = KnowledgeDocumentCodec.encode(result, limits).canonicalJson(); }
        catch (IllegalArgumentException rejected) { throw invalid(); }
        long characters = metadata.codePointCount(0, metadata.length());
        for (var item : bundle.items()) characters += item.content().canonicalJson().codePointCount(0, item.content().canonicalJson().length());
        if (characters > request.budget().maxCharacters()) throw invalid();
        if (request.operation() instanceof com.skillforge.runtime.api.KnowledgeOperation.ListDocuments list) {
            if (!(result instanceof com.skillforge.runtime.api.KnowledgeDocumentResult.CatalogPage page)
                    || !bundle.items().isEmpty() || page.documents().size() > Math.min(list.pageSize(), request.budget().maxItems())) throw invalid();
            var ids = new java.util.HashSet<java.util.List<String>>();
            for (var document : page.documents()) {
                requireDocument(request, document);
                if (!ids.add(java.util.List.of(document.sourceId(), document.documentId(), document.sourceVersion()))
                        || !document.title().toLowerCase(java.util.Locale.ROOT).contains(list.titleFilter().toLowerCase(java.util.Locale.ROOT))) throw invalid();
            }
        } else if (request.operation() instanceof com.skillforge.runtime.api.KnowledgeOperation.ReadDocument read) {
            if (!(result instanceof com.skillforge.runtime.api.KnowledgeDocumentResult.DocumentSlice slice)
                    || !slice.document().sourceId().equals(read.sourceId()) || !slice.document().documentId().equals(read.documentId())
                    || !slice.document().sourceVersion().equals(read.sourceVersion()) || slice.startOffset() != read.offset()
                    || slice.endOffset() - slice.startOffset() > read.maxCharacters() || bundle.items().size() > 1) throw invalid();
            requireDocument(request, slice.document());
            if (slice.startOffset() == slice.endOffset()) { if (!bundle.items().isEmpty()) throw invalid(); }
            else {
                if (bundle.items().size() != 1) throw invalid();
                var item = bundle.items().getFirst();
                if (item.operator() != com.skillforge.runtime.api.KnowledgeOperatorType.DOCUMENT_READ || !item.sourceId().equals(read.sourceId())
                        || !item.sourceVersion().equals(read.sourceVersion()) || !"skillforge.runtime.knowledge.document-slice.v1".equals(item.content().schemaId())
                        || !item.provenanceRefs().stream().filter(value -> value.startsWith("knowledge-document:")).toList().equals(java.util.List.of("knowledge-document:" + read.documentId()))) throw invalid();
                String text;
                try { text = CanonicalJson.parse(item.content().canonicalJson(), limits).requireFields("text").field("text").stringValue(); }
                catch (IllegalArgumentException rejected) { throw invalid(); }
                if (text.codePointCount(0, text.length()) != slice.endOffset() - slice.startOffset()) throw invalid();
            }
        }
    }
    private static void requireDocument(com.skillforge.runtime.api.KnowledgeRequest request, com.skillforge.runtime.api.KnowledgeDocumentResult.Document document) {
        if (!request.allowedSourceVersions().contains(new KnowledgeSourceVersion(document.sourceId(), document.sourceVersion()))) throw invalid();
    }

    private static boolean narrower(KnowledgeBudget step, KnowledgeBudget request) {
        return step.maxQueries() <= request.maxQueries() && step.maxItems() <= request.maxItems()
                && step.maxTokens() <= request.maxTokens() && step.maxCharacters() <= request.maxCharacters()
                && step.timeoutMs() <= request.timeoutMs();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid Knowledge result binding");
    }
}
