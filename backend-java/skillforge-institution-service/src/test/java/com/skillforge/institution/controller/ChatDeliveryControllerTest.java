package com.skillforge.institution.controller;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.chat.dto.response.ChatResult;
import com.skillforge.core.chat.persistence.SessionMapper;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.chat.persistence.SessionDO;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.institution.runtime.AgentRuntimeInboundGateway;
import com.skillforge.institution.service.SessionDeliveryStatusService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 投递查询先走既有 Run 归属核验，再读取三元关联的 Outbox 事实。 */
class ChatDeliveryControllerTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void requiresSessionAndRuntimeRunBeforeReadingDeliveryFacts() {
        SessionMapper mapper = mock(SessionMapper.class);
        AgentRuntimeInboundGateway runtime = mock(AgentRuntimeInboundGateway.class);
        SessionDeliveryStatusService deliveries = mock(SessionDeliveryStatusService.class);
        SessionDO session = new SessionDO();
        session.setSessionId("session-1");
        ChatResult run = new ChatResult("completed", "session-1", "run-1", null, null, null);
        var expected = new SessionDeliveryStatusService.DeliveryStatus("run-1", "completed",
                new SessionDeliveryStatusService.SseStatus("replay_available", 1),
                new SessionDeliveryStatusService.WebhookStatus("unknown", 0, 0, 0, 0), List.of());
        when(mapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(session);
        when(runtime.chatRunStatus(com.skillforge.institution.TestSessions.view(session), "run-1")).thenReturn(run);
        when(deliveries.status("7", "session-1", "run-1", "completed")).thenReturn(expected);
        TenantContext.setTenantId("7");
        ChatDeliveryController controller = new ChatDeliveryController(
                new SessionService(mapper, mock(OperationalSettingsService.class), mock(com.skillforge.core.chat.access.SessionAccessPolicy.class)), runtime, deliveries);

        var response = controller.delivery("session-1", "run-1");
        assertEquals("OK", response.code());
        assertSame(expected, response.data());

        verify(runtime).chatRunStatus(com.skillforge.institution.TestSessions.view(session), "run-1");
        verify(deliveries).status("7", "session-1", "run-1", "completed");
    }

    @Test
    void rejectedRunNeverReadsDeliveryFacts() {
        SessionMapper mapper = mock(SessionMapper.class);
        AgentRuntimeInboundGateway runtime = mock(AgentRuntimeInboundGateway.class);
        SessionDeliveryStatusService deliveries = mock(SessionDeliveryStatusService.class);
        SessionDO session = new SessionDO();
        session.setSessionId("session-1");
        when(mapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(session);
        when(runtime.chatRunStatus(com.skillforge.institution.TestSessions.view(session), "other-run"))
                .thenThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "Run 不属于当前会话"));
        TenantContext.setTenantId("7");
        ChatDeliveryController controller = new ChatDeliveryController(
                new SessionService(mapper, mock(OperationalSettingsService.class), mock(com.skillforge.core.chat.access.SessionAccessPolicy.class)), runtime, deliveries);

        assertThrows(ApiException.class, () -> controller.delivery("session-1", "other-run"));

        verifyNoInteractions(deliveries);
    }
}
