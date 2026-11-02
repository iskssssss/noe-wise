package com.skillforge.institution.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.shared.exception.ApiException;
import org.junit.jupiter.api.Test;

class HandoffActionServiceTest {
    @Test void receiptDoesNotExposeAnotherMembersCommand() {
        var handoffs = mock(HandoffApplicationService.class);
        var mapper = mock(HandoffActionMapper.class);
        var access = mock(FoundationAccess.class);
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false, "7", "43"));
        String command = "12345678-1234-4234-8234-123456789abc";
        when(mapper.find(7, command)).thenReturn(new HandoffActionMapper.Row("ticket", 42, "accept", 1, "a".repeat(64)));
        var service = new HandoffActionService(handoffs, mapper, access);
        assertNull(service.receipt(7, 43, "ticket", command));
        assertThrows(ApiException.class, () -> service.receipt(7, 42, "ticket", command));
        verify(handoffs).require(7, "ticket");
        verify(mapper).find(7, command);
    }
}
