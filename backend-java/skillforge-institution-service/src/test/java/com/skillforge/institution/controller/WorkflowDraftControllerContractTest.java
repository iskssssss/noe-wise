package com.skillforge.institution.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.workflow.WorkflowDraftLifecycleService;
import com.skillforge.core.workflow.WorkflowBuilderSnapshotService;
import com.skillforge.core.workflow.WorkflowBuilderInstallationService;
import com.skillforge.core.workflow.WorkflowInstallationBindings;
import com.skillforge.core.workflow.WorkflowInstanceService;
import com.skillforge.institution.service.WorkflowManagementService;
import com.skillforge.core.workflow.dto.request.SaveWorkflowBuilderSnapshotRequest;
import com.skillforge.core.workflow.dto.request.WorkflowInstallByWorkflowIdRequest;
import com.skillforge.core.workflow.dto.response.BuilderWorkflowInstanceResponse;
import com.skillforge.core.workflow.dto.response.WorkflowBuilderSnapshotResponse;
import com.skillforge.core.workflow.dto.response.WorkflowDraftDiffResponse;
import com.skillforge.core.workflow.dto.response.WorkflowDraftListItemResponse;
import com.skillforge.core.workflow.dto.response.WorkflowDraftListResponse;
import com.skillforge.core.workflow.dto.response.WorkflowDraftResponse;
import com.skillforge.core.workflow.dto.response.WorkflowDraftValidationResponse;
import com.skillforge.core.workflow.dto.response.WorkflowInstallResponse;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;

/** Builder 草稿控制面契约：发布物只读，只能显式 fork；绝不暴露执行旁路。 */
class WorkflowDraftControllerContractTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void validatesUnsavedContentThroughTheCorrectPermissionAndTenantBoundary() throws Exception {
        var lifecycle = mock(WorkflowDraftLifecycleService.class);
        var management = mock(WorkflowManagementService.class);
        var snapshots = mock(WorkflowBuilderSnapshotService.class);
        var controller = new WorkflowDraftController(lifecycle, snapshots,
                mock(WorkflowBuilderInstallationService.class), management);
        Map<String, Object> definition = Map.of("entry", "start");
        var newResult = new WorkflowDraftValidationResponse(0L, List.of());
        var draftResult = new WorkflowDraftValidationResponse(4L, List.of());
        when(lifecycle.validateNewContent(definition)).thenReturn(newResult);
        when(lifecycle.validateContent(7L, "request", 4L, definition)).thenReturn(draftResult);
        TenantContext.setTenantId("7");

        assertEquals(newResult, controller.validateNewContent(new WorkflowDraftController.ValidateContentCommand(definition)).data());
        assertEquals(draftResult, controller.validateContent(
                new WorkflowDraftController.ValidateDraftContentCommand("request", definition), "\"4\"").data());
        verify(management).require(7L, "request", "manage");
        verifyNoInteractions(snapshots);
        var permission = com.skillforge.core.foundation.access.RequirePermission.class;
        assertEquals("foundation.workflows.create", WorkflowDraftController.class
                .getMethod("validateNewContent", WorkflowDraftController.ValidateContentCommand.class)
                .getAnnotation(permission).value());
        assertEquals("foundation.workflows.update", WorkflowDraftController.class
                .getMethod("validateContent", WorkflowDraftController.ValidateDraftContentCommand.class, String.class)
                .getAnnotation(permission).value());
    }

    @Test
    void forksPublishedDefinitionWithTenantAndIfMatchRevision() {
        WorkflowDraftLifecycleService lifecycle = mock(WorkflowDraftLifecycleService.class);
        WorkflowDraftController controller = controller(lifecycle, mock(WorkflowBuilderSnapshotService.class),
                mock(WorkflowBuilderInstallationService.class));
        WorkflowDraftResponse expected = new WorkflowDraftResponse("request", "draft", 5L,
                Map.of("entry", "start", "nodes", List.of(), "edges", List.of()), List.of(), null);
        when(lifecycle.forkPublishedDraft(7L, "request", 4L)).thenReturn(expected);
        TenantContext.setTenantId("7");

        WorkflowDraftResponse actual = controller.forkPublishedDraft("request", "\"4\"");

        assertEquals(expected, actual);
        verify(lifecycle).forkPublishedDraft(7L, "request", 4L);
    }

    @Test
    void listsOnlyTheCurrentTenantBuilderContainers() {
        WorkflowDraftLifecycleService lifecycle = mock(WorkflowDraftLifecycleService.class);
        WorkflowManagementService management = mock(WorkflowManagementService.class);
        WorkflowDraftController controller = new WorkflowDraftController(lifecycle, mock(WorkflowBuilderSnapshotService.class),
                mock(WorkflowBuilderInstallationService.class), management);
        WorkflowDraftListResponse expected = new WorkflowDraftListResponse(1,
                List.of(new WorkflowDraftListItemResponse("request", "draft", 4L,
                        null, "申请办理", "可办理事项")));
        when(management.list(7L)).thenReturn(expected);
        TenantContext.setTenantId("7");

        assertEquals(expected, controller.list().data());
        verify(management).list(7L);
    }

    @Test
    void rejectsMalformedIfMatchBeforeCallingLifecycle() {
        WorkflowDraftLifecycleService lifecycle = mock(WorkflowDraftLifecycleService.class);
        WorkflowDraftController controller = controller(lifecycle, mock(WorkflowBuilderSnapshotService.class),
                mock(WorkflowBuilderInstallationService.class));
        TenantContext.setTenantId("7");

        ApiException error = assertThrows(ApiException.class,
                () -> controller.forkPublishedDraft("request", "not-a-revision"));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        verifyNoInteractions(lifecycle);
    }

    @Test
    void readsDiffThroughTheSameTenantScopedBuilderControlPlane() {
        WorkflowDraftLifecycleService lifecycle = mock(WorkflowDraftLifecycleService.class);
        WorkflowDraftController controller = controller(lifecycle, mock(WorkflowBuilderSnapshotService.class),
                mock(WorkflowBuilderInstallationService.class));
        WorkflowDraftDiffResponse expected = new WorkflowDraftDiffResponse(4L, 2L, List.of());
        when(lifecycle.diff(7L, "request")).thenReturn(expected);
        TenantContext.setTenantId("7");

        assertEquals(expected, controller.diff("request").data());
        verify(lifecycle).diff(7L, "request");
    }

    @Test
    void listsInstancesThroughTheImmutableInstallationReadChain() {
        WorkflowManagementService instances = mock(WorkflowManagementService.class);
        WorkflowDraftController controller = new WorkflowDraftController(mock(WorkflowDraftLifecycleService.class),
                mock(WorkflowBuilderSnapshotService.class), mock(WorkflowBuilderInstallationService.class), instances);
        BuilderWorkflowInstanceResponse expected = new BuilderWorkflowInstanceResponse(11L, "request_7",
                "request", 91L, 3L, 501L, "published", Map.of("locale", "zh-CN"), "prompt", null);
        when(instances.instances(7L)).thenReturn(List.of(expected));
        TenantContext.setTenantId("7");

        var response = controller.listInstances();

        assertEquals(List.of(expected), response.data().items());
        verify(instances).instances(7L);
    }

    @Test
    void savesSnapshotWithTheCurrentDraftRevisionAndNoLifecycleWrite() {
        WorkflowDraftLifecycleService lifecycle = mock(WorkflowDraftLifecycleService.class);
        WorkflowBuilderSnapshotService snapshots = mock(WorkflowBuilderSnapshotService.class);
        WorkflowDraftController controller = controller(lifecycle, snapshots,
                mock(WorkflowBuilderInstallationService.class));
        WorkflowBuilderSnapshotResponse expected = new WorkflowBuilderSnapshotResponse("request", 4L,
                Map.of("nodes", List.of(Map.of("id", "start", "x", 10, "y", 20))),
                Map.of("zoom", 1.2, "panX", 40, "panY", 12));
        when(snapshots.save(7L, "request", 4L, expected.layout(), expected.viewport())).thenReturn(expected);
        TenantContext.setTenantId("7");

        assertEquals(expected, controller.saveSnapshot("request", "4",
                new SaveWorkflowBuilderSnapshotRequest(expected.layout(), expected.viewport())));
        verify(snapshots).save(7L, "request", 4L, expected.layout(), expected.viewport());
        verifyNoInteractions(lifecycle);
    }

    @Test
    void installsOrUpgradesOnlyThroughTheTenantScopedBuilderControlPlane() {
        WorkflowDraftLifecycleService lifecycle = mock(WorkflowDraftLifecycleService.class);
        WorkflowBuilderInstallationService installations = mock(WorkflowBuilderInstallationService.class);
        WorkflowDraftController controller = new WorkflowDraftController(
                lifecycle, mock(WorkflowBuilderSnapshotService.class), installations,
                mock(WorkflowManagementService.class));
        WorkflowInstallResponse expected = new WorkflowInstallResponse(
                "installed", "request", "7", "request_7", 1L);
        Map<String, Object> config = Map.of("locale", "zh-CN");
        WorkflowInstallationBindings bindings = WorkflowInstallationBindings.empty();
        when(installations.installOrUpgrade(7L, "request", config, "custom", bindings))
                .thenReturn(expected);
        TenantContext.setTenantId("7");

        assertEquals(expected, controller.installOrUpgrade("request",
                new WorkflowInstallByWorkflowIdRequest(config, "custom", bindings)));
        verify(installations).installOrUpgrade(7L, "request", config, "custom", bindings);
        verifyNoInteractions(lifecycle);
    }

    @Test
    void readsPublicationHistoryAndExactDetailThroughAuthorizedManagementFacade() {
        var lifecycle = mock(WorkflowDraftLifecycleService.class);
        var management = mock(WorkflowManagementService.class);
        var controller = new WorkflowDraftController(lifecycle, mock(WorkflowBuilderSnapshotService.class),
                mock(WorkflowBuilderInstallationService.class), management);
        TenantContext.setTenantId("7");
        controller.history("request");
        controller.publicationDetail("request", 91L);
        verify(management).history(7L, "request");
        verify(management).publicationDetail(7L, "request", 91L);
        verifyNoInteractions(lifecycle);
    }

    @Test
    void publicationFacadeRequiresResourceReadBeforeLoadingSummaryOrGraph() {
        var lifecycle = mock(WorkflowDraftLifecycleService.class);
        var resources = mock(com.skillforge.institution.service.InstitutionResourceAccess.class);
        var management = new WorkflowManagementService(lifecycle, mock(WorkflowInstanceService.class), resources,
                mock(com.skillforge.core.foundation.access.FoundationOrganizationService.class),
                mock(com.skillforge.core.foundation.catalog.entitlements.FeatureGate.class),
                mock(com.skillforge.core.foundation.catalog.entitlements.ResourceQuota.class));
        when(lifecycle.containerId(7L, "request")).thenReturn(42L);
        var denied = new ApiException(HttpStatus.FORBIDDEN, "没有资源访问权限");
        org.mockito.Mockito.doThrow(denied).when(resources).require("workflow", "42", "read");
        assertEquals(denied, assertThrows(ApiException.class, () -> management.history(7L, "request")));
        assertEquals(denied, assertThrows(ApiException.class, () -> management.publicationDetail(7L, "request", 91L)));
        verify(lifecycle, org.mockito.Mockito.never()).history(7L, "request");
        verify(lifecycle, org.mockito.Mockito.never()).publicationDetail(7L, "request", 91L);
    }

    @Test
    void exposesLifecycleOperationsButNoRunResumeOrCheckpointEndpoint() {
        assertFalse(Arrays.stream(WorkflowDraftController.class.getDeclaredMethods())
                .map(Method::getName)
                .anyMatch(name -> name.equals("run") || name.equals("resume") || name.toLowerCase().contains("checkpoint")));
        assertFalse(Arrays.stream(WorkflowDraftController.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(PostMapping.class))
                .filter(java.util.Objects::nonNull)
                .flatMap(mapping -> Arrays.stream(mapping.value()))
                .anyMatch(path -> path.equals("/run") || path.equals("/resume") || path.contains("checkpoint")));
    }

    private static WorkflowDraftController controller(
            WorkflowDraftLifecycleService lifecycle,
            WorkflowBuilderSnapshotService snapshots,
            WorkflowBuilderInstallationService installations) {
        return new WorkflowDraftController(lifecycle, snapshots, installations,
                mock(WorkflowManagementService.class));
    }
}
