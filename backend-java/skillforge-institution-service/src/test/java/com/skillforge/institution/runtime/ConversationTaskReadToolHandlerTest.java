package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class ConversationTaskReadToolHandlerTest {
    private final ConversationTaskPort tasks = mock(ConversationTaskPort.class);
    private final ConversationTaskReadToolHandler handler = new ConversationTaskReadToolHandler(tasks, new ObjectMapper());

    @Test void readsOwnedTaskWithoutExposingSourcesOrMaintenance() {
        when(tasks.find(owner(), agent(), "task")).thenReturn(Optional.of(task()));

        var result = handler.execute(Map.of("task_id", "task"), context());

        var view = (Map<?, ?>) result.get("task");
        assertEquals(Set.of("task_id", "revision", "status", "title", "summary", "state", "updated_at"),
                view.keySet());
        var state = (Map<?, ?>) view.get("state");
        var fact = (Map<?, ?>) ((Map<?, ?>) state.get("facts")).get("门店");
        assertEquals(Map.of("value", "一店", "source_event_id", "source", "confirmed", true), fact);
        verify(tasks).find(owner(), agent(), "task");
    }

    @Test void missingOrForeignTaskUsesSameNotFoundResultAndArgumentsCannotOverrideOwner() {
        when(tasks.find(owner(), agent(), "missing")).thenReturn(Optional.empty());
        assertEquals(Map.of("success", false, "error", "task_not_found"),
                handler.execute(Map.of("task_id", "missing"), context()));
        assertEquals(Map.of("success", false, "error", "invalid_arguments"),
                handler.execute(Map.of("task_id", "task", "session_id", "other"), context()));
        verify(tasks).find(owner(), agent(), "missing");
        verifyNoMoreInteractions(tasks);
    }

    private ConversationTask task() {
        return new ConversationTask("task", owner().sessionRef().tenantId(), owner().requireUser(), agent(),
                3, ConversationTask.Status.OPEN, "目标", "摘要",
                new TypedPayload(ConversationTask.STATE_SCHEMA, "{\"facts\":{\"门店\":{\"value\":\"一店\","
                        + "\"sourceEventId\":\"source\",\"sourceSessionId\":\"secret\",\"confirmed\":true}},"
                        + "\"pending\":[]}"),
                List.of(new ConversationTask.Source(owner(), new EventId("source"))), List.of(), Instant.EPOCH);
    }

    private PlatformToolExecutionContext context() {
        var identity = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(
                owner().sessionRef().tenantId(), owner().requireUser()), owner(), Set.of(), 1,
                new ResultRef("verification"));
        return new PlatformToolExecutionContext(new PortCallContext(run(), identity,
                new InvocationControl(Instant.now().plusSeconds(30), () -> false)), "invocation", "attempt");
    }

    private RunIdentity run() {
        var input = new InputId("input");
        return new RunIdentity(owner(), new AgentId((owner()).target().id()), input, new RunRef(owner().sessionRef().tenantId(), "run"), new TraceEnvelope("trace", "span", input, Optional.empty()), new ResultRef("result"), new RunRequester.EndUser((owner()).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner()).sessionRef().tenantId(), "input:" + (input).value()));
    }

    private SessionOwner owner() {
        var tenant = new TenantId("tenant");
        return SessionOwner.user(new SessionRef(tenant, "session"), new AgentId("agent"), new EndUserId(1));
    }

    private AgentId agent() { return owner().target().requireAgent(); }
}
