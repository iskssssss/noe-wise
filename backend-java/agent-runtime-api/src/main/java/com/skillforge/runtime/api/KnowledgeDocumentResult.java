package com.skillforge.runtime.api;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/** 目录与原文范围的类型化回执；原文只在EvidenceItem中保存一次。 */
public sealed interface KnowledgeDocumentResult permits KnowledgeDocumentResult.CatalogPage,
        KnowledgeDocumentResult.DocumentSlice {
    record Document(String sourceId, String documentId, String sourceVersion, String title,
                    int totalCharacters) {
        public Document {
            sourceId = ApiContracts.requireText(sourceId, "sourceId");
            documentId = ApiContracts.requireText(documentId, "documentId");
            sourceVersion = ApiContracts.requireText(sourceVersion, "sourceVersion");
            title = ApiContracts.requireNonNull(title, "title");
            if (totalCharacters < 0) throw new IllegalArgumentException("knowledge_document_result_invalid");
        }
    }
    record CatalogPage(List<Document> documents, Optional<String> nextCursor) implements KnowledgeDocumentResult {
        public CatalogPage {
            documents = ApiContracts.immutableDistinctList(documents, "documents");
            nextCursor = ApiContracts.requireOptional(nextCursor, "nextCursor");
            nextCursor.ifPresent(value -> ApiContracts.requireText(value, "nextCursor"));
            if (documents.size() > KnowledgeOperationLimits.MAX_PAGE_SIZE || documents.isEmpty() && nextCursor.isPresent())
                throw new IllegalArgumentException("knowledge_document_result_invalid");
        }
    }
    record DocumentSlice(Document document, int startOffset, int endOffset,
                         OptionalInt nextOffset) implements KnowledgeDocumentResult {
        public DocumentSlice {
            document = ApiContracts.requireNonNull(document, "document");
            nextOffset = ApiContracts.requireNonNull(nextOffset, "nextOffset");
            if (startOffset < 0 || endOffset < startOffset || endOffset > document.totalCharacters()
                    || endOffset < document.totalCharacters() && (nextOffset.isEmpty() || nextOffset.getAsInt() != endOffset)
                    || endOffset == document.totalCharacters() && nextOffset.isPresent()
                    || startOffset == endOffset && endOffset < document.totalCharacters())
                throw new IllegalArgumentException("knowledge_document_result_invalid");
        }
    }
}
