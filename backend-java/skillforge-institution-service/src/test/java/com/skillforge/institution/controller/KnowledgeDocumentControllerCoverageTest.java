package com.skillforge.institution.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.knowledge.KnowledgeReindexReceiptService;
import com.skillforge.core.knowledge.KnowledgeService;
import com.skillforge.core.knowledge.dto.knowledge.ReindexResponse;
import com.skillforge.institution.service.KnowledgeManagementService;
import com.skillforge.core.knowledge.dto.response.*;
import com.skillforge.core.knowledge.dto.query.KnowledgeDocumentQuery;
import com.skillforge.core.knowledge.dto.request.UpdateChunkRequest;
import com.skillforge.core.knowledge.dto.request.UpdateDocumentRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 文档与分段控制器覆盖：响应投影、分页边界与写操作委托。 */
class KnowledgeDocumentControllerCoverageTest {

    private KnowledgeService knowledge;
    private KnowledgeManagementService management;
    private KnowledgeReindexReceiptService reindexReceipts;
    private FoundationAccess access;
    private KnowledgeDocumentController controller;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId("7");
        knowledge = mock(KnowledgeService.class);
        management = mock(KnowledgeManagementService.class);
        reindexReceipts = mock(KnowledgeReindexReceiptService.class);
        access = mock(FoundationAccess.class);
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false, "7", "9"));
        controller = new KnowledgeDocumentController(knowledge, management, reindexReceipts, access);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void listsAndProjectsFullDocumentAndChunkResponses() {
        Instant createdAt = Instant.parse("2026-08-25T00:00:00Z");
        KnowledgeDocumentQuery query = new KnowledgeDocumentQuery(2, 50, "ready", null);
        var row = new DocumentItemResponse(41L, "Guide", "file", "ready", "v1", 3, createdAt);
        when(knowledge.listDocuments("7", 31L, query)).thenReturn(new DocumentListResponse(1L, List.of(row)));

        var listedDocument = controller.listDocuments(31L, query).data().items().get(0);
        assertThat(listedDocument.chunkCount()).isEqualTo(3);
        assertThat(listedDocument.sourceVersion()).isEqualTo("v1");

        var detail = new DocumentDetailResponse(41L, 31L, "Guide", "file", "ready", "v1",
                "body", "guide.md", "md", 128L, "direct", createdAt);
        when(knowledge.getDocument("7", 41L)).thenReturn(detail);
        assertThat(controller.getDocument(41L).data().originalFilename()).isEqualTo("guide.md");

        var chunk = new ChunkItemResponse(51L, 0, "part", true, "v1", createdAt);
        when(knowledge.listChunks("7", 41L, 1, 200)).thenReturn(new ChunkListResponse(1L, List.of(chunk)));
        var listedChunk = controller.listChunks(41L, 0, 999).data().items().get(0);
        assertThat(listedChunk.hasEmbedding()).isTrue();
        assertThat(listedChunk.sourceVersion()).isEqualTo("v1");
    }

    @Test
    void writesDocumentsChunksAndReindexResults() {
        var updated = new DocumentDetailResponse(41L, 31L, "Guide 2", "file", "ready", "v2",
                "new body", null, null, null, null, null);
        when(knowledge.updateDocument("7", 41L, "Guide 2", "new body", "v1")).thenReturn(updated);
        assertThat(controller.updateDocument(41L, new UpdateDocumentRequest("Guide 2", "new body", "v1"))
                .title()).isEqualTo("Guide 2");

        UUID requestId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        var receipt = new KnowledgeReindexReceiptService.Receipt(
                requestId, "SUCCEEDED", new ReindexResponse(41L, 2, "v2"), null);
        when(reindexReceipts.reindex("7", 41L, "9", requestId, "v1")).thenReturn(receipt);
        when(reindexReceipts.result("7", 41L, "9", requestId)).thenReturn(receipt);
        assertThat(controller.reindexDocumentEndpoint(
                new KnowledgeDocumentController.ReindexDocumentCommand(41L, requestId, "v1")).data())
                .isSameAs(receipt);
        assertThat(controller.reindexResult(41L, requestId).getBody().data()).isSameAs(receipt);
        verify(management, times(2)).requireDocument("7", 41L, "manage");
        verify(management).requireDocument("7", 41L, "read");
        controller.deleteDocument(41L, "v1");

        var chunk = new ChunkItemResponse(51L, 1, "edited", false, "v2", null);
        when(knowledge.updateChunk("7", 51L, "edited", "v1")).thenReturn(chunk);
        assertThat(controller.updateChunk(51L, new UpdateChunkRequest("edited", "v1")).hasEmbedding()).isFalse();
        controller.deleteChunk(51L, "v1");

        verify(knowledge).deleteDocument("7", 41L, "v1");
        verify(knowledge).deleteChunk("7", 51L, "v1");
    }

    @Test
    void typedEmptyPagesAndNullableDocumentMetadataAreReturnedDirectly() {
        when(knowledge.listDocuments("7", 31L, null)).thenReturn(new DocumentListResponse(0L, List.of()));
        assertThat(controller.listDocuments(31L, null).data().items()).isEmpty();
        var detail = new DocumentDetailResponse(41L, 31L, "x", "file", "ready", "v1",
                null, null, null, null, null, null);
        when(knowledge.getDocument("7", 41L)).thenReturn(detail);
        assertThat(controller.getDocument(41L).data()).isSameAs(detail);
        when(knowledge.listChunks("7", 41L, 1, 1)).thenReturn(new ChunkListResponse(0L, List.of()));
        assertThat(controller.listChunks(41L, -1, 0).data().items()).isEmpty();
    }
}
