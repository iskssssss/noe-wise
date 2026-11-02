package com.skillforge.institution.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.core.capability.contract.ResourceType;
import com.skillforge.core.channel.dto.openplatform.OpenPlatformChannelBindingRequest;
import com.skillforge.core.capability.dto.openplatform.OpenPlatformDeploymentRequest;
import com.skillforge.core.capability.dto.openplatform.OpenPlatformResourceContentRequest;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.channel.ChannelBindingView;
import com.skillforge.core.channel.OpenPlatformChannelBindingService;
import com.skillforge.core.capability.persistence.OpenPlatformDeploymentDO;
import com.skillforge.core.capability.persistence.OpenPlatformResourceRevisionDO;
import com.skillforge.core.capability.application.OpenPlatformResourceLifecycleService;
import com.skillforge.core.capability.application.ResourceRevisionConflictException;
import java.util.Map;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class OpenPlatformResourceLifecycleControllerTest {
    private final OpenPlatformChannelBindingService bindings = mock(OpenPlatformChannelBindingService.class);

    @AfterEach
    void clear() {
        ServicePrincipalContext.clear();
    }

    @Test
    void createsRevisionAndReturnsEtagAndRevisionHeaders() {
        OpenPlatformResourceLifecycleService service = mock(OpenPlatformResourceLifecycleService.class);
        OpenPlatformResourceLifecycleController controller = new OpenPlatformResourceLifecycleController(service, bindings);
        ServicePrincipalContext.set(identity(OpenPlatformResourceLifecycleController.WRITE_SCOPE));
        OpenPlatformResourceRevisionDO row = revision("draft", 1L, "etag-1");
        when(service.createDraft("tenant-1", ResourceType.AGENT, "agent-1", Map.of("name", "a")))
                .thenReturn(row);

        ResponseEntity<?> response = controller.createDraft("agent", "agent-1",
                new OpenPlatformResourceContentRequest(Map.of("name", "a")));

        assertEquals("\"etag-1\"", response.getHeaders().getETag());
        assertEquals("1", response.getHeaders().getFirst(OpenPlatformResourceLifecycleController.REVISION_HEADER));
    }

    @Test
    void requiresWriteScopeAndRejectsInvalidTypeOrContent() {
        OpenPlatformResourceLifecycleService service = mock(OpenPlatformResourceLifecycleService.class);
        OpenPlatformResourceLifecycleController controller = new OpenPlatformResourceLifecycleController(service, bindings);
        ServicePrincipalContext.set(identity(OpenPlatformResourceLifecycleController.READ_SCOPE));
        assertThrows(ApiException.class, () -> controller.createDraft("agent", "agent-1",
                new OpenPlatformResourceContentRequest(Map.of())));
        ServicePrincipalContext.set(identity(OpenPlatformResourceLifecycleController.WRITE_SCOPE));
        assertThrows(ApiException.class, () -> controller.createDraft("unknown", "agent-1",
                new OpenPlatformResourceContentRequest(Map.of())));
        assertThrows(ApiException.class, () -> controller.createDraft("agent", "agent-1", null));
    }

    @Test
    void deployAndBindReturnCasHeaders() {
        OpenPlatformResourceLifecycleService service = mock(OpenPlatformResourceLifecycleService.class);
        OpenPlatformResourceLifecycleController controller = new OpenPlatformResourceLifecycleController(service, bindings);
        ServicePrincipalContext.set(identity(OpenPlatformResourceLifecycleController.WRITE_SCOPE));
        OpenPlatformDeploymentDO deployment = new OpenPlatformDeploymentDO();
        deployment.setDeploymentId("deployment-1");
        deployment.setResourceType("agent");
        deployment.setResourceId("agent-1");
        deployment.setResourceRevision(2L);
        deployment.setEnvironment("prod");
        deployment.setStatus("active");
        deployment.setDeploymentRevision(1L);
        deployment.setEtag("deployment-etag");
        when(service.deploy("tenant-1", "deployment-1", ResourceType.AGENT, "agent-1", 2L,
                "prod", null, null)).thenReturn(deployment);
        ResponseEntity<?> deployed = controller.deploy("deployment-1", null, null,
                new OpenPlatformDeploymentRequest("agent", "agent-1", 2L, "prod"));
        assertEquals("1", deployed.getHeaders().getFirst(OpenPlatformResourceLifecycleController.DEPLOYMENT_REVISION_HEADER));

        ChannelBindingView binding = new ChannelBindingView(null, null, 10L, "deployment-1", "agent-1",
                2L, 1L, "active", "binding-etag", null, null);
        when(bindings.bindChannel("tenant-1", 10L, "deployment-1", null, null)).thenReturn(binding);
        ResponseEntity<?> bound = controller.bindChannel(10L, null, null,
                new OpenPlatformChannelBindingRequest("deployment-1"));
        assertEquals("1", bound.getHeaders().getFirst(OpenPlatformResourceLifecycleController.BINDING_REVISION_HEADER));
    }

    @Test
    void updatesValidatesPublishesAndReadsLatestRevision() {
        OpenPlatformResourceLifecycleService service = mock(OpenPlatformResourceLifecycleService.class);
        OpenPlatformResourceLifecycleController controller = new OpenPlatformResourceLifecycleController(service, bindings);
        ServicePrincipalContext.set(identity(OpenPlatformResourceLifecycleController.WRITE_SCOPE));
        OpenPlatformResourceRevisionDO row = revision("draft", 1L, "etag-1");
        when(service.updateDraft("tenant-1", ResourceType.AGENT, "agent-1", 1L, "etag-1", Map.of("name", "b")))
                .thenReturn(row);
        when(service.markReady("tenant-1", ResourceType.AGENT, "agent-1", 1L, "etag-1")).thenReturn(row);
        when(service.publish("tenant-1", ResourceType.AGENT, "agent-1", 1L, "etag-1")).thenReturn(row);

        controller.updateDraft("agent", "agent-1", 1L, "etag-1",
                new OpenPlatformResourceContentRequest(Map.of("name", "b")));
        controller.ready("agent", "agent-1", 1L, "etag-1");
        controller.publish("agent", "agent-1", 1L, "etag-1");

        ServicePrincipalContext.set(identity(OpenPlatformResourceLifecycleController.READ_SCOPE));
        when(service.latest("tenant-1", ResourceType.AGENT, "agent-1")).thenReturn(row);
        assertEquals("agent-1", controller.latest("agent", "agent-1").getBody().data().resourceId());
    }

    @Test
    void mapsCasConflictAndRejectsMalformedDeploymentOrBinding() {
        OpenPlatformResourceLifecycleService service = mock(OpenPlatformResourceLifecycleService.class);
        OpenPlatformResourceLifecycleController controller = new OpenPlatformResourceLifecycleController(service, bindings);
        ServicePrincipalContext.set(identity(OpenPlatformResourceLifecycleController.WRITE_SCOPE));
        when(service.updateDraft(any(), any(), any(), anyLong(), any(), any()))
                .thenThrow(new ResourceRevisionConflictException(2L, "etag-2"));
        assertThrows(ApiException.class, () -> controller.updateDraft("agent", "agent-1", 1L, "etag-1",
                new OpenPlatformResourceContentRequest(Map.of())));
        assertThrows(ApiException.class, () -> controller.deploy("deployment-1", null, null,
                new OpenPlatformDeploymentRequest("agent", "", 1L, "prod")));
        assertThrows(ApiException.class, () -> controller.bindChannel(10L, null, null,
                new OpenPlatformChannelBindingRequest("")));
    }

    @Test
    void listsCatalogWithReadScopeAndReadsSpecificRevisionWithoutExposingOtherRows() {
        OpenPlatformResourceLifecycleService service = mock(OpenPlatformResourceLifecycleService.class);
        OpenPlatformResourceLifecycleController controller = new OpenPlatformResourceLifecycleController(service, bindings);
        ServicePrincipalContext.set(identity(OpenPlatformResourceLifecycleController.READ_SCOPE));
        OpenPlatformResourceRevisionDO row = revision("published", 2L, "etag-2");
        when(service.listLatest("tenant-1", ResourceType.AGENT, "published", 20)).thenReturn(List.of(row));
        when(service.revision("tenant-1", ResourceType.AGENT, "agent-1", 2L)).thenReturn(row);

        var catalog = controller.catalog("agent", "published", 20);
        assertEquals(1, catalog.getBody().data().size());
        assertEquals("agent-1", catalog.getBody().data().getFirst().resourceId());
        assertEquals(2L, catalog.getBody().data().getFirst().revision());
        assertEquals("agent-1", controller.revision("agent", "agent-1", 2L).getBody().data().resourceId());
    }

    @Test
    void catalogRejectsInvalidReadParametersAndMissingRevision() {
        OpenPlatformResourceLifecycleService service = mock(OpenPlatformResourceLifecycleService.class);
        OpenPlatformResourceLifecycleController controller = new OpenPlatformResourceLifecycleController(service, bindings);
        ServicePrincipalContext.set(identity(OpenPlatformResourceLifecycleController.READ_SCOPE));
        assertThrows(ApiException.class, () -> controller.catalog("unsupported", null, 20));
        assertThrows(ApiException.class, () -> controller.catalog(null, null, 0));
        when(service.revision("tenant-1", ResourceType.AGENT, "missing", 1L)).thenReturn(null);
        assertThrows(ApiException.class, () -> controller.revision("agent", "missing", 1L));
    }

    private ServicePrincipalContext.Identity identity(String scope) {
        return new ServicePrincipalContext.Identity("principal-1", "tenant-1", "integration-1", 1L,
                Set.of(scope));
    }

    private OpenPlatformResourceRevisionDO revision(String status, long revision, String etag) {
        OpenPlatformResourceRevisionDO row = new OpenPlatformResourceRevisionDO();
        row.setResourceType("agent");
        row.setResourceId("agent-1");
        row.setRevision(revision);
        row.setStatus(status);
        row.setContent(Map.of("name", "a"));
        row.setContentHash("hash");
        row.setEtag(etag);
        return row;
    }
}
