package com.skillforge.institution.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.skillforge.core.capability.application.ConnectorInstanceService;
import com.skillforge.core.shared.exception.ApiException;
import org.junit.jupiter.api.Test;

class ConnectorDeleteReceiptServiceTest {
    private final ConnectorDeleteReceiptMapper receipts = mock(ConnectorDeleteReceiptMapper.class);
    private final ConnectorInstanceService instances = mock(ConnectorInstanceService.class);
    private final ConnectorDeleteReceiptService service = new ConnectorDeleteReceiptService(receipts, instances);

    @Test void firstClaimDeletesAndReplayOnlyReturnsReceipt() {
        when(receipts.claim(1, "request", "actor", "instance")).thenReturn(1, 0);
        when(receipts.find(1, "request")).thenReturn(new ConnectorDeleteReceiptMapper.Row("actor", "instance"));
        service.delete(1, "request", "actor", "instance");
        service.delete(1, "request", "actor", "instance");
        verify(instances, times(1)).deleteUnused(1L, "instance");
    }

    @Test void conflictingActorOrInstanceDoesNotDelete() {
        when(receipts.find(1, "request")).thenReturn(new ConnectorDeleteReceiptMapper.Row("owner", "instance"));
        assertThrows(ApiException.class, () -> service.delete(1, "request", "other", "instance"));
        assertThrows(ApiException.class, () -> service.delete(1, "request", "owner", "other"));
        verifyNoInteractions(instances);
        assertNull(service.find(1, "request", "other"));
        assertNotNull(service.find(1, "request", "owner"));
    }
}
