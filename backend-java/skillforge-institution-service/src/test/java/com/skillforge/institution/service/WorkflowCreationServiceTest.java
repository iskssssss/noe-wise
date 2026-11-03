package com.skillforge.institution.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.workflow.WorkflowDraftLifecycleService;
import com.skillforge.core.workflow.dto.response.WorkflowDraftResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WorkflowCreationServiceTest {
    private final WorkflowManagementService management=mock(WorkflowManagementService.class);
    private final WorkflowDraftLifecycleService lifecycle=mock(WorkflowDraftLifecycleService.class);
    private final WorkflowCreateReceiptMapper receipts=mock(WorkflowCreateReceiptMapper.class);
    private final FoundationAccess access=mock(FoundationAccess.class);
    private final CapabilityCatalogDatabaseContext context=mock(CapabilityCatalogDatabaseContext.class);
    private final WorkflowCreationService service=new WorkflowCreationService(management,lifecycle,receipts,context,access,new ObjectMapper());
    @AfterEach void clear() { TenantContext.clear(); }

    @Test void creationGeneratesIdentityAndCommitsTheSameIdentityToReceipt() {
        TenantContext.setTenantId("7");
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false,"7","70"));
        when(receipts.claim(anyString(),eq(7L),eq("70"),anyString())).thenReturn(1);
        when(receipts.complete(anyString(),anyString())).thenReturn(1);
        when(management.create(eq(7L),anyString(),anyMap())).thenAnswer(call -> {
            String id=call.getArgument(1);
            Map<String,Object> definition=call.getArgument(2);
            assertEquals(id,((Map<?,?>)definition.get("meta")).get("name"));
            return new WorkflowDraftResponse(id,"draft",1,definition,List.of(),null);
        });
        UUID request=UUID.randomUUID();
        var result=service.create(new WorkflowCreationService.Command(request,Map.of("meta",Map.of("display_name","测试"))));
        assertTrue(result.draft().workflowId().startsWith("workflow-"));
        verify(receipts).complete(request.toString(),result.draft().workflowId());
    }
    @Test void receiptRequiresOriginalActorAndResourcePermission() {
        TenantContext.setTenantId("7");
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false,"7","70"));
        UUID request=UUID.randomUUID();
        when(receipts.find(request.toString())).thenReturn(new WorkflowCreateReceiptMapper.Row("71","hash","workflow-old"));
        assertNull(service.result(request));
        verifyNoInteractions(management);
        when(receipts.find(request.toString())).thenReturn(new WorkflowCreateReceiptMapper.Row("70","hash","workflow-old"));
        var draft=new WorkflowDraftResponse("workflow-old","draft",1,Map.of(),List.of(),null);
        when(lifecycle.getCurrentDraft(7,"workflow-old")).thenReturn(draft);
        assertEquals(draft,service.result(request).draft());
        verify(management).require(7,"workflow-old","read");
    }
    @Test void sameRequestWithDifferentContentDoesNotCreateAnotherWorkflow() {
        TenantContext.setTenantId("7");
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false,"7","70"));
        UUID request=UUID.randomUUID();
        when(receipts.claim(anyString(),eq(7L),eq("70"),anyString())).thenReturn(0);
        when(receipts.find(request.toString())).thenReturn(new WorkflowCreateReceiptMapper.Row("70","different","workflow-old"));
        assertThrows(ApiException.class,()->service.create(new WorkflowCreationService.Command(request,Map.of("meta",Map.of()))));
        verifyNoInteractions(management);
    }
}
