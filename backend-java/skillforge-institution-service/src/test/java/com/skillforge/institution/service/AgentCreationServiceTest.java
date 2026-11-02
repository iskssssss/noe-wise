package com.skillforge.institution.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.foundation.access.FoundationAccess;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AgentCreationServiceTest {
    @AfterEach void clear() { TenantContext.clear(); }

    @Test void readRequiresOriginalActorBeforeAccessingAgent() {
        var agents = mock(AgentManagementService.class);
        var receipts = mock(AgentCreateReceiptMapper.class);
        var access = mock(FoundationAccess.class);
        var context = mock(CapabilityCatalogDatabaseContext.class);
        var service = new AgentCreationService(agents, receipts, context, access, new ObjectMapper());
        var request = UUID.randomUUID();
        TenantContext.setTenantId("7");
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false, "7", "70"));
        when(receipts.find(request.toString())).thenReturn(new AgentCreateReceiptMapper.Row("71", "hash", "123"));
        assertNull(service.result(request));
        verify(agents, never()).getAgentItem(anyString());
        when(receipts.find(request.toString())).thenReturn(new AgentCreateReceiptMapper.Row("70", "hash", "123"));
        assertEquals("123", service.result(request).agentId());
        verify(agents).getAgentItem("123");
        verify(access, times(2)).require("foundation.agents.read");
    }
}
