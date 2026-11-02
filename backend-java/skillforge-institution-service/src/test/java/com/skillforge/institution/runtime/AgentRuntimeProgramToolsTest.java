package com.skillforge.institution.runtime;

import com.skillforge.core.capability.connector.PlatformToolExecutionContext;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore;
import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;
import com.skillforge.core.capability.integration.runtime.tool.JdbcMcpBindings;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ToolPortal.*;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.core.FrozenModelRequest;
import com.skillforge.runtime.api.port.ModelPort.*;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.ProgramToolProgram;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.spi.SessionToolDirectory;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class AgentRuntimeProgramToolsTest {
    @Test void oneProgramWithTenOrThirtyMessagesHydratesItsFullModelOnlyOnceForThreeRpcs() throws Exception {
        for(int messages:List.of(10,30)) {
            var fixture=new Fixture(); var bundle=fixture.manifestSource(messages,ToolChoice.AUTO);
            var boundary=fixture.rpcBoundary();
            var calls=fixture.tools.bind(fixture.context());
            verify(fixture.store,never()).materials(any(),any());
            for(int ordinal=1;ordinal<=3;ordinal++) {
                int value=ordinal;
                assertSame(boundary,assertThrows(IllegalStateException.class,()->calls.invoke(value,"sf_visible",new TypedPayload("application/json","{}"))));
            }
            verify(fixture.store,times(1)).materials(fixture.parent.runRef().tenantId(),fixture.codec.modelRequestReferences(bundle.root().content()));
            int leaves=fixture.codec.modelRequestReferences(bundle.root().content()).size();
            System.out.printf("PROGRAM_RPC_MODEL_READS messages=%d rpcs=3 baselineBatches=3 batches=1 baselineLeafRows=%d leafRows=%d%n",messages,3*leaves,leaves);
            verify(fixture.store,times(3)).admitProgramChild(any(),any());
            verify(fixture.store,times(3)).load(fixture.parent.runRef());
            var again=fixture.tools.bind(fixture.context());
            assertSame(boundary,assertThrows(IllegalStateException.class,()->again.invoke(4,"sf_visible",new TypedPayload("application/json","{}"))));
            verify(fixture.store,times(2)).materials(any(),any());
            verifyNoInteractions(fixture.providers);
        }
    }

    @Test void failedFirstRpcRetainsNoDeclarationsAndMissingOrBadMessageLeavesRejectBeforeChildAdmission() throws Exception {
        var fixture=new Fixture();var bundle=fixture.manifestSource(10,ToolChoice.AUTO);var calls=fixture.tools.bind(fixture.context());
        assertThrows(com.skillforge.core.capability.connector.ProgramToolInvoker.AdmissionRejected.class,
                ()->calls.invoke(1,"invisible",new TypedPayload("application/json","{}")));
        var refs=fixture.codec.modelRequestReferences(bundle.root().content());
        when(fixture.store.materials(fixture.parent.runRef().tenantId(),refs)).thenReturn(refs.stream().map(ref->Optional.<ExecutionStore.Material>empty()).toList());
        assertThrows(IllegalArgumentException.class,()->calls.invoke(2,"sf_visible",new TypedPayload("application/json","{}")));
        var parts=new ArrayList<>(bundle.parts());var original=parts.getFirst();
        parts.set(0,new ExecutionStore.Material(original.reference(),new TypedPayload(original.content().schemaId(),"{}")));
        when(fixture.store.materials(fixture.parent.runRef().tenantId(),refs)).thenReturn(parts.stream().map(Optional::of).toList());
        assertThrows(IllegalArgumentException.class,()->calls.invoke(3,"sf_visible",new TypedPayload("application/json","{}")));
        verify(fixture.store,times(3)).materials(any(),any());
        verify(fixture.store,never()).admitProgramChild(any(),any());verifyNoInteractions(fixture.providers);
    }

    @Test void noToolChoiceNeverLeavesReusableDeclarationsInTheProgramScope() throws Exception {
        var fixture=new Fixture();fixture.manifestSource(10,ToolChoice.NONE);var calls=fixture.tools.bind(fixture.context());
        for(int ordinal=1;ordinal<=2;ordinal++) {
            int value=ordinal;
            assertEquals("program_tool_requires_direct_call",assertThrows(com.skillforge.core.capability.connector.ProgramToolInvoker.AdmissionRejected.class,
                    ()->calls.invoke(value,"sf_visible",new TypedPayload("application/json","{}"))).reasonCode());
        }
        verify(fixture.store,times(2)).materials(any(),any());verify(fixture.store,never()).admitProgramChild(any(),any());
    }

    @Test void successfulDeclarationsDoNotBypassSecondRpcCancellationOrChangedParentAttempt() throws Exception {
        var fixture=new Fixture();fixture.manifestSource(10,ToolChoice.AUTO);var boundary=fixture.rpcBoundary();
        var original=fixture.context();var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
        var context=new PlatformToolExecutionContext(new PortCallContext(original.run(),original.callContext().identity(),
                new InvocationControl(Instant.now().plusSeconds(60),cancelled::get)),original.invocationId(),original.attemptId());
        var calls=fixture.tools.bind(context);
        assertSame(boundary,assertThrows(IllegalStateException.class,()->calls.invoke(1,"sf_visible",new TypedPayload("application/json","{}"))));
        cancelled.set(true);
        assertEquals("program_scope_closed",assertThrows(ExecutionStore.Rejection.class,()->calls.invoke(2,"sf_visible",new TypedPayload("application/json","{}"))).reason());
        cancelled.set(false);var action=fixture.store.action(fixture.source.action()).orElseThrow();when(action.currentAttempt()).thenReturn("replacement-attempt");
        assertEquals("program_scope_rejected",assertThrows(ExecutionStore.Rejection.class,()->calls.invoke(3,"sf_visible",new TypedPayload("application/json","{}"))).reason());
        verify(fixture.store,times(1)).materials(any(),any());verify(fixture.store,times(1)).admitProgramChild(any(),any());
    }

    @Test void loadedDeclarationsStillRecheckCurrentIamAndParentSendPermissionForEveryRpc() throws Exception {
        var fixture=new Fixture();fixture.manifestSource(10,ToolChoice.AUTO);var boundary=fixture.rpcBoundary();
        var parent=fixture.store.load(fixture.parent.runRef()).orElseThrow();
        when(parent.phase()).thenReturn(ExecutionStore.Phase.AWAITING_RESULT);when(parent.fence()).thenReturn(1L);
        when(parent.lease()).thenReturn(Optional.of(new LeaseFence(fixture.parent.runRef().tenantId(),"claim","worker",1,Instant.now().plusSeconds(60))));
        when(fixture.sourceAttempt.fence()).thenReturn(1L);
        var session=mock(org.apache.ibatis.session.SqlSession.class);
        when(fixture.store.participate(eq(session),eq(fixture.parent.runRef().tenantId()),any())).thenAnswer(call->((java.util.function.Supplier<?>)call.getArgument(2)).get());
        when(fixture.store.authorizeSend(any(),any())).thenReturn(true);
        var material=fixture.store.material(fixture.parent.runRef().tenantId(),AgentRuntimeExecutionCatalog.reference(fixture.parent.runRef())).orElseThrow();
        var current=fixture.catalogs.read(material,fixture.parent).tools().getFirst();
        when(fixture.capabilities.requireCurrent(eq(session),eq(fixture.parent),any())).thenReturn(current);
        when(fixture.identities.authorize(eq(session),any())).thenReturn(new IdentityPolicyPort.AuthorizationResult.Allowed(11,new ResultRef("audit")));
        doAnswer(call->{
            ExecutionStore.ProgramChildAdmission command=call.getArgument(0);JdbcExecutionStore.ProgramChildGuard guard=call.getArgument(1);
            guard.require(session,parent,command.source(),command.child(),false);throw boundary;
        }).when(fixture.store).admitProgramChild(any(),any());
        var calls=fixture.tools.bind(fixture.context());
        assertSame(boundary,assertThrows(IllegalStateException.class,()->calls.invoke(1,"sf_visible",new TypedPayload("application/json","{}"))));
        when(fixture.identities.authorize(eq(session),any())).thenReturn(new IdentityPolicyPort.AuthorizationResult.Denied("revoked",11,new ResultRef("audit-denied")));
        assertEquals("program_prepare_rejected",assertThrows(ExecutionStore.Rejection.class,()->calls.invoke(2,"sf_visible",new TypedPayload("application/json","{}"))).reason());
        when(fixture.store.authorizeSend(any(),any())).thenReturn(false);
        assertEquals("program_scope_closed",assertThrows(ExecutionStore.Rejection.class,()->calls.invoke(3,"sf_visible",new TypedPayload("application/json","{}"))).reason());
        verify(fixture.store,times(1)).materials(any(),any());verify(fixture.identities,times(2)).authorize(eq(session),any());
        verify(fixture.store,times(3)).authorizeSend(any(),any());verifyNoInteractions(fixture.providers);
    }

    @Test void anExistingUnknownChildIsNotDrivenAgainByTheScopedInvoker() throws Exception {
        var fixture=new Fixture();fixture.manifestSource(10,ToolChoice.AUTO);fixture.rpcBoundary();
        when(fixture.store.load(fixture.child.runRef())).thenReturn(Optional.of(fixture.run));
        when(fixture.run.phase()).thenReturn(ExecutionStore.Phase.UNCERTAIN);
        doReturn(new ExecutionStore.AdmissionResult(fixture.run,false)).when(fixture.store).admitProgramChild(any(),any());
        var calls=fixture.tools.bind(fixture.context());
        for(int repeat=0;repeat<2;repeat++)assertEquals("program_tool_outcome_unknown",
                assertThrows(ExecutionStore.Rejection.class,()->calls.invoke(1,"sf_visible",new TypedPayload("application/json","{}"))).reason());
        verify(fixture.store,times(1)).materials(any(),any());verify(fixture.worker,never()).driveProgramChild(any(),any(),any());
        verifyNoInteractions(fixture.providers);
    }

    @Test void childDispatchKeepsParentFactReadsInsideTheBorrowedTransactionScope() throws Exception {
        var fixture = new Fixture();fixture.resourceSource();
        var actor = fixture.context().callContext().identity();
        when(fixture.run.actorVerificationRef()).thenReturn(actor.verificationRef());
        when(fixture.identities.originalVerification(fixture.child.owner(), actor.verificationRef())).thenReturn(Optional.of(actor));
        when(fixture.bindings.find(fixture.parent.runRef())).thenReturn(Optional.of(mock(AgentRunBindings.Binding.class)));
        fixture.tools.begin(fixture.child.runRef());
        try {
            var configuration = fixture.tools.configuration(fixture.run);
            var request = fixture.codec.decodeTool(configuration.request().content());
            var prepared = new ExecutionStore.Prepared(ProgramToolProgram.action(fixture.child.runRef()), 1,
                    ExecutionStore.Kind.TOOL, request.target().handle().value(), configuration.capability(),
                    configuration.request(), "fixture-child-request");
            var session = mock(org.apache.ibatis.session.SqlSession.class);
            var parentRun = fixture.store.load(fixture.parent.runRef()).orElseThrow();
            when(parentRun.phase()).thenReturn(ExecutionStore.Phase.AWAITING_RESULT);
            when(parentRun.lease()).thenReturn(Optional.of(new LeaseFence(fixture.parent.runRef().tenantId(),
                    "claim", "worker", 1, Instant.now().plusSeconds(60))));
            when(fixture.store.lockBorrowed(session, fixture.parent.runRef())).thenReturn(parentRun);
            var borrowed = new java.util.concurrent.atomic.AtomicBoolean();
            when(fixture.store.participate(eq(session), eq(fixture.child.runRef().tenantId()), any())).thenAnswer(invocation -> {
                assertFalse(borrowed.getAndSet(true));
                try { return ((java.util.function.Supplier<?>) invocation.getArgument(2)).get(); }
                finally { borrowed.set(false); }
            });
            var reads = new java.util.concurrent.atomic.AtomicInteger();
            when(fixture.store.action(fixture.source.action())).thenAnswer(invocation -> {
                assertTrue(borrowed.get(), "父锁返回后的原Action读取仍必须借用相同派发事务");
                reads.incrementAndGet();
                return Optional.empty(); // 保留事实缺失时的原拒绝；本测试不制造授权成功。
            });
            assertFalse(fixture.tools.allowed(session, fixture.run, actor, prepared));
            assertEquals(1, reads.get());assertFalse(borrowed.get());
            verifyNoInteractions(fixture.providers);
        } finally { fixture.tools.end(fixture.child.runRef()); }
    }

    @Test void nestedProgramIsExplicitAdmissionRejectionBeforeChildRegistration() throws Exception {
        var fixture = new Fixture(); var schema = new TypedPayload("json-schema", "{}");
        var target = new FrozenToolTarget(new ToolHandle("tool:nested-program"), new CapabilityRef(CapabilityType.TOOL, "program", "1"),
                "deployment", 1, schema, schema, ToolEffect.NON_IDEMPOTENT_WRITE, false, Duration.ofSeconds(1), true,
                ToolConfirmationPolicy.NEVER, Set.of(), Set.of(), true);
        var binding = new ToolBinding(ToolBinding.ProviderType.PLATFORM, "run_code", "run_code");
        var entry = new SessionToolDirectory.Entry(target, binding, new SessionToolDirectory.Discovery(
                binding.serverIdentity(), binding.serverIdentity() + "/run_code", "run_code", Optional.empty(), "program"));
        fixture.modelSource(List.of(new ModelTool("sf_nested", target.handle().value(), "program", schema, true, ModelToolKind.TOOL_CALL, false)), ToolChoice.AUTO);
        when(fixture.store.load(fixture.child.runRef())).thenReturn(Optional.empty());
        var material = new ExecutionStore.Material(AgentRuntimeExecutionCatalog.reference(fixture.parent.runRef()), new TypedPayload("fixture.catalog", "{}"));
        when(fixture.store.material(fixture.parent.runRef().tenantId(), material.reference())).thenReturn(Optional.of(material));
        when(fixture.catalogs.read(material, fixture.parent)).thenReturn(new AgentRuntimeExecutionCatalog.FrozenCatalog(fixture.parent,
                List.of(entry), Map.of(target.handle().value(), "run_code"), List.of(), List.of(), Set.of(target.capability()), List.of(), PlanPort.State.absent(), com.skillforge.runtime.session.SessionCollaboration.Selection.inactive(), Optional.empty()));
        assertEquals("program_tool_requires_direct_call", assertThrows(com.skillforge.core.capability.connector.ProgramToolInvoker.AdmissionRejected.class,
                () -> fixture.tools.bind(fixture.context()).invoke( 1, "sf_nested", new TypedPayload("application/json", "{}"))).reasonCode());
        verify(fixture.store, never()).admitProgramChild(any(), any());
        verifyNoInteractions(fixture.providers);
    }

    @Test void firstProgramRpcSelectsExactCatalogTargetUsingTheOriginalVisibleModelName() throws Exception {
        var fixture = new Fixture();
        String opaque = "tool:clock:parent-input";
        var schema = new TypedPayload("json-schema", "{}");
        var target = new FrozenToolTarget(new ToolHandle(opaque), new CapabilityRef(CapabilityType.TOOL, "clock", "1"),
                "deployment", 1, schema, schema, ToolEffect.READ_ONLY, true, Duration.ofSeconds(1), true,
                ToolConfirmationPolicy.NEVER, Set.of(), Set.of(), true);
        var binding = new ToolBinding(ToolBinding.ProviderType.PLATFORM, "get_current_time", "get_current_time");
        var entry = new SessionToolDirectory.Entry(target, binding, new SessionToolDirectory.Discovery(
                binding.serverIdentity(), binding.serverIdentity() + "/get_current_time", "get_current_time", Optional.empty(), "clock"));
        fixture.modelSource(List.of(new ModelTool("sf_visible", opaque, "clock", schema, true, ModelToolKind.TOOL_CALL, false)), ToolChoice.AUTO);
        when(fixture.store.load(fixture.child.runRef())).thenReturn(Optional.empty());
        var catalogMaterial = new ExecutionStore.Material(AgentRuntimeExecutionCatalog.reference(fixture.parent.runRef()),
                new TypedPayload("fixture.catalog", "{}"));
        when(fixture.store.material(fixture.parent.runRef().tenantId(), catalogMaterial.reference())).thenReturn(Optional.of(catalogMaterial));
        var catalog = new AgentRuntimeExecutionCatalog.FrozenCatalog(fixture.parent, List.of(entry), Map.of(opaque, "tool:clock"),
                List.of(), List.of(), Set.of(target.capability()), List.of(), PlanPort.State.absent(), com.skillforge.runtime.session.SessionCollaboration.Selection.inactive(), Optional.empty());
        when(fixture.catalogs.read(catalogMaterial, fixture.parent)).thenReturn(catalog);
        when(fixture.store.originalInput(fixture.parent.runRef())).thenReturn(Optional.of(new ExecutionStore.Material(
                new ResultRef("parent-input"), fixture.codec.encodeInput(List.of(new ModelMessage("user", ModelRole.USER, "run")),
                        new TypedPayload("input.v1", "{}")))));
        var registration = mock(ExecutionBudgetPort.Registration.class);
        when(registration.root()).thenReturn(fixture.parent.runRef());
        when(registration.policy()).thenReturn(new ExecutionBudgetPort.Policy.Enforced(mock(ExecutionBudgetPort.Limits.class)));
        when(fixture.budgets.find(fixture.parent.runRef())).thenReturn(Optional.of(registration));
        var reached = new IllegalStateException("first-admission-boundary");
        when(fixture.store.admitProgramChild(any(), any())).thenThrow(reached);

        assertSame(reached, assertThrows(IllegalStateException.class, () -> fixture.tools.bind(fixture.context()).invoke( 1, "sf_visible", new TypedPayload("application/json", "{}"))));
        var admission = org.mockito.ArgumentCaptor.forClass(ExecutionStore.ProgramChildAdmission.class);
        verify(fixture.store).admitProgramChild(admission.capture(), any());
        var request = fixture.codec.decodeTool(admission.getValue().child().input().content());
        assertEquals(target, request.target());assertEquals(binding, request.binding());assertEquals("sf_visible", request.wireName());
        verify(fixture.catalogs).read(catalogMaterial, fixture.parent);
        verifyNoInteractions(fixture.providers);
    }

    @Test void actualProgramRpcMapsVisibleNameToExactChildAdmissionWithoutRebuildingTheModelDirectory() throws Exception {
        var fixture = new Fixture();
        fixture.modelSource(List.of(new ModelTool("sf_visible", "tool:clock", "clock",
                new TypedPayload("json-schema", "{}"), true, ModelToolKind.TOOL_CALL, false)), ToolChoice.AUTO);
        var registration = mock(ExecutionBudgetPort.Registration.class);
        var policy = new ExecutionBudgetPort.Policy.Enforced(mock(ExecutionBudgetPort.Limits.class));
        when(registration.root()).thenReturn(fixture.parent.runRef());when(registration.policy()).thenReturn(policy);
        when(fixture.budgets.find(fixture.parent.runRef())).thenReturn(Optional.of(registration));
        var reached = new IllegalStateException("admission-boundary");
        when(fixture.store.admitProgramChild(any(), any())).thenThrow(reached);

        assertSame(reached, assertThrows(IllegalStateException.class, () -> fixture.tools.bind(fixture.context()).invoke( 1, "sf_visible", new TypedPayload("application/json", "{}"))));
        var admission = org.mockito.ArgumentCaptor.forClass(ExecutionStore.ProgramChildAdmission.class);
        verify(fixture.store).admitProgramChild(admission.capture(), any());
        var request = fixture.codec.decodeTool(admission.getValue().child().input().content());
        assertEquals("tool:clock", request.target().handle().value());
        assertEquals("sf_visible", request.wireName());
        verifyNoInteractions(fixture.catalogs, fixture.providers);
    }

    @Test void actualProgramRpcRejectsUnknownNamesOpaqueAliasesAndControlDeclarationsBeforeAdmission() throws Exception {
        var fixture = new Fixture();
        var visible = new ModelTool("sf_visible", "tool:clock:parent-input", "clock",
                new TypedPayload("json-schema", "{}"), true, ModelToolKind.TOOL_CALL, false);
        fixture.modelSource(List.of(visible), ToolChoice.AUTO);
        for (String name : List.of("sf_unknown", "tool:clock:parent-input", "tool:clock")) {
            assertEquals("program_tool_not_available", assertThrows(com.skillforge.core.capability.connector.ProgramToolInvoker.AdmissionRejected.class,
                    () -> fixture.tools.bind(fixture.context()).invoke( 1, name, new TypedPayload("application/json", "{}"))).reasonCode());
        }
        fixture.modelSource(List.of(new ModelTool("sf_control", "runtime:agent-action", "control",
                new TypedPayload("json-schema", "{}"), true, ModelToolKind.AGENT_ACTION, false)), ToolChoice.AUTO);
        assertEquals("program_tool_not_available", assertThrows(com.skillforge.core.capability.connector.ProgramToolInvoker.AdmissionRejected.class,
                () -> fixture.tools.bind(fixture.context()).invoke( 1, "sf_control", new TypedPayload("application/json", "{}"))).reasonCode());
        fixture.modelSource(List.of(visible), ToolChoice.NONE);
        assertEquals("program_tool_requires_direct_call", assertThrows(com.skillforge.core.capability.connector.ProgramToolInvoker.AdmissionRejected.class,
                () -> fixture.tools.bind(fixture.context()).invoke( 1, "sf_visible", new TypedPayload("application/json", "{}"))).reasonCode());
        verify(fixture.store, never()).admitProgramChild(any(), any());
        verifyNoInteractions(fixture.providers);
    }

    @Test void storeRejectionIsNotConvertedIntoAnUndispatchedCapabilityResult() throws Exception {
        var fixture = new Fixture();
        fixture.modelSource(List.of(new ModelTool("sf_visible", "tool:clock", "clock",
                new TypedPayload("json-schema", "{}"), true, ModelToolKind.TOOL_CALL, false)), ToolChoice.AUTO);
        var failure = new ExecutionStore.Rejection("program_tool_not_available");
        when(fixture.store.actions(fixture.parent.runRef(), 0, 100)).thenThrow(failure);
        assertSame(failure, assertThrows(ExecutionStore.Rejection.class,
                () -> fixture.tools.bind(fixture.context()).invoke( 1, "sf_unknown", new TypedPayload("application/json", "{}"))));
        verify(fixture.store, never()).admitProgramChild(any(), any());
        verifyNoInteractions(fixture.providers);
    }

    @Test void ordinalSourceCreatesStableIndependentRunAndNeverWorkflowIdentity() {
        var fixture=new Fixture();var first=AgentRuntimeProgramTools.childIdentity(fixture.parent,fixture.source);
        assertEquals(first,AgentRuntimeProgramTools.childIdentity(fixture.parent,fixture.source));assertEquals(fixture.parent.owner(),first.owner());
        assertEquals(fixture.parent.trace().rootInputId(),first.trace().rootInputId());assertNotEquals(fixture.parent.runRef(),first.runRef());
        assertTrue(first.runRef().runId().startsWith("program-tool:"));assertFalse(first.runRef().runId().contains("workflow"));
        assertNotEquals(first,AgentRuntimeProgramTools.childIdentity(fixture.parent,new ExecutionStore.ProgramChildSource(fixture.source.action(),fixture.source.attemptId(),2)));
    }
    @Test void configurationRequiresExactChildIdentityAndDurableSourceBeforeInlineOrProvider() throws Exception {
        var fixture=new Fixture();fixture.configuration();var parent=mock(ExecutionStore.Run.class);when(parent.identity()).thenReturn(fixture.parent);
        when(fixture.store.programChildSource(fixture.child.runRef(),fixture.parent.runRef(),fixture.source)).thenReturn(false);
        assertThrows(ExecutionStore.Rejection.class,()->fixture.tools.requireInline(parent,fixture.source,fixture.run));
        when(fixture.store.programChildSource(fixture.child.runRef(),fixture.parent.runRef(),fixture.source)).thenReturn(true);
        assertDoesNotThrow(()->fixture.tools.requireInline(parent,fixture.source,fixture.run));
        assertThrows(ExecutionStore.Rejection.class,()->fixture.tools.requireInline(parent,new ExecutionStore.ProgramChildSource(fixture.source.action(),"other-attempt",1),fixture.run));
    }
    @Test void recursionAndExpiredParentRejectBeforeAnyStoreOrProviderWork() {
        var fixture=new Fixture();var actor=new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(fixture.parent.runRef().tenantId(),fixture.parent.owner().requireUser()),fixture.parent.owner(),Set.of(),1,new ResultRef("actor"));
        var call=new PortCallContext(fixture.parent,actor,new InvocationControl(Instant.now().plusSeconds(60),()->false));
        var recursive=new PlatformToolExecutionContext(call,"action","attempt",true,false);
        assertEquals("program_recursion_forbidden",assertThrows(ExecutionStore.Rejection.class,()->fixture.tools.bind(recursive).invoke(1,"tool:clock",new TypedPayload("application/json","{}"))).reason());
        var expired=new PlatformToolExecutionContext(new PortCallContext(fixture.parent,actor,new InvocationControl(Instant.now().minusSeconds(1),()->false)),"action","attempt");
        assertEquals("program_scope_closed",assertThrows(ExecutionStore.Rejection.class,()->fixture.tools.bind(expired).invoke(1,"tool:clock",new TypedPayload("application/json","{}"))).reason());
        verifyNoInteractions(fixture.store,fixture.providers);
    }
    @Test void parentControlScopeAlwaysReleasesOnFailureAndCannotBeBoundTwice() {
        var fixture=new Fixture();var context=mock(PlatformToolExecutionContext.class);
        assertThrows(IllegalStateException.class,()->fixture.tools.withParentControl(fixture.child.runRef(),context,()->{throw new IllegalStateException("stop");}));
        assertEquals("released",fixture.tools.withParentControl(fixture.child.runRef(),context,()->"released"));
        assertThrows(ExecutionStore.Rejection.class,()->fixture.tools.withParentControl(fixture.child.runRef(),context,
                ()->fixture.tools.withParentControl(fixture.child.runRef(),context,()->"nested")));
    }
    @Test void resourceOriginProvesDurableProgramPurposeAndOriginalParentToolWithoutCreatingDelegateBinding() throws Exception {
        var fixture=new Fixture();fixture.resourceSource();assertEquals(fixture.parent,fixture.tools.resourceOrigin(fixture.child));
        when(fixture.sourceAttempt.request()).thenReturn(new ResultRef("different-original-request"));
        assertThrows(ExecutionStore.Rejection.class,()->fixture.tools.resourceOrigin(fixture.child));
        when(fixture.sourceAttempt.request()).thenReturn(new ResultRef("parent-request"));
        when(fixture.store.programChildSource(fixture.child.runRef(),fixture.parent.runRef(),fixture.source)).thenReturn(false);
        assertThrows(ExecutionStore.Rejection.class,()->fixture.tools.resourceOrigin(fixture.child));
    }
    @Test void resourceOriginRejectsMissingChildInputAndCrossOwnerParentWithoutOrdinaryFallback() throws Exception {
        var fixture=new Fixture();fixture.resourceSource();when(fixture.store.originalInput(fixture.child.runRef())).thenReturn(Optional.empty());
        assertThrows(ExecutionStore.Rejection.class,()->fixture.tools.resourceOrigin(fixture.child));
        var ordinary=mock(ExecutionStore.Run.class);when(ordinary.identity()).thenReturn(fixture.parent);when(ordinary.configuration()).thenReturn(new ResultRef("ordinary-profile"));
        when(fixture.store.load(fixture.parent.runRef())).thenReturn(Optional.of(ordinary));assertEquals(fixture.parent,fixture.tools.resourceOrigin(fixture.parent));
        var other=new RunIdentity(SessionOwner.user(fixture.parent.owner().sessionRef(),fixture.parent.agentId(),new EndUserId(10)), new AgentId((SessionOwner.user(fixture.parent.owner().sessionRef(),fixture.parent.agentId(),new EndUserId(10))).target().id()), fixture.parent.causationInputId(), fixture.parent.runRef(), fixture.parent.trace(), fixture.parent.causationIdentityRef(), new RunRequester.EndUser((SessionOwner.user(fixture.parent.owner().sessionRef(),fixture.parent.agentId(),new EndUserId(10))).requireUser()), java.util.Optional.empty(), new RunOriginRef((SessionOwner.user(fixture.parent.owner().sessionRef(),fixture.parent.agentId(),new EndUserId(10))).sessionRef().tenantId(), "input:" + (fixture.parent.causationInputId()).value()));
        when(ordinary.identity()).thenReturn(other);assertThrows(ExecutionStore.Rejection.class,()->fixture.tools.resourceOrigin(fixture.parent));
    }

    /** 已领取的普通Run直接复用本次读取；Program仍核原父链和实际请求，不借复用跳过硬证明。 */
    @Test void loadedResourceOriginAvoidsFirstRunReloadWhileKeepingProgramProof() throws Exception {
        var fixture=new Fixture();var ordinary=mock(ExecutionStore.Run.class);
        when(ordinary.identity()).thenReturn(fixture.parent);when(ordinary.configuration()).thenReturn(new ResultRef("ordinary-profile"));
        assertEquals(fixture.parent,fixture.tools.resourceOrigin(ordinary));verifyNoInteractions(fixture.store);
        fixture.resourceSource();clearInvocations(fixture.store);
        assertEquals(fixture.parent,fixture.tools.resourceOrigin(fixture.run));
        verify(fixture.store,never()).load(fixture.child.runRef());
        verify(fixture.store,times(1)).load(fixture.parent.runRef());
        verify(fixture.store).programChildSource(fixture.child.runRef(),fixture.parent.runRef(),fixture.source);
        when(fixture.sourceAttempt.request()).thenReturn(new ResultRef("wrong-original-request"));
        assertThrows(ExecutionStore.Rejection.class,()->fixture.tools.resourceOrigin(fixture.run));
    }
    private static final class Fixture {
        final JdbcExecutionStore store=mock(JdbcExecutionStore.class);final RegisteredExecutionProviderCalls providers=mock(RegisteredExecutionProviderCalls.class);
        final ExecutionBudgetPort budgets=mock(ExecutionBudgetPort.class);
        final AgentRuntimeExecutionCatalog catalogs=mock(AgentRuntimeExecutionCatalog.class);
        final JdbcIdentityPolicyPort identities=mock(JdbcIdentityPolicyPort.class);
        final AgentRunBindings bindings=mock(AgentRunBindings.class);
        final AgentRuntimePublishedCapabilities capabilities=mock(AgentRuntimePublishedCapabilities.class);
        final AgentRuntimeExecutionWorker worker=mock(AgentRuntimeExecutionWorker.class);
        // 与生产内部快照相同的多态身份编码，必须写出 subject/requester 的 kind。
        final ObjectMapper json=SessionSubjectJson.configure(new ObjectMapper().findAndRegisterModules())
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        final RunIdentity parent;final ExecutionStore.ProgramChildSource source;final RunIdentity child;final ExecutionStore.Run run=mock(ExecutionStore.Run.class);
        final AgentExecutionCodec codec=new AgentExecutionCodec(new CodecLimits(1_000_000,100_000,128));final AgentRuntimeProgramTools tools;
        ExecutionStore.Material childRequest;ExecutionStore.Attempt sourceAttempt;
        @SuppressWarnings("unchecked") Fixture() {
            var tenant=new TenantId("1");var input=new InputId("parent-input");var owner=SessionOwner.user(new SessionRef(tenant,"session"),new AgentId("7"),new EndUserId(9));
            parent=new RunIdentity(owner, new AgentId((owner).target().id()), input, new RunRef(tenant,"parent"), new TraceEnvelope("trace","span",input,Optional.empty()), new ResultRef("identity"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
            source=new ExecutionStore.ProgramChildSource(new ActionRef(tenant,"parent-code-action"),"parent-code-attempt",1);child=AgentRuntimeProgramTools.childIdentity(parent,source);
            when(run.identity()).thenReturn(child);when(run.configuration()).thenReturn(AgentRuntimeProgramTools.reference(child.runRef()));
            var workerProvider=(ObjectProvider<AgentRuntimeExecutionWorker>)mock(ObjectProvider.class);
            when(workerProvider.getObject()).thenReturn(worker);
            tools=new AgentRuntimeProgramTools(store,budgets,identities,catalogs,
                    capabilities,bindings,mock(JdbcMcpBindings.class),providers,
                    workerProvider,value->true,json,new AgentRuntimeProperties());
        }
        void configuration() throws Exception {configuration("parent-fingerprint");}
        void configuration(String parentFingerprint) throws Exception {
            var target=new FrozenToolTarget(new ToolHandle("tool:clock"),new CapabilityRef(CapabilityType.TOOL,"clock","1"),"deployment",1,new TypedPayload("json-schema","{}"),new TypedPayload("json-schema","{}"),ToolEffect.READ_ONLY,true,Duration.ofSeconds(1),true,ToolConfirmationPolicy.NEVER,Set.of(),Set.of(),true);
            var request=new AgentExecutionCodec.ToolRequest(target,new ToolBinding(ToolBinding.ProviderType.PLATFORM,"get_current_time","get_current_time"),new TypedPayload("application/json","{}"),"program-call","sf_visible");
            var material=new ExecutionStore.Material(new ResultRef("program-tool-request:"+child.runRef().runId()),codec.encodeTool(request));childRequest=material;
            var frozen=new AgentRuntimeProgramTools.Frozen(parent,child,source,new ResultRef("parent-config"),new ResultRef("parent-request"),parentFingerprint,new ResultRef("parent-capability"),material,Instant.now().plusSeconds(60),Optional.empty());
            var configuration=new ExecutionStore.Material(run.configuration(),new TypedPayload(AgentRuntimeProgramTools.SCHEMA,json.writeValueAsString(frozen)));
            when(store.material(child.runRef().tenantId(),configuration.reference())).thenReturn(Optional.of(configuration));
        }
        void resourceSource() throws Exception {
            var target=new FrozenToolTarget(new ToolHandle("tool:run-code"),new CapabilityRef(CapabilityType.TOOL,"run-code","1"),"code-deployment",1,new TypedPayload("json-schema","{}"),new TypedPayload("json-schema","{}"),ToolEffect.NON_IDEMPOTENT_WRITE,false,Duration.ofSeconds(30),true,ToolConfirmationPolicy.NEVER,Set.of(),Set.of(),true);
            var sourceRequest=new AgentExecutionCodec.ToolRequest(target,new ToolBinding(ToolBinding.ProviderType.PLATFORM,"run_code","run_code"),new TypedPayload("application/json","{}"),"parent-call","run_code");
            var content=codec.encodeTool(sourceRequest);var fingerprint=AgentExecutionCodec.digest(content.schemaId()+"\0"+content.canonicalJson());configuration(fingerprint);
            var parentRun=mock(ExecutionStore.Run.class);when(parentRun.identity()).thenReturn(parent);when(parentRun.configuration()).thenReturn(new ResultRef("parent-config"));
            when(parentRun.actorVerificationRef()).thenReturn(new ResultRef("actor"));
            when(store.load(child.runRef())).thenReturn(Optional.of(run));when(store.load(parent.runRef())).thenReturn(Optional.of(parentRun));
            when(store.originalInput(child.runRef())).thenReturn(Optional.of(childRequest));when(store.programChildSource(child.runRef(),parent.runRef(),source)).thenReturn(true);
            var action=mock(ExecutionStore.Action.class);when(store.action(source.action())).thenReturn(Optional.of(action));when(action.reference()).thenReturn(source.action());when(action.run()).thenReturn(parent.runRef());
            when(action.currentAttempt()).thenReturn(source.attemptId());when(action.ordinal()).thenReturn(2L);
            when(action.target()).thenReturn(target.handle().value());
            when(action.kind()).thenReturn(ExecutionStore.Kind.TOOL);when(action.capability()).thenReturn(new ResultRef("parent-capability"));when(action.fingerprint()).thenReturn(fingerprint);
            sourceAttempt=mock(ExecutionStore.Attempt.class);when(store.attempt(parent.runRef(),source.attemptId())).thenReturn(Optional.of(sourceAttempt));
            when(sourceAttempt.id()).thenReturn(source.attemptId());when(sourceAttempt.run()).thenReturn(parent.runRef());when(sourceAttempt.action()).thenReturn(source.action());when(sourceAttempt.request()).thenReturn(new ResultRef("parent-request"));
            when(store.material(parent.runRef().tenantId(),new ResultRef("parent-request"))).thenReturn(Optional.of(new ExecutionStore.Material(new ResultRef("parent-request"),content)));
        }
        PlatformToolExecutionContext context() {
            var actor = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(parent.runRef().tenantId(), parent.owner().requireUser()),
                    parent.owner(), Set.of(), 1, new ResultRef("actor"));
            return new PlatformToolExecutionContext(new PortCallContext(parent, actor,
                    new InvocationControl(Instant.now().plusSeconds(60), () -> false)), source.action().actionId(), source.attemptId());
        }
        AgentExecutionCodec.ModelRequestMaterials manifestSource(int count,ToolChoice choice) throws Exception {
            var declarations=List.of(new ModelTool("sf_visible","tool:clock","clock",new TypedPayload("json-schema","{}"),true,ModelToolKind.TOOL_CALL,false));
            modelSource(declarations,choice);
            var target=new FrozenModelTarget(new ModelBindingRef(parent.runRef().tenantId(),"model",1),"model",
                    new ModelCapabilitySnapshot(Set.of(ResponseFormatKind.TEXT),true,true,new ResultRef("capability")),Duration.ofSeconds(30),new ResultRef("resolution"));
            var messages=new ArrayList<ModelMessage>();for(int index=0;index<count;index++)messages.add(new ModelMessage("m"+index,ModelRole.USER,"large history "+"x".repeat(1024)));
            var request=new FrozenModelRequest(new RequestedModelStrategy("strategy","1"),target,messages,declarations,choice,
                    new ResponseContract(new ResponseFormat.NativeConversation(),EnforcementLevel.STRICT,"supported"),
                    new GenerationOptions(OptionalInt.empty(),OptionalDouble.empty()),ReasoningPolicy.DEFAULT);
            var bundle=codec.encodeModelRequestMaterials(new AgentExecutionCodec.ModelCallRequest(request,List.of(),0,Optional.empty()));
            var attempt=store.attempt(parent.runRef(),"model-attempt").orElseThrow();when(attempt.request()).thenReturn(bundle.root().reference());
            when(store.material(parent.runRef().tenantId(),bundle.root().reference())).thenReturn(Optional.of(bundle.root()));
            when(store.materials(parent.runRef().tenantId(),codec.modelRequestReferences(bundle.root().content()))).thenReturn(bundle.parts().stream().map(Optional::of).toList());
            return bundle;
        }
        IllegalStateException rpcBoundary() {
            when(store.load(argThat(ref->ref!=null&&!ref.equals(parent.runRef())))).thenReturn(Optional.empty());
            var schema=new TypedPayload("json-schema","{}");
            var target=new FrozenToolTarget(new ToolHandle("tool:clock"),new CapabilityRef(CapabilityType.TOOL,"clock","1"),"deployment",1,schema,schema,ToolEffect.READ_ONLY,true,Duration.ofSeconds(1),true,ToolConfirmationPolicy.NEVER,Set.of(),Set.of(),true);
            var binding=new ToolBinding(ToolBinding.ProviderType.PLATFORM,"get_current_time","get_current_time");
            var entry=new SessionToolDirectory.Entry(target,binding,new SessionToolDirectory.Discovery(binding.serverIdentity(),binding.serverIdentity()+"/get_current_time","get_current_time",Optional.empty(),"clock"));
            var material=new ExecutionStore.Material(AgentRuntimeExecutionCatalog.reference(parent.runRef()),new TypedPayload("fixture.catalog","{}"));
            when(store.material(parent.runRef().tenantId(),material.reference())).thenReturn(Optional.of(material));
            when(catalogs.read(material,parent)).thenReturn(new AgentRuntimeExecutionCatalog.FrozenCatalog(parent,List.of(entry),Map.of("tool:clock","clock"),List.of(),List.of(),Set.of(target.capability()),List.of(),PlanPort.State.absent(),com.skillforge.runtime.session.SessionCollaboration.Selection.inactive(),Optional.empty()));
            when(store.originalInput(parent.runRef())).thenReturn(Optional.of(new ExecutionStore.Material(new ResultRef("parent-input"),codec.encodeInput(List.of(new ModelMessage("user",ModelRole.USER,"run")),new TypedPayload("input.v1","{}")))));
            var registration=mock(ExecutionBudgetPort.Registration.class);when(registration.root()).thenReturn(parent.runRef());
            when(registration.policy()).thenReturn(new ExecutionBudgetPort.Policy.Enforced(mock(ExecutionBudgetPort.Limits.class)));when(budgets.find(parent.runRef())).thenReturn(Optional.of(registration));
            var boundary=new IllegalStateException("admission-boundary");when(store.admitProgramChild(any(),any())).thenThrow(boundary);return boundary;
        }
        void modelSource(List<ModelTool> declarations, ToolChoice choice) throws Exception {
            resourceSource();
            var target = new FrozenModelTarget(new ModelBindingRef(parent.runRef().tenantId(), "model", 1), "model",
                    new ModelCapabilitySnapshot(Set.of(ResponseFormatKind.TEXT), true, true, new ResultRef("capability")),
                    Duration.ofSeconds(30), new ResultRef("resolution"));
            var request = new FrozenModelRequest(new RequestedModelStrategy("strategy", "1"), target,
                    List.of(new ModelMessage("user", ModelRole.USER, "run")), declarations, choice,
                    new ResponseContract(new ResponseFormat.NativeConversation(), EnforcementLevel.STRICT, "supported"),
                    new GenerationOptions(OptionalInt.empty(), OptionalDouble.empty()), ReasoningPolicy.DEFAULT);
            var action = mock(ExecutionStore.Action.class);
            var reference = new ActionRef(parent.runRef().tenantId(), "model-action");
            when(action.reference()).thenReturn(reference);when(action.run()).thenReturn(parent.runRef());
            when(action.ordinal()).thenReturn(1L);when(action.kind()).thenReturn(ExecutionStore.Kind.MODEL);
            when(action.selectedAttempt()).thenReturn(Optional.of("model-attempt"));
            var attempt = mock(ExecutionStore.Attempt.class);
            when(attempt.run()).thenReturn(parent.runRef());when(attempt.action()).thenReturn(reference);
            when(attempt.adopted()).thenReturn(true);when(attempt.result()).thenReturn(Optional.of(new ResultRef("model-result")));
            when(attempt.request()).thenReturn(new ResultRef("model-request"));
            when(store.actions(parent.runRef(), 0, 100)).thenReturn(List.of(action));
            when(store.attempt(parent.runRef(), "model-attempt")).thenReturn(Optional.of(attempt));
            when(store.material(parent.runRef().tenantId(), new ResultRef("model-request"))).thenReturn(Optional.of(
                    new ExecutionStore.Material(new ResultRef("model-request"), codec.encodeModelRequest(
                            new AgentExecutionCodec.ModelCallRequest(request, List.of(), 0, Optional.empty())))));
        }
    }
}
