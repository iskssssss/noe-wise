package com.skillforge.institution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.skillforge.core.knowledge.KnowledgeBaseRevisionService;
import com.skillforge.core.knowledge.KnowledgeService;
import com.skillforge.core.knowledge.dto.response.CreateKnowledgeBaseResponse;
import com.skillforge.core.knowledge.dto.response.KnowledgeBaseListResponse;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.access.FoundationOrganizationService;
import com.skillforge.core.foundation.catalog.entitlements.FeatureGate;
import com.skillforge.core.foundation.catalog.entitlements.ResourceKind;
import com.skillforge.core.foundation.catalog.entitlements.ResourceQuota;
import com.skillforge.core.workflow.WorkflowDraftLifecycleService;
import com.skillforge.core.workflow.WorkflowInstanceService;
import com.skillforge.core.workflow.dto.response.WorkflowDraftListResponse;
import com.skillforge.core.workflow.dto.response.WorkflowDraftResponse;
import com.skillforge.institution.controller.KnowledgeController;
import com.skillforge.common.context.TenantContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** 验证真实管理消费者在资源体读取/副作用之前接入权限、配额和所有权。 */
class FoundationResourceConsumerTest {
    private final InstitutionResourceAccess resources = mock(InstitutionResourceAccess.class);
    private final FoundationOrganizationService organization = mock(FoundationOrganizationService.class);
    private final FeatureGate features = mock(FeatureGate.class);
    private final ResourceQuota quota = mock(ResourceQuota.class);
    private final FoundationAccess access = mock(FoundationAccess.class);
    private final KnowledgeService knowledge = mock(KnowledgeService.class);
    private final KnowledgeManagementService management = new KnowledgeManagementService(knowledge, resources, organization, features, quota, access);

    @AfterEach void clear() { TenantContext.clear(); }

    @Test void knowledgeCreateChecksCapacityBeforeWriteAndRegistersExactObject() {
        when(knowledge.createBase("7", "FAQ", null, null, null, null)).thenReturn(new CreateKnowledgeBaseResponse(91L, "FAQ"));
        assertThat(management.create("7", "FAQ", null).id()).isEqualTo(91L);
        var ordered = inOrder(features, quota, knowledge, organization);
        ordered.verify(features).require("7", "knowledge");
        ordered.verify(quota).requireCapacity("7", ResourceKind.KNOWLEDGE_BASE, 1);
        ordered.verify(knowledge).createBase("7", "FAQ", null, null, null, null);
        ordered.verify(organization).registerResource("knowledge", "91");
    }

    @Test void quotaRejectionDoesNotCreateKnowledgeOrOwnership() {
        doThrow(new ApiException(HttpStatus.CONFLICT, "quota exceeded")).when(quota).requireCapacity("7", ResourceKind.KNOWLEDGE_BASE, 1);
        assertThatThrownBy(() -> management.create("7", "FAQ", null)).isInstanceOf(ApiException.class);
        verifyNoInteractions(knowledge, organization);
    }

    @Test void knowledgeCreationWithSettingsChecksUpdatePermissionBeforeWriting() {
        var settings = new KnowledgeService.KnowledgeBaseSettingsUpdate(640, 40, null, null, 7, null, null, null, null);
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "no update")).when(access).require("foundation.knowledge.update");
        assertThatThrownBy(() -> management.create("7", "FAQ", null, "economical", "full_text", false, settings))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(knowledge, organization, quota);
        org.mockito.Mockito.reset(access);
        when(knowledge.createBase("7", "FAQ", null, "economical", "full_text", false))
                .thenReturn(new CreateKnowledgeBaseResponse(91L, "FAQ"));
        management.create("7", "FAQ", null, "economical", "full_text", false, settings);
        verify(resources).require("knowledge", "91", "manage");
        verify(knowledge).updateBase("7", 91L, null, null, null, settings);
    }

    @Test void externallyAuthorizedKnowledgeCreationUsesExistingExternalBoundary() {
        var settings = new KnowledgeService.KnowledgeBaseSettingsUpdate(640, null, null, null, null, null, null, null, null);
        when(resources.external()).thenReturn(true);
        when(knowledge.createBase("7", "FAQ", null, "economical", "full_text", false))
                .thenReturn(new CreateKnowledgeBaseResponse(91L, "FAQ"));
        management.create("7", "FAQ", null, "economical", "full_text", false, settings);
        verifyNoInteractions(access, organization);
        verify(resources).require("knowledge", "91", "manage");
        verify(knowledge).updateBase("7", 91L, null, null, null, settings);
    }
    /** 已认证独立凭据不制造成员所有者；删除按当前租户原子清理授权。 */
    @Test void externalKnowledgeCreateAndDeleteNeverInventMemberOwnership() {
        when(resources.external()).thenReturn(true);
        when(knowledge.createBase("7", "FAQ", null, null, null, null)).thenReturn(new CreateKnowledgeBaseResponse(91L, "FAQ"));
        management.create("7", "FAQ", null);
        verifyNoInteractions(organization);
        management.delete("7", 91L);
        verify(knowledge).deleteBase("7", 91L);
        verify(organization).forgetResourceForTenant("7", "knowledge", "91");
    }

    @Test void knowledgeControllerDeniesObjectBeforeReadingBody() {
        TenantContext.setTenantId("7");
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "resource denied")).when(resources).require("knowledge", "91", "read");
        var controller = new KnowledgeController(knowledge, mock(KnowledgeBaseRevisionService.class), management);
        assertThatThrownBy(() -> controller.getBase(91L)).isInstanceOf(ApiException.class);
        verifyNoInteractions(knowledge);
    }

    @Test void knowledgeListPassesStringIdsAsExactAllowedQueryScope() {
        when(resources.filter("knowledge", "read")).thenReturn(new FoundationAccess.ResourceFilter(false, List.of("9223372036854775806")));
        var result = new KnowledgeBaseListResponse(0, List.of());
        when(knowledge.listBases("7", List.of(9223372036854775806L))).thenReturn(result);
        assertThat(management.list("7")).isSameAs(result);
        verify(knowledge).listBases("7", List.of(9223372036854775806L));
    }

    @Test void workflowCreationAndListsUseContainerIdentity() {
        var lifecycle = mock(WorkflowDraftLifecycleService.class);
        var instances = mock(WorkflowInstanceService.class);
        var workflows = new WorkflowManagementService(lifecycle, instances, resources, organization, features, quota);
        var created = new WorkflowDraftResponse("review", "draft", 1L, Map.of(), List.of(), null);
        when(lifecycle.create(7L, "review", Map.of())).thenReturn(created);
        when(lifecycle.containerId(7L, "review")).thenReturn(41L);
        assertThat(workflows.create(7L, "review", Map.of())).isSameAs(created);
        var ordered = inOrder(features, quota, lifecycle, organization);
        ordered.verify(features).require("7", "workflows");
        ordered.verify(quota).requireCapacity("7", ResourceKind.WORKFLOW, 1);
        ordered.verify(lifecycle).create(7L, "review", Map.of());
        ordered.verify(lifecycle).containerId(7L, "review");
        ordered.verify(organization).registerResource("workflow", "41");
        when(resources.filter("workflow", "read")).thenReturn(new FoundationAccess.ResourceFilter(false, List.of("41")));
        when(lifecycle.list(7L, List.of(41L))).thenReturn(new WorkflowDraftListResponse(0, List.of()));
        workflows.list(7L); workflows.instances(7L);
        verify(lifecycle).list(7L, List.of(41L));
        verify(instances).listBuilderInstances(7L, List.of(41L));
    }
}
