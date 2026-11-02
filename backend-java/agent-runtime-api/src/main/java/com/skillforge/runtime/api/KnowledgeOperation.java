package com.skillforge.runtime.api;

import java.util.Optional;

/** 知识访问的封闭选择器；目录、原文读取与全文检索是不同动作。 */
public sealed interface KnowledgeOperation permits KnowledgeOperation.Search,
        KnowledgeOperation.ListDocuments, KnowledgeOperation.ReadDocument {
    record Search(String query) implements KnowledgeOperation {
        public Search { query = ApiContracts.requireText(query, "query"); }
    }

    /** cursor只定位同一冻结范围的下一页，不携带访问授权。 */
    record ListDocuments(String titleFilter, Optional<String> cursor, int pageSize) implements KnowledgeOperation {
        public ListDocuments {
            titleFilter = ApiContracts.requireNonNull(titleFilter, "titleFilter");
            cursor = ApiContracts.requireOptional(cursor, "cursor");
            cursor.ifPresent(value -> ApiContracts.requireText(value, "cursor"));
            if (titleFilter.codePointCount(0, titleFilter.length()) > KnowledgeOperationLimits.MAX_TITLE_FILTER_CHARACTERS || cursor.map(String::length).orElse(0) > KnowledgeOperationLimits.MAX_CURSOR_CHARACTERS
                    || pageSize < 1 || pageSize > KnowledgeOperationLimits.MAX_PAGE_SIZE) throw new IllegalArgumentException("knowledge_document_selector_invalid");
        }
    }

    /** offset/maxCharacters均以Unicode码点计数，读取精确版本而非当前文档指针。 */
    record ReadDocument(String sourceId, String documentId, String sourceVersion, int offset,
                        int maxCharacters) implements KnowledgeOperation {
        public ReadDocument {
            sourceId = ApiContracts.requireText(sourceId, "sourceId");
            documentId = ApiContracts.requireText(documentId, "documentId");
            sourceVersion = ApiContracts.requireText(sourceVersion, "sourceVersion");
            if (offset < 0 || maxCharacters < 1 || maxCharacters > KnowledgeOperationLimits.MAX_READ_CHARACTERS)
                throw new IllegalArgumentException("knowledge_document_selector_invalid");
        }
    }
}
