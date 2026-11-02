package com.skillforge.runtime.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.RunStatus;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ActionRef;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.port.LeaseFence;
import com.skillforge.runtime.session.ExecutionCoordinator.Adoption;
import com.skillforge.runtime.session.ExecutionCoordinator.CapabilityResult;
import com.skillforge.runtime.session.ExecutionCoordinator.Step;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** 协调算法夹具只验证提交顺序、加载次数和无重放；不证明真实 PostgreSQL、HTTP 或权限链通过。 */
class ExecutionCoordinatorTest {
    /** 只读材料严格匹配已提交context；离开本drive作用域后不保留可借用的活跃状态。 */
    @Test void exposesOnlyExactCommittedMaterialsDuringActiveDriveAndRemovesThemOnExit() {
        var fixture = new Fixture(); fixture.checkActive = true;
        assertTrue(fixture.coordinator.activeMaterials(SessionFixtures.IDENTITY, new ResultRef("context-0")).isEmpty());
        var result = fixture.coordinator.drive(fixture.claim);
        assertEquals(ExecutionCoordinator.Stop.FINISHED, result.stop());
        assertEquals(2, fixture.invocations);
        assertTrue(fixture.coordinator.activeMaterials(SessionFixtures.IDENTITY, new ResultRef("context-2")).isEmpty());
    }
    @Test void singleAndBatchAuthorizationRefusalsCommitTheProgramFailureWithoutSending() {
        for (boolean batch : List.of(false, true)) {
            var f = new Fixture(); f.batch = batch; f.store.dispatchRejection = "execution_dispatch_unauthorized";
            var result = f.coordinator.drive(f.claim);
            assertEquals(ExecutionCoordinator.Stop.FINISHED, result.stop()); assertEquals(RunStatus.FAILED, result.run().orElseThrow().status());
            assertEquals(0, f.invocations); assertTrue(f.store.attempts.isEmpty()); assertTrue(f.store.completions.isEmpty());
            assertEquals(1, f.store.finishes.size()); assertEquals("dispatch-denied", f.store.finishes.getFirst().events().getFirst().type());
            assertEquals(1, f.nexts);
        }
    }

    @Test void technicalDispatchExceptionPropagatesWithoutInventingTerminalFailure() {
        var f = new Fixture(); var failure = new IllegalStateException("database_transport_failed"); f.store.dispatchFailure = failure;
        assertSame(failure, assertThrows(IllegalStateException.class, () -> f.coordinator.drive(f.claim)));
        assertTrue(f.store.finishes.isEmpty()); assertTrue(f.store.attempts.isEmpty()); assertEquals(0, f.invocations);
        assertEquals(RunStatus.RUNNING, f.store.current.status());
    }

    @Test void pausedOwnershipRevisionAndUnknownDispatchRefusalsNeverBecomeTerminal() {
        for (boolean batch : List.of(false, true)) for (String reason : List.of("execution_dispatch_paused", "execution_lease_or_revision_changed", "execution_dispatch_actor_changed", "unknown_store_rejection")) {
            var f = new Fixture(); f.batch = batch; f.store.dispatchRejection = reason;
            var result = f.coordinator.drive(f.claim);
            assertEquals(ExecutionCoordinator.Stop.REJECTED, result.stop()); assertEquals(reason, result.reason());
            assertEquals(RunStatus.RUNNING, result.run().orElseThrow().status()); assertTrue(f.store.finishes.isEmpty()); assertEquals(0, f.invocations);
        }
    }

    /** 两个动作连续推进只领取和打开一次，下一动作读取的反馈已由结果事务保存。 */
    @Test
    void drivesTwoActionsFromReturnedRunsWithoutReloadingOrReopeningMaterials() {
        var fixture = new Fixture();
        fixture.checkReentrant = true;
        var result = fixture.coordinator.drive(fixture.claim);
        assertEquals(ExecutionCoordinator.Stop.FINISHED, result.stop());
        assertEquals(RunStatus.COMPLETED, result.run().orElseThrow().status());
        assertEquals(1, fixture.store.claims);
        assertEquals(0, fixture.store.loads);
        assertEquals(1, fixture.store.materialReads);
        assertEquals(1, fixture.opens);
        assertEquals(2, fixture.invocations);
        assertEquals(2, fixture.store.completions.size());
        assertEquals(List.of("open", "next-0", "invoke-1", "adopt-1", "next-1", "invoke-2", "adopt-2", "next-2"), fixture.steps);
        assertEquals(new ResultRef("context-2"), result.run().orElseThrow().progress().context());
    }

    /** 重新领取发现派发后无确定结果，只返回 UNKNOWN，既不规划也不调用能力。 */
    @Test
    void recoveredAwaitingResultDoesNotReplayAnyExternalCall() {
        var fixture = new Fixture();
        fixture.store.awaiting(false);
        var result = fixture.coordinator.drive(fixture.claim);
        assertEquals(ExecutionCoordinator.Stop.UNKNOWN, result.stop());
        assertEquals(0, fixture.invocations);
        assertEquals(0, fixture.nexts);
        assertEquals(0, fixture.store.dispatches);
        assertTrue(fixture.store.completions.isEmpty());
        assertEquals(0, fixture.store.loads);
        assertEquals(1, fixture.store.quiescences);
        assertEquals(ExecutionStore.Phase.UNCERTAIN, fixture.store.current.phase());
        assertEquals(ExecutionCoordinator.Stop.NOT_CLAIMED, fixture.coordinator.drive(fixture.claim).stop());
        assertEquals(1, fixture.store.quiescences);
    }

    /** 重复派发回执 Existing 不拥有外调权，不能转成第二次发送。 */
    @Test
    void existingDispatchWithoutResultNeverInvokesCapability() {
        var fixture = new Fixture();
        fixture.store.existing = true;
        var result = fixture.coordinator.drive(fixture.claim);
        assertEquals(ExecutionCoordinator.Stop.UNKNOWN, result.stop());
        assertEquals(1, fixture.store.dispatches);
        assertEquals(0, fixture.invocations);
        assertTrue(fixture.store.completions.isEmpty());
        assertTrue(fixture.store.attempts.getFirst().result().isEmpty());
        assertEquals(1, fixture.store.quiescences);
    }

    /** 取消在外调期间生效，真实迟到结果仍保存，但不采用候选材料或计算下一动作。 */
    @Test
    void cancelledLateResultIsSavedWithoutAdvancingLocalOrPersistentProgress() {
        var fixture = new Fixture();
        fixture.store.cancelOnComplete = true;
        var result = fixture.coordinator.drive(fixture.claim);
        assertEquals(ExecutionCoordinator.Stop.CANCELLED, result.stop());
        assertEquals(RunStatus.CANCELLED, result.run().orElseThrow().status());
        assertEquals(1, fixture.invocations);
        assertEquals(1, fixture.nexts);
        assertEquals(1, fixture.store.completions.size());
        assertEquals(0, result.run().orElseThrow().progress().decisions());
        assertEquals(new ResultRef("context-0"), result.run().orElseThrow().progress().context());
        assertEquals(Optional.of(new ResultRef("result-1")), fixture.store.attempts.getFirst().result());
        assertFalse(fixture.store.attempts.getFirst().adopted());
    }

    /** 接管者持有新租约时，旧外调结果只落账，旧驱动不能借新 fence 继续推进。 */
    @Test
    void oldLeaseLateResultIsSavedWithoutUsingTakeoverLease() {
        var fixture = new Fixture();
        fixture.store.takeoverOnComplete = true;
        var result = fixture.coordinator.drive(fixture.claim);
        assertEquals(ExecutionCoordinator.Stop.OWNERSHIP_LOST, result.stop());
        assertEquals(2, result.run().orElseThrow().fence());
        assertEquals(1, fixture.store.completions.getFirst().lease().fencingToken());
        assertEquals(0, result.run().orElseThrow().progress().decisions());
        assertEquals(1, fixture.nexts);
        assertEquals(1, fixture.invocations);
        assertEquals(Optional.of(new ResultRef("result-1")), fixture.store.attempts.getFirst().result());
        assertFalse(fixture.store.attempts.getFirst().adopted());
    }

    /** 已保存确定结果只从原材料采纳，第一动作不再次发送，原预算结算由 Store 复用。 */
    @Test
    void adoptsSavedResultAndContinuesFromItsActualFeedback() {
        var fixture = new Fixture();
        fixture.store.awaiting(true);
        var result = fixture.coordinator.drive(fixture.claim);
        assertEquals(ExecutionCoordinator.Stop.FINISHED, result.stop());
        assertEquals(1, fixture.invocations);
        assertEquals("attempt-1", fixture.store.completions.getFirst().attemptId());
        assertEquals(new ResultRef("feedback-1"), fixture.store.completions.getFirst().feedback().orElseThrow().reference());
        assertEquals(ExecutionBudgetPort.Usage.unreported(), fixture.store.completions.getFirst().usage());
        assertEquals(0, fixture.store.loads);
    }

    /** 外调技术异常保留原 Attempt，不伪造 UNKNOWN 材料或自动再派发。 */
    @Test
    void capabilityFailureLeavesRegisteredAttemptWithoutFabricatedResult() {
        var fixture = new Fixture();
        fixture.failure = new IllegalStateException("controlled-provider-disconnected");
        assertSame(fixture.failure, assertThrows(IllegalStateException.class, () -> fixture.coordinator.drive(fixture.claim)));
        assertEquals(1, fixture.invocations);
        assertEquals(ExecutionStore.Phase.AWAITING_RESULT, fixture.store.current.phase());
        assertTrue(fixture.store.attempts.getFirst().result().isEmpty());
        assertTrue(fixture.store.completions.isEmpty());
        var resumed = fixture.coordinator.drive(fixture.claim);
        assertEquals(ExecutionCoordinator.Stop.UNKNOWN, resumed.stop());
        assertEquals(1, fixture.invocations);
    }

    /** 外调完成时内存取消信号已到达，仍先保存实际结果，然后停止新动作并关闭控制登记。 */
    @Test
    void memoryCancellationNeverDropsActualResultAndClosesControl() {
        var fixture = new Fixture(); fixture.cancelDuringInvoke = true;
        var result = fixture.coordinator.drive(fixture.claim);
        assertEquals(ExecutionCoordinator.Stop.CANCELLED, result.stop());
        assertEquals(1, fixture.invocations); assertEquals(1, fixture.nexts);
        assertEquals(1, fixture.store.completions.size()); assertEquals(1, fixture.closedControls);
        assertEquals(Optional.of(new ResultRef("result-1")), fixture.store.attempts.getFirst().result());
    }

    /** HUMAN已接受响应只走一次本地CAS采纳，不登记或调用任何Provider Attempt。 */
    @Test
    void acceptsHumanResponseLocallyWithoutExternalDispatchOrBudgetResult() {
        var fixture = new Fixture(); fixture.store.human();
        var result = fixture.coordinator.drive(fixture.claim);
        assertEquals(ExecutionCoordinator.Stop.FINISHED, result.stop());
        assertEquals(1, fixture.store.localResponses); assertEquals(0, fixture.invocations); assertEquals(0, fixture.store.dispatches);
        assertTrue(fixture.store.completions.isEmpty()); assertTrue(fixture.store.attempts.isEmpty());
        assertEquals(List.of("open", "accept-human", "next-2"), fixture.steps);
        assertEquals(new ResultRef("context-human"), result.run().orElseThrow().progress().context());
        assertEquals(1, fixture.closedControls);
    }

    /** 委派依赖已由Host接受的系统响应同样只走本地CAS，不能重发原START或WAIT。 */
    @Test
    void acceptsDelegationDependencyLocallyWithoutRedispatchingOriginalCommand() {
        var fixture = new Fixture(); fixture.store.delegation();
        var result = fixture.coordinator.drive(fixture.claim);
        assertEquals(ExecutionCoordinator.Stop.FINISHED, result.stop());
        assertEquals(1, fixture.store.localResponses); assertEquals(0, fixture.invocations); assertEquals(0, fixture.store.dispatches);
        assertTrue(fixture.store.attempts.isEmpty()); assertTrue(fixture.store.completions.isEmpty());
        assertEquals(List.of("open", "accept-delegation", "next-2"), fixture.steps);
    }

    /** 本地Plan仅走明确事务边界，不能把业务控制伪装成模型或工具外调。 */
    @Test
    void executesPlanThroughLocalBoundaryWithoutAnyProviderAttempt() {
        var fixture = new Fixture(); fixture.plan = true;
        var result = fixture.coordinator.drive(fixture.claim);
        assertEquals(ExecutionCoordinator.Stop.FINISHED, result.stop()); assertEquals(1, fixture.localPlans);
        assertEquals(0, fixture.invocations); assertEquals(0, fixture.store.dispatches); assertTrue(fixture.store.attempts.isEmpty());
        assertEquals(List.of("open", "next-0", "execute-plan", "adopt-plan", "next-2"), fixture.steps);
    }

    /** 边界处只读取最早一条已保存补充，本地CAS成功后才替换材料，不登记预算或外调。 */
    @Test
    void acceptsOldestSupplementalInputAtReadyBoundaryWithoutProviderCall() {
        var fixture = new Fixture(); fixture.store.supplement();
        var result = fixture.coordinator.drive(fixture.claim);
        assertEquals(ExecutionCoordinator.Stop.FINISHED, result.stop()); assertEquals(1, fixture.store.inputAcceptances);
        assertEquals(1, fixture.inputCommits);
        assertEquals(0, fixture.invocations); assertEquals(0, fixture.store.dispatches); assertTrue(fixture.store.attempts.isEmpty());
        assertEquals(List.of("open", "accept-input", "next-2"), fixture.steps); assertEquals(2, fixture.store.materialReads);
    }

    /** 旧输入的幂等回执可能早于当前操作者；未推进时不能发布候选材料或回退活跃身份。 */
    @Test
    void doesNotPublishSupplementalActorWhenInputAcceptanceDidNotAdvance() {
        var fixture = new Fixture(); fixture.store.supplement(); fixture.store.rejectInput = true;
        var currentActor = new ResultRef("later-committed-actor");
        fixture.store.current = withActor(fixture.store.current, currentActor);
        var result = fixture.coordinator.drive(fixture.claim);
        assertEquals(ExecutionCoordinator.Stop.OWNERSHIP_LOST, result.stop());
        assertEquals(currentActor, result.run().orElseThrow().actorVerificationRef());
        assertEquals(1, fixture.store.inputAcceptances); assertEquals(0, fixture.inputCommits);
        assertEquals(0, fixture.nexts); assertEquals(0, fixture.invocations);
        assertEquals(new ResultRef("context-0"), result.run().orElseThrow().progress().context());
    }

    /** 明确支持原operation结果查询时只采用真实已确定结果，原Attempt不重新登记或发送。 */
    @Test
    void observesOriginalPendingOperationWithoutCreatingOrReplayingItsInvocation() {
        var fixture = new Fixture(); fixture.store.awaiting(false); fixture.observeOriginal = true;
        var result = fixture.coordinator.drive(fixture.claim);
        assertEquals(ExecutionCoordinator.Stop.FINISHED, result.stop()); assertEquals(1, fixture.observations);
        assertEquals(1, fixture.invocations); assertEquals(1, fixture.store.dispatches); assertEquals(0, fixture.store.quiescences);
        assertEquals("attempt-1", fixture.store.completions.getFirst().attemptId()); assertEquals(2, fixture.store.attempts.size());
    }

    /** 查询也必须通过当前读取授权；拒绝后不把旧Attempt包装成Registered或调用Provider。 */
    @Test
    void rejectedOriginalOperationObservationNeverInvokesOrFabricatesAResult() {
        var fixture = new Fixture(); fixture.store.awaiting(false); fixture.observeFailure = new SecurityException("controlled-query-access-revoked");
        assertSame(fixture.observeFailure, assertThrows(SecurityException.class, () -> fixture.coordinator.drive(fixture.claim)));
        assertEquals(0, fixture.invocations); assertEquals(0, fixture.store.dispatches); assertTrue(fixture.store.completions.isEmpty());
        assertTrue(fixture.store.attempts.getFirst().result().isEmpty());
    }

    /** 夹具材料有明确字段；生产装配可改为包含模型消息、预算和冻结能力的同类不可变类型。 */
    private record Materials(int completed, List<ResultRef> feedback) {
        /** 保存已经过结果提交的反馈引用，不维护独立持久状态。 */
        private Materials { feedback = List.copyOf(feedback); }
    }

    /** 只装配协调器协议，能力回执是受控单测事实，不声称实际 Provider 已执行。 */
    private static final class Fixture implements ExecutionCoordinator.Program<Materials>, ExecutionCoordinator.Capabilities, ExecutionCoordinator.LocalPlans {
        private final Store store = new Store();
        private final ExecutionStore.Claim claim = new ExecutionStore.Claim(SessionFixtures.IDENTITY.runRef(), "worker", "claim", Duration.ofMinutes(1));
        private final ExecutionCoordinator<Materials> coordinator = new ExecutionCoordinator<>(store, this, this, run -> new ExecutionCoordinator.Control() {
            @Override public boolean cancelled() { return memoryCancelled; }
            @Override public boolean ownershipLost() { return false; }
            @Override public void close() { closedControls++; }
        }, this);
        private final List<String> steps = new ArrayList<>();
        private int opens;
        private int nexts;
        private int invocations;
        private boolean checkReentrant;
        private boolean checkActive;
        private RuntimeException failure;
        private boolean cancelDuringInvoke;
        private boolean memoryCancelled;
        private int closedControls;
        private boolean plan;
        private boolean batch;
        @Override public Optional<Step.Finish> dispatchRejected(ExecutionStore.Run run, Materials materials, String reason) {
            return "execution_dispatch_unauthorized".equals(reason) ? Optional.of(new Step.Finish(RunStatus.FAILED,
                    List.of(new ExecutionStore.Event(new com.skillforge.runtime.api.EventId("denial"), "dispatch-denied", new TypedPayload("application/json", "{}"))))) : Optional.empty();
        }
        private int localPlans;
        private boolean observeOriginal;
        private int observations;
        private SecurityException observeFailure;
        private int inputCommits;

        /** 首次解析只消费已保存上下文，不调用旧 checkpoint 解码。 */
        @Override public Materials open(ExecutionStore.Run run, ExecutionStore.Material context) {
            opens++;
            steps.add("open");
            assertEquals(run.progress().context(), context.reference());
            return new Materials(0, List.of());
        }

        /** 下一动作必须读取已提交后的反馈引用，不能提前采用 adopt 候选。 */
        @Override public Step next(ExecutionStore.Run run, Materials materials) {
            nexts++;
            if (store.inputAcceptances > 0) assertEquals(1, inputCommits);
            steps.add("next-" + materials.completed());
            for (var reference : materials.feedback()) assertTrue(store.saved.stream().anyMatch(value -> value.reference().equals(reference)));
            if (materials.completed() == 2) return new Step.Finish(RunStatus.COMPLETED, List.of());
            if (batch) {
                var source = dispatch(1).action();
                store.actions.add(new ExecutionStore.Action(source.action(), run.identity().runRef(), source.ordinal(), source.kind(), source.target(), source.capability(),
                        source.request().reference(), source.fingerprint(), ExecutionStore.ActionStatus.SUCCEEDED, "source-attempt", Optional.of("source-attempt"), Optional.empty()));
                return new Step.Batch(source.action(), List.of(2, 3).stream().map(ordinal -> {
                var call = dispatch(ordinal);
                return (ExecutionStore.BatchMember) new ExecutionStore.BatchMember.Fresh(ordinal - 1, new ExecutionStore.BatchRequest(call.action(), call.attemptId(), call.budget()));
            }).toList(), SessionFixtures.ADMISSION);
            }
            if (plan) return new Step.PlanLocal(new ActionRef(SessionFixtures.TENANT, "original-model-plan"), new com.skillforge.runtime.api.port.PlanCommand(
                    com.skillforge.runtime.api.port.PlanCommand.Operation.GET, "actual-work", 0, Optional.empty(), "", "", "", List.of(), List.of()),
                    SessionFixtures.ADMISSION, material("plan-request", "{}"));
            return dispatch(materials.completed() + 1);
        }

        @Override public Step continuation(ExecutionStore.Run run, Materials materials, ExecutionStore.Waiting waiting, ExecutionStore.Material response) {
            throw new AssertionError("fixture has no tool-input wait");
        }

        @Override public Adoption<Materials> acceptResponse(ExecutionStore.Run run, Materials materials, ExecutionStore.Waiting waiting, ExecutionStore.Material response) {
            steps.add(waiting.kind() == ExecutionStore.WaitKind.HUMAN ? "accept-human" : "accept-delegation");
            assertTrue(waiting.kind() == ExecutionStore.WaitKind.HUMAN || waiting.kind() == ExecutionStore.WaitKind.DELEGATION);
            assertEquals(waiting.acceptedResponse().orElseThrow().response(), response.reference());
            var context = material("context-human", "{\"response\":\"" + response.reference().value() + "\"}");
            return new Adoption<>(new Materials(2, List.of()), new ExecutionStore.Progress(1, 0, 0, context.reference()), context, List.of(), Optional.empty());
        }

        @Override public boolean batchParallel(Materials materials) { throw new AssertionError("fixture does not execute tool batches"); }
        @Override public ExecutionCoordinator.BatchResult inspectBatch(ExecutionStore.Run run, Materials materials, ExecutionCoordinator.BatchCall result, int slot) {
            throw new AssertionError("fixture has no parallel tool batch");
        }
        @Override public Adoption<Materials> acceptInput(ExecutionStore.Run run, Materials materials, ExecutionStore.SupplementalInput input, ExecutionStore.Material content) {
            steps.add("accept-input"); assertEquals(input.content(), content.reference()); var context = material("context-input", "{}");
            return new Adoption<>(new Materials(2, List.of()), new ExecutionStore.Progress(run.progress().decisions(), run.progress().toolCalls(), run.progress().knowledgeCalls(), context.reference()), context, List.of(), Optional.empty());
        }
        /** 已提交回执才可通知活动材料；下一次next调用前完成通知。 */
        @Override public Materials inputCommitted(ExecutionStore.InputAcceptance accepted, Materials materials) {
            assertTrue(accepted.advanced()); assertEquals(accepted.run(), store.current);
            assertTrue(store.saved.stream().anyMatch(value -> value.reference().equals(accepted.run().progress().context())));
            inputCommits++; return materials;
        }
        @Override public Adoption<Materials> planAdoption(ExecutionStore.Run run, Materials materials, Step.PlanLocal command, ExecutionStore.Material actualResult) {
            steps.add("adopt-plan"); var context = material("context-plan", "{}");
            return new Adoption<>(new Materials(2, List.of()), new ExecutionStore.Progress(run.progress().decisions(), run.progress().toolCalls(), run.progress().knowledgeCalls(), context.reference()), context, List.of(), Optional.empty());
        }
        @Override public <T> ExecutionCoordinator.LocalPlanResult<T> execute(ExecutionStore.Run current, Step.PlanLocal command, java.util.function.BiFunction<ExecutionStore.Run, ExecutionStore.Material, Adoption<T>> adopter) {
            assertTrue(plan); localPlans++; steps.add("execute-plan"); var result = material("plan-result", "{}"); var adoption = adopter.apply(current, result);
            store.saved.add(result); store.saved.add(adoption.context()); store.current = run(RunStatus.RUNNING, current.revision() + 1, ExecutionStore.Phase.READY, adoption.next(), Optional.empty(), false);
            return new ExecutionCoordinator.LocalPlanResult<>(store.current, Optional.of(adoption), true);
        }

        @Override public Adoption<Materials> adoptBatch(ExecutionStore.Run run, Materials materials, List<ExecutionCoordinator.BatchOutcome> results) {
            throw new AssertionError("fixture has no parallel tool batch");
        }
        @Override public ExecutionCoordinator.BatchPlan<Materials> batchContinuation(ExecutionStore.Run run, Materials materials, List<ExecutionCoordinator.BatchOutcome> results, List<ExecutionCoordinator.BatchResponse> responses) { throw new AssertionError("fixture has no parallel tool batch"); }

        /** 每轮上下文只记录原上下文和实际结果引用，不递归保存整个模型历史。 */
        @Override public Adoption<Materials> adopt(ExecutionStore.Run run, Materials materials,
                ExecutionStore.Prepared prepared, CapabilityResult result) {
            int ordinal = Math.toIntExact(prepared.ordinal());
            steps.add("adopt-" + ordinal);
            var feedback = result.feedback().orElseThrow();
            var references = new ArrayList<>(materials.feedback());
            references.add(feedback.reference());
            var context = material("context-" + ordinal, "{\"previous\":\"" + run.progress().context().value()
                    + "\",\"result\":\"" + result.result().reference().value() + "\",\"feedback\":\"" + feedback.reference().value() + "\"}");
            var next = new ExecutionStore.Progress(1, ordinal - 1, 0, context.reference());
            return new Adoption<>(new Materials(ordinal, references), next, context, List.of(), Optional.empty());
        }

        /** 注册必须先于真实边界调用；重入同 Run 只能返回 BUSY。 */
        @Override public CapabilityResult invoke(ExecutionStore.DispatchResult.Registered registered, ExecutionStore.Prepared prepared) {
            invocations++;
            assertEquals(registered.attempt(), store.attempts.getLast());
            steps.add("invoke-" + prepared.ordinal());
            if (checkReentrant) assertEquals(ExecutionCoordinator.Stop.BUSY, coordinator.drive(claim).stop());
            if (checkActive) {
                var current = coordinator.activeMaterials(registered.run().identity(), registered.run().progress().context()).orElseThrow();
                assertEquals(prepared.ordinal() - 1, current.completed());
                assertTrue(coordinator.activeMaterials(registered.run().identity(), new ResultRef("uncommitted-context")).isEmpty());
            }
            if (failure != null) throw failure;
            if (cancelDuringInvoke) memoryCancelled = true;
            return actual(Math.toIntExact(prepared.ordinal()));
        }
        /** 本受控查询只证明协调器使用原请求/Attempt，不代表任何真实供应商支持query。 */
        @Override public Optional<CapabilityResult> observe(ExecutionStore.Run run, ExecutionStore.Prepared prepared, ExecutionStore.Attempt original) {
            observations++; if (observeFailure != null) throw observeFailure;
            if (!observeOriginal) return Optional.empty();
            assertEquals(original.request(), prepared.request().reference()); assertTrue(original.result().isEmpty());
            assertEquals(run.identity().runRef(), original.run()); return Optional.of(actual(Math.toIntExact(prepared.ordinal())));
        }
    }

    /** 显式 Store 协议夹具，事务/租约/权限正确性仍须真实 PostgreSQL 验证。 */
    private static final class Store implements ExecutionStore {
        private final List<Material> saved = new ArrayList<>(List.of(ExecutionCoordinatorTest.material("context-0", "{}")));
        private final List<Action> actions = new ArrayList<>();
        private final List<Attempt> attempts = new ArrayList<>();
        private final List<Completion> completions = new ArrayList<>();
        private Run current = run(RunStatus.RUNNING, 1, Phase.READY,
                new Progress(0, 0, 0, new ResultRef("context-0")), Optional.empty(), false);
        private int claims;
        private int loads;
        private int materialReads;
        private int dispatches;
        private String dispatchRejection;
        private RuntimeException dispatchFailure;
        private final List<Finish> finishes = new ArrayList<>();
        private int quiescences;
        private int localResponses;
        private Waiting humanWaiting;
        private boolean existing;
        private boolean cancelOnComplete;
        private boolean takeoverOnComplete;
        private SupplementalInput supplemental;
        private int inputAcceptances;
        private boolean rejectInput;

        /** 返回一次领取的当前状态，不产生额外历史快照。 */
        @Override public Optional<Run> claim(Claim command) { claims++; return current.phase() == Phase.UNCERTAIN ? Optional.empty() : Optional.of(current); }
        /** 本接口被正常循环误用时计数并直接失败。 */
        @Override public Optional<Run> load(RunRef reference) { loads++; throw new AssertionError("normal drive must not load"); }
        /** 仅读取已保存材料，测试不会把候选内容伪装为已落库。 */
        @Override public Optional<Material> material(TenantId tenant, ResultRef reference) {
            materialReads++;
            return saved.stream().filter(value -> value.reference().equals(reference)).findFirst();
        }
        /** 纯内存夹具按输入顺序恢复批量材料，不调用逐叶存储入口。 */
        @Override public List<Optional<Material>> materials(TenantId tenant, List<ResultRef> references) {
            materialReads++;
            return references.stream().map(reference -> saved.stream()
                    .filter(value -> value.reference().equals(reference)).findFirst()).toList();
        }
        /** 返回原动作，禁止按当前准备重新构造历史结果。 */
        @Override public Optional<Action> action(ActionRef reference) { return actions.stream().filter(value -> value.reference().equals(reference)).findFirst(); }
        @Override public List<Action> actions(RunRef run, long afterOrdinal, int limit) { throw new AssertionError("unexpected actions scan"); }
        @Override public TokenUsage usage(RunRef run) { throw new AssertionError("unexpected usage aggregation"); }
        @Override public InputOffer offerInput(OfferInput command) { throw new AssertionError("unexpected offer input"); }
        @Override public List<SupplementalInput> inputs(RunRef run, long afterOrdinal, boolean pendingOnly, int limit) { return supplemental == null || supplemental.appliedRevision().isPresent() ? List.of() : List.of(supplemental); }
        @Override public InputAcceptance acceptInput(AcceptInput command) {
            if (rejectInput) { inputAcceptances++; return new InputAcceptance(current, supplemental, SessionFixtures.ADMISSION, false); }
            inputAcceptances++; saved.add(command.context()); current = run(RunStatus.RUNNING, current.revision() + 1, Phase.READY, command.next(), Optional.empty(), false);
            supplemental = new SupplementalInput(supplemental.run(), supplemental.input(), supplemental.content(), supplemental.fingerprint(), supplemental.actorRef(), supplemental.ordinal(), OptionalLong.of(current.revision()));
            return new InputAcceptance(current, supplemental, SessionFixtures.ADMISSION, true);
        }
        private void supplement() {
            var content = ExecutionCoordinatorTest.material("supplemental-actual", "{}"); saved.add(content);
            supplemental = new SupplementalInput(current.identity().runRef(), new com.skillforge.runtime.api.InputId("supplemental-input"), content.reference(), "actual-fingerprint", current.actorVerificationRef(), 1, OptionalLong.empty());
        }
        /** 返回原派发身份，恢复不签发第二份发送权。 */
        @Override public Optional<Attempt> attempt(RunRef run, String id) { return attempts.stream().filter(value -> value.id().equals(id)).findFirst(); }

        /** 受控登记模拟 Store 返回下一版本；Existing 分支不代表可再次发送。 */
        @Override public DispatchResult dispatch(Dispatch command) {
            dispatches++;
            if (dispatchFailure != null) throw dispatchFailure;
            if (dispatchRejection != null) return new DispatchResult.Rejected(dispatchRejection);
            assertEquals(current.revision(), command.revision());
            var prepared = command.action();
            saved.add(prepared.request()); saved.addAll(prepared.requestParts());
            var action = new Action(prepared.action(), command.run(), prepared.ordinal(), prepared.kind(), prepared.target(),
                    prepared.capability(), prepared.request().reference(), prepared.fingerprint(), ActionStatus.DISPATCHED,
                    command.attemptId(), Optional.empty(), Optional.empty());
            var attempt = new Attempt(command.attemptId(), action.reference(), command.run(), 1, prepared.request().reference(), command.continuation(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false);
            actions.add(action);
            attempts.add(attempt);
            current = run(RunStatus.RUNNING, current.revision() + 1, Phase.AWAITING_RESULT, current.progress(), Optional.of(action.reference()), false);
            return existing ? new DispatchResult.Existing(current, action, attempt) : new DispatchResult.Registered(current, action, attempt, command.actor());
        }

        /** 发送授权属于真实宿主最后一跳；纯协调夹具不得把调用误作已通过生产授权。 */
        @Override public boolean authorizeSend(DispatchResult.Registered registered, Prepared prepared) {
            throw new AssertionError("unexpected provider send authorization");
        }

        /** 保存原结果后按明确 advanced 决定是否采用候选；取消分支保留原 progress。 */
        @Override public CompletionResult complete(Completion command) {
            completions.add(command);
            saved.add(command.result());
            command.feedback().ifPresent(saved::add);
            boolean advance = !cancelOnComplete && !takeoverOnComplete;
            if (advance) saved.add(command.context());
            var previous = attempts.stream().filter(value -> value.id().equals(command.attemptId())).findFirst().orElseThrow();
            var attempt = new Attempt(previous.id(), previous.action(), previous.run(), previous.fence(), previous.request(), previous.continuation(), Optional.of(command.kind()),
                    Optional.of(command.result().reference()), previous.uncertainty(), command.feedback().map(Material::reference), command.externalOperation(), advance);
            attempts.set(attempts.indexOf(previous), attempt);
            current = advance
                    ? run(RunStatus.RUNNING, current.revision() + 1, Phase.READY, command.next(), Optional.empty(), false)
                    : run(RunStatus.CANCELLED, current.revision() + 1, Phase.FINISHED, current.progress(), Optional.empty(), true);
            if (takeoverOnComplete) {
                var lease = new LeaseFence(SessionFixtures.TENANT, "claim-takeover", "worker-takeover", 2, SessionFixtures.NOW.plusSeconds(90));
                current = new Run(SessionFixtures.IDENTITY, current.configuration(), RunStatus.RUNNING, current.revision(), 2,
                        false, Phase.AWAITING_RESULT, current.progress(), Optional.of(lease), Optional.of(previous.action()),
                        Optional.empty(), Optional.empty(), current.actorVerificationRef(), Optional.empty(), current.createdAt(), current.updatedAt());
            }
            return new CompletionResult(current, attempt, true, advance);
        }

        /** 完成只采用程序明确返回的终态及事件。 */
        @Override public Run finish(Finish command) {
            finishes.add(command);
            assertEquals(current.revision(), command.revision());
            current = run(command.status(), current.revision() + 1, Phase.FINISHED, current.progress(), Optional.empty(), false);
            return current;
        }

        /** 构造已中断原动作；可选结果来自夹具明确保存的材料。 */
        private void awaiting(boolean withResult) {
            var command = ExecutionCoordinatorTest.dispatch(1);
            var prepared = command.action();
            saved.add(prepared.request()); saved.addAll(prepared.requestParts());
            actions.add(new Action(prepared.action(), SessionFixtures.IDENTITY.runRef(), 1, prepared.kind(), prepared.target(), prepared.capability(),
                    prepared.request().reference(), prepared.fingerprint(), withResult ? ActionStatus.SUCCEEDED : ActionStatus.DISPATCHED,
                    command.attemptId(), withResult ? Optional.of(command.attemptId()) : Optional.empty(), Optional.empty()));
            if (withResult) {
                var actual = actual(1);
                saved.add(actual.result());
                saved.add(actual.feedback().orElseThrow());
                attempts.add(new Attempt(command.attemptId(), prepared.action(), SessionFixtures.IDENTITY.runRef(), 1, prepared.request().reference(), Optional.empty(),
                        Optional.of(ResultKind.SUCCEEDED), Optional.of(actual.result().reference()), Optional.empty(), actual.feedback().map(Material::reference), Optional.empty(), false));
            } else attempts.add(new Attempt(command.attemptId(), prepared.action(), SessionFixtures.IDENTITY.runRef(), 1, prepared.request().reference(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false));
            current = run(RunStatus.RUNNING, 2, Phase.AWAITING_RESULT, current.progress(), Optional.of(prepared.action()), false);
        }

        /** 未纳入本组协调算法验证的 Store 操作不允许被隐式调用。 */
        @Override public AdmissionResult admit(Admission command) { throw new AssertionError("unexpected admit"); }
        /** 未纳入本组协调算法验证的取消入口不能被程序直接调用。 */
        @Override public Run cancel(Cancel command) { throw new AssertionError("unexpected cancel"); }
        @Override public ResumeResult resume(Resume command) { throw new AssertionError("unexpected resume"); }
        @Override public Optional<Waiting> waiting(RunRef run, WaitRef reference) {
            if (humanWaiting == null || !humanWaiting.reference().equals(reference)) throw new AssertionError("unexpected wait read");
            return Optional.of(humanWaiting);
        }
        @Override public Optional<Waiting> response(RunRef run, com.skillforge.runtime.api.InputId input) { throw new AssertionError("unexpected response read"); }
        @Override public Optional<Waiting> waiting(RunRef run, String sourceAttempt) { throw new AssertionError("unexpected attempt wait read"); }
        @Override public QuiescenceResult quiesceUnknown(QuiesceUnknown command) {
            quiescences++;
            var attempt = attempts.stream().filter(value -> value.id().equals(command.attemptId())).findFirst().orElseThrow();
            current = run(RunStatus.SUSPENDED, current.revision() + 1, Phase.UNCERTAIN, current.progress(), current.currentAction(), false);
            return new QuiescenceResult(current, attempt, true);
        }
        @Override public LocalResponseResult acceptLocalResponse(AcceptLocalResponse command) {
            localResponses++; assertEquals(current.revision(), command.revision());
            assertEquals(humanWaiting.reference(), command.waiting()); saved.add(command.context());
            current = run(RunStatus.RUNNING, current.revision() + 1, Phase.READY, command.next(), Optional.empty(), false);
            humanWaiting = new Waiting(humanWaiting.reference(), humanWaiting.kind(), humanWaiting.run(), humanWaiting.sourceAttempt(), Map.of(), humanWaiting.internal(),
                    humanWaiting.acceptedResponse(), Optional.empty(), OptionalLong.of(current.revision()));
            return new LocalResponseResult(current, humanWaiting, true);
        }

        private void human() {
            localWaiting(WaitKind.HUMAN, Optional.empty());
        }
        private void delegation() {
            localWaiting(WaitKind.DELEGATION, Optional.of(new DelegationWait("business-wait", List.of("actual-delegation"), List.of(new RunRef(SessionFixtures.TENANT, "actual-child")))));
        }
        private void localWaiting(WaitKind kind, Optional<InternalWait> internal) {
            var reference = new WaitRef(new ActionRef(SessionFixtures.TENANT, "original-model-question"), 1);
            var response = ExecutionCoordinatorTest.material("human-response", "{\"response\":\"真实回答\"}"); saved.add(response);
            humanWaiting = new Waiting(reference, kind, SessionFixtures.IDENTITY.runRef(), "original-model-attempt", Map.of(), internal,
                    Optional.of(new AcceptedResponse(new com.skillforge.runtime.api.InputId("human-reply"), response.reference(), "human-fingerprint", SessionFixtures.ADMISSION.verificationRef(), 3)),
                    Optional.empty(), OptionalLong.empty());
            current = new Run(current.identity(), current.configuration(), RunStatus.RUNNING, 3, current.fence(), false, Phase.READY,
                    new Progress(1, 0, 0, current.progress().context()), current.lease(), Optional.of(reference.action()), Optional.empty(), Optional.of(reference),
                    current.actorVerificationRef(), Optional.empty(), current.createdAt(), SessionFixtures.NOW.plusNanos(3));
        }
        /** 租约维护由真实控制调度负责，本组不伪造心跳成功。 */
        @Override public Run renew(RunRef run, LeaseFence lease, Duration duration) { throw new AssertionError("unexpected renew"); }
        @Override public Optional<Material> originalInput(RunRef run) { throw new AssertionError("unexpected original input"); }
        @Override public Optional<SavedEvent> event(RunRef run, com.skillforge.runtime.api.EventId event) { throw new AssertionError("unexpected exact event"); }
        @Override public Optional<SavedEvent> event(SessionRef session, com.skillforge.runtime.api.EventId event) { throw new AssertionError("unexpected exact session event"); }
        @Override public BatchDispatchResult dispatchBatch(BatchDispatch command) {
            if (dispatchRejection != null) return new BatchDispatchResult.Rejected(dispatchRejection);
            throw new AssertionError("unexpected batch");
        }
        @Override public CompletionResult completeBatch(BatchCompletion command) { throw new AssertionError("unexpected batch result"); }
        @Override public BatchAdoptionResult adoptBatch(BatchAdopt command) { throw new AssertionError("unexpected batch adoption"); }
        @Override public BatchState quiesceBatch(BatchQuiesce command) { throw new AssertionError("unexpected batch quiescence"); }
        @Override public List<BatchEntry> batch(BatchRef reference) { throw new AssertionError("unexpected batch read"); }
        @Override public List<Waiting> batchWaits(BatchRef reference) { throw new AssertionError("unexpected batch waits"); }
        @Override public BatchDispatchResult dispatchBatchContinuations(BatchContinue command) { throw new AssertionError("unexpected batch continuation"); }
        @Override public Run stopBatch(BatchStop command) { throw new AssertionError("unexpected batch stop"); }
        /** 有界补扫属于独立调度装配，正常活跃循环不扫描。 */
        @Override public List<RunRef> due(TenantId tenant, int limit) { throw new AssertionError("unexpected due"); }
        /** 最早到期调度由宿主实现；本用例不扫描或安排后续任务。 */
        @Override public Optional<java.time.Instant> nextDueAt(TenantId tenant) { throw new AssertionError("unexpected next due read"); }
        /** 页面事件消费不进入本组执行路径。 */
        @Override public List<SavedEvent> events(com.skillforge.runtime.api.SessionRef session, long afterSequence, int limit) { throw new AssertionError("unexpected events"); }
        @Override public List<SavedEvent> eventsInRange(com.skillforge.runtime.api.SessionRef session, long afterSequence, long throughSequence, int limit) { throw new AssertionError("unexpected events"); }
    }

    /** 生成两个不同真实准备种类，所有引用和预算身份保持一致。 */
    private static Step.Dispatch dispatch(int ordinal) {
        var kind = ordinal == 1 ? ExecutionStore.Kind.MODEL : ExecutionStore.Kind.TOOL;
        var prepared = new ExecutionStore.Prepared(new ActionRef(SessionFixtures.TENANT, "action-" + ordinal), ordinal,
                kind, kind == ExecutionStore.Kind.MODEL ? "model" : "template_render", new ResultRef("capability-" + ordinal),
                material("request-" + ordinal, "{\"input\":\"request-" + ordinal + "\"}"), "fingerprint-" + ordinal);
        var category = ordinal == 1 ? ExecutionBudgetPort.Category.BUSINESS_MODEL : ExecutionBudgetPort.Category.TOOL;
        var attempt = "attempt-" + ordinal;
        return new Step.Dispatch(prepared, attempt, new ExecutionBudgetPort.Demand(SessionFixtures.IDENTITY.runRef(), attempt,
                prepared.fingerprint(), category, ExecutionBudgetPort.Resources.call(category, 10, 10), false), SessionFixtures.ADMISSION, Optional.empty(), List.of());
    }

    /** 结果与安全反馈是本用例真实返回的受控事实，和已保存状态明确分离。 */
    private static CapabilityResult actual(int ordinal) {
        return new CapabilityResult(ExecutionStore.ResultKind.SUCCEEDED, material("result-" + ordinal, "{\"actual\":\"result-" + ordinal + "\"}"),
                Optional.of(material("feedback-" + ordinal, "{\"safe\":\"feedback-" + ordinal + "\"}")), Optional.empty(),
                ExecutionBudgetPort.Usage.reported(OptionalLong.of(3), OptionalLong.of(2)));
    }

    /** 当前格式材料夹具不包含 Run、checkpoint 或内存替代账本。 */
    private static ExecutionStore.Material material(String id, String json) {
        return new ExecutionStore.Material(new ResultRef(id), new TypedPayload("fixture.execution.v1", json));
    }

    /** 构造已采纳后续输入的当前操作者，原Run身份及所有进度保持不变。 */
    private static ExecutionStore.Run withActor(ExecutionStore.Run run, ResultRef actor) {
        return new ExecutionStore.Run(run.identity(), run.configuration(), run.status(), run.revision(), run.fence(), run.cancelled(), run.phase(),
                run.progress(), run.lease(), run.currentAction(), run.currentBatch(), run.waiting(), actor, run.nextDueAt(), run.createdAt(), run.updatedAt());
    }

    /** 固定租约和时间只构造合法状态，测试不把它视为真实数据库授予的写权。 */
    private static ExecutionStore.Run run(RunStatus status, long revision, ExecutionStore.Phase phase,
            ExecutionStore.Progress progress, Optional<ActionRef> action, boolean cancelled) {
        var lease = phase == ExecutionStore.Phase.FINISHED || phase == ExecutionStore.Phase.UNCERTAIN ? Optional.<LeaseFence>empty()
                : Optional.of(new LeaseFence(SessionFixtures.TENANT, "claim", "worker", 1, SessionFixtures.NOW.plusSeconds(60)));
        return new ExecutionStore.Run(SessionFixtures.IDENTITY, new ResultRef("external-configuration"), status, revision, 1,
                cancelled, phase, progress, lease, action, Optional.empty(), Optional.empty(), SessionFixtures.ADMISSION.verificationRef(), Optional.empty(), SessionFixtures.NOW, SessionFixtures.NOW.plusNanos(revision));
    }
}
