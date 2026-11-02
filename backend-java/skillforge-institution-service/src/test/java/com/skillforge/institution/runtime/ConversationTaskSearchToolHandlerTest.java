package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class ConversationTaskSearchToolHandlerTest {
    private final ConversationTaskPort tasks = mock(ConversationTaskPort.class);
    private final ConversationTaskSearchToolHandler handler = new ConversationTaskSearchToolHandler(tasks);

    @Test void searchesCompleteSubjectScopeAndReturnsOnlySafeSummaries() {
        when(tasks.search(owner(), agent(), "门店", true, 30, 10)).thenReturn(List.of(task()));

        var result = handler.execute(Map.of("query", "门店", "offset", 30, "limit", 10), context());

        assertEquals(true, result.get("success"));
        var item = (Map<?, ?>) ((List<?>) result.get("tasks")).getFirst();
        assertEquals(Set.of("task_id", "revision", "status", "title", "summary", "updated_at"), item.keySet());
        assertEquals("task", item.get("task_id"));
        assertEquals(31, result.get("next_offset"));
        verify(tasks).search(owner(), agent(), "门店", true, 30, 10);
    }

    @Test void rejectsUntrustedIdentityAndPagingArgumentsBeforeSearch() {
        var invalid = List.of(Map.<String, Object>of("tenant_id", "other"), Map.<String, Object>of("offset", -1),
                Map.<String, Object>of("offset", 1.5), Map.<String, Object>of("limit", 0),
                Map.<String, Object>of("limit", 101), Map.<String, Object>of("query", "x".repeat(1_001)));
        invalid.forEach(arguments -> assertEquals(Map.of("success", false, "error", "invalid_arguments"),
                handler.execute(arguments, context())));
        verifyNoInteractions(tasks);
    }

    private ConversationTask task() {
        return new ConversationTask("task", owner().sessionRef().tenantId(), owner().requireUser(), agent(),
                3, ConversationTask.Status.OPEN, "门店计划", "摘要",
                new TypedPayload(ConversationTask.STATE_SCHEMA, "{\"facts\":{},\"pending\":[]}"),
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
