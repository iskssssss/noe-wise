package com.skillforge.platform.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.agent.systemagent.SystemAgentService;
import com.skillforge.core.agent.systemagent.SystemAgentTypes.View;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.shared.exception.ApiException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SystemAgentCreationServiceTest {
    @Test
    void generatesKeyOnceAndRecoversOriginalRequest() {
        var access = mock(FoundationAccess.class);
        var agents = mock(SystemAgentService.class);
        var receipts = mock(SystemAgentCreateReceiptMapper.class);
        var context = mock(CapabilityCatalogDatabaseContext.class);
        var service = new SystemAgentCreationService(access, agents, receipts, context, new ObjectMapper());
        var requestId = UUID.randomUUID();
        var fingerprint = new AtomicReference<String>();
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(true, null, "8"));
        when(receipts.claim(eq(requestId.toString()), eq("8"), anyString())).thenAnswer(call -> {
            if (fingerprint.get() == null) { fingerprint.set(call.getArgument(2)); return 1; }
            return 0;
        });
        var view = mock(View.class);
        when(view.id()).thenReturn("123");
        when(agents.create("agent-" + requestId, "助手", "说明", "AGENT_RUN", null, null, "8")).thenReturn(view);
        when(receipts.complete(requestId.toString(), "123")).thenReturn(1);
        when(receipts.find(requestId.toString())).thenAnswer(call -> new SystemAgentCreateReceiptMapper.Row("8", fingerprint.get(), "123"));
        var command = new SystemAgentCreationService.Command(requestId,
                new SystemAgentCreationService.Payload(null, "助手", "说明", "AGENT_RUN", null, null));
        assertEquals("123", service.create(command).agentId());
        assertEquals("123", service.create(command).agentId());
        verify(agents, times(1)).create("agent-" + requestId, "助手", "说明", "AGENT_RUN", null, null, "8");
        var changed = new SystemAgentCreationService.Command(requestId,
                new SystemAgentCreationService.Payload(null, "其他", "说明", "AGENT_RUN", null, null));
        assertThrows(ApiException.class, () -> service.create(changed));
    }
}
