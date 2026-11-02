package com.skillforge.institution.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.knowledge.dto.knowledge.CreateKnowledgeWriteProposalRequest;
import com.skillforge.core.knowledge.dto.knowledge.ReviewKnowledgeWriteProposalRequest;
import com.skillforge.core.knowledge.knowledge.KnowledgeWriteProposal;
import com.skillforge.core.knowledge.knowledge.KnowledgeWriteProposalReviewResult;
import com.skillforge.core.knowledge.application.KnowledgeWriteProposalService;
import com.skillforge.institution.service.KnowledgeProposalManagementService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class KnowledgeWriteProposalControllerTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void routesCurrentTenantAndNeverChangesProposalStatusAtCreateBoundary() {
        KnowledgeWriteProposalService service = mock(KnowledgeWriteProposalService.class);
        KnowledgeProposalManagementService management = mock(KnowledgeProposalManagementService.class);
        when(management.reviewer()).thenReturn("11");
        KnowledgeWriteProposalController controller = new KnowledgeWriteProposalController(service, management);
        TenantContext.setTenantId("7");
        KnowledgeWriteProposal created = proposal("p-controller");
        when(service.create(any(), any())).thenReturn(created);
        when(management.pending("7")).thenReturn(List.of(created));
        KnowledgeWriteProposal approved = new KnowledgeWriteProposal("p-controller", KnowledgeWriteProposal.Target.MEMORY,
                "event-p-controller", "bundle-p-controller", Map.of(), KnowledgeWriteProposal.Status.APPROVED);
        when(service.approve("7", "p-controller", "11", "ok")).thenReturn(
                new KnowledgeWriteProposalReviewResult(approved, "applied", 99L, null));

        assertEquals(created, controller.create(new CreateKnowledgeWriteProposalRequest(
                KnowledgeWriteProposal.Target.MEMORY, "p-controller", "event-p-controller",
                "bundle-p-controller", Map.of())).data());
        assertEquals(List.of(created), controller.pending().data());
        assertEquals(KnowledgeWriteProposal.Status.APPROVED,
                controller.approveEndpoint(new KnowledgeWriteProposalController.ApproveCommand(
                        "p-controller", new ReviewKnowledgeWriteProposalRequest("admin", "ok")))
                        .data().proposal().status());

        verify(service).create("7", created);
        verify(management).pending("7");
        verify(service).approve("7", "p-controller", "11", "ok");
    }

    private KnowledgeWriteProposal proposal(String proposalId) {
        return new KnowledgeWriteProposal(proposalId, KnowledgeWriteProposal.Target.MEMORY,
                "event-" + proposalId, "bundle-" + proposalId, Map.of(),
                KnowledgeWriteProposal.Status.PENDING_CONFIRM);
    }
}
