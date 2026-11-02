package com.skillforge.institution.artifact;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ArtifactClosingBudgetTest {
    private final PlatformToolExecutionContext context = context();

    @Test void closingPlanContainsOnlyLocalReinspectionNotSandboxTransfer() {
        var plan = new ArtifactClosingBudget().plan(context.run(), List.of());
        assertEquals(ArtifactPublicInspection.MAX_SCAN_DURATION, plan.localProcessing());
        assertEquals(120, plan.localProcessing().toSeconds());
        assertEquals(2, plan.modelCalls());
        assertEquals(1, plan.finalizationCalls());
    }

    @Test void planLeavesNextOrdinaryGenerationOpportunityUnderActualConsumerRule() {
        var plan = new ArtifactClosingBudget().plan(context.run(), List.of());
        var spec = new ExecutionBudgetPort.ClosingSpec(plan.modelCalls(),plan.finalizationCalls(),
                plan.localProcessing().toSeconds(),plan.receiptInputUpperBound(),plan.facts(),Set.of());
        // consumer: used + next ordinary model timeout + protected closing; 准备继续耗时也不会吃满边界。
        long usedAtAdmission=85_208;
        long preparationElapsed=5_114;
        long required=(120+spec.activeSeconds(Duration.ofSeconds(120)))*1000;
        assertTrue(usedAtAdmission+preparationElapsed+required<=600_000);
        assertFalse(usedAtAdmission+preparationElapsed+(120+240+153)*1000<=600_000,
                "adaptive transfer reservation consumed all ordinary-phase headroom");
    }

    @Test void preservesClosingFactsAndReceiptBoundWithoutChangingCallCount() {
        var facts=List.of("confirmed-output-plan");
        var plan=new ArtifactClosingBudget().plan(context.run(),facts);
        assertEquals(facts,plan.facts());
        assertEquals(32_768,plan.receiptInputUpperBound());
        assertEquals(240,new ExecutionBudgetPort.ClosingSpec(plan.modelCalls(),plan.finalizationCalls(),
                plan.localProcessing().toSeconds(),plan.receiptInputUpperBound(),plan.facts(),Set.of())
                .activeSeconds(Duration.ofSeconds(120)));
    }

    @Test void deadlineCopyPreservesIndependentProgramAndWorkflowOrigins() {
        var scoped = new PlatformToolExecutionContext(context.callContext(), context.invocationId(), context.attemptId(), true, true);
        var copied = ArtifactClosingBudget.deliveryContext(scoped, Duration.ofSeconds(10));
        assertTrue(copied.programChild());assertTrue(copied.workflowNode());
        assertEquals(scoped.invocationId(), copied.invocationId());assertEquals(scoped.attemptId(), copied.attemptId());
    }

    @Test void finalizeToolPassesOneSharedInspectionDeadlineToActualService() throws Exception {
        var original=context.callContext();
        var extended=new PlatformToolExecutionContext(new PortCallContext(original.runIdentity(),original.identity(),
                new InvocationControl(Instant.now().plusSeconds(600),original.control().cancellationSignal())),
                context.invocationId(),context.attemptId());
        var service=mock(DeliverableService.class);
        var captured=new AtomicReference<PlatformToolExecutionContext>();
        when(service.finalizeOutputs(any(),any())).thenAnswer(call->{
            captured.set(call.getArgument(1));return Map.of("success",true);
        });
        Instant before=Instant.now();
        var tool=new FinalizeTaskOutputsTool(service,new ObjectMapper());
        assertEquals(true,tool.execute(Map.of("deliverables",List.of()),extended).get("success"));
        var bounded=captured.get();
        assertNotNull(bounded);
        assertFalse(bounded.deadlineAt().isBefore(before.plusSeconds(120)));
        assertTrue(bounded.deadlineAt().isBefore(Instant.now().plusSeconds(121)));
        assertTrue(bounded.deadlineAt().isBefore(extended.deadlineAt()));
        assertEquals(extended.run(),bounded.run());
        assertSame(extended.cancellationSignal(),bounded.cancellationSignal());
    }

    @Test void finalizationNeverExtendsAnEarlierCallerDeadline() {
        assertEquals(context.deadlineAt(),ArtifactClosingBudget.finalizationContext(context).deadlineAt());
    }

    @Test void deliveryUsesItsOwnFrozenTransferWindowRatherThanClosingOrModelBudget() {
        Instant before=Instant.now();
        var bounded=ArtifactClosingBudget.deliveryContext(context,Duration.ofSeconds(3));
        assertFalse(bounded.deadlineAt().isBefore(before.plusSeconds(3)));
        assertTrue(bounded.deadlineAt().isBefore(Instant.now().plusSeconds(4)));
        assertTrue(bounded.deadlineAt().isBefore(context.deadlineAt()));
    }

    @Test void deliveryKeepsEarlierDeadlineIdentityAndCancellation() {
        var bounded=ArtifactClosingBudget.deliveryContext(context,Duration.ofSeconds(900));
        assertEquals(context.deadlineAt(),bounded.deadlineAt());
        assertSame(context.run(),bounded.run());
        assertSame(context.cancellationSignal(),bounded.cancellationSignal());
        assertEquals(context.invocationId(),bounded.invocationId());
        assertEquals(context.attemptId(),bounded.attemptId());
    }

    @Test void missingTrustedBudgetStillRejectsPlanAdmissionAsTechnicalFailure() {
        var error=assertThrows(IllegalStateException.class,()->new ArtifactClosingBudget().admit(context.run()));
        assertEquals("output_budget_scope_required",error.getMessage());
    }

    /** 候选入口在任一阶段都声明完整未来需求，不能借剩余预算接口缩减生成义务。 */
    @Test void candidateAdmissionRetainsTheCompleteFuturePlan() {
        var budgets=budgets();
        try(var ignored=ExecutionBudgetScope.open(binding(budgets))) {
            new ArtifactClosingBudget().admit(context.run());
        }
        var complete=new ExecutionBudgetPort.Closing(new ExecutionBudgetPort.Resources(2,0,1,0,32968,40,33008),240);
        verify(budgets).admitClosing(context.run().runRef(),new ExecutionBudgetPort.ClosingAdmission(complete,complete));
    }

    /** 当前冻结模型与工具已经计量；后续只有核验窗口、最终模型及它读取的新增回执。 */
    @Test void finalizationAdmissionExplicitlySizesOnlyFutureClosingWork() {
        var budgets=budgets();
        try(var ignored=ExecutionBudgetScope.open(binding(budgets))) {
            new ArtifactClosingBudget().admitFinalization(context.run());
        }
        var complete=new ExecutionBudgetPort.Closing(new ExecutionBudgetPort.Resources(2,0,1,0,32968,40,33008),240);
        var remaining=new ExecutionBudgetPort.Closing(new ExecutionBudgetPort.Resources(1,0,0,0,32868,20,32888),240);
        verify(budgets).admitClosing(context.run().runRef(),new ExecutionBudgetPort.ClosingAdmission(complete,remaining));
        verify(budgets,never()).updateClosing(any(),any());
    }

    @Test void finalizationMapsOnlyConfirmedBudgetRefusalToBusinessFeedback() {
        var budgets=budgets();
        doThrow(new ExecutionBudgetPort.ClosingInsufficient(ExecutionBudgetPort.Phase.BUSINESS,List.of(
                new ExecutionBudgetPort.ClosingShortfall(ExecutionBudgetPort.BalanceScope.ROOT,"ACTIVE_MILLIS",240_000,100_000,140_000,0,0,100_000))))
                .when(budgets).admitClosing(any(),any());
        try(var ignored=ExecutionBudgetScope.open(binding(budgets))) {
            assertEquals(ArtifactClosingBudget.REJECTION_CODE,assertThrows(IllegalArgumentException.class,
                    ()->new ArtifactClosingBudget().admitFinalization(context.run())).getMessage());
        }
        doThrow(new IllegalStateException("budget_storage_failed")).when(budgets).admitClosing(any(),any());
        try(var ignored=ExecutionBudgetScope.open(binding(budgets))) {
            assertEquals("budget_storage_failed",assertThrows(IllegalStateException.class,
                    ()->new ArtifactClosingBudget().admitFinalization(context.run())).getMessage());
        }
    }

    /** 快照只提供可信身份与主 Run 角色；当前阶段由生产账本在原子准入时读取。 */
    private ExecutionBudgetPort budgets() {
        var budgets=mock(ExecutionBudgetPort.class);
        var capacity=new ExecutionBudgetPort.Resources(8,4,8,4,100_000,100_000,200_000);
        var registration=new ExecutionBudgetPort.Registration(context.run().runRef(),context.run().runRef(),
                ExecutionBudgetPort.Role.PRIMARY,new ExecutionBudgetPort.Policy.Enforced(new ExecutionBudgetPort.Limits(ExecutionBudgetPort.Scenario.STANDARD,
                capacity,capacity,capacity,600,600,"artifact-closing-test")));
        when(budgets.snapshot(context.run().runRef())).thenReturn(new ExecutionBudgetPort.Snapshot(registration,
                ExecutionBudgetPort.Resources.ZERO,ExecutionBudgetPort.Resources.ZERO,ExecutionBudgetPort.Resources.ZERO,
                ExecutionBudgetPort.Resources.ZERO,ExecutionBudgetPort.Closing.ZERO,ExecutionBudgetPort.Phase.BUSINESS,0,0,0));
        return budgets;
    }

    /** 冻结模型计量与原请求相同，公开回执上界由成果入口另行显式增加。 */
    private ExecutionBudgetScope.Binding binding(ExecutionBudgetPort budgets) {
        return new ExecutionBudgetScope.Binding(budgets,context.run().runRef(),()->ExecutionBudgetPort.ClosingSpec.TEXT,
                Optional::empty,()->Optional.of(new ExecutionBudgetScope.ClosingSizing(
                ExecutionBudgetPort.Category.BUSINESS_MODEL,100,20,Duration.ofSeconds(120))),()->Duration.ZERO);
    }

    private static PlatformToolExecutionContext context() {
        var tenant = new TenantId("1"); var input = new InputId("input");
        var owner = SessionOwner.user(new SessionRef(tenant, "session"), new AgentId("7"), new EndUserId(9));
        var run = new RunIdentity(owner, new AgentId((owner).target().id()), input, new RunRef(tenant, "run"), new TraceEnvelope("trace", "span", input, Optional.empty()), new ResultRef("result"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
        var identity = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(tenant, owner.requireUser()), owner,
                Set.of(), 1, new ResultRef("verification"));
        return new PlatformToolExecutionContext(new PortCallContext(run, identity,
                new InvocationControl(Instant.now().plusSeconds(5), () -> false)), "invocation", "attempt");
    }
}
