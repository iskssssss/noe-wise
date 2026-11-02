package com.skillforge.institution.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skillforge.institution.runtime.AgentRunBindings;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.SessionTarget;
import com.skillforge.runtime.api.TenantId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 委派产物投影只披露当前授权的子 Run，并按精确父 Run 回溯产品会话。 */
class StudioArtifactProjectionTest {
    private final StudioArtifactProjectionStore store = mock(StudioArtifactProjectionStore.class);
    private final AgentRunBindings bindings = mock(AgentRunBindings.class);
    private final StudioArtifactProjection projection = new StudioArtifactProjection(store, bindings);

    @Test
    void userListIncludesOnlyCurrentlyAuthorizedChildSessions() {
        var visible = row("delegation:one", "child-one", "parent-one", "product", "AGENT", "7", "8", 9);
        var revoked = row("delegation:two", "child-two", "parent-two", "product", "AGENT", "7", "10", 9);
        when(store.children("7", "product")).thenReturn(List.of(visible, revoked));
        AgentRunBindings.Binding readable = arrangeBinding(visible);
        arrangeBinding(revoked);
        doThrow(new SecurityException("revoked")).when(bindings).authorizeRead(
                org.mockito.ArgumentMatchers.any(AgentRunBindings.Binding.class),
                org.mockito.ArgumentMatchers.eq("10"));

        assertEquals(List.of("product", "delegation:one"), projection.visibleSessions("7", "product"));

        verify(bindings).authorizeRead(readable, "8");
        verify(bindings).authorizeRead(org.mockito.ArgumentMatchers.any(AgentRunBindings.Binding.class),
                org.mockito.ArgumentMatchers.eq("10"));
    }

    @Test
    void ticketRouteRequiresExactChildRunAndRechecksParentAndChildBindings() {
        var row = row("delegation:one", "child-one", "parent-one", "product", "AGENT", "7", "8", 9);
        when(store.find("7", "delegation:one", "child-one")).thenReturn(Optional.of(row));
        AgentRunBindings.Binding parent = arrangeBinding(row);

        var route = projection.route("7", "delegation:one", "child-one").orElseThrow();

        assertEquals("product", route.productSession());
        assertEquals("AGENT", route.originTargetKind());
        assertEquals("7", route.originTargetId());
        assertEquals("8", route.sourceAgentId());
        assertEquals("parent-one", route.parentRunId());
        verify(bindings).authorizeRead(parent, "8");
        assertTrue(projection.route("7", "ordinary-session", "child-one").isEmpty());
        verify(store, never()).find("7", "ordinary-session", "child-one");
    }

    @Test
    void ticketRouteRejectsAChildBoundToAnotherParentRun() {
        var row = row("delegation:one", "child-one", "parent-one", "product", "AGENT", "7", "8", 9);
        when(store.find("7", "delegation:one", "child-one")).thenReturn(Optional.of(row));
        arrangeBinding(row, "parent-other");

        assertThrows(RuntimeException.class,
                () -> projection.route("7", "delegation:one", "child-one"));
    }

    private AgentRunBindings.Binding arrangeBinding(StudioArtifactProjectionMapper.Row row) {
        return arrangeBinding(row, row.parentRunId);
    }

    private AgentRunBindings.Binding arrangeBinding(StudioArtifactProjectionMapper.Row row, String childParentRun) {
        TenantId tenant = new TenantId("7");
        RunRef parentRun = new RunRef(tenant, row.parentRunId);
        SessionOwner parentOwner = SessionOwner.user(new SessionRef(tenant, row.productSessionId),
                SessionTarget.agent(new AgentId(row.originTargetId)), new EndUserId(row.endUserId));
        AgentRunBindings.Scope scope = mock(AgentRunBindings.Scope.class);
        when(scope.primaryRun()).thenReturn(parentRun);
        AgentRunBindings.Binding parent = mock(AgentRunBindings.Binding.class);
        when(parent.role()).thenReturn(AgentRunBindings.Role.PRIMARY);
        when(parent.owner()).thenReturn(parentOwner);
        when(parent.scope()).thenReturn(scope);

        AgentRunBindings.Binding child = mock(AgentRunBindings.Binding.class);
        when(child.role()).thenReturn(AgentRunBindings.Role.DELEGATE);
        when(child.owner()).thenReturn(SessionOwner.user(new SessionRef(tenant, row.childSessionId),
                SessionTarget.agent(new AgentId(row.sourceAgentId)), new EndUserId(row.endUserId)));
        when(child.actor()).thenReturn(new AgentId(row.sourceAgentId));
        when(child.parentRunRef()).thenReturn(Optional.of(new RunRef(tenant, childParentRun)));
        when(child.scope()).thenReturn(scope);

        when(bindings.find(parentRun)).thenReturn(Optional.of(parent));
        when(bindings.find(new RunRef(tenant, row.childRunId))).thenReturn(Optional.of(child));
        return parent;
    }

    private static StudioArtifactProjectionMapper.Row row(String childSession, String childRun,
            String parentRun, String productSession, String targetKind, String targetId,
            String agent, long endUser) {
        var row = new StudioArtifactProjectionMapper.Row();
        row.childSessionId = childSession;
        row.childRunId = childRun;
        row.parentRunId = parentRun;
        row.productSessionId = productSession;
        row.originTargetKind = targetKind;
        row.originTargetId = targetId;
        row.sourceAgentId = agent;
        row.endUserId = endUser;
        return row;
    }
}
