package com.skillforge.institution.tool;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.*;
import com.skillforge.core.capability.tool.ToolDeployment;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.dto.capability.CreateCapabilityDeploymentRequest;
import com.skillforge.institution.service.TenantToolDeploymentProviderValidator;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ToolDeploymentCreationServiceTest {
    @Test
    void generatedKeyIsStableForOriginalRequestAndReplay() {
        var access = mock(FoundationAccess.class);
        var context = mock(CapabilityCatalogDatabaseContext.class);
        var catalog = mock(CapabilityCatalogService.class);
        var validator = mock(TenantToolDeploymentProviderValidator.class);
        var receipts = mock(ToolDeploymentCreateReceiptMapper.class);
        var service = new ToolDeploymentCreationService(access, context, catalog, validator, receipts, new ObjectMapper());
        var id = UUID.randomUUID();
        String key = "tool-" + id;
        var fingerprint = new AtomicReference<String>();
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false, "7", "8"));
        when(receipts.claim(eq(7L), eq(id.toString()), eq("8"), anyString())).thenAnswer(call -> {
            if (fingerprint.get() == null) { fingerprint.set(call.getArgument(3)); return 1; }
            return 0;
        });
        when(catalog.createDeployment(any(), eq(key), eq(21L), eq(ToolDeployment.ProviderType.PLATFORM),
                eq("get_current_time"), eq(ToolDeployment.Environment.DEVELOPMENT), eq("ACTIVE")))
                .thenReturn(new CapabilityDeploymentView(12L, 7L, key, 21L, null, null, ToolDeployment.ProviderType.PLATFORM,
                        "get_current_time", ToolDeployment.Environment.DEVELOPMENT, "ACTIVE", 1L, null, null));
        when(receipts.complete(7L, id.toString(), 12L, key)).thenReturn(1);
        when(receipts.find(7L, id.toString())).thenAnswer(call -> new ToolDeploymentCreateReceiptMapper.Row("8", fingerprint.get(), "SUCCEEDED", 12L, key));
        var input = new CreateCapabilityDeploymentRequest(null, 21L, "PLATFORM", "get_current_time", "DEVELOPMENT", "ACTIVE");
        var command = new ToolDeploymentCreationService.Command(id, input);
        assertEquals(key, service.create(7L, command).deploymentKey());
        assertEquals(key, service.create(7L, command).deploymentKey());
        verify(catalog, times(1)).createDeployment(any(), eq(key), eq(21L), any(), anyString(), any(), anyString());
        var changed = new CreateCapabilityDeploymentRequest(null, 21L, "PLATFORM", "other", "DEVELOPMENT", "ACTIVE");
        assertThrows(ApiException.class, () -> service.create(7L, new ToolDeploymentCreationService.Command(id, changed)));
    }
}
