package com.skillforge.institution.controller;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.skillforge.common.context.ChannelContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.chat.dto.response.ChatResult;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.chat.persistence.SessionMapper;
import com.skillforge.core.chat.persistence.SessionDO;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.institution.runtime.AgentRuntimeInboundGateway;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 渠道开放的运行控制入口仍须经会话的租户与渠道归属校验。 */
class ChatRunChannelAccessTest {

    private final SessionMapper mapper = mock(SessionMapper.class);
    private final AgentRuntimeInboundGateway runtime = mock(AgentRuntimeInboundGateway.class);
    private final com.skillforge.institution.runtime.AgentRuntimeRunControls controls = mock(com.skillforge.institution.runtime.AgentRuntimeRunControls.class);
    private final ChatRunController controller = new ChatRunController(
            new SessionService(mapper, mock(OperationalSettingsService.class), mock(com.skillforge.core.chat.access.SessionAccessPolicy.class)), runtime, controls);

    @BeforeEach
    void resetRequestContexts() {
        clearRequestContexts();
    }

    @AfterEach
    void clearRequestContexts() {
        ChannelContext.clear();
        TenantContext.clear();
    }

    @Test
    void sameChannelSessionCanBeCancelled() {
        SessionDO session = session(7L, 8L);
        ChatResult expected = result();
        authenticate("7", "8");
        when(mapper.selectOne(any())).thenReturn(session);
        when(controls.cancelChat(com.skillforge.institution.TestSessions.view(session), "run-1")).thenReturn(expected);

        var response = controller.cancel(new ChatRunController.CancelRequest("session-1", "run-1"));
        assertEquals("OK", response.code());
        assertSame(expected, response.data());

        verify(controls).cancelChat(com.skillforge.institution.TestSessions.view(session), "run-1");
        verify(controls, never()).chatRunStatus(any(com.skillforge.core.chat.session.SessionView.class), anyString());
    }

    @Test
    void sameChannelSessionCanReadStatusWithoutCancelling() {
        SessionDO session = session(7L, 8L);
        ChatResult expected = result();
        authenticate("7", "8");
        when(mapper.selectOne(any())).thenReturn(session);
        when(controls.chatRunStatus(com.skillforge.institution.TestSessions.view(session), "run-1")).thenReturn(expected);

        var response = controller.status("session-1", "run-1");
        assertEquals("OK", response.code());
        assertSame(expected, response.data());

        verify(controls).chatRunStatus(com.skillforge.institution.TestSessions.view(session), "run-1");
        verify(controls, never()).cancelChat(any(com.skillforge.core.chat.session.SessionView.class), anyString());
    }

    @Test
    void otherChannelAndUnassignedSessionsAreRejectedBeforeRuntime() {
        authenticate("7", "8");

        when(mapper.selectOne(any())).thenReturn(session(7L, 9L));
        assertRejectedForBothOperations();

        when(mapper.selectOne(any())).thenReturn(session(7L, null));
        assertRejectedForBothOperations();

        verifyNoInteractions(runtime, controls);
    }

    @Test
    void missingTenantIsRejectedBeforeSessionLookupOrRuntime() {
        ChannelContext.setChannelId("8");

        assertRejectedForBothOperations();

        verifyNoInteractions(mapper, runtime, controls);
    }

    @Test
    void sessionOutsideCurrentTenantIsRejectedBeforeRuntime() {
        authenticate("7", "8");
        when(mapper.selectOne(any())).thenReturn(null);

        assertRejectedForBothOperations();

        verifyNoInteractions(runtime, controls);
    }

    private void assertRejectedForBothOperations() {
        assertThrows(ApiException.class, () -> controller.steer(new ChatRunController.SteeringRequest("session-1", "run-1", "correction-1", "只保留这次记录")));
        assertThrows(ApiException.class, () -> controller.steering("session-1", "run-1"));
        assertThrows(ApiException.class,
                () -> controller.cancel(new ChatRunController.CancelRequest("session-1", "run-1")));
        assertThrows(ApiException.class, () -> controller.status("session-1", "run-1"));
    }

    private static void authenticate(String tenantId, String channelId) {
        TenantContext.setTenantId(tenantId);
        ChannelContext.setChannelId(channelId);
    }

    private static SessionDO session(Long tenantId, Long channelId) {
        SessionDO session = new SessionDO();
        session.setSessionId("session-1");
        session.setTenantId(tenantId);
        session.setChannelId(channelId);
        return session;
    }

    private static ChatResult result() {
        return new ChatResult("running", "session-1", "run-1", null, null, null);
    }
}
