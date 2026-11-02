package com.skillforge.institution.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.core.capability.contract.ResourceType;
import com.skillforge.core.capability.dto.openplatform.OpenPlatformResourceMappingRequest;
import com.skillforge.core.capability.dto.openplatform.OpenPlatformResourceMappingResponse;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.capability.persistence.ResourceMappingDO;
import com.skillforge.core.capability.application.ResourceMappingService;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class OpenPlatformResourceMappingControllerTest {

    @AfterEach
    void clear() {
        ServicePrincipalContext.clear();
    }

    @Test
    void writesWithCasHeadersAndReadsWithSeparateScope() {
        ResourceMappingService service = mock(ResourceMappingService.class);
        OpenPlatformResourceMappingController controller = new OpenPlatformResourceMappingController(service);
        ServicePrincipalContext.set(new ServicePrincipalContext.Identity(
                "principal-1", "tenant-1", "integration-1", 1L,
                Set.of(OpenPlatformResourceMappingController.WRITE_SCOPE)));
        ResourceMappingDO row = row();
        when(service.upsert("tenant-1", "integration-1", ResourceType.WORKFLOW,
                "external-1", "workflow-1", "sha256:a", 1L, "\"etag-1\""))
                .thenReturn(row);

        ResponseEntity<OpenPlatformResourceMappingResponse> response = controller.upsert(
                "workflow", "external-1", "\"etag-1\"", 1L,
                new OpenPlatformResourceMappingRequest("workflow-1", "sha256:a"));

        assertEquals("\"etag-1\"", response.getHeaders().getETag());
        assertEquals("2", response.getHeaders().getFirst(OpenPlatformResourceMappingController.REVISION_HEADER));
        ServicePrincipalContext.set(new ServicePrincipalContext.Identity(
                "principal-1", "tenant-1", "integration-1", 1L,
                Set.of(OpenPlatformResourceMappingController.READ_SCOPE)));
        when(service.find("tenant-1", "integration-1", ResourceType.WORKFLOW, "external-1")).thenReturn(row);
        assertEquals("workflow-1", controller.get("workflow", "external-1").getBody().data().resourceId());
    }

    @Test
    void rejectsMissingScopeInvalidTypeAndMissingMapping() {
        ResourceMappingService service = mock(ResourceMappingService.class);
        OpenPlatformResourceMappingController controller = new OpenPlatformResourceMappingController(service);
        ServicePrincipalContext.set(new ServicePrincipalContext.Identity(
                "principal-1", "tenant-1", "integration-1", 1L, Set.of()));
        assertThrows(ApiException.class, () -> controller.get("workflow", "external-1"));
        ServicePrincipalContext.set(new ServicePrincipalContext.Identity(
                "principal-1", "tenant-1", "integration-1", 1L,
                Set.of(OpenPlatformResourceMappingController.WRITE_SCOPE)));
        assertThrows(ApiException.class, () -> controller.upsert("unknown", "external-1", null, null,
                new OpenPlatformResourceMappingRequest("workflow-1", "sha256:a")));
        assertThrows(ApiException.class, () -> controller.upsert("workflow", "external-1", null, null,
                new OpenPlatformResourceMappingRequest("", "sha256:a")));
        ServicePrincipalContext.set(new ServicePrincipalContext.Identity(
                "principal-1", "tenant-1", "integration-1", 1L,
                Set.of(OpenPlatformResourceMappingController.READ_SCOPE)));
        when(service.find("tenant-1", "integration-1", ResourceType.WORKFLOW, "missing")).thenReturn(null);
        assertThrows(ApiException.class, () -> controller.get("workflow", "missing"));
    }

    private static ResourceMappingDO row() {
        ResourceMappingDO row = new ResourceMappingDO();
        row.setIntegrationId("integration-1");
        row.setResourceType("workflow");
        row.setExternalRef("external-1");
        row.setResourceId("workflow-1");
        row.setRevision(2L);
        row.setEtag("etag-1");
        row.setContentHash("sha256:a");
        return row;
    }
}
