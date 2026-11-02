package com.skillforge.institution.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.skillforge.institution.group.GroupControlService;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;

/** 宿主映射只消费正式冻结effect；持久控制竞争由中心服务与真实Store测试覆盖。 */
class AgentRuntimeGroupControlTest {
    private static final CodecLimits LIMITS = new CodecLimits(1_000_000, 100_000, 128);
    private static final TenantId TENANT = new TenantId("41");
    private static final GroupBindingRef GROUP = new GroupBindingRef(TENANT, "702");
    private static final SessionOwner OWNER = new SessionOwner(new SessionRef(TENANT, "shared"),
            SessionSubject.group(GROUP), SessionTarget.group(GROUP));

    @Test void actualFrozenToolEffectSelectsBusinessWriteWithoutUsingToolNamesOrArguments() {
        var controls = mock(GroupControlService.class); var session = mock(SqlSession.class);
        var gate = new AgentRuntimeGroupControl(controls, new AgentExecutionCodec(LIMITS)); var identity = identity(OWNER);
        var run = mock(ExecutionStore.Run.class); when(run.identity()).thenReturn(identity);
        when(controls.allowDispatch(session, identity, false)).thenReturn(true);
        when(controls.allowDispatch(session, identity, true)).thenReturn(false);
        for (var effect : ToolEffect.values()) {
            var prepared = prepared(ExecutionStore.Kind.TOOL, tool(effect));
            assertEquals(effect == ToolEffect.READ_ONLY, gate.allowed(session, run, prepared));
        }
        verify(controls).allowDispatch(session, identity, false);
        verify(controls, times(3)).allowDispatch(session, identity, true);
    }

    @Test void modelTasksAndScriptUseExecutionGateWhileTheirRealChildToolsKeepSeparateWriteQualification() {
        var controls = mock(GroupControlService.class); var session = mock(SqlSession.class);
        var gate = new AgentRuntimeGroupControl(controls, new AgentExecutionCodec(LIMITS)); var identity = identity(OWNER);
        var run = mock(ExecutionStore.Run.class); when(run.identity()).thenReturn(identity);
        when(controls.allowDispatch(session, identity, false)).thenReturn(false);
        for (var kind : List.of(ExecutionStore.Kind.MODEL, ExecutionStore.Kind.KNOWLEDGE,
                ExecutionStore.Kind.WORKFLOW, ExecutionStore.Kind.DELEGATION))
            assertFalse(gate.allowed(session, run, prepared(kind, new TypedPayload("execution.v1", "{}"))));
        assertFalse(gate.allowed(session, run, prepared(ExecutionStore.Kind.TOOL,
                new TypedPayload(WorkflowScriptNodeCodec.REQUEST_SCHEMA, "{}"))));
        verify(controls, times(5)).allowDispatch(session, identity, false);
        verify(controls, never()).allowDispatch(session, identity, true);
    }

    @Test void personalRuntimeKeepsItsOriginalAuthorizationWithoutConsultingGroupControl() {
        var controls = mock(GroupControlService.class); var gate = new AgentRuntimeGroupControl(controls, new AgentExecutionCodec(LIMITS));
        var run = mock(ExecutionStore.Run.class);
        when(run.identity()).thenReturn(identity(SessionOwner.user(OWNER.sessionRef(), new AgentId("80"), new EndUserId(9))));
        assertTrue(gate.allowed(mock(SqlSession.class), run, prepared(ExecutionStore.Kind.TOOL, new TypedPayload("execution.v1", "{}"))));
        verifyNoInteractions(controls);
    }

    private static RunIdentity identity(SessionOwner owner) {
        var input = new InputId("group-trigger:trigger");
        return new RunIdentity(owner, new AgentId("80"), input, new RunRef(TENANT, "root-run"),
                new TraceEnvelope("trace", "span", input, Optional.empty()), new ResultRef("verified"),
                new RunRequester.EndUser(new EndUserId(9)), Optional.of(new SourceMessageRef(owner.sessionRef(), "message")),
                new RunOriginRef(TENANT, "group-trigger:trigger"));
    }
    private static ExecutionStore.Prepared prepared(ExecutionStore.Kind kind, TypedPayload payload) {
        return new ExecutionStore.Prepared(new ActionRef(TENANT, "action:" + kind), 1, kind, "target", new ResultRef("capability"),
                new ExecutionStore.Material(new ResultRef("request"), payload), "fingerprint");
    }
    private static TypedPayload tool(ToolEffect effect) {
        var target = new ToolPortal.FrozenToolTarget(new ToolPortal.ToolHandle("tool:mount"),
                new CapabilityRef(CapabilityType.TOOL, "tool", "version"), "deployment", 1,
                new TypedPayload("input.v1", "{}"), new TypedPayload("output.v1", "{}"), effect,
                effect == ToolEffect.IDEMPOTENT_WRITE, Duration.ofSeconds(5), true,
                ToolPortal.ToolConfirmationPolicy.NEVER, Set.of(), Set.of(), true);
        return new AgentExecutionCodec(LIMITS).encodeTool(new AgentExecutionCodec.ToolRequest(target,
                new ToolPortal.ToolBinding(ToolPortal.ToolBinding.ProviderType.TEST_MOCK, "provider", "same-tool"),
                new TypedPayload("application/json", "{}"), "call", "same_tool"));
    }
}
