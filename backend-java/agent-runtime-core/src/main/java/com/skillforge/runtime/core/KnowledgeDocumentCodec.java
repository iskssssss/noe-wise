package com.skillforge.runtime.core;

import com.skillforge.runtime.json.CodecLimits;

import com.skillforge.runtime.api.KnowledgeDocumentResult;
import com.skillforge.runtime.api.TypedPayload;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/** 文档访问元数据唯一规范编码；同一结构用于结果持久化、模型输入和字符预算。 */
final class KnowledgeDocumentCodec implements com.skillforge.runtime.codec.spi.KnowledgeDocumentCodec {
    private final CodecLimits limits;

    KnowledgeDocumentCodec(CodecLimits limits) {
        this.limits = java.util.Objects.requireNonNull(limits, "limits");
    }

    @Override
    public TypedPayload encode(KnowledgeDocumentResult result) {
        return encode(result, limits);
    }

    @Override
    public TypedPayload encodeText(String text) {
        return encodeText(text, limits);
    }

    @Override
    public KnowledgeDocumentResult decode(TypedPayload payload) {
        return decode(payload, limits);
    }

    /** 原文JSON与metadata共用规范编码，换行等控制字符使用唯一短转义。 */
    static TypedPayload encodeText(String text, CodecLimits limits) {
        var budget = new OutcomeEncodingBudget(limits);
        budget.object(1, "text"); budget.string(2, text);
        return new TypedPayload(TEXT_SCHEMA, CanonicalJson.encode(object(field("text", CanonicalJson.string(text)))));
    }

    static TypedPayload encode(KnowledgeDocumentResult result, CodecLimits limits) {
        preflight(new OutcomeEncodingBudget(limits), result, 1);
        return new TypedPayload(SCHEMA, CanonicalJson.encode(value(result)));
    }
    static KnowledgeDocumentResult decode(TypedPayload payload, CodecLimits limits) {
        if (!SCHEMA.equals(payload.schemaId())) throw new IllegalArgumentException("knowledge_document_schema_invalid");
        var result = read(CanonicalJson.parse(payload.canonicalJson(), limits));
        if (!encode(result, limits).equals(payload)) throw new IllegalArgumentException("knowledge_document_schema_invalid");
        return result;
    }
    static void preflight(OutcomeEncodingBudget budget, KnowledgeDocumentResult result, int depth) {
        switch (result) {
            case KnowledgeDocumentResult.CatalogPage page -> {
                budget.object(depth, "type", "documents", "next_cursor"); budget.string(depth + 1, "catalog");
                budget.array(depth + 1, page.documents().size());
                for (var document : page.documents()) documentBudget(budget, document, depth + 2);
                if (page.nextCursor().isPresent()) budget.string(depth + 1, page.nextCursor().orElseThrow()); else budget.nil(depth + 1);
            }
            case KnowledgeDocumentResult.DocumentSlice slice -> {
                budget.object(depth, "type", "document", "start_offset", "end_offset", "next_offset");
                budget.string(depth + 1, "document_slice"); documentBudget(budget, slice.document(), depth + 1);
                budget.number(depth + 1, slice.startOffset()); budget.number(depth + 1, slice.endOffset());
                if (slice.nextOffset().isPresent()) budget.number(depth + 1, slice.nextOffset().getAsInt()); else budget.nil(depth + 1);
            }
        }
    }
    private static void documentBudget(OutcomeEncodingBudget budget, KnowledgeDocumentResult.Document document, int depth) {
        budget.object(depth, "source_id", "document_id", "source_version", "title", "total_characters");
        budget.string(depth + 1, document.sourceId()); budget.string(depth + 1, document.documentId());
        budget.string(depth + 1, document.sourceVersion()); budget.string(depth + 1, document.title());
        budget.number(depth + 1, document.totalCharacters());
    }
    static CanonicalJson.Value value(KnowledgeDocumentResult result) {
        return switch (result) {
            case KnowledgeDocumentResult.CatalogPage page -> object(field("type", CanonicalJson.string("catalog")),
                    field("documents", CanonicalJson.array(page.documents().stream().map(KnowledgeDocumentCodec::document).toList())),
                    field("next_cursor", page.nextCursor().map(CanonicalJson::string).orElseGet(CanonicalJson::nil)));
            case KnowledgeDocumentResult.DocumentSlice slice -> object(field("type", CanonicalJson.string("document_slice")),
                    field("document", document(slice.document())), field("start_offset", number(slice.startOffset())),
                    field("end_offset", number(slice.endOffset())), field("next_offset", slice.nextOffset().isPresent() ? number(slice.nextOffset().getAsInt()) : CanonicalJson.nil()));
        };
    }
    private static CanonicalJson.Value document(KnowledgeDocumentResult.Document value) {
        return object(field("source_id", CanonicalJson.string(value.sourceId())), field("document_id", CanonicalJson.string(value.documentId())),
                field("source_version", CanonicalJson.string(value.sourceVersion())), field("title", CanonicalJson.string(value.title())),
                field("total_characters", number(value.totalCharacters())));
    }
    static KnowledgeDocumentResult read(CanonicalJson.Value value) {
        return switch (value.field("type").stringValue()) {
            case "catalog" -> {
                value.requireFields("type", "documents", "next_cursor");
                yield new KnowledgeDocumentResult.CatalogPage(value.field("documents").arrayValue().stream().map(KnowledgeDocumentCodec::readDocument).toList(),
                        value.field("next_cursor").isNull() ? Optional.empty() : Optional.of(value.field("next_cursor").stringValue()));
            }
            case "document_slice" -> {
                value.requireFields("type", "document", "start_offset", "end_offset", "next_offset");
                yield new KnowledgeDocumentResult.DocumentSlice(readDocument(value.field("document")), readNumber(value.field("start_offset")),
                        readNumber(value.field("end_offset")), value.field("next_offset").isNull() ? OptionalInt.empty() : OptionalInt.of(readNumber(value.field("next_offset"))));
            }
            default -> throw new IllegalArgumentException("knowledge_document_schema_invalid");
        };
    }
    private static KnowledgeDocumentResult.Document readDocument(CanonicalJson.Value value) {
        value.requireFields("source_id", "document_id", "source_version", "title", "total_characters");
        return new KnowledgeDocumentResult.Document(value.field("source_id").stringValue(), value.field("document_id").stringValue(),
                value.field("source_version").stringValue(), value.field("title").stringValue(), readNumber(value.field("total_characters")));
    }
    private static int readNumber(CanonicalJson.Value value) {
        String encoded = CanonicalJson.encode(value);
        if (!encoded.matches("0|[1-9][0-9]*")) throw new IllegalArgumentException("knowledge_document_integer_invalid");
        return Integer.parseInt(encoded);
    }
    private static CanonicalJson.Value number(int value) { return CanonicalJson.number(Integer.toString(value)); }
    private static CanonicalJson.Field field(String name, CanonicalJson.Value value) { return new CanonicalJson.Field(name, value); }
    private static CanonicalJson.Value object(CanonicalJson.Field... fields) { return CanonicalJson.object(List.of(fields)); }
}
