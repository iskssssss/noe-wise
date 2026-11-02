package com.skillforge.institution.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.capability.*;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.workflow.dto.response.BuilderWorkflowInstanceResponse;
import com.skillforge.institution.service.WorkflowManagementService;
import com.skillforge.institution.service.WorkflowMountOptionService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkflowMountOptionControllerTest {
    private final FoundationAccess access = mock(FoundationAccess.class);
    private final WorkflowManagementService workflows = mock(WorkflowManagementService.class);
    private final CapabilityCatalogService catalog = mock(CapabilityCatalogService.class);
    private final WorkflowMountOptionService service = new WorkflowMountOptionService(access, workflows, catalog);

    @BeforeEach void identity() { TenantContext.setTenantId("7"); }
    @AfterEach void clear() { TenantContext.clear(); }

    @Test void groupsPublishedInstancesByStableDefinitionAndFiltersUnavailable() throws Exception {
        when(workflows.instances(7)).thenReturn(List.of(instance(1, 20L, 2L, "published"),
                instance(2, 21L, 3L, "published"), instance(3, 22L, 4L, "published"),
                instance(4, 23L, null, "published"), instance(5, 24L, 5L, "draft")));
        when(catalog.publishedVersions(CapabilityCatalogScope.tenant(7L), List.of(20L, 21L, 22L)))
                .thenReturn(List.of(version(20, 10, CapabilityType.WORKFLOW),
                        version(21, 10, CapabilityType.WORKFLOW), version(22, 11, CapabilityType.SKILL)));
        var result = service.list();
        assertEquals(List.of(1L, 2L), result.stream().map(WorkflowMountOptionService.Option::id).toList());
        assertEquals(List.of(10L, 10L), result.stream().map(WorkflowMountOptionService.Option::definitionId).toList());
        verify(access).require("foundation.capabilities.read");
        assertEquals("foundation.workflows.read", WorkflowMountOptionController.class.getMethod("list").getAnnotation(RequirePermission.class).value());
    }

    @Test void noInstalledInstanceSkipsCatalog() {
        when(workflows.instances(7)).thenReturn(List.of(instance(1, 20L, null, "published")));
        assertTrue(service.list().isEmpty());
        verifyNoInteractions(catalog);
    }

    @Test void deniedCapabilityReadDoesNotReadInstances() {
        doThrow(new IllegalStateException("denied")).when(access).require("foundation.capabilities.read");
        assertThrows(IllegalStateException.class, service::list);
        verifyNoInteractions(workflows, catalog);
    }

    private static BuilderWorkflowInstanceResponse instance(long id, Long version, Long revision, String status) {
        return new BuilderWorkflowInstanceResponse(id, "instance" + id, "workflow", 1L, revision, version, status, Map.of(), null, null);
    }
    private static CapabilityVersionView version(long id, long definition, CapabilityType type) {
        return new CapabilityVersionView(id, definition, 7L, type, "1", "name", "", "", Map.of(), "", "PUBLISHED", null, null);
    }
}
