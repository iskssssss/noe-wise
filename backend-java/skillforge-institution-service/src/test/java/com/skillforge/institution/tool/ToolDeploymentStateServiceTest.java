package com.skillforge.institution.tool;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.capability.CapabilityCatalogService;
import com.skillforge.core.foundation.access.FoundationAccess;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ToolDeploymentStateServiceTest {
    @Test void technicalFailureCannotProduceSuccessOrRejectedReceipt() {
        var access = mock(FoundationAccess.class);
        var context = mock(CapabilityCatalogDatabaseContext.class);
        var catalog = mock(CapabilityCatalogService.class);
        var receipts = mock(ToolDeploymentStateReceiptMapper.class);
        var service = new ToolDeploymentStateService(access, context, catalog, receipts);
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false, "7", "8"));
        when(receipts.claim(eq(7L), anyString(), eq("8"), anyString(), eq("key"), eq(1L), eq(false))).thenReturn(1);
        var failure = new IllegalStateException("database unavailable");
        when(catalog.setDeploymentActive(any(), eq("key"), eq(1L), eq(false))).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.change(7, new ToolDeploymentStateService.Command(UUID.randomUUID(), "key", 1, false))));
        verify(access).require("foundation.capabilities.cancel");
        verify(receipts, never()).complete(anyLong(), anyString(), anyString(), anyLong());
        verify(receipts, never()).reject(anyLong(), anyString());
    }
}
