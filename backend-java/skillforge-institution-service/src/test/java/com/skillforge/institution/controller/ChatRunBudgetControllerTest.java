package com.skillforge.institution.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.institution.runtime.AgentRuntimeRunControls;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 公开额度仅取当前已授权Run的原账本；拒绝路径不读取预算事实。 */
class ChatRunBudgetControllerTest {
    private final SessionService sessions = mock(SessionService.class);
    private final AgentRuntimeRunControls controls = mock(AgentRuntimeRunControls.class);
    private final ExecutionBudgetPort budgets = mock(ExecutionBudgetPort.class);
    private final ChatRunBudgetController controller = new ChatRunBudgetController(sessions, controls, budgets);

    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test void mapsOriginalFrozenLimitsAndUnknownReservationsAsExactDecimalStrings() throws Exception {
        TenantContext.setTenantId("1"); var session = org.mockito.Mockito.mock(SessionView.class);
        when(sessions.require("1", "session")).thenReturn(session);
        var run = new RunRef(new TenantId("1"), "run");
        var root = new RunRef(run.tenantId(), "root");
        var rootLimits = new ExecutionBudgetPort.Resources(100, 20, 80, 30, 9_007_199_254_740_993L, 100, 9_007_199_254_741_093L);
        var runLimits = new ExecutionBudgetPort.Resources(10, 3, 8, 3, 1000, 100, 1100);
        var limits = new ExecutionBudgetPort.Limits(ExecutionBudgetPort.Scenario.TEAM, rootLimits, runLimits, runLimits,
                3600, 600, "budget-policy.v3:task_budget:7");
        var reserved = new ExecutionBudgetPort.Resources(0, 0, 0, 0, 67, 34, 101);
        var consumed = new ExecutionBudgetPort.Resources(2, 1, 3, 1, 12, 4, 16);
        var closing = new ExecutionBudgetPort.Closing(new ExecutionBudgetPort.Resources(1, 0, 0, 0, 40, 10, 50), 61);
        var snapshot = new ExecutionBudgetPort.Snapshot(new ExecutionBudgetPort.Registration(run, root, ExecutionBudgetPort.Role.MEMBER, new ExecutionBudgetPort.Policy.Enforced(limits)),
                consumed, reserved, consumed, reserved, closing, ExecutionBudgetPort.Phase.CLOSING, 3456, 1234, 0);
        when(budgets.snapshot(run)).thenReturn(snapshot);

        var response = controller.budget("session", "run");
        var body = new ObjectMapper().valueToTree(response.data());
        assertEquals("run", body.path("run_id").asText()); assertEquals("root", body.path("root_run_id").asText());
        assertEquals("TEAM", body.path("scenario").asText()); assertEquals("MEMBER", body.path("role").asText());
        assertEquals("CLOSING", body.path("phase").asText()); assertEquals(limits.sourceVersion(), body.path("source_version").asText());
        assertEquals("9007199254740993", body.path("root").path("limits").path("input_tokens").textValue());
        assertEquals("10", body.path("run").path("limits").path("business_model_calls").textValue());
        assertEquals("3", body.path("run").path("limits").path("auxiliary_model_calls").textValue());
        assertEquals("101", body.path("root").path("reserved").path("total_tokens").textValue());
        assertEquals(body.path("root").path("reserved"), body.path("run").path("reserved"));
        assertEquals("3456", body.path("root").path("confirmed_active_millis").textValue());
        assertEquals("1234", body.path("run").path("confirmed_active_millis").textValue());
        assertEquals("61", body.path("closing").path("active_seconds").textValue());
        for (String omitted : java.util.List.of("stage", "remaining", "tenant_id", "attempts")) assertFalse(body.has(omitted));
        verify(controls).chatRunStatus(session, "run"); verify(budgets).snapshot(run); verifyNoMoreInteractions(budgets);
    }

    @Test void deniedSessionOrRunNeverReadsBudgetAndMissingTenantNeverReadsSession() {
        assertThrows(RuntimeException.class, () -> controller.budget("session", "run"));
        verifyNoInteractions(sessions, controls, budgets);
        TenantContext.setTenantId("1");
        when(sessions.require("1", "session")).thenThrow(new SecurityException("session_denied"));
        assertThrows(SecurityException.class, () -> controller.budget("session", "run")); verifyNoInteractions(controls, budgets);
        var session = org.mockito.Mockito.mock(SessionView.class); doReturn(session).when(sessions).require("1", "session");
        doThrow(new SecurityException("run_denied")).when(controls).chatRunStatus(session, "run");
        assertThrows(ApiException.class, () -> controller.budget("session", "run")); verifyNoInteractions(budgets);
    }

    @Test void budgetRouteRequiresExistingSessionReadPermission() throws Exception {
        var method = ChatRunBudgetController.class.getMethod("budget", String.class, String.class);
        assertEquals("foundation.sessions.read", method.getAnnotation(RequirePermission.class).value());
    }

    @Test void platformBudgetProjectionRejectsObserveOnlyInsteadOfInventingFiniteCaps() {
        var run = new RunRef(new TenantId("1"), "observe-only");
        var registration = new ExecutionBudgetPort.Registration(run, run, ExecutionBudgetPort.Role.PRIMARY,
                new ExecutionBudgetPort.Policy.ObserveOnly(ExecutionBudgetPort.Scenario.STANDARD, "original-desktop-policy"));
        var snapshot = new ExecutionBudgetPort.Snapshot(registration, ExecutionBudgetPort.Resources.ZERO, ExecutionBudgetPort.Resources.ZERO,
                ExecutionBudgetPort.Resources.ZERO, ExecutionBudgetPort.Resources.ZERO,
                ExecutionBudgetPort.Closing.ZERO, ExecutionBudgetPort.Phase.BUSINESS, 0, 0, 0);
        assertEquals("budget_policy_not_supported", assertThrows(IllegalArgumentException.class,
                () -> ChatRunBudgetController.BudgetResponse.from(snapshot)).getMessage());
    }
}
