package com.skillforge.runtime.core;

import com.skillforge.runtime.json.CodecLimits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.KnowledgePortal.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;

class KnowledgeDocumentCodecTest {
    private static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");
    private static final CodecLimits LIMITS = new CodecLimits(100_000, 100_000, 64);

    @Test void sdkDocumentCodecKeepsCanonicalBytesAndStrictBoundedDecode() {
        var codec = CoreRuntime.knowledgeDocuments(LIMITS);
        var document = new KnowledgeDocumentResult.Document("source", "document", "v1", "资料😀", 4);
        var page = new KnowledgeDocumentResult.CatalogPage(List.of(document), Optional.empty());
        var encoded = codec.encode(page);
        assertEquals(KnowledgeDocumentCodec.encode(page, LIMITS), encoded);
        assertEquals(page, codec.decode(encoded));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(new TypedPayload("application/json", encoded.canonicalJson())));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(new TypedPayload(encoded.schemaId(), " " + encoded.canonicalJson())));
        assertEquals(KnowledgeDocumentCodec.encodeText("首行\n😀", LIMITS), codec.encodeText("首行\n😀"));
        assertThrows(IllegalArgumentException.class, () -> CoreRuntime.knowledgeDocuments(new CodecLimits(2, 3, 2)).encodeText("正文"));
        assertThrows(NullPointerException.class, () -> CoreRuntime.knowledgeDocuments(null));
    }

    @Test void catalogAndSliceRoundTripAndRejectMalformedRanges() {
        var document = new KnowledgeDocumentResult.Document("source", "document", "v1", "资料😀", 4);
        var page = new KnowledgeDocumentResult.CatalogPage(List.of(document), Optional.of("next"));
        var slice = new KnowledgeDocumentResult.DocumentSlice(document, 0, 2, OptionalInt.of(2));
        for (var value : List.of(page, slice)) assertEquals(value, KnowledgeDocumentCodec.decode(KnowledgeDocumentCodec.encode(value, LIMITS), LIMITS));
        assertThrows(IllegalArgumentException.class, () -> new KnowledgeDocumentResult.DocumentSlice(document, 0, 2, OptionalInt.empty()));
        assertThrows(IllegalArgumentException.class, () -> new KnowledgeDocumentResult.DocumentSlice(document, 2, 2, OptionalInt.of(2)));
        var encoded = KnowledgeDocumentCodec.encode(slice, LIMITS);
        assertThrows(IllegalArgumentException.class, () -> KnowledgeDocumentCodec.decode(new TypedPayload(encoded.schemaId(), encoded.canonicalJson().replace("\"end_offset\":2", "\"end_offset\":\"2\"")), LIMITS));
    }

    @Test void documentSelectorsRoundTripAsExplicitFrozenOperations() {
        for (var operation : List.<KnowledgeOperation>of(new KnowledgeOperation.ListDocuments("资料", Optional.of("cursor"), 3),
                new KnowledgeOperation.ReadDocument("source", "document", "v1", 2, 16_000))) {
            var request = request(operation, 4096);
            var frozen = frozen(request);
            assertEquals(frozen, FrozenKnowledgeRequest.decode(frozen.encode(LIMITS), LIMITS));
        }
    }

    @Test void providerMetadataCannotEscapeManifestSelectorOrDocumentProvenance() {
        var read = request(new KnowledgeOperation.ReadDocument("source", "document", "v1", 0, 2), 4096);
        var original = slice(read, "document", "knowledge-document:document", "甲😀");
        frozen(read).requireResult(original);
        assertThrows(IllegalArgumentException.class, () -> frozen(read).requireResult(slice(read, "other-document", "knowledge-document:document", "甲😀")));
        assertThrows(IllegalArgumentException.class, () -> frozen(read).requireResult(slice(read, "document", "knowledge-document:other-document", "甲😀")));
        assertThrows(IllegalArgumentException.class, () -> frozen(read).requireResult(slice(read, "document", "knowledge-document:document", "甲😀乙")));
        var list = request(new KnowledgeOperation.ListDocuments("", Optional.empty(), 3), 4096);
        var foreign = new KnowledgeDocumentResult.CatalogPage(List.of(new KnowledgeDocumentResult.Document("foreign", "secret", "v1", "资料", 1)), Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> frozen(list).requireResult(bundle(list, List.of(), foreign)));
    }

    @Test void metadataConsumesCharacterBudgetEvenWithoutEvidenceItems() {
        var request = request(new KnowledgeOperation.ListDocuments("", Optional.empty(), 3), 32);
        var page = new KnowledgeDocumentResult.CatalogPage(List.of(new KnowledgeDocumentResult.Document("source", "document", "v1", "资料目录😀", 4)), Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> frozen(request).requireResult(bundle(request, List.of(), page)));
    }

    @Test void documentMetadataPreservesLegallyEmptyTitle() {
        var document = new KnowledgeDocumentResult.Document("source", "document", "v1", "", 0);
        var page = new KnowledgeDocumentResult.CatalogPage(List.of(document), Optional.empty());
        assertEquals(page, KnowledgeDocumentCodec.decode(KnowledgeDocumentCodec.encode(page, LIMITS), LIMITS));
    }

    @Test void documentTextUsesCanonicalShortEscapesAndRejectsAlternateProviderEncoding() {
        String text = "首行\n\t\"\\😀";
        String golden = "{\"text\":\"首行\\n\\t\\\"\\\\😀\"}";
        var exact = new CodecLimits(golden.length(), 3, 2);
        assertEquals(new TypedPayload(KnowledgeDocumentCodec.TEXT_SCHEMA, golden), KnowledgeDocumentCodec.encodeText(text, exact));
        assertThrows(IllegalArgumentException.class, () -> KnowledgeDocumentCodec.encodeText(text, new CodecLimits(golden.length() - 1, 3, 2)));
        var request = request(new KnowledgeOperation.ReadDocument("source", "document", "v1", 0, 2), 4096);
        var valid = slice(request, "document", "knowledge-document:document", "甲\n"); frozen(request).requireResult(valid);
        var item = valid.items().getFirst();
        var alias = new EvidenceItem(item.itemId(), item.sourceId(), item.sourceVersion(), item.operator(),
                new TypedPayload(item.content().schemaId(), item.content().canonicalJson().replace("\\n", "\\u000a")), item.provenanceRefs(), item.graphPath(), item.relevance(), item.trustLevel());
        assertThrows(IllegalArgumentException.class, () -> frozen(request).requireResult(bundle(request, List.of(alias), valid.documentResult().orElseThrow())));
    }

    private static KnowledgeRequest request(KnowledgeOperation operation, int characters) {
        var tenant = new TenantId("1");
        return new KnowledgeRequest("request", tenant, new RunRequester.EndUser(new EndUserId(1)), new AgentId("agent"), new SessionRef(tenant, "session"),
                new TraceEnvelope("trace", "span", new InputId("input"), Optional.empty()), KnowledgeMode.GROUNDING, "read documents", operation,
                Set.of("source"), Set.of(new KnowledgeSourceVersion("source", "v1")), Set.of(KnowledgeOperatorType.DOCUMENT_LIST, KnowledgeOperatorType.DOCUMENT_READ),
                new KnowledgeBudget(1, 5, 1000, characters, 1000), NOW, Optional.empty(), true);
    }
    private static FrozenKnowledgeRequest frozen(KnowledgeRequest request) {
        return new FrozenKnowledgeRequest(new FrozenKnowledgeTarget(new KnowledgeHandle("knowledge:mount"), new CapabilityRef(CapabilityType.KNOWLEDGE_SOURCE, "cap", "version"),
                "source", 1, request.allowedSourceVersions(), request.allowedOperators(), Duration.ofSeconds(1)), request);
    }
    private static EvidenceBundle slice(KnowledgeRequest request, String document, String provenance, String text) {
        var metadata = new KnowledgeDocumentResult.DocumentSlice(new KnowledgeDocumentResult.Document("source", document, "v1", "资料", 4), 0, 2, OptionalInt.of(2));
        var item = new EvidenceItem("item", "source", "v1", KnowledgeOperatorType.DOCUMENT_READ,
                new TypedPayload("skillforge.runtime.knowledge.document-slice.v1", "{\"text\":" + CanonicalJson.encode(CanonicalJson.string(text)) + "}"), List.of(provenance), List.of(), 1, "published-source");
        return bundle(request, List.of(item), metadata);
    }
    private static EvidenceBundle bundle(KnowledgeRequest request, List<EvidenceItem> items, KnowledgeDocumentResult result) {
        var operator = result instanceof KnowledgeDocumentResult.CatalogPage ? KnowledgeOperatorType.DOCUMENT_LIST : KnowledgeOperatorType.DOCUMENT_READ;
        return new EvidenceBundle("bundle", request.knowledgeRequestId(), new KnowledgePlan("plan", request.knowledgeRequestId(), List.of(new KnowledgeStep("step", "document access", Set.of("source"), operator, List.of(), request.budget())), "page", "documents-v1"),
                items, List.of(), EvidenceSufficiency.PARTIAL, List.of(new KnowledgeTraceStep("step", "document access", items.stream().map(EvidenceItem::itemId).toList(), items.stream().map(EvidenceItem::itemId).toList(), 0, 1)), 1, NOW, Optional.of(result));
    }
}
