package com.skillforge.institution.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.knowledge.KnowledgeBaseRevisionService;
import com.skillforge.core.knowledge.KnowledgeService;
import com.skillforge.institution.service.KnowledgeManagementService;
import com.skillforge.core.knowledge.KnowledgeHit;
import com.skillforge.core.knowledge.dto.response.*;
import com.skillforge.core.knowledge.enums.KnowledgeBaseStatus;
import com.skillforge.core.knowledge.dto.request.CreateKnowledgeBaseRequest;
import com.skillforge.core.knowledge.dto.request.KnowledgeSearchRequest;
import com.skillforge.core.knowledge.dto.request.MarkKnowledgeBaseRevisionReadyRequest;
import com.skillforge.core.knowledge.dto.request.KnowledgeCapabilityPolicyRequest;
import com.skillforge.core.knowledge.dto.request.UpdateKnowledgeBaseRequest;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 知识库控制面覆盖：基础 CRUD、不可变 revision 生命周期、上传与检索响应投影。 */
class KnowledgeControllerCoverageTest {

    private KnowledgeService knowledge;
    private KnowledgeBaseRevisionService revisions;
    private KnowledgeController controller;
    private KnowledgeManagementService management;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId("7");
        knowledge = mock(KnowledgeService.class);
        revisions = mock(KnowledgeBaseRevisionService.class);
        management = mock(KnowledgeManagementService.class);
        controller = new KnowledgeController(knowledge, revisions, management);
        when(management.list("7")).thenReturn(new KnowledgeBaseListResponse(1,
                List.of(new KnowledgeBaseItemResponse(31L, "FAQ", null, KnowledgeBaseStatus.fromCode("active"), 2, 9))));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void baseCrudAndRevisionLifecycleUseCurrentTenant() {
        assertThat(controller.listBases().data().total()).isEqualTo(1);
        when(management.create("7", "FAQ", "help", null, null, null, null)).thenReturn(new CreateKnowledgeBaseResponse(31L, "FAQ"));
        assertThat(controller.createBase(new CreateKnowledgeBaseRequest("FAQ", "help", null, null, null, null, null, null, null, null, null)).data().id()).isEqualTo(31L);
        var detail = new KnowledgeBaseDetailResponse(31L, "FAQ", null, "active", 4L, 500, 50,
                "high_quality", "hybrid", 5, true, 0.5, 0.7, false, 2, 9, Instant.parse("2026-08-25T00:00:00Z"), "high_quality");
        when(knowledge.getBase("7", 31L)).thenReturn(detail);
        when(knowledge.updateBase(any(), any(), any(), any(), any(), any())).thenReturn(detail);
        assertThat(controller.getBase(31L).data().name()).isEqualTo("FAQ");
        controller.updateBase(31L, new UpdateKnowledgeBaseRequest("FAQ2", null, "archived", 300,
                30, "economical", "full_text", 3, false, 0.2, 0.3, true));
        controller.deleteBase(31L);
        verify(management).delete("7", 31L);

        controller.listRevisions(31L);
        controller.createRevision(31L);
        controller.markRevisionReady(31L, 2L, new MarkKnowledgeBaseRevisionReadyRequest(
                List.of("v1"), new KnowledgeCapabilityPolicyRequest(
                        true, false, Set.of("VECTOR"), "PINNED", true)));
        controller.publishRevision(31L, 2L);
        verify(revisions).publish("7", 31L, 2L);
    }

    @Test
    void createPassesAllAdvancedSettingsToAtomicManagement() {
        var settings = new KnowledgeService.KnowledgeBaseSettingsUpdate(640, 40, null, null, 7, true, 0.6, 0.4, null);
        when(management.create("7", "FAQ", null, "high_quality", "hybrid", true, settings))
                .thenReturn(new CreateKnowledgeBaseResponse(31L, "FAQ"));
        var request = new CreateKnowledgeBaseRequest("FAQ", null, "high_quality", "hybrid", true,
                640, 40, 7, true, 0.6, 0.4);
        assertThat(controller.createBase(request).data().id()).isEqualTo(31L);
    }

    @Test
    void searchResponseProjectsTypedHits() {
        when(knowledge.search(any(), any(), any(), anyInt(), any())).thenReturn(
                List.of(new KnowledgeHit("31", "d1", "c1", "v1", "answer", 0.9, 0.8)));
        var inherited = new KnowledgeSearchRequest("31", "query", null, null, null, null, null, null);
        assertThat(controller.search(inherited).data().items()).hasSize(1);
        verify(knowledge).search("7", "31", "query", 0, inherited);
        var override = new KnowledgeSearchRequest("31", "query", 2, "hybrid", true, 0.4, 0.7, true);
        assertThat(controller.search(override).data().items()).hasSize(1);
        verify(knowledge).search("7", "31", "query", 2, override);
    }
}
