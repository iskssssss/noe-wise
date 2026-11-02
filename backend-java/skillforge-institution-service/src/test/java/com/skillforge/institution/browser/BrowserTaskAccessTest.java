package com.skillforge.institution.browser;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.execution.browser.BrowserReceipt;
import com.skillforge.institution.artifact.ArtifactAccess;
import com.skillforge.institution.runtime.AgentRunBindings;
import com.skillforge.institution.runtime.AgentRuntimeProgramTools;
import com.skillforge.institution.runtime.AgentRuntimeWorkflowNodeExecutions;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 使用真实共享授权边界和三个真实 Handler，验证来源撤权前没有浏览器副作用。 */
class BrowserTaskAccessTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TenantId TENANT = new TenantId("7");
    private static final String TASK = "e76ddb32-3257-4a6a-8dba-240d3cae8b58";
    private final ArtifactAccess artifacts = mock(ArtifactAccess.class);
    private final AgentRunBindings bindings = mock(AgentRunBindings.class);
    private final ExecutionStore executions = mock(ExecutionStore.class);
    private final AgentRuntimeProgramTools programs = mock(AgentRuntimeProgramTools.class);
    private final AgentRuntimeWorkflowNodeExecutions workflows = mock(AgentRuntimeWorkflowNodeExecutions.class);
    private final org.springframework.beans.factory.support.StaticListableBeanFactory sources =
            new org.springframework.beans.factory.support.StaticListableBeanFactory(Map.of(
                    "executions", executions, "programs", programs, "workflows", workflows));
    private final BrowserTaskAccess access = new BrowserTaskAccess(artifacts, bindings,
            sources.getBeanProvider(ExecutionStore.class), sources.getBeanProvider(AgentRuntimeProgramTools.class),
            sources.getBeanProvider(AgentRuntimeWorkflowNodeExecutions.class));
    private final BrowserClient client = mock(BrowserClient.class);
    private final BrowserMapper mapper = mock(BrowserMapper.class);
    private final BrowserScreenshotSink screenshots = mock(BrowserScreenshotSink.class);
    private final BrowserToolHandlers handlers = new BrowserToolHandlers(client, mapper, JSON, screenshots, access);
    private final AgentRunBindings.Requester requester = new AgentRunBindings.Requester("member-b", "");
    private final AgentRunBindings.Binding source = mock(AgentRunBindings.Binding.class);
    private final PlatformToolExecutionContext context = context();
    private final BrowserReceipt receipt;

    BrowserTaskAccessTest() throws Exception {
        when(programs.resourceOrigin(any(RunIdentity.class))).thenAnswer(call -> call.getArgument(0));
        when(workflows.resourceOrigin(any(RunIdentity.class))).thenAnswer(call -> call.getArgument(0));
        var current = mock(AgentRunBindings.Binding.class);
        var callerScope = mock(AgentRunBindings.Scope.class);
        when(current.owner()).thenReturn(context.run().owner());
        when(current.actor()).thenReturn(context.run().agentId());
        when(current.scope()).thenReturn(callerScope);
        when(callerScope.requester()).thenReturn(requester);
        when(bindings.find(context.run().runRef())).thenReturn(Optional.of(current));
        var origin = SessionOwner.user(new SessionRef(TENANT, "old-product"), new AgentId("agent-a"), new EndUserId(1));
        var child = SessionOwner.user(new SessionRef(TENANT, "delegation:old-child"), new AgentId("agent-a"), new EndUserId(1));
        var sourceScope = mock(AgentRunBindings.Scope.class);
        when(source.owner()).thenReturn(child);
        when(source.actor()).thenReturn(new AgentId("agent-a"));
        when(source.scope()).thenReturn(sourceScope);
        when(sourceScope.origin()).thenReturn(origin);
        when(bindings.find(new RunRef(TENANT, "old-run"))).thenReturn(Optional.of(source));
        var oldRun = mock(ExecutionStore.Run.class);
        when(oldRun.identity()).thenReturn(identity(child, "old-run"));
        when(executions.load(new RunRef(TENANT, "old-run"))).thenReturn(Optional.of(oldRun));
        receipt = new BrowserReceipt(null, "old-invocation", "start", TASK, child.sessionRef().sessionId(),
                "old-run", Instant.EPOCH.toString(), "active", null, "browser", null, JSON.createObjectNode());
        when(client.taskStates("7", "1")).thenReturn(List.of(receipt));
        when(client.execute(any())).thenReturn(new BrowserReceipt(null, "invocation", "observe", TASK,
                "current-session", "current-run", Instant.EPOCH.toString(), "succeeded", null, "browser", null,
                JSON.createObjectNode().put("text", "observed")));
    }

    @Test void revokedSourceIsHiddenAndCallAndStopNeverDispatch() throws Exception {
        doThrow(new SecurityException("source_revoked")).when(bindings)
                .authorizeRead(source, "agent-a", requester);
        var directory = handlers.browserTasks().execute(Map.of(), context);
        assertTrue(JSON.valueToTree(directory).path("items").isEmpty());
        for (var result : List.of(handlers.browserCall().execute(call(), context),
                handlers.browserStop().execute(Map.of("task", TASK), context))) {
            assertEquals("rejected", result.get("status"));
            assertEquals("browser_task_source_unreadable", result.get("error_code"));
            assertEquals(false, result.get("dispatched"));
        }
        verify(client, never()).execute(any());
        verifyNoInteractions(mapper, screenshots, artifacts);
    }

    @Test void authorizedCrossSessionTaskRemainsUsableWithItsOriginalSource() throws Exception {
        assertEquals(1, JSON.valueToTree(handlers.browserTasks().execute(Map.of(), context)).path("items").size());
        assertEquals("succeeded", handlers.browserCall().execute(call(), context).get("status"));
        verify(bindings, times(2)).authorizeRead(source, "agent-a", requester);
        var sent = org.mockito.ArgumentCaptor.forClass(com.skillforge.execution.browser.BrowserRequest.class);
        verify(client).execute(sent.capture());
        assertEquals(TASK, sent.getValue().task());
        assertEquals("current-session", sent.getValue().session());
        assertEquals("current-run", sent.getValue().run());
    }

    @Test void uiUsesTheSameOriginalProductAndDelegationReadBoundary() {
        assertTrue(access.readableCurrent("7", "1", receipt, "old-product"));
        verify(artifacts).authorizeCurrent("7", "old-product");
        verify(bindings).authorizeRead(source, "agent-a");
        doThrow(new SecurityException("delegation_revoked")).when(bindings).authorizeRead(source, "agent-a");
        assertFalse(access.readableCurrent("7", "1", receipt, "old-product"));
        assertFalse(access.readableCurrent("7", "1", receipt, "another-product"));
    }

    @Test void unknownOrWrongOwnerSourceCannotBorrowPersonalTaskIdentity() throws Exception {
        assertFalse(access.readableCurrent("7", "2", receipt, null));
        assertFalse(access.readableCurrent("8", "1", receipt, null));
        when(bindings.find(new RunRef(TENANT, "old-run"))).thenReturn(Optional.empty());
        assertTrue(JSON.valueToTree(handlers.browserTasks().execute(Map.of(), context)).path("items").isEmpty());
        assertEquals(false, handlers.browserStop().execute(Map.of("task", TASK), context).get("dispatched"));
        verify(client, never()).execute(any());
    }

    @Test void currentRunMustMatchItsPersistedCallerBeforeReadingAnyOldTask() throws Exception {
        when(bindings.find(context.run().runRef())).thenReturn(Optional.empty());
        assertEquals(false, handlers.browserCall().execute(call(), context).get("dispatched"));
        verify(bindings, never()).authorizeRead(any(), anyString(), any());
        verify(client, never()).execute(any());
    }

    @Test void rejectedPersistedSourceChainIsUnreadableWithoutDispatch() throws Exception {
        when(programs.resourceOrigin(argThat((RunIdentity actual) -> actual != null && actual.runRef().runId().equals("old-run"))))
                .thenThrow(new ExecutionStore.Rejection("program_child_source_rejected"));
        assertFalse(access.readableCurrent("7", "1", receipt, null));
        assertTrue(JSON.valueToTree(handlers.browserTasks().execute(Map.of(), context)).path("items").isEmpty());
        assertEquals(false, handlers.browserCall().execute(call(), context).get("dispatched"));
        assertEquals(false, handlers.browserStop().execute(Map.of("task", TASK), context).get("dispatched"));
        verify(client, never()).execute(any());
    }

    @Test void programAndWorkflowChildrenUseVerifiedParentBindingsWithoutChangingActualCallIdentity() throws Exception {
        for (String kind : List.of("program", "workflow")) {
            var actual = identity(context.run().owner(), kind + "-caller");
            var oldActual = identity(source.owner(), kind + "-source");
            var oldParent = identity(source.owner(), "old-run");
            var oldRun = mock(ExecutionStore.Run.class);
            when(oldRun.identity()).thenReturn(oldActual);
            when(executions.load(oldActual.runRef())).thenReturn(Optional.of(oldRun));
            if (kind.equals("program")) {
                when(programs.resourceOrigin(actual)).thenReturn(context.run());
                when(programs.resourceOrigin(oldActual)).thenReturn(oldParent);
            } else {
                when(workflows.resourceOrigin(actual)).thenReturn(context.run());
                when(workflows.resourceOrigin(oldActual)).thenReturn(oldParent);
            }
            var oldReceipt = new BrowserReceipt(null, "old-invocation", "start", TASK, receipt.session(),
                    oldActual.runRef().runId(), Instant.EPOCH.toString(), "active", null, "browser", null, JSON.createObjectNode());
            when(client.taskStates("7", "1")).thenReturn(List.of(oldReceipt));
            var childContext = new PlatformToolExecutionContext(new PortCallContext(actual,
                    context.callContext().identity(), context.callContext().control()), "invocation", "attempt");
            assertEquals("succeeded", handlers.browserCall().execute(call(), childContext).get("status"));
            verify(client).execute(argThat(request -> request.run().equals(actual.runRef().runId()) && request.task().equals(TASK)));
        }
    }

    private static RunIdentity identity(SessionOwner owner, String runId) {
        var input = new InputId("input");
        return new RunIdentity(owner, owner.target().requireAgent(), input, new RunRef(TENANT, runId),
                new TraceEnvelope("trace", "span", input, Optional.empty()), new ResultRef("identity"),
                new RunRequester.EndUser(owner.requireUser()), Optional.empty(), new RunOriginRef(TENANT, "input:input"));
    }

    private static Map<String, Object> call() {
        return Map.of("task", TASK, "command", Map.of("operation", "observe", "parameters", Map.of()));
    }

    private static PlatformToolExecutionContext context() {
        var owner = SessionOwner.user(new SessionRef(TENANT, "current-session"), new AgentId("agent-b"), new EndUserId(1));
        var input = new InputId("input");
        var run = new RunIdentity(owner, new AgentId("agent-b"), input, new RunRef(TENANT, "current-run"),
                new TraceEnvelope("trace", "span", input, Optional.empty()), new ResultRef("identity"),
                new RunRequester.EndUser(owner.requireUser()), Optional.empty(), new RunOriginRef(TENANT, "input:input"));
        var identity = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(TENANT, owner.requireUser()), owner,
                Set.of(), 1, new ResultRef("verified"));
        return new PlatformToolExecutionContext(new PortCallContext(run, identity,
                new InvocationControl(Instant.now().plusSeconds(30), () -> false)), "invocation", "attempt");
    }
}
