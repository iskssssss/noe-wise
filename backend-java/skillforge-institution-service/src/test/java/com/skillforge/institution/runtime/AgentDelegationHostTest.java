package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.foundation.persistence.ServicePrincipalMapper;
import com.skillforge.core.agent.model.AgentDelegationConfig;
import com.skillforge.institution.artifact.DelegationDeliverableFacts;
import com.skillforge.institution.attachment.SessionAttachmentService;
import com.skillforge.institution.team.TeamResultService;
import com.skillforge.institution.team.TeamDefinition;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore;
import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;
import com.skillforge.runtime.adapter.jdbc.session.JdbcDagWorkPort;
import com.skillforge.runtime.adapter.jdbc.session.JdbcSessionDelegations;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.*;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.spi.SessionDelegations.*;
import com.skillforge.runtime.starter.*;
import com.skillforge.core.foundation.integration.runtime.AgentRuntimeTrustedAccess;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

final class AgentDelegationHostTest {
    private static final TenantId TENANT = new TenantId("1");
    private static final Instant NOW = Instant.parse("2026-10-22T00:00:00Z");
    private static final RunIdentity PARENT = new RunIdentity(SessionOwner.user(new SessionRef(TENANT,"session"),
            SessionTarget.agent(new AgentId("1")),new EndUserId(7)), new AgentId("1"), new InputId("original"), new RunRef(TENANT,"parent"), new TraceEnvelope("trace","span",new InputId("root"),Optional.empty()), new ResultRef("verification"), new RunRequester.EndUser((SessionOwner.user(new SessionRef(TENANT,"session"),
            SessionTarget.agent(new AgentId("1")),new EndUserId(7))).requireUser()), java.util.Optional.empty(), new RunOriginRef((SessionOwner.user(new SessionRef(TENANT,"session"),
            SessionTarget.agent(new AgentId("1")),new EndUserId(7))).sessionRef().tenantId(), "input:" + (new InputId("original")).value()));

    /** schema目录只取冻结配置与原目录交集；自身、配置外成员、未冻结成员均不能扩充START。 */
    @Test void nativeHandlesProjectOnlyActualFrozenPrimaryDirectoryWithoutQueries() {
        var binding = frozenBinding(true);
        assertEquals(List.of("member:2"),AgentDelegationHost.frozenTargetHandles(binding));
        when(binding.role()).thenReturn(AgentRunBindings.Role.DELEGATE);
        assertEquals(List.of(),AgentDelegationHost.frozenTargetHandles(binding));
        assertEquals(List.of(),AgentDelegationHost.frozenTargetHandles(frozenBinding(false)));
        var empty = frozenBinding(true);
        when(empty.scope().directory()).thenReturn(Map.of("1",member("1")));
        assertEquals(List.of(),AgentDelegationHost.frozenTargetHandles(empty));
    }

    /** 原冻结句柄可见不授予当前派发权；真实START仍经过当前authorizeTarget，撤权后拒绝。 */
    @Test void frozenNativeDirectoryDoesNotBypassCurrentTargetAuthorizationAtDispatch() {
        var fixture = new Fixture(); var binding = frozenBinding(true);
        assertEquals(List.of("member:2"),AgentDelegationHost.frozenTargetHandles(binding));
        doThrow(new SecurityException("current_target_access_revoked")).when(fixture.bindings).authorizeTarget(binding,"2");
        var actor = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(TENANT,PARENT.owner().requireUser()),PARENT.owner(),Set.of("session.execute"),1,new ResultRef("actor"));
        var request = new SessionExecutionDelegations(new CodecLimits(100_000,10_000,100)).encodeRequest(
                new SessionExecutionDelegations.Request(DelegationCommand.delegate("member:2","完成真实子任务",false),"call","delegate"));
        var prepared = new ExecutionStore.Prepared(new ActionRef(TENANT,"action"),1,ExecutionStore.Kind.DELEGATION,"start",
                new ResultRef("agent-run-binding:" + PARENT.runRef().runId() + ":mounts"),new ExecutionStore.Material(new ResultRef("request"),request),"fingerprint");
        assertFalse(fixture.host.requireDispatch(mock(org.apache.ibatis.session.SqlSession.class),run(PARENT,ExecutionStore.Phase.READY,false),actor,prepared,binding));
        verify(fixture.bindings).authorize(binding); verify(fixture.bindings).authorizeTarget(binding,"2");
        verifyNoInteractions(fixture.identities);
    }

    /** 只构造本次投影消费的冻结事实；配置含未冻结4，目录含配置外3以防并集或自行补值。 */
    private static AgentRunBindings.Binding frozenBinding(boolean enabled) {
        var binding = mock(AgentRunBindings.Binding.class); var scope = mock(AgentRunBindings.Scope.class);
        when(binding.scope()).thenReturn(scope); when(binding.owner()).thenReturn(PARENT.owner());
        when(binding.actor()).thenReturn(PARENT.agentId()); when(binding.role()).thenReturn(AgentRunBindings.Role.PRIMARY);
        when(scope.delegation()).thenReturn(new AgentDelegationConfig(enabled,List.of("1","2","4")));
        when(scope.directory()).thenReturn(Map.of("1",member("1"),"2",member("2"),"3",member("3")));
        return binding;
    }

    /** 真实目录的公开成员形状，不含私有快照或任何额外执行授权。 */
    private static TeamDefinition.Member member(String id) { return new TeamDefinition.Member(id,"version-1","成员" + id,"角色",List.of()); }

    @Test void delegatedTaskCarriesAllFieldsOnceAndNeverCopiesPrivateTargetSnapshot() {
        var entry = entry(Optional.empty());
        var text = AgentDelegationHost.taskInput(entry, Map.of());
        for (var field : List.of("OBJECTIVE_MARKER","CONTEXT_MARKER","CONSTRAINT_MARKER","EXPECTED_MARKER"))
            assertEquals(1, text.split(field,-1).length-1);
        assertFalse(text.contains("PRIVATE_SNAPSHOT_MARKER"));
    }
    @Test void managedAttachmentMetadataRetainsRealSandboxPathAndStructuredFields() {
        var item = new SessionAttachmentService.FrozenMetadata("attachment-id","orders.csv","attachments/id/input.csv",12,"hash",NOW.plusSeconds(60).toString());
        var context = Map.<String,Object>of("attachment_ids",List.of("attachment-id"),"attachments",List.of(item));
        var canonical = AgentDelegationHost.attachmentMetadata(context);
        var row = (Map<?,?>)((List<?>)canonical.get("attachments")).getFirst();
        assertEquals("attachments/id/input.csv",row.get("input_path")); assertEquals("orders.csv",row.get("name"));
        assertFalse(row.containsKey("inputPath"));
        var text = AgentDelegationHost.taskInput(entry(Optional.empty()),context);
        assertTrue(text.contains("/input/data/加input_path")); assertFalse(text.contains("PRIVATE_SNAPSHOT_MARKER"));
    }
    @Test void childOwnerUsesActualTargetAgentAndOriginalEndUser() {
        var entry = entry(Optional.empty()); var owner = AgentDelegationHost.childOwner(entry);
        assertEquals(SessionTarget.agent(new AgentId("2")),owner.target()); assertEquals(PARENT.owner().requireUser(),owner.requireUser());
        assertEquals("delegation:delegation-id",owner.sessionRef().sessionId());
    }
    @Test void missingChildWithPersistedCancellationIsKnownLocalZeroWithoutModelCall() {
        var fixture = new Fixture(); var entry = entry(Optional.of("cancel:real"));
        when(fixture.execution.load(entry.childRun())).thenReturn(Optional.empty());
        var result = fixture.host.result(entry).orElseThrow();
        assertEquals(State.CANCELLED,result.state()); assertEquals("cancelled_before_execution",result.reason());
        assertEquals(new TokenUsage(0,true),result.usage());
    }
    @Test void childFactRequiresFullOwnerAndActualAgentIdentity() {
        var fixture = new Fixture(); var entry = entry(Optional.empty());
        var wrongOwner = SessionOwner.user(PARENT.owner().sessionRef(),entry.target().agentId(),PARENT.owner().requireUser());
        var wrong = new RunIdentity(wrongOwner, entry.target().agentId(), entry.childInput(), entry.childRun(), PARENT.trace(), new ResultRef("child-verification"), new RunRequester.EndUser((wrongOwner).requireUser()), java.util.Optional.empty(), new RunOriginRef((wrongOwner).sessionRef().tenantId(), "input:" + (entry.childInput()).value()));
        when(fixture.execution.load(entry.childRun())).thenReturn(Optional.of(run(wrong,ExecutionStore.Phase.READY,false)));
        assertThrows(SecurityException.class,()->fixture.host.fact(entry));
    }
    @Test void parentCancellationReadsOnlyNewStructuralRunAndChecksIdentity() {
        var fixture = new Fixture();
        when(fixture.execution.load(PARENT.runRef())).thenReturn(Optional.of(run(PARENT,ExecutionStore.Phase.READY,true)));
        assertTrue(fixture.host.parentCancelled(PARENT));
        var wrongOwner = SessionOwner.user(PARENT.owner().sessionRef(),new AgentId("3"),PARENT.owner().requireUser());
        var wrong = new RunIdentity(wrongOwner, new AgentId("3"), PARENT.causationInputId(), PARENT.runRef(), PARENT.trace(), PARENT.causationIdentityRef(), new RunRequester.EndUser((wrongOwner).requireUser()), java.util.Optional.empty(), new RunOriginRef((wrongOwner).sessionRef().tenantId(), "input:" + (PARENT.causationInputId()).value()));
        when(fixture.execution.load(PARENT.runRef())).thenReturn(Optional.of(run(wrong,ExecutionStore.Phase.READY,false)));
        assertThrows(SecurityException.class,()->fixture.host.parentCancelled(PARENT));
    }
    @Test void resultsCannotWakeParentBeforeExactDependencyWaitExists() {
        var fixture = new Fixture();
        when(fixture.execution.load(PARENT.runRef())).thenReturn(Optional.of(run(PARENT,ExecutionStore.Phase.READY,false)));
        assertFalse(fixture.host.wake(new Wait("business-wait",PARENT,List.of("delegation-id"),false,1),List.of(entry(Optional.empty()))));
        verify(fixture.execution,never()).acceptDependency(any(),any());
    }
    @Test void completedChildUsesDurableCompletionAndPreservesReportedUsageCompleteness() {
        var fixture = new Fixture(); var entry = entry(Optional.empty()); var identity = childIdentity(entry);
        var run = run(identity,ExecutionStore.Phase.FINISHED,false);
        when(fixture.execution.load(entry.childRun())).thenReturn(Optional.of(run));
        when(fixture.execution.usage(entry.childRun())).thenReturn(new ExecutionStore.TokenUsage(37,false));
        when(fixture.deliverables.read(PARENT,identity)).thenReturn(Optional.of(new DelegationDeliverableFacts.Published(List.of("deliverable:actual"),"正式成果已发布")));
        var event = new ExecutionStore.Event(new EventId("finish"),AgentExecutionProgram.COMPLETED_EVENT,new TypedPayload(AgentExecutionProgram.COMPLETED_SCHEMA,
                "{\"agentId\":\"2\",\"inputId\":\"child-input\",\"reason\":\"completed\",\"status\":\"COMPLETED\",\"text\":\"真实安全结果\"}"));
        when(fixture.execution.events(identity.owner().sessionRef(),0,100)).thenReturn(List.of(new ExecutionStore.SavedEvent(entry.childRun(),1,1,NOW,event)));
        var result = fixture.host.result(entry).orElseThrow();
        assertEquals(State.COMPLETED,result.state()); assertTrue(result.summary().startsWith("真实安全结果"));
        assertTrue(result.evidence().contains("deliverable:actual")); assertEquals(new TokenUsage(37,false),result.usage());
    }
    @Test void uncertainChildYieldsExplicitUnknownWithoutReplayingOrInventingUsage() {
        var fixture = new Fixture(); var entry = entry(Optional.empty());
        when(fixture.execution.load(entry.childRun())).thenReturn(Optional.of(run(childIdentity(entry),ExecutionStore.Phase.UNCERTAIN,false)));
        when(fixture.execution.usage(entry.childRun())).thenReturn(new ExecutionStore.TokenUsage(17,false));
        var result = fixture.host.result(entry).orElseThrow();
        assertEquals(State.FAILED,result.state()); assertEquals("external_effect_outcome_unknown",result.reason());
        assertEquals(new TokenUsage(17,false),result.usage()); verify(fixture.execution,never()).admitChild(any(),any());
        verifyNoInteractions(fixture.deliverables);
    }
    private static RunIdentity childIdentity(Entry entry) {
        return new RunIdentity(AgentDelegationHost.childOwner(entry), entry.target().agentId(), entry.childInput(), entry.childRun(), PARENT.trace(), new ResultRef("child-verified"), new RunRequester.EndUser((AgentDelegationHost.childOwner(entry)).requireUser()), java.util.Optional.empty(), new RunOriginRef((AgentDelegationHost.childOwner(entry)).sessionRef().tenantId(), "input:" + (entry.childInput()).value()));
    }
    private static ExecutionStore.Run run(RunIdentity identity, ExecutionStore.Phase phase, boolean cancelled) {
        return new ExecutionStore.Run(identity,new ResultRef("config"),phase == ExecutionStore.Phase.FINISHED ? RunStatus.COMPLETED : RunStatus.RUNNING,1,0,cancelled,phase,
                new ExecutionStore.Progress(0,0,0,new ResultRef("context")),Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),
                new ResultRef("actor"),Optional.empty(),NOW,NOW);
    }
    private static Entry entry(Optional<String> cancellation) {
        var target = new Target("member:2",new AgentId("2"),"成员","角色",List.of(),new TypedPayload("private","{\"secret\":\"PRIVATE_SNAPSHOT_MARKER\"}"));
        return new Entry("delegation-id",PARENT,"operation",target,new Task("OBJECTIVE_MARKER","CONTEXT_MARKER",List.of("CONSTRAINT_MARKER"),"EXPECTED_MARKER"),
                new RunRef(TENANT,"child"),new InputId("child-input"),Optional.empty(),State.QUEUED,Optional.empty(),cancellation,1,NOW,NOW);
    }
    private static final class Fixture {
        final JdbcExecutionStore execution = mock(JdbcExecutionStore.class);
        final AgentRunBindings bindings = mock(AgentRunBindings.class);
        final JdbcIdentityPolicyPort identities = mock(JdbcIdentityPolicyPort.class);
        final DelegationDeliverableFacts deliverables = mock(DelegationDeliverableFacts.class);
        final AgentDelegationHost host = new AgentDelegationHost(bindings,execution,mock(AgentRuntimeTrustedAccess.class),
                mock(AgentRuntimeHostProvisioner.class),mock(ServicePrincipalMapper.class),identities,
                mock(JdbcSessionDelegations.class),new ObjectMapper(),Clock.fixed(NOW,ZoneOffset.UTC),mock(AgentExecutionProgram.class),
                new AgentExecutionCodec(new CodecLimits(100_000,10_000,100)),mock(AgentRuntimeExecutionCatalog.class),new AgentRuntimeProperties(),
                mock(JdbcDagWorkPort.class),mock(TeamResultService.class),provider(),provider(),provider(),mock(ExecutionBudgetPort.class),deliverables,mock(SessionAttachmentService.class));
    }
    @SuppressWarnings("unchecked") private static <T> ObjectProvider<T> provider() { return mock(ObjectProvider.class); }
}
