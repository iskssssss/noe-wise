package com.skillforge.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.AgentTemplateService;
import com.skillforge.core.agent.mapper.AgentDeploymentMapper;
import com.skillforge.core.agent.model.AgentTemplateDO;
import com.skillforge.core.agent.model.AgentTemplateDefinition;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.integration.PlatformManagementService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentDefinitionImpactServiceTest {
    private final AgentTemplateService definitions=mock(AgentTemplateService.class);
    private final AgentDeploymentMapper deployments=mock(AgentDeploymentMapper.class);
    private final PlatformManagementService management=mock(PlatformManagementService.class);
    private final CapabilityCatalogDatabaseContext database=mock(CapabilityCatalogDatabaseContext.class);
    private final ObjectMapper json=new ObjectMapper();
    private final com.skillforge.core.agent.AgentDefinitionLifecycleService lifecycle=mock(com.skillforge.core.agent.AgentDefinitionLifecycleService.class);
    private final AgentDefinitionImpactService service=new AgentDefinitionImpactService(definitions,deployments,management,database,json,lifecycle);
    private AgentTemplateDO draft;
    private static final String EMPTY="{\"resources\":[],\"delegations\":[],\"parameter_keys\":[]}";

    @BeforeEach void setUp() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        draft=new AgentTemplateDO();draft.setId(31L);draft.setOwnerKind("platform");draft.setDraftRevision(4L);
        draft.setPublishedAt(Instant.EPOCH);draft.setDefinition(candidate());
        var publication=new AgentTemplateDO();publication.setVersion("1.0.0");publication.setDefinition(AgentTemplateDefinition.empty());
        when(definitions.getById(31L)).thenReturn(draft);when(definitions.currentPublished(31L)).thenReturn(publication);
        when(management.authorizedDefinitionReadScope(31L)).thenReturn(new PlatformManagementService.TenantScope("actor",2L,Set.of(7L)));
        when(deployments.databaseContext()).thenReturn(new AgentDeploymentMapper.DatabaseContext("","true"));
        when(deployments.impact(7L,31L)).thenReturn(List.of(new AgentDeploymentMapper.ImpactRow(9007199254740993L,7L,11L,5L,EMPTY)));
    }
    @AfterEach void clear() {
        TransactionSynchronizationManager.getSynchronizations().forEach(sync->sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        TransactionSynchronizationManager.clear();TenantContext.clear();
    }

    @Test void exactDraftComparedToPublicationShowsOnlyAuthorizedDeploymentDeclarationImpact() throws Exception {
        doAnswer(call->{assertEquals("7",TenantContext.getTenantId());return null;}).when(database).bindTenant(7L);
        var result=service.impact(31L,4L);
        assertEquals("1.0.0",result.currentPublishedRevision());assertEquals(4L,result.draftRevision());
        assertEquals(List.of("knowledge"),result.changes().addedResourceSlots().stream().map(slot->slot.key()).toList());
        assertEquals(List.of("assistant"),result.changes().addedDelegationSlots().stream().map(slot->slot.key()).toList());
        assertEquals(List.of("store"),result.changes().addedParameterKeys());assertEquals(1,result.visibleDeploymentCount());
        var deployment=result.deployments().getFirst();
        assertEquals(Boolean.TRUE,deployment.currentDeclarationsSatisfied());assertFalse(deployment.draftDeclarationsSatisfied());
        assertEquals(5L,deployment.deploymentRevision());
        assertEquals(List.of("resource_bindings.knowledge","delegation_bindings.assistant","parameters.store"),deployment.draftIssues().stream().map(issue->issue.path()).toList());
        var tree=json.readTree(json.writeValueAsString(result));
        assertEquals("9007199254740993",tree.path("deployments").get(0).path("deployment_id").textValue());
        assertTrue(tree.path("draft_revision").isIntegralNumber());
        String wire=tree.toString();assertFalse(wire.contains("{{parameters"));assertFalse(wire.contains("ready"));
        assertFalse(wire.contains("resource_bindings\":"));assertFalse(wire.contains("parameters\":"));
        verify(deployments,never()).impact(8L,31L);verify(deployments).restoreDatabaseContext("","true");
        verify(definitions,never()).updateById(any(AgentTemplateDO.class));
        assertNull(TenantContext.getTenantId());
    }

    @Test void candidatePreviewUsesUnsavedDefinitionAndKeepsAuthorizedTargetScope() {
        var proposed=AgentTemplateDefinition.empty();
        when(lifecycle.validateCandidate(31L,4L,proposed)).thenReturn(new com.skillforge.core.agent.dto.response.AgentTemplateValidationResponse(true,List.of()));
        var result=service.preview(31L,4L,proposed);
        assertTrue(result.validation().valid());
        assertTrue(result.impact().changes().addedResourceSlots().isEmpty());
        assertTrue(result.impact().deployments().getFirst().draftDeclarationsSatisfied());
        verify(deployments,never()).impact(8L,31L);
        verify(definitions,never()).updateById(any(AgentTemplateDO.class));
    }

    @Test void invalidCandidateDoesNotReadDeploymentScope() {
        var proposed=AgentTemplateDefinition.empty();
        when(lifecycle.validateCandidate(31L,4L,proposed)).thenReturn(new com.skillforge.core.agent.dto.response.AgentTemplateValidationResponse(false,List.of()));
        var result=service.preview(31L,4L,proposed);
        assertFalse(result.validation().valid());
        assertNull(result.impact());
        verifyNoInteractions(management,deployments);
    }

    @Test void changedAndRemovedDeclarationsAreComparedAgainstImmutablePublication() {
        var publication=new AgentTemplateDO();publication.setVersion("1.0.0");
        publication.setDefinition(new AgentTemplateDefinition("{{parameters.old}}","role","1",null,List.of(),
                List.of(new AgentTemplateDefinition.ResourceSlot("knowledge","旧","",CapabilityType.WORKFLOW),
                        new AgentTemplateDefinition.ResourceSlot("retired","旧","",CapabilityType.WORKFLOW)),
                List.of(new AgentTemplateDefinition.DelegationSlot("old","旧",""))));
        when(definitions.currentPublished(31L)).thenReturn(publication);
        var changes=service.impact(31L,4).changes();
        assertTrue(changes.addedResourceSlots().isEmpty());assertEquals("knowledge",changes.changedResourceSlots().getFirst().key());
        assertEquals(List.of("retired"),changes.removedResourceSlotKeys());assertEquals(List.of("old"),changes.removedDelegationSlotKeys());
        assertEquals(List.of("old"),changes.removedParameterKeys());
    }

    @Test void firstPublicationHasExplicitNullBaselineAndEmptyTargetScopeDoesNotScanDeployments() {
        draft.setPublishedAt(null);
        when(management.authorizedDefinitionReadScope(31L)).thenReturn(new PlatformManagementService.TenantScope("actor",2L,Set.of()));
        var result=service.impact(31L,4L);
        assertNull(result.currentPublishedRevision());assertEquals(0,result.visibleDeploymentCount());
        assertEquals("knowledge",result.changes().addedResourceSlots().getFirst().key());
        verify(definitions,never()).currentPublished(any());verifyNoInteractions(deployments,database);
    }

    @Test void deniedDefinitionNeverReadsSourceOrAnyTenantData() {
        doThrow(new ApiException(HttpStatus.FORBIDDEN,"no target")).when(management).authorizedDefinitionReadScope(31L);
        assertEquals(403,assertThrows(ApiException.class,()->service.impact(31L,4L)).getStatus().value());
        verifyNoInteractions(definitions,deployments,database);
    }

    @Test void staleDraftIsRejectedBeforePublicationOrDeploymentQueries() {
        assertEquals(409,assertThrows(ApiException.class,()->service.impact(31L,3L)).getStatus().value());
        verify(definitions,never()).currentPublished(any());verifyNoInteractions(deployments,database);
    }

    @Test void malformedProjectionAndBindFailureBothRestoreDatabaseAndThreadScope() {
        when(deployments.impact(7L,31L)).thenReturn(List.of(new AgentDeploymentMapper.ImpactRow(1L,7L,11L,1L,"{}")));
        assertThrows(IllegalStateException.class,()->service.impact(31L,4));
        verify(deployments).restoreDatabaseContext("","true");assertNull(TenantContext.getTenantId());
        clearInvocations(deployments);
        doThrow(new IllegalStateException("bind failed")).when(database).bindTenant(7L);
        assertThrows(IllegalStateException.class,()->service.impact(31L,4));
        verify(deployments).restoreDatabaseContext("","true");assertNull(TenantContext.getTenantId());
    }

    @Test void previewRequiresOneReadOnlyRepeatableReadTransactionForTheWholeSnapshot() throws Exception {
        var transaction=AgentDefinitionImpactService.class.getMethod("impact",Long.class,long.class).getAnnotation(Transactional.class);
        assertTrue(transaction.readOnly());assertEquals(Isolation.REPEATABLE_READ,transaction.isolation());
    }

    private AgentTemplateDefinition candidate() {
        return new AgentTemplateDefinition("私有提示词 {{parameters.store}}","role","1",null,List.of(),
                List.of(new AgentTemplateDefinition.ResourceSlot("knowledge","知识","",CapabilityType.KNOWLEDGE_SOURCE)),
                List.of(new AgentTemplateDefinition.DelegationSlot("assistant","助理","")));
    }
}
