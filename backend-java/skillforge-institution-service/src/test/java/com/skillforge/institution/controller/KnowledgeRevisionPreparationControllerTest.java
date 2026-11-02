package com.skillforge.institution.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.knowledge.KnowledgeBaseRevisionService;
import com.skillforge.core.knowledge.dto.request.KnowledgeCapabilityPolicyRequest;
import com.skillforge.core.knowledge.dto.request.MarkKnowledgeBaseRevisionReadyRequest;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.service.KnowledgeManagementService;
import jakarta.validation.Validation;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class KnowledgeRevisionPreparationControllerTest {
    private final KnowledgeBaseRevisionService revisions = mock(KnowledgeBaseRevisionService.class);
    private final KnowledgeManagementService management = mock(KnowledgeManagementService.class);
    private final FoundationAccess access = mock(FoundationAccess.class);
    private final KnowledgeRevisionPreparationController controller =
            new KnowledgeRevisionPreparationController(revisions, management, access);
    private final KnowledgeCapabilityPolicyRequest policy =
            new KnowledgeCapabilityPolicyRequest(true, false, Set.of("KEYWORD"), "PINNED", true);

    @AfterEach void clear() { TenantContext.clear(); }

    @Test void combinesCreateAnnotationUpdatePermissionAndObjectManageScope() throws Exception {
        TenantContext.setTenantId("7");
        var command = new KnowledgeRevisionPreparationController.PrepareCommand(31L,
                new MarkKnowledgeBaseRevisionReadyRequest(List.of("source-selected"), policy));
        controller.prepare(command);
        assertEquals("foundation.knowledge.create", KnowledgeRevisionPreparationController.class
                .getMethod("prepare", KnowledgeRevisionPreparationController.PrepareCommand.class)
                .getAnnotation(RequirePermission.class).value());
        var order = inOrder(access, management, revisions);
        order.verify(access).require("foundation.knowledge.update");
        order.verify(management).requireBase("7", 31L, "manage");
        order.verify(revisions).prepare("7", 31L, List.of("source-selected"), policy);
        verifyNoMoreInteractions(revisions);
    }

    @Test void missingUpdatePermissionStopsBeforeObjectReadAndPreparation() {
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "denied")).when(access).require("foundation.knowledge.update");
        var command = new KnowledgeRevisionPreparationController.PrepareCommand(31L,
                new MarkKnowledgeBaseRevisionReadyRequest(List.of("source-selected"), policy));
        assertThrows(ApiException.class, () -> controller.prepare(command));
        verifyNoInteractions(management, revisions);
    }

    @Test void commandReusesNonemptyBoundedManifestAndCompletePolicyValidation() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (var payload : List.of(
                    new MarkKnowledgeBaseRevisionReadyRequest(List.of(), policy),
                    new MarkKnowledgeBaseRevisionReadyRequest(Collections.nCopies(2001, "v1"), policy),
                    new MarkKnowledgeBaseRevisionReadyRequest(List.of("v1"), null))) {
                assertFalse(validator.validate(new KnowledgeRevisionPreparationController.PrepareCommand(31L, payload)).isEmpty());
            }
            assertTrue(validator.validate(new KnowledgeRevisionPreparationController.PrepareCommand(31L,
                    new MarkKnowledgeBaseRevisionReadyRequest(List.of("v1"), policy))).isEmpty());
        }
    }
}
