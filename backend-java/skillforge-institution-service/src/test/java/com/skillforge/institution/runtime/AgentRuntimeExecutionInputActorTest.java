package com.skillforge.institution.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.skillforge.runtime.adapter.jdbc.budget.JdbcExecutionBudgets;
import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;
import com.skillforge.core.knowledge.integration.runtime.knowledge.JdbcKnowledgeBindings;
import com.skillforge.runtime.adapter.jdbc.model.JdbcModelBindings;
import com.skillforge.core.capability.integration.runtime.tool.JdbcMcpBindings;
import com.skillforge.core.workflow.integration.runtime.workflow.JdbcWorkflowBindings;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ModelPort.*;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.AgentExecutionProgram;
import com.skillforge.runtime.session.ExecutionCoordinator;
import com.skillforge.runtime.session.ProgramToolProgram;
import com.skillforge.runtime.session.WorkflowNodeProgram;
import com.skillforge.runtime.session.spi.*;
import com.skillforge.runtime.starter.AgentRuntimeModelConfiguration;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** 活动宿主消费已提交操作者的直接回归；不代表真实数据库、IAM或Provider验收。 */
class AgentRuntimeExecutionInputActorTest {
    @Test void publicGuardPreservesExactInternalContextFactsOnFinishAndAdoptionWithoutPublishingThem() {
        var material=new ExecutionStore.Event(new EventId("internal-material"),"agent.context-material",
                new TypedPayload("skillforge.runtime.execution.context-material.v1","{\"sourceContext\":\"private-source\",\"messages\":{}}"));
        var attempt=new ExecutionStore.Event(new EventId("internal-attempt"),"agent.context-compaction-attempt",
                new TypedPayload("skillforge.runtime.execution.context-compaction-attempt.v1","{\"summaryStatus\":\"UNAVAILABLE\"}"));
        var events=List.of(material,attempt);
        var program=mock(ExecutionCoordinator.Program.class);var run=mock(ExecutionStore.Run.class);var state=new Object();
        var finish=new ExecutionCoordinator.Step.Finish(RunStatus.TERMINATED,events);
        when(program.next(run,state)).thenReturn(finish);
        var guard=new com.skillforge.core.chat.integration.publicread.PublicOutputGuard(java.nio.file.Path.of("private-guard-root").toAbsolutePath().toString());
        ExecutionCoordinator.Program<Object> wrapped=org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                AgentRuntimeExecutionConfiguration.class,"protectProgram",program,guard,new com.fasterxml.jackson.databind.ObjectMapper(),new CodecLimits(100_000,10_000,32));
        assertSame(finish,wrapped.next(run,state));
        var progress=new ExecutionStore.Progress(1,0,0,new ResultRef("context"));
        var context=new ExecutionStore.Material(progress.context(),new TypedPayload("context.v1","{}"));
        var adoption=new ExecutionCoordinator.Adoption<>(state,progress,context,events,Optional.empty());
        when(program.adopt(run,state,null,null)).thenReturn(adoption);
        assertEquals(events,wrapped.adopt(run,state,null,null).events());
        when(program.inspectBatch(run,state,null,1)).thenReturn(new ExecutionCoordinator.BatchResult(Optional.empty(),events));
        assertEquals(events,wrapped.inspectBatch(run,state,null,1).events());
        for(var invalid:List.of(new ExecutionStore.Event(new EventId("bad-schema"),material.type(),new TypedPayload("wrong.v1","{}")),
                new ExecutionStore.Event(new EventId("bad-type"),"unknown",material.data()))) {
            when(program.next(run,state)).thenReturn(new ExecutionCoordinator.Step.Finish(RunStatus.TERMINATED,List.of(invalid)));
            assertEquals("execution_public_event_unsupported",assertThrows(IllegalArgumentException.class,()->wrapped.next(run,state)).getMessage());
        }
    }

    /** 真实Agent拒绝必须穿过机构类型路由和公开保护器，最终由协调器提交FAILED及完成事件。 */
    @Test void composedInstitutionProgramsCommitRealAuthorizationFailureForSingleAndBatchDispatch() throws Exception {
        for (boolean batch : List.of(false, true)) {
            var f = new Fixture(); var run = f.originalRun; var limits = new CodecLimits(1_000_000, 100_000, 128);
            var agent = spy(new AgentExecutionProgram(mock(AgentExecutionProgram.Sources.class),
                    new com.skillforge.runtime.core.CoreRuntime(limits, new com.skillforge.runtime.session.SessionRuntimePayloads()), limits));
            var frozen = mock(AgentExecutionProgram.Materials.class); when(frozen.lastResult()).thenReturn(Optional.of(new ResultRef("original-model-result")));
            var context = new ExecutionStore.Material(new ResultRef("context"), new TypedPayload("context.v1", "{}"));
            when(run.progress()).thenReturn(new ExecutionStore.Progress(1, 0, 0, context.reference()));
            when(run.phase()).thenReturn(ExecutionStore.Phase.READY); when(run.status()).thenReturn(RunStatus.RUNNING);
            when(run.lease()).thenReturn(Optional.of(new LeaseFence(f.tenant, "claim", "worker", 1, java.time.Instant.now().plusSeconds(60)))); when(run.currentAction()).thenReturn(Optional.empty());
            when(run.currentBatch()).thenReturn(Optional.empty()); when(run.waiting()).thenReturn(Optional.empty()); when(run.revision()).thenReturn(7L);
            doReturn(frozen).when(agent).open(run, context);
            var prepared = new ExecutionStore.Prepared(new ActionRef(f.tenant, "new-attempt"), 2, ExecutionStore.Kind.MODEL, "model", new ResultRef("capability"),
                    new ExecutionStore.Material(new ResultRef("request"), new TypedPayload("request.v1", "{}")), "fingerprint");
            var demand = new ExecutionBudgetPort.Demand(f.identity.runRef(), "new-attempt:1", prepared.fingerprint(), ExecutionBudgetPort.Category.BUSINESS_MODEL,
                    ExecutionBudgetPort.Resources.call(ExecutionBudgetPort.Category.BUSINESS_MODEL, 1, 1), false);
            ExecutionCoordinator.Step step = new ExecutionCoordinator.Step.Dispatch(prepared, "new-attempt:1", demand, f.original, Optional.empty(), List.of());
            if (batch) {
                var action = new ActionRef(f.tenant, "original-model");
                var source = new ExecutionStore.Action(action, f.identity.runRef(), 1, ExecutionStore.Kind.MODEL, "model", new ResultRef("capability"), new ResultRef("original-request"),
                        "source-fingerprint", ExecutionStore.ActionStatus.SUCCEEDED, "source-attempt", Optional.of("source-attempt"), Optional.empty());
                when(f.store.action(action)).thenReturn(Optional.of(source));
                var members = new java.util.ArrayList<ExecutionStore.BatchMember>();
                for (int index = 1; index <= 2; index++) {
                    var tool = new ExecutionStore.Prepared(new ActionRef(f.tenant, "tool-" + index), index + 1, ExecutionStore.Kind.TOOL, "tool", new ResultRef("tool-capability"), prepared.request(), "tool-fingerprint-" + index);
                    var budget = new ExecutionBudgetPort.Demand(f.identity.runRef(), "tool-attempt-" + index, tool.fingerprint(), ExecutionBudgetPort.Category.TOOL, ExecutionBudgetPort.Resources.call(ExecutionBudgetPort.Category.TOOL, 0, 0), false);
                    members.add(new ExecutionStore.BatchMember.Fresh(index, new ExecutionStore.BatchRequest(tool, "tool-attempt-" + index, budget)));
                }
                step = new ExecutionCoordinator.Step.Batch(action, members, f.original);
                when(f.store.dispatchBatch(any())).thenReturn(new ExecutionStore.BatchDispatchResult.Rejected("execution_dispatch_unauthorized"));
            } else when(f.store.dispatch(any())).thenReturn(new ExecutionStore.DispatchResult.Rejected("execution_dispatch_unauthorized"));
            doReturn(step).when(agent).next(run, frozen);
            var router = new AgentRuntimeExecutionPrograms(agent, mock(WorkflowNodeProgram.class), mock(ProgramToolProgram.class));
            var guard = new com.skillforge.core.chat.integration.publicread.PublicOutputGuard(java.nio.file.Path.of("private-guard-root").toAbsolutePath().toString());
            ExecutionCoordinator.Program<AgentRuntimeExecutionPrograms.Materials> composed = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    AgentRuntimeExecutionConfiguration.class, "protectProgram", router, guard, new com.fasterxml.jackson.databind.ObjectMapper(), limits);
            when(f.store.claim(any())).thenReturn(Optional.of(run)); when(f.store.material(f.tenant, context.reference())).thenReturn(Optional.of(context));
            when(f.store.inputs(any(), anyLong(), anyBoolean(), anyInt())).thenReturn(List.of());
            var finished = mock(ExecutionStore.Run.class); when(finished.identity()).thenReturn(f.identity); when(finished.phase()).thenReturn(ExecutionStore.Phase.FINISHED); when(finished.status()).thenReturn(RunStatus.FAILED);
            when(f.store.finish(any())).thenReturn(finished);
            var capabilities = mock(ExecutionCoordinator.Capabilities.class);
            var coordinator = new ExecutionCoordinator<>(f.store, composed, capabilities, ignored -> mock(ExecutionCoordinator.Control.class), mock(ExecutionCoordinator.LocalPlans.class));
            var outcome = coordinator.drive(new ExecutionStore.Claim(f.identity.runRef(), "worker", "claim", Duration.ofMinutes(1)));
            assertEquals(ExecutionCoordinator.Stop.FINISHED, outcome.stop()); assertEquals(RunStatus.FAILED, outcome.run().orElseThrow().status());
            var submitted = org.mockito.ArgumentCaptor.forClass(ExecutionStore.Finish.class); verify(f.store).finish(submitted.capture());
            assertEquals(RunStatus.FAILED, submitted.getValue().status());
            var event = submitted.getValue().events().getFirst(); assertEquals(AgentExecutionProgram.COMPLETED_EVENT, event.type());
            var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(event.data().canonicalJson());
            assertEquals("execution_dispatch_unauthorized", body.path("reason").asText()); assertEquals("FAILED", body.path("status").asText());
            assertEquals("original-model-result", body.path("resultRef").asText()); assertEquals("", body.path("text").asText());
            verifyNoInteractions(capabilities); verify(f.store, never()).complete(any());
            assertTrue(composed.dispatchRejected(run, new AgentRuntimeExecutionPrograms.AgentMaterials(frozen), "execution_dispatch_paused").isEmpty());
        }
    }

    @Test void composedInstitutionProgramsForwardRejectedBatchAdoptionWithoutDroppingTypedMaterialsOrProtection() {
        var f = new Fixture(); var agent = mock(AgentExecutionProgram.class); var frozen = mock(AgentExecutionProgram.Materials.class);
        var context = new ExecutionStore.Material(new ResultRef("stopped-context"), new TypedPayload("context.v1", "{}"));
        var progress = new ExecutionStore.Progress(1, 2, 0, context.reference());
        var event = new ExecutionStore.Event(new EventId("stopped-context"), "agent.context-material", new TypedPayload("skillforge.runtime.execution.context-material.v1", "{}"));
        var adoption = new ExecutionCoordinator.Adoption<>(frozen, progress, context, List.of(event), Optional.empty());
        when(agent.batchDispatchRejectionAdoption(eq(f.originalRun), eq(frozen), anyList(), eq("execution_dispatch_unauthorized"))).thenReturn(Optional.of(adoption));
        var router = new AgentRuntimeExecutionPrograms(agent, mock(WorkflowNodeProgram.class), mock(ProgramToolProgram.class));
        var guard = new com.skillforge.core.chat.integration.publicread.PublicOutputGuard(java.nio.file.Path.of("private-guard-root").toAbsolutePath().toString());
        ExecutionCoordinator.Program<AgentRuntimeExecutionPrograms.Materials> composed = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                AgentRuntimeExecutionConfiguration.class, "protectProgram", router, guard, new com.fasterxml.jackson.databind.ObjectMapper(), new CodecLimits(1_000_000, 100_000, 128));
        var actual = composed.batchDispatchRejectionAdoption(f.originalRun, new AgentRuntimeExecutionPrograms.AgentMaterials(frozen), List.of(), "execution_dispatch_unauthorized").orElseThrow();
        assertEquals(new AgentRuntimeExecutionPrograms.AgentMaterials(frozen), actual.materials()); assertEquals(progress, actual.next()); assertEquals(List.of(event), actual.events());
        verify(agent).batchDispatchRejectionAdoption(f.originalRun, frozen, List.of(), "execution_dispatch_unauthorized");
    }

    /** 新操作者立即进入当前派发守卫，配置与冻结绑定不重新读取，旧操作者不再通过帧检查。 */
    @Test void committedActorReplacesOnlyActiveFrameIdentityBeforeNextDispatch() {
        var f = new Fixture(); f.sources.begin(f.identity.runRef());
        try {
            f.sources.bindControl(f.identity.runRef(), mock(ExecutionCoordinator.Control.class));
            var frozen = f.sources.configuration(f.originalRun, f.input.reference());
            clearInvocations(f.store, f.identities, f.models, f.preparations, f.catalogs, f.bindings, f.budgets);
            var current = new VerifiedIdentity(f.original.principal(), f.owner, Set.of("session.execute"), 2, new ResultRef("saved-controller"));
            var acceptedRun = mock(ExecutionStore.Run.class);
            when(acceptedRun.identity()).thenReturn(f.identity); when(acceptedRun.actorVerificationRef()).thenReturn(current.verificationRef());
            f.sources.actorChanged(acceptedRun, current);
            var loaded = f.sources.configuration(acceptedRun, f.input.reference());
            assertEquals(frozen.withActor(current), loaded); assertSame(frozen.profile(), loaded.profile()); assertSame(frozen.target(), loaded.target());
            var session = mock(SqlSession.class); var prepared = mock(ExecutionStore.Prepared.class);
            when(prepared.kind()).thenReturn(ExecutionStore.Kind.DELEGATION);
            assertFalse(f.sources.allowed(session, acceptedRun, current, prepared));
            verify(f.delegations).requireDispatch(session, acceptedRun, current, prepared, f.binding);
            clearInvocations(f.delegations);
            assertFalse(f.sources.allowed(session, acceptedRun, f.original, prepared)); verifyNoInteractions(f.delegations);
            var mismatched = new VerifiedIdentity(current.principal(), f.owner, current.scopes(), 2, new ResultRef("uncommitted-actor"));
            assertThrows(IllegalStateException.class, () -> f.sources.actorChanged(acceptedRun, mismatched));
            assertSame(loaded, f.sources.configuration(acceptedRun, f.input.reference()));
            verifyNoInteractions(f.store, f.identities, f.models, f.preparations, f.catalogs, f.bindings, f.budgets);
        } finally { f.sources.end(f.identity.runRef()); }
    }

    /** 机构类型路由必须把已提交回执送到实际Agent程序，不因默认hook而丢失身份同步。 */
    @Test void programRouterForwardsCommittedInputToTheOwningAgentProgram() {
        var agent = mock(AgentExecutionProgram.class); var node = mock(WorkflowNodeProgram.class); var tool = mock(ProgramToolProgram.class);
        var router = new AgentRuntimeExecutionPrograms(agent, node, tool); var run = mock(ExecutionStore.Run.class);
        var identity = mock(RunIdentity.class); when(identity.runRef()).thenReturn(new RunRef(new TenantId("1"), "routed-agent")); when(run.identity()).thenReturn(identity);
        when(run.configuration()).thenReturn(new ResultRef("ordinary-agent-profile"));
        var before = mock(AgentExecutionProgram.Materials.class); var after = mock(AgentExecutionProgram.Materials.class);
        var receipt = mock(ExecutionStore.InputAcceptance.class); when(receipt.run()).thenReturn(run);
        when(agent.inputCommitted(receipt, before)).thenReturn(after);
        assertEquals(new AgentRuntimeExecutionPrograms.AgentMaterials(after), router.inputCommitted(receipt, new AgentRuntimeExecutionPrograms.AgentMaterials(before)));
        verify(agent).inputCommitted(receipt, before); verifyNoInteractions(node, tool);
    }

    /** 已保存MODEL结果恢复前，两层正式包装必须让实际Agent加载原manifest正文闭包。 */
    @Test void publicProtectionAndProgramRouterRestoreTheActualManifestRequest() {
        var limits=new CodecLimits(1_000_000,100_000,128); var codec=new AgentExecutionCodec(limits);
        var sources=mock(AgentExecutionProgram.Sources.class); var core=mock(com.skillforge.runtime.core.CoreRuntime.class);
        var agent=new AgentExecutionProgram(sources,core,limits);
        var node=mock(WorkflowNodeProgram.class); var tool=mock(ProgramToolProgram.class);
        var router=new AgentRuntimeExecutionPrograms(agent,node,tool);
        var guard=mock(com.skillforge.core.chat.integration.publicread.PublicOutputGuard.class);
        ExecutionCoordinator.Program<AgentRuntimeExecutionPrograms.Materials> protectedProgram=org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                AgentRuntimeExecutionConfiguration.class,"protectProgram",router,guard,new com.fasterxml.jackson.databind.ObjectMapper(),limits);
        var run=mock(ExecutionStore.Run.class); var identity=mock(RunIdentity.class); var tenant=new TenantId("1");
        when(identity.runRef()).thenReturn(new RunRef(tenant,"restored-model")); when(run.identity()).thenReturn(identity);
        when(run.configuration()).thenReturn(new ResultRef("ordinary-agent-profile"));
        var target=new FrozenModelTarget(new ModelBindingRef(tenant,"binding",1),"model",
                new ModelCapabilitySnapshot(Set.of(ResponseFormatKind.TEXT),false,false,new ResultRef("capability")),Duration.ofSeconds(30),new ResultRef("resolution"));
        var frozen=new com.skillforge.runtime.core.FrozenModelRequest(new RequestedModelStrategy("strategy","1"),target,
                List.of(new ModelMessage("user",ModelRole.USER,"原请求")),new ResponseContract(new ResponseFormat.Text(),EnforcementLevel.STRICT,"text"),
                new GenerationOptions(java.util.OptionalInt.empty(),java.util.OptionalDouble.empty()),ReasoningPolicy.DISABLED);
        var original=new AgentExecutionCodec.ModelCallRequest(frozen,List.of(),0,Optional.empty()); var bundle=codec.encodeModelRequestMaterials(original);
        var refs=codec.modelRequestReferences(bundle.root().content());
        when(sources.materials(run,refs)).thenReturn(bundle.parts().stream().map(Optional::of).toList());
        var prepared=new ExecutionStore.Prepared(new ActionRef(tenant,"model-action"),1,ExecutionStore.Kind.MODEL,"model",new ResultRef("capability"),bundle.root(),"fingerprint");
        var restored=protectedProgram.restorePrepared(run,prepared);
        assertEquals(prepared.request(),restored.request()); assertEquals(prepared.fingerprint(),restored.fingerprint());
        assertEquals(bundle.parts(),restored.requestParts()); assertEquals(original,codec.decodeModelRequest(restored));
        verify(sources).materials(run,refs); verifyNoMoreInteractions(sources); verifyNoInteractions(node,tool,guard,core);
    }

    /** 两层正式宿主包装保留冻结Agent决定的串行策略，不能由包装默认值授予并行资格。 */
    @Test void publicProtectionAndProgramRouterPreserveAgentBatchSchedulingMode() {
        var agent = mock(AgentExecutionProgram.class); var node = mock(WorkflowNodeProgram.class); var tool = mock(ProgramToolProgram.class);
        var router = new AgentRuntimeExecutionPrograms(agent, node, tool);
        var guard = mock(com.skillforge.core.chat.integration.publicread.PublicOutputGuard.class);
        ExecutionCoordinator.Program<AgentRuntimeExecutionPrograms.Materials> protectedProgram = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                AgentRuntimeExecutionConfiguration.class, "protectProgram", router, guard,
                new com.fasterxml.jackson.databind.ObjectMapper(), new CodecLimits(1_000_000, 100_000, 128));
        var frozen = mock(AgentExecutionProgram.Materials.class);
        var materials = new AgentRuntimeExecutionPrograms.AgentMaterials(frozen);
        when(agent.batchParallel(frozen)).thenReturn(false);
        assertFalse(protectedProgram.batchParallel(materials));
        when(agent.batchParallel(frozen)).thenReturn(true);
        assertTrue(protectedProgram.batchParallel(materials));
        verify(agent, times(2)).batchParallel(frozen);
        verifyNoInteractions(node, tool, guard);
    }

    /** 经正式加载入口建立一份活动帧；依赖都是显式单测事实，没有测试专用生产入口。 */
    private static final class Fixture {
        private final TenantId tenant = new TenantId("1");
        private final GroupBindingRef group = new GroupBindingRef(tenant, "11");
        private final SessionOwner owner = new SessionOwner(new SessionRef(tenant, "shared-session"), SessionSubject.group(group), SessionTarget.group(group));
        private final VerifiedIdentity original = new VerifiedIdentity(new PrincipalRef.ServicePrincipal(tenant, "integration", "chat", 1), owner,
                Set.of("session.execute"), 1, new ResultRef("original-actor"));
        private final RunIdentity identity = new RunIdentity(owner, new AgentId("2"), new InputId("original-input"), new RunRef(tenant, "actor-run"),
                new TraceEnvelope("trace", "span", new InputId("root-input"), Optional.empty()), original.verificationRef(), new RunRequester.EndUser(new EndUserId(3)),
                Optional.empty(), new RunOriginRef(tenant, "original-trigger"));
        private final ExecutionStore store = mock(ExecutionStore.class);
        private final JdbcIdentityPolicyPort identities = mock(JdbcIdentityPolicyPort.class);
        private final JdbcModelBindings models = mock(JdbcModelBindings.class);
        private final AgentRuntimeModelConfiguration preparations = mock(AgentRuntimeModelConfiguration.class);
        private final AgentRuntimeExecutionCatalog catalogs = mock(AgentRuntimeExecutionCatalog.class);
        private final AgentRunBindings bindings = mock(AgentRunBindings.class);
        private final AgentRunBindings.Binding binding = mock(AgentRunBindings.Binding.class, RETURNS_DEEP_STUBS);
        private final JdbcExecutionBudgets budgets = mock(JdbcExecutionBudgets.class);
        private final AgentDelegationHost delegations = mock(AgentDelegationHost.class);
        private final ExecutionStore.Run originalRun = mock(ExecutionStore.Run.class);
        private final ExecutionStore.Material input;
        private final AgentRuntimeExecutionSources sources;

        /** 冻结模型、预算、目录与原输入各装配一次，后续断言不允许再访问这些权威来源。 */
        private Fixture() {
            var codec = new AgentExecutionCodec(new CodecLimits(1_000_000, 100_000, 128));
            input = new ExecutionStore.Material(new ResultRef("input-content"), codec.encodeInput(List.of(new ModelMessage("original", ModelRole.USER, "原输入")),
                    new TypedPayload("frozen-input.v1", "{}")));
            when(originalRun.identity()).thenReturn(identity); when(originalRun.configuration()).thenReturn(new ResultRef("profile"));
            when(originalRun.actorVerificationRef()).thenReturn(original.verificationRef());
            var target = new FrozenModelTarget(new ModelBindingRef(tenant, "binding", 1), "model",
                    new ModelCapabilitySnapshot(Set.of(ResponseFormatKind.TEXT), false, false, new ResultRef("model-capability")), Duration.ofSeconds(30), new ResultRef("model-resolution"));
            var strategy = new RequestedModelStrategy("strategy", "1"); var profile = mock(SessionPreparations.Profile.class);
            when(profile.binding()).thenReturn(target.binding()); when(profile.strategy()).thenReturn(strategy);
            when(profile.systemPrompt()).thenReturn(SessionPrompt.single("system", SessionPrompt.Type.HOST, "1", "系统指令"));
            when(preparations.preparationProfile(tenant, originalRun.configuration())).thenReturn(Optional.of(profile));
            var model = mock(JdbcModelBindings.Binding.class); when(model.target()).thenReturn(target); when(model.strategy()).thenReturn(strategy);
            when(models.findForOwner(target.binding(), identity.agentId())).thenReturn(Optional.of(model));
            when(binding.owner()).thenReturn(owner); when(binding.actor()).thenReturn(identity.agentId()); when(binding.role()).thenReturn(AgentRunBindings.Role.DELEGATE);
            when(binding.member().mounts().generation()).thenReturn(1L); when(bindings.find(identity.runRef())).thenReturn(Optional.of(binding));
            when(identities.originalVerification(owner, original.verificationRef())).thenReturn(Optional.of(original));
            when(store.material(eq(tenant), any())).thenAnswer(call -> Optional.of(call.getArgument(1).equals(input.reference()) ? input :
                    new ExecutionStore.Material(call.getArgument(1), new TypedPayload(AgentRuntimeExecutionCatalog.SCHEMA, "{}"))));
            when(catalogs.read(any(), eq(identity))).thenReturn(new AgentRuntimeExecutionCatalog.FrozenCatalog(identity, List.of(), Map.of(), List.of(), List.of(), Set.of(),
                    List.of(), PlanPort.State.absent(), com.skillforge.runtime.session.SessionCollaboration.Selection.inactive(), Optional.empty()));
            var resources = new ExecutionBudgetPort.Resources(10, 0, 10, 10, 10_000, 10_000, 20_000);
            var policy = new ExecutionBudgetPort.Policy.Enforced(new ExecutionBudgetPort.Limits(ExecutionBudgetPort.Scenario.STANDARD,
                    resources, resources, resources, 300, 300, "input-actor-test"));
            var registration = new ExecutionBudgetPort.Registration(identity.runRef(), identity.runRef(), ExecutionBudgetPort.Role.PRIMARY, policy);
            var snapshot = new ExecutionBudgetPort.Snapshot(registration, ExecutionBudgetPort.Resources.ZERO, ExecutionBudgetPort.Resources.ZERO,
                    ExecutionBudgetPort.Resources.ZERO, ExecutionBudgetPort.Resources.ZERO, ExecutionBudgetPort.Closing.ZERO, ExecutionBudgetPort.Phase.BUSINESS, 0, 0, 0);
            when(budgets.snapshot(identity.runRef())).thenReturn(snapshot);
            sources = new AgentRuntimeExecutionSources(preparations, models, mock(JdbcMcpBindings.class), mock(JdbcKnowledgeBindings.class), mock(JdbcWorkflowBindings.class), mock(MediaPort.class),
                    mock(AgentRuntimePublishedCapabilities.class), catalogs, identities, mock(SessionTokenCounter.class), budgets, store, bindings, new AgentRuntimeProperties(),
                    provider(delegations), mock(ObjectProvider.class), mock(ObjectProvider.class), mock(ObjectProvider.class), Clock.systemUTC(),mock(GroupMemoryRecall.class), com.skillforge.runtime.session.spi.SessionContextSummary.disabled(), new com.fasterxml.jackson.databind.ObjectMapper(), mock(AgentRuntimeCompactionMapper.class));
        }
    }

    /** 通过正式ObjectProvider注入实际用例拥有的宿主依赖。 */
    private static <T> ObjectProvider<T> provider(T value) {
        @SuppressWarnings("unchecked") var provider = (ObjectProvider<T>) mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(value); return provider;
    }
}
