package com.skillforge.institution.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.agent.AgentDefinitionLifecycleService;
import com.skillforge.core.agent.AgentTemplateService;
import com.skillforge.core.agent.dto.response.AdminAgentTemplateDetailResponse;
import com.skillforge.core.agent.mapper.AgentDeploymentMapper;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.institution.InstitutionDeploymentManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** 机构源影响读取先核源动作、owner和精确修订；跨Tenant窗口真实隔离由所属PG/HTTP组合验证。 */
class TenantAgentDefinitionImpactServiceTest {
    private final AgentDefinitionLifecycleService lifecycle=mock(AgentDefinitionLifecycleService.class);
    private final AgentTemplateService templates=mock(AgentTemplateService.class);
    private final AgentDeploymentMapper deployments=mock(AgentDeploymentMapper.class);
    private final InstitutionDeploymentManagementService management=mock(InstitutionDeploymentManagementService.class);
    private final CapabilityCatalogDatabaseContext database=mock(CapabilityCatalogDatabaseContext.class);
    private final TenantAgentDefinitionImpactService service=new TenantAgentDefinitionImpactService(lifecycle,templates,deployments,management,database,new ObjectMapper());
    /** 源权限被拒绝时不读取目标、发布或任何结构投影。 */
    @Test void ownerPermissionFailurePrecedesAllTargetReads() {
        when(lifecycle.detail(7L)).thenThrow(new ApiException(HttpStatus.FORBIDDEN,"源不可见"));
        assertThatThrownBy(()->service.impact(7L,1)).isInstanceOf(ApiException.class);
        verifyNoInteractions(templates,deployments,management,database);
    }
    /** 预览的草稿修订不一致时，不查目标数量或偷偷预览另一个版本。 */
    @Test void staleRevisionStopsBeforePublicationAndTargetProjection() {
        var draft=mock(AdminAgentTemplateDetailResponse.class);when(draft.draftRevision()).thenReturn(4L);when(lifecycle.detail(7L)).thenReturn(draft);
        assertThatThrownBy(()->service.impact(7L,3)).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getStatus().value()).isEqualTo(409));
        verifyNoInteractions(templates,deployments,management,database);
    }
    @Test void invalidCandidateReturnsIssuesWithoutReadingTargets() {
        var candidate=com.skillforge.core.agent.model.AgentTemplateDefinition.empty();
        var validation=new com.skillforge.core.agent.dto.response.AgentTemplateValidationResponse(false, java.util.List.of());
        when(lifecycle.validateCandidate(7L,3,candidate)).thenReturn(validation);
        var result=service.preview(7L,3,candidate);
        assertThat(result.validation()).isEqualTo(validation);
        assertThat(result.impact()).isNull();
        verifyNoInteractions(templates,deployments,management,database);
    }
    @Test void validCandidatePreviewUsesCandidateInsteadOfSavedDraft() {
        var candidate=new com.skillforge.core.agent.model.AgentTemplateDefinition("new {{parameters.branch}}", "role", "model", null,
                java.util.List.of(), java.util.List.of(new com.skillforge.core.agent.model.AgentTemplateDefinition.ResourceSlot(
                        "records", "资料", "", com.skillforge.core.capability.CapabilityType.KNOWLEDGE_SOURCE)), java.util.List.of());
        var draft=mock(AdminAgentTemplateDetailResponse.class);
        when(draft.draftRevision()).thenReturn(3L);
        when(draft.definition()).thenReturn(com.skillforge.core.agent.model.AgentTemplateDefinition.empty());
        when(lifecycle.detail(7L)).thenReturn(draft);
        when(lifecycle.validateCandidate(7L,3,candidate)).thenReturn(new com.skillforge.core.agent.dto.response.AgentTemplateValidationResponse(true,java.util.List.of()));
        when(management.authorizedTargets("tenant.agent_deployments.read")).thenReturn(java.util.Set.of());
        when(deployments.impact(7L,7L)).thenReturn(java.util.List.of());
        com.skillforge.common.context.TenantContext.setTenantId("7");
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        org.springframework.transaction.support.TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            var preview=service.preview(7L,3,candidate);
            assertThat(preview.impact().changes().addedResourceSlots()).extracting("key").containsExactly("records");
            assertThat(preview.impact().changes().addedParameterKeys()).containsExactly("branch");
        } finally {
            for (var sync : org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations())
                sync.afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK);
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
            org.springframework.transaction.support.TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
            com.skillforge.common.context.TenantContext.clear();
        }
    }
}
