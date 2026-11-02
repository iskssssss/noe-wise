package com.skillforge.institution.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.core.foundation.contract.ControlOperation;
import com.skillforge.core.foundation.contract.ControlOperationStatus;
import com.skillforge.core.foundation.dto.openplatform.OpenPlatformControlOperationItemRequest;
import com.skillforge.core.foundation.dto.openplatform.OpenPlatformControlOperationRequest;
import com.skillforge.core.foundation.dto.openplatform.OpenPlatformControlOperationResponse;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.application.ControlOperationService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OpenPlatformControlOperationControllerTest {

    @AfterEach
    void clearPrincipal() {
        ServicePrincipalContext.clear();
    }

    @Test
    void acceptsWithWriteScopeAndReadsOnlyWithReadScope() {
        ControlOperationService service = mock(ControlOperationService.class);
        OpenPlatformControlOperationController controller = new OpenPlatformControlOperationController(service);
        ControlOperation operation = new ControlOperation("op-1", "op-1", "tenant-1", "integration-1",
                "publish", ControlOperationStatus.ACCEPTED, "idem-1", "sha256:req", List.of(), Instant.now(), null);
        when(service.accept(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(
                new ControlOperationService.Acceptance(operation, true));
        ServicePrincipalContext.set(new ServicePrincipalContext.Identity(
                "principal-1", "tenant-1", "integration-1", 1L,
                Set.of(OpenPlatformControlOperationController.WRITE_SCOPE)));

        OpenPlatformControlOperationResponse response = controller.accept("idem-1",
                new OpenPlatformControlOperationRequest("op-1", null, "publish",
                        List.of(new OpenPlatformControlOperationItemRequest("item-1", "workflow-1")))).data();

        assertEquals("accepted", response.status());
        assertEquals(true, response.created());
        ServicePrincipalContext.set(new ServicePrincipalContext.Identity(
                "principal-1", "tenant-1", "integration-1", 1L,
                Set.of(OpenPlatformControlOperationController.READ_SCOPE)));
        when(service.find("tenant-1", "integration-1", "op-1")).thenReturn(operation);
        assertEquals("op-1", controller.get("op-1").data().operationId());
    }

    @Test
    void rejectsMissingScopeMalformedRequestAndUnknownOperation() {
        ControlOperationService service = mock(ControlOperationService.class);
        OpenPlatformControlOperationController controller = new OpenPlatformControlOperationController(service);
        ServicePrincipalContext.set(new ServicePrincipalContext.Identity(
                "principal-1", "tenant-1", "integration-1", 1L, Set.of()));
        assertThrows(ApiException.class, () -> controller.accept("idem-1",
                new OpenPlatformControlOperationRequest("op-1", null, "publish", List.of())));

        ServicePrincipalContext.set(new ServicePrincipalContext.Identity(
                "principal-1", "tenant-1", "integration-1", 1L,
                Set.of(OpenPlatformControlOperationController.WRITE_SCOPE)));
        assertThrows(ApiException.class, () -> controller.accept(" ",
                new OpenPlatformControlOperationRequest("op-1", null, "publish", List.of())));
        assertThrows(ApiException.class, () -> controller.accept("idem-1",
                new OpenPlatformControlOperationRequest("op-1", null, "publish",
                        List.of(new OpenPlatformControlOperationItemRequest("", "workflow-1")))));
        assertThrows(ApiException.class, () -> controller.accept("idem-1",
                new OpenPlatformControlOperationRequest("op-1", null, "publish",
                        List.of(new OpenPlatformControlOperationItemRequest("item-1", "workflow-1"),
                                new OpenPlatformControlOperationItemRequest("item-1", "workflow-2")))));

        ServicePrincipalContext.set(new ServicePrincipalContext.Identity(
                "principal-1", "tenant-1", "integration-1", 1L,
                Set.of(OpenPlatformControlOperationController.READ_SCOPE)));
        when(service.find("tenant-1", "integration-1", "missing")).thenReturn(null);
        assertThrows(ApiException.class, () -> controller.get("missing"));
    }
}
