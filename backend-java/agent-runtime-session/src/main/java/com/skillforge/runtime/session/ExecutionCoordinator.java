package com.skillforge.runtime.session;

import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.RunStatus;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.skillforge.runtime.api.port.PlanCommand;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.spi.ExecutionStore.Action;
import com.skillforge.runtime.session.spi.ExecutionStore.Attempt;
import com.skillforge.runtime.session.spi.ExecutionStore.Claim;
import com.skillforge.runtime.session.spi.ExecutionStore.Completion;
import com.skillforge.runtime.session.spi.ExecutionStore.DispatchResult;
import com.skillforge.runtime.session.spi.ExecutionStore.Event;
import com.skillforge.runtime.session.spi.ExecutionStore.Material;
import com.skillforge.runtime.session.spi.ExecutionStore.Prepared;
import com.skillforge.runtime.session.spi.ExecutionStore.Progress;
import com.skillforge.runtime.session.spi.ExecutionStore.ResultKind;
import com.skillforge.runtime.session.spi.ExecutionStore.Run;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一个已获执行槽位的 Run 的唯一内存推进入口；数据库返回的 Run 决定每轮写权。
 * 正常循环不重新加载运行状态或 checkpoint，外部调用始终在派发事务返回之后执行。
 *
 * @param <M> 装配声明的已加载材料类型，可以持有 Core 上下文及模型消息；不拥有持久写权
 */
public final class ExecutionCoordinator<M> {
    private final ExecutionStore store;
    private final Program<M> program;
    private final Capabilities capabilities;
    private final Controls controls;
    private final LocalPlans localPlans;
    /** 只避免本实例重复领取，不替代数据库租约、CAS 或跨进程排他。 */
    private final Set<RunRef> active = ConcurrentHashMap.newKeySet();
    /** 最多每个活跃Run一份已提交材料；不保存候选状态，不作为外部派发授权。 */
    private final ConcurrentHashMap<RunRef, Loaded<M>> loaded = new ConcurrentHashMap<>();

    /**
     * 精确读取本次活跃作用域的不可变材料；完整Run身份和已提交context必须同时匹配。
     * 本方法只提供已激活Skill等只读事实，不能授予发送权或在缺失时重建历史。
     */
    public Optional<M> activeMaterials(com.skillforge.runtime.api.port.RunIdentity identity, ResultRef context) {
        Objects.requireNonNull(identity); Objects.requireNonNull(context);
        var current = loaded.get(identity.runRef());
        return current != null && current.identity().equals(identity) && current.context().equals(context)
                ? Optional.of(current.materials()) : Optional.empty();
    }

    /** 装配持久提交边界、纯推进程序和真实能力适配器，不装配旧 Core 效果循环。 */
    public ExecutionCoordinator(ExecutionStore store, Program<M> program, Capabilities capabilities, Controls controls, LocalPlans localPlans) {
        this.store = Objects.requireNonNull(store, "store");
        this.program = Objects.requireNonNull(program, "program");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.controls = Objects.requireNonNull(controls, "controls");
        this.localPlans = Objects.requireNonNull(localPlans, "localPlans");
    }

    /**
     * 领取一个 Run，首次读取当前上下文后连续推进，直到结束、等待或失去写权。
     *
     * <p>调用方必须已经占有一个有界执行槽位。外调异常向调用方传播，原 Attempt 保留在
     * AWAITING_RESULT；本方法不制造 UNKNOWN 正文，也不自动重放。所有正常状态更新只采用
     * claim、dispatch、complete 和 finish 的返回值，迟到结果落账后不再调用程序或能力。</p>
     */
    public Outcome drive(Claim claim) {
        Objects.requireNonNull(claim, "claim");
        if (!active.add(claim.run())) return new Outcome(Stop.BUSY, Optional.empty(), "execution_already_active");
        try {
            var claimed = store.claim(claim);
            if (claimed.isEmpty()) return new Outcome(Stop.NOT_CLAIMED, Optional.empty(), "execution_not_claimed");
            var run = claimed.orElseThrow();
            requireRun(claim.run(), run);
            try (var control = Objects.requireNonNull(controls.open(run), "control")) {
            if (stopped(run)) return stoppedOutcome(run);
            var context = material(run, run.progress().context());
            var materials = Objects.requireNonNull(program.open(run, context), "materials");
            while (true) {
                if (stopped(run)) return stoppedOutcome(run);
                if (control.cancelled()) return outcome(Stop.CANCELLED, run, "execution_cancelled");
                if (control.ownershipLost()) return outcome(Stop.OWNERSHIP_LOST, run, "execution_ownership_lost");
                loaded.put(run.identity().runRef(), new Loaded<>(run.identity(), context.reference(), materials));
                if (run.currentBatch().isPresent()) {
                    var resumed = new ExecutionBatchDriver<>(store, program, capabilities).recover(run, materials, context, control);
                    if (!resumed.advanced()) return resumed.stop() == Stop.REJECTED
                            ? rejectedDispatch(resumed.run(), materials, resumed.reason()) : outcome(resumed.stop(), resumed.run(), resumed.reason());
                    run = resumed.run(); materials = resumed.materials(); context = resumed.context(); continue;
                }
                if (run.phase() == ExecutionStore.Phase.AWAITING_RESULT) {
                    var saved = recoveryResult(run);
                    if (saved.isEmpty()) {
                        var quiet = quiesce(run);
                        if (quiet.attempt().result().isPresent() && quiet.attempt().resultKind().orElseThrow() != ResultKind.UNKNOWN
                                && quiet.run().phase() == ExecutionStore.Phase.AWAITING_RESULT) { run = quiet.run(); continue; }
                        return quiet.run().phase() == ExecutionStore.Phase.UNCERTAIN
                                ? outcome(Stop.UNKNOWN, quiet.run(), "execution_result_unknown") : lost(quiet.run());
                    }
                    var result = saved.orElseThrow();
                    if (result.outcome().kind() == ResultKind.UNKNOWN) {
                        var quiet = quiesce(run);
                        if (quiet.attempt().result().isPresent() && quiet.attempt().resultKind().orElseThrow() != ResultKind.UNKNOWN
                                && quiet.run().phase() == ExecutionStore.Phase.AWAITING_RESULT) { run = quiet.run(); continue; }
                        return outcome(Stop.UNKNOWN, quiet.run(), "execution_result_unknown");
                    }
                    var adopted = complete(run, materials, result.prepared(), result.attempt(), result.outcome());
                    if (!adopted.committed().advanced()) return lost(adopted.committed().run());
                    run = adopted.committed().run();
                    materials = adopted.adoption().materials();
                    context = adopted.adoption().context();
                    continue;
                }
                if (run.phase() == ExecutionStore.Phase.READY && run.currentAction().isEmpty() && run.currentBatch().isEmpty() && run.waiting().isEmpty()) {
                    var pending = store.inputs(run.identity().runRef(), 0, true, 1);
                    if (!pending.isEmpty()) {
                        if (pending.size() != 1) throw new IllegalStateException("execution_supplemental_page_invalid");
                        var input = pending.getFirst(); var content = material(run, input.content());
                        var adoption = Objects.requireNonNull(program.acceptInput(run, materials, input, content));
                        if (adoption.waiting().isPresent()) throw new IllegalStateException("execution_supplemental_wait_invalid");
                        var accepted = store.acceptInput(new ExecutionStore.AcceptInput(run.identity().runRef(), input.input(), run.revision(), run.lease().orElseThrow(), adoption.next(), adoption.context(), adoption.events()));
                        requireRun(run.identity().runRef(), accepted.run());
                        if (!accepted.advanced()) return lost(accepted.run());
                        if (!accepted.run().progress().equals(adoption.next()) || !accepted.input().input().equals(input.input())) throw new IllegalStateException("execution_supplemental_receipt_mismatch");
                        run = accepted.run(); materials = Objects.requireNonNull(program.inputCommitted(accepted, adoption.materials())); context = adoption.context(); continue;
                    }
                }
                Step step;
                if (run.waiting().isPresent()) {
                    var waiting = store.waiting(run.identity().runRef(), run.waiting().orElseThrow())
                            .orElseThrow(() -> new IllegalStateException("execution_wait_missing"));
                    var response = waiting.acceptedResponse().orElseThrow(() -> new IllegalStateException("execution_wait_response_missing"));
                    var content = material(run, response.response());
                    if (waiting.kind() == ExecutionStore.WaitKind.HUMAN || waiting.kind() == ExecutionStore.WaitKind.DELEGATION || waiting.kind() == ExecutionStore.WaitKind.PLAN) {
                        var adoption = Objects.requireNonNull(program.acceptResponse(run, materials, waiting, content), "adoption");
                        if (adoption.waiting().isPresent()) throw new IllegalStateException("execution_local_response_rewait_invalid");
                        var accepted = store.acceptLocalResponse(new ExecutionStore.AcceptLocalResponse(run.identity().runRef(), waiting.reference(),
                                response.input(), run.revision(), run.lease().orElseThrow(), adoption.next(), adoption.context(), adoption.events()));
                        requireRun(run.identity().runRef(), accepted.run());
                        if (!accepted.advanced()) return lost(accepted.run());
                        if (!accepted.run().progress().equals(adoption.next())) throw new IllegalStateException("execution_committed_progress_mismatch");
                        run = accepted.run(); materials = adoption.materials(); context = adoption.context(); continue;
                    }
                    step = Objects.requireNonNull(program.continuation(run, materials, waiting, content), "step");
                } else step = Objects.requireNonNull(program.next(run, materials), "step");
                if (step instanceof Step.Yield yielded) return outcome(Stop.YIELDED, run, yielded.reason());
                if (step instanceof Step.Finish finish) {
                    var finished = store.finish(new ExecutionStore.Finish(run.identity().runRef(), run.revision(),
                            run.lease().orElseThrow(), finish.status(), finish.events(), finish.terminalContext()));
                    requireRun(run.identity().runRef(), finished);
                    return stoppedOutcome(finished);
                }
                if (step instanceof Step.PlanLocal plan) {
                    if (control.cancelled()) return outcome(Stop.CANCELLED, run, "execution_cancelled");
                    if (control.ownershipLost()) return outcome(Stop.OWNERSHIP_LOST, run, "execution_ownership_lost");
                    var before = materials;
                    var local = Objects.requireNonNull(localPlans.execute(run, plan, (current, result) -> program.planAdoption(current, before, plan, result)));
                    requireRun(run.identity().runRef(), local.run());
                    if (!local.advanced()) return lost(local.run());
                    var adoption = local.adoption().orElseThrow(() -> new IllegalStateException("execution_local_plan_adoption_missing"));
                    if (!local.run().progress().equals(adoption.next()) || !retainsLease(run, local.run()) && local.run().phase() != ExecutionStore.Phase.WAITING)
                        throw new IllegalStateException("execution_local_plan_receipt_mismatch");
                    run = local.run(); materials = adoption.materials(); context = adoption.context(); continue;
                }
                if (step instanceof Step.Batch batch) {
                    var result = new ExecutionBatchDriver<>(store, program, capabilities).dispatch(run, materials, context, batch, control);
                    if (!result.advanced()) return result.stop() == Stop.REJECTED
                            ? rejectedDispatch(result.run(), materials, result.reason()) : outcome(result.stop(), result.run(), result.reason());
                    run = result.run(); materials = result.materials(); context = result.context(); continue;
                }
                var dispatch = (Step.Dispatch) step;
                if (control.cancelled()) return outcome(Stop.CANCELLED, run, "execution_cancelled");
                if (control.ownershipLost()) return outcome(Stop.OWNERSHIP_LOST, run, "execution_ownership_lost");
                var registered = store.dispatch(new ExecutionStore.Dispatch(run.identity().runRef(), run.revision(),
                        run.lease().orElseThrow(), dispatch.action(), dispatch.attemptId(), dispatch.budget(), dispatch.actor(), dispatch.continuation(), dispatch.events()));
                if (registered instanceof DispatchResult.Rejected rejected)
                    return rejectedDispatch(run, materials, rejected.reason());
                Run dispatched;
                Attempt attempt;
                CapabilityResult actual;
                if (registered instanceof DispatchResult.Registered fresh) {
                    requireDispatch(run, dispatch, fresh.run(), fresh.action(), fresh.attempt());
                    dispatched = fresh.run();
                    attempt = fresh.attempt();
                    if (stopped(dispatched)) return stoppedOutcome(dispatched);
                    if (!retainsLease(run, dispatched)) return lost(dispatched);
                    if (attempt.result().isPresent() || attempt.fence() != dispatched.fence())
                        throw new IllegalStateException("execution_registered_attempt_invalid");
                    if (control.cancelled()) return outcome(Stop.CANCELLED, dispatched, "execution_cancelled");
                    if (control.ownershipLost()) return outcome(Stop.OWNERSHIP_LOST, dispatched, "execution_ownership_lost");
                    // 只有本次原子登记签发的 Registered 能进入实际外调；此时没有数据库事务在途。
                    actual = Objects.requireNonNull(capabilities.invoke(fresh, dispatch.action()), "capabilityResult");
                } else {
                    var existing = (DispatchResult.Existing) registered;
                    requireDispatch(run, dispatch, existing.run(), existing.action(), existing.attempt());
                    dispatched = existing.run();
                    attempt = existing.attempt();
                    if (stopped(dispatched)) return stoppedOutcome(dispatched);
                    if (!retainsLease(run, dispatched)) return lost(dispatched);
                    if (attempt.result().isEmpty()) {
                        var quiet = quiesce(dispatched);
                        if (quiet.attempt().result().isEmpty() || quiet.attempt().resultKind().orElseThrow() == ResultKind.UNKNOWN)
                            return outcome(Stop.UNKNOWN, quiet.run(), "execution_result_unknown");
                        if (stopped(quiet.run())) return stoppedOutcome(quiet.run());
                        if (!retainsLease(dispatched, quiet.run())) return lost(quiet.run());
                        dispatched = quiet.run(); attempt = quiet.attempt();
                    }
                    actual = savedResult(dispatched, attempt);
                    if (actual.kind() == ResultKind.UNKNOWN) return outcome(Stop.UNKNOWN, quiesce(dispatched).run(), "execution_result_unknown");
                }
                var adopted = complete(dispatched, materials, dispatch.action(), attempt, actual);
                if (!adopted.committed().advanced()) return lost(adopted.committed().run());
                run = adopted.committed().run();
                // 结果和上下文材料已提交后才替换当前材料；拒绝、取消和旧租约不能在内存推进。
                materials = adopted.adoption().materials();
                context = adopted.adoption().context();
                if (stopped(run)) return stoppedOutcome(run);
                if (control.cancelled()) return outcome(Stop.CANCELLED, run, "execution_cancelled");
                if (control.ownershipLost()) return outcome(Stop.OWNERSHIP_LOST, run, "execution_ownership_lost");
                if (actual.kind() == ResultKind.UNKNOWN) return outcome(Stop.UNKNOWN, run, "execution_result_unknown");
            }
            }
        } finally {
            loaded.remove(claim.run());
            active.remove(claim.run());
        }
    }
    /** 单次、整组及批次续跑的确定派发拒绝共用出口；终态语义与完成事件由Program拥有。 */
    private Outcome rejectedDispatch(Run run, M materials, String reason) {
        if (run.currentBatch().isPresent()) return outcome(Stop.REJECTED, run, reason);
        var terminal = Objects.requireNonNull(program.dispatchRejected(run, materials, reason));
        if (terminal.isEmpty()) return outcome(Stop.REJECTED, run, reason);
        var finish = terminal.orElseThrow();
        var finished = store.finish(new ExecutionStore.Finish(run.identity().runRef(), run.revision(),
                run.lease().orElseThrow(), finish.status(), finish.events(), finish.terminalContext()));
        requireRun(run.identity().runRef(), finished);
        return stoppedOutcome(finished);
    }

    private record Loaded<T>(com.skillforge.runtime.api.port.RunIdentity identity, ResultRef context, T materials) { }

    /** 计算候选后一次保存真实结果、用量、上下文与公开事件；提交前不发布新材料。 */
    private Adopted<M> complete(Run run, M materials, Prepared prepared, Attempt attempt, CapabilityResult actual) {
        var adoption = Objects.requireNonNull(program.adopt(run, materials, prepared, actual), "adoption");
        var committed = store.complete(new Completion(run.identity().runRef(), attempt.id(), actual.kind(),
                actual.result(), actual.feedback(), actual.externalOperation(), actual.usage(), run.revision(),
                run.lease().orElseThrow(), adoption.next(), adoption.context(), adoption.events(), adoption.waiting()));
        requireRun(run.identity().runRef(), committed.run());
        if (committed.advanced() && !committed.run().progress().equals(adoption.next()))
            throw new IllegalStateException("execution_committed_progress_mismatch");
        return new Adopted<>(committed, adoption);
    }

    /** 恢复只读原Attempt及明确支持的原operation结果查询；不登记新Attempt或构造发送许可。 */
    private Optional<Saved> recoveryResult(Run run) {
        var reference = run.currentAction().orElseThrow(() -> new IllegalStateException("execution_current_action_missing"));
        var action = store.action(reference).orElseThrow(() -> new IllegalStateException("execution_action_missing"));
        if (!action.run().equals(run.identity().runRef())) throw new IllegalStateException("execution_action_owner_mismatch");
        var id = action.currentAttempt();
        var attempt = store.attempt(run.identity().runRef(), id)
                .orElseThrow(() -> new IllegalStateException("execution_attempt_missing"));
        if (!attempt.action().equals(reference) || !attempt.run().equals(run.identity().runRef()))
            throw new IllegalStateException("execution_attempt_owner_mismatch");
        var request = material(run, attempt.request());
        var prepared = program.restorePrepared(run,new Prepared(action.reference(), action.ordinal(), action.kind(), action.target(), action.capability(),
                request, AgentExecutionCodec.digest(request.content().schemaId() + "\0" + request.content().canonicalJson())));
        if (attempt.result().isEmpty()) return capabilities.observe(run, prepared, attempt).map(actual -> {
            if (actual.kind() != ResultKind.SUCCEEDED && actual.kind() != ResultKind.FAILED) throw new IllegalStateException("execution_observed_result_not_final");
            return new Saved(prepared, attempt, actual);
        });
        return Optional.of(new Saved(prepared, attempt, savedResult(run, attempt)));
    }

    /** 无结果登记只静止一次；竞争期间取得准确结果时由调用方走同一原结果采纳路径。 */
    private ExecutionStore.QuiescenceResult quiesce(Run run) {
        var action = store.action(run.currentAction().orElseThrow())
                .orElseThrow(() -> new IllegalStateException("execution_action_missing"));
        var quiet = store.quiesceUnknown(new ExecutionStore.QuiesceUnknown(run.identity().runRef(), action.currentAttempt(),
                run.revision(), run.lease().orElseThrow(), List.of()));
        requireRun(run.identity().runRef(), quiet.run());
        return quiet;
    }

    /** 原结果用量已经归预算账本；重复 complete 的 Store 路径必须复用原结算，不覆盖其事实。 */
    private CapabilityResult savedResult(Run run, Attempt attempt) {
        return new CapabilityResult(attempt.resultKind().orElseThrow(), material(run, attempt.result().orElseThrow()),
                attempt.feedback().map(reference -> material(run, reference)), attempt.externalOperation(),
                ExecutionBudgetPort.Usage.unreported());
    }

    /** 材料读取仅发生在首次领取或原结果恢复，引用内容矛盾时失败关闭。 */
    private Material material(Run run, ResultRef reference) {
        var loaded = store.material(run.identity().runRef().tenantId(), reference)
                .orElseThrow(() -> new IllegalStateException("execution_material_missing"));
        if (!loaded.reference().equals(reference)) throw new IllegalStateException("execution_material_reference_mismatch");
        return loaded;
    }

    /** 真实派发回执必须属于本轮请求；Existing 不能被包装成新的 Registered。 */
    private static void requireDispatch(Run before, Step.Dispatch command, Run run, Action action, Attempt attempt) {
        requireRun(before.identity().runRef(), run);
        var prepared = command.action();
        if (!action.reference().equals(prepared.action()) || !action.run().equals(before.identity().runRef())
                || action.ordinal() != prepared.ordinal() || action.kind() != prepared.kind()
                || !action.target().equals(prepared.target()) || !action.capability().equals(prepared.capability())
                || (command.continuation().isEmpty() && (!action.request().equals(prepared.request().reference()) || !action.fingerprint().equals(prepared.fingerprint())))
                || !attempt.request().equals(prepared.request().reference()) || !attempt.continuation().equals(command.continuation())
                || !attempt.id().equals(command.attemptId()) || !attempt.id().equals(action.currentAttempt())
                || !attempt.action().equals(action.reference())
                || !attempt.run().equals(before.identity().runRef())) {
            throw new IllegalStateException("execution_dispatch_receipt_mismatch");
        }
    }

    /** 返回状态的完整运行身份必须匹配所领取的 Run。 */
    private static void requireRun(RunRef expected, Run run) {
        if (!expected.equals(run.identity().runRef())) throw new IllegalStateException("execution_run_owner_mismatch");
    }

    /** 回执不能借用接管者的新租约；原领取身份之外的结果只允许由 Store 落账。 */
    private static boolean retainsLease(Run before, Run after) {
        if (before.lease().isEmpty() || after.lease().isEmpty()) return false;
        var original = before.lease().orElseThrow();
        var current = after.lease().orElseThrow();
        return original.fencingToken() == current.fencingToken()
                && original.claimId().equals(current.claimId()) && original.workerId().equals(current.workerId());
    }

    /** 等待、取消、终态和租约丢失都阻止准备新的外部动作。 */
    private static boolean stopped(Run run) {
        return run.cancelled() || run.phase() == ExecutionStore.Phase.FINISHED
                || run.phase() == ExecutionStore.Phase.WAITING || run.phase() == ExecutionStore.Phase.UNCERTAIN || run.lease().isEmpty();
    }

    /** 停止原因来自提交结果，不在内存中推定新的持久终态。 */
    private static Outcome stoppedOutcome(Run run) {
        if (run.cancelled()) return outcome(Stop.CANCELLED, run, "execution_cancelled");
        if (run.phase() == ExecutionStore.Phase.FINISHED) return outcome(Stop.FINISHED, run, "execution_finished");
        if (run.phase() == ExecutionStore.Phase.WAITING) return outcome(Stop.WAITING, run, "execution_waiting");
        if (run.phase() == ExecutionStore.Phase.UNCERTAIN) return outcome(Stop.UNKNOWN, run, "execution_result_unknown");
        return outcome(Stop.OWNERSHIP_LOST, run, "execution_ownership_lost");
    }

    /** 迟到结果可能已落账，但没有推进权时不得继续读取下一动作。 */
    private static Outcome lost(Run run) {
        return stopped(run) ? stoppedOutcome(run) : outcome(Stop.OWNERSHIP_LOST, run, "execution_completion_not_advanced");
    }

    /** 显式携带当前已提交状态，退出不额外加载 Run。 */
    private static Outcome outcome(Stop stop, Run run, String reason) {
        return new Outcome(stop, Optional.of(run), reason);
    }

    /**
     * 已加载材料的程序，准备和采纳只返回候选，不写存储或发起模型/工具调用。
     * @param <M> 明确的装配材料类型，不使用通用对象或反射传递固定结构
     */
    public interface Program<M> {
        /** 只读恢复原请求正文闭包；不得登记派发或授予发送权。 */
        default Prepared restorePrepared(Run run, Prepared prepared) { return prepared; }
        /** 首次领取时解析上下文；冻结配置由其引用的权威来源读取一次，不假设配置属于 ExecutionStore。 */
        M open(Run run, Material context);
        /** 使用当前已提交 Run 和已加载材料计算下一实际动作或本地结束。 */
        Step next(Run run, M materials);
        /** 明确的派发前拒绝可由状态所有者收口；暂停、写权变化及未知拒绝默认保留原状态。 */
        default Optional<Step.Finish> dispatchRejected(Run run, M materials, String reason) { return Optional.empty(); }
        /** 已全部返回的批次在拒绝续跑后采纳原事实；不允许借终止掩盖未决外部效果。 */
        default Optional<Adoption<M>> batchDispatchRejectionAdoption(Run run, M materials, List<BatchOutcome> results, String reason) { return Optional.empty(); }
        /** 已接受等待响应只准备原逻辑动作的新Attempt，不能要求模型重新作出原决定。 */
        Step continuation(Run run, M materials, ExecutionStore.Waiting waiting, Material response);
        /** 人类响应只形成一次本地消息采纳，不发起Provider或登记预算。 */
        Adoption<M> acceptResponse(Run run, M materials, ExecutionStore.Waiting waiting, Material response);
        /** 原Run补充只本地追加用户消息，不能重写已产生的模型决定或当前外部请求。 */
        Adoption<M> acceptInput(Run run, M materials, ExecutionStore.SupplementalInput input, Material content);
        /** 仅提交成功后消费返回的可信操作者，更新内存材料及宿主活动帧，不重新加载配置或派发能力。 */
        default M inputCommitted(ExecutionStore.InputAcceptance accepted, M materials) { return materials; }
        /** 原MODEL决定的Plan实际回执只作本地上下文转换，业务与context由LocalPlans同事务提交。 */
        Adoption<M> planAdoption(Run run, M materials, Step.PlanLocal command, Material actualResult);
        /** 只校验一个真实批次结果并形成同事务等待/公开事实，不追加部分模型回合。 */
        BatchResult inspectBatch(Run run, M materials, BatchCall result, int slot);
        /** 从原冻结材料决定同组调度方式；恢复不读取当前目录。 */
        boolean batchParallel(M materials);
        /** 全部准确结果按原slot一次性形成完整assistant/N tool回合和精确增量。 */
        Adoption<M> adoptBatch(Run run, M materials, List<BatchOutcome> results);
        /** 完整当前槽位与精确已接受回复共同准备续跑；预算终止仍提交已发生的物理次数。 */
        BatchPlan<M> batchContinuation(Run run, M materials, List<BatchOutcome> results, List<BatchResponse> responses);
        /** 只从实际回执计算上下文增量/引用与下一进度；新材料仅在 complete 成功后采用。 */
        Adoption<M> adopt(Run run, M materials, Prepared prepared, CapabilityResult result);
    }

    /** 实际能力适配边界；只接收刚登记的派发许可，按 Prepared.kind 调用真实 Provider 或 Handler。 */
    @FunctionalInterface
    public interface Capabilities {
        /** 外调不持数据库事务；返回真实结果及已完成安全转换的材料，不能把返回值冒充已保存结果。 */
        CapabilityResult invoke(DispatchResult.Registered dispatch, Prepared prepared);
        /**
         * 仅明确拥有原operation查询契约的适配器可覆盖；须核当前读取授权及原请求身份。
         * 返回值只允许确定成功/失败，尚未确定或不存在时返回empty。不得submit、重发、取消或包装Registered。
         */
        default Optional<CapabilityResult> observe(Run run, Prepared prepared, Attempt original) { return Optional.empty(); }
    }

    /** 单一活动调度器提供内存取消与失租观察，getter不得访问持久存储。 */
    @FunctionalInterface
    public interface Controls {
        /** 领取后登记一个活动Run；close释放该活动登记。 */
        Control open(Run run);
    }
    /** Plan所属宿主和Store在同一事务执行本地业务及pure adopter，不注册Provider调用。 */
    public interface LocalPlans {
        <T> LocalPlanResult<T> execute(Run run, Step.PlanLocal command, java.util.function.BiFunction<Run, Material, Adoption<T>> adopter);
    }
    /** 本地真实业务及context已提交后返回当前Run；advanced=false禁止采用内存候选。 */
    public record LocalPlanResult<T>(Run run, Optional<Adoption<T>> adoption, boolean advanced) {
        public LocalPlanResult { Objects.requireNonNull(run); Objects.requireNonNull(adoption); if (advanced && adoption.isEmpty()) throw new IllegalArgumentException("execution_local_plan_adoption_missing"); }
    }
    /** 真实外调结果仍须提交；这些信号只阻止准备或发送后续新动作。 */
    public interface Control extends AutoCloseable {
        boolean cancelled();
        boolean ownershipLost();
        @Override void close();
    }

    /** 程序只描述下一业务边界，不提供 CLAIM/START/RESULT 内部阶段协议。 */
    public sealed interface Step {
        /** 原MODEL ActionId是本地业务幂等键；不造Kind.PLAN、Provider Attempt或预算登记。 */
        record PlanLocal(com.skillforge.runtime.api.port.ActionRef sourceModelAction, PlanCommand command, VerifiedIdentity actor, Material request) implements Step {
            public PlanLocal { Objects.requireNonNull(sourceModelAction); Objects.requireNonNull(command); Objects.requireNonNull(actor); Objects.requireNonNull(request); }
        }
        /** 整组必须由同一原MODEL决定拥有；登记后各Dispatch只发一次。 */
        record Batch(com.skillforge.runtime.api.port.ActionRef sourceModelAction, List<ExecutionStore.BatchMember> members,
                VerifiedIdentity actor) implements Step {
            public Batch {
                Objects.requireNonNull(sourceModelAction); members = List.copyOf(members); Objects.requireNonNull(actor);
                if (members.size() < 2 || members.size() > com.skillforge.runtime.api.ToolCallLimits.READ_ONLY_BATCH) throw new IllegalArgumentException("execution_batch_size_invalid");
            }
            /** 仅新物理调用具有发送需求；顺序仍由members的原slot决定。 */
            public List<Dispatch> calls() { return members.stream().filter(ExecutionStore.BatchMember.Fresh.class::isInstance)
                    .map(ExecutionStore.BatchMember.Fresh.class::cast).map(member -> new Dispatch(member.request().action(), member.request().attemptId(), member.request().budget(), actor, Optional.empty(), List.of())).toList(); }
        }
        /** @param action 本轮真实准备请求 @param attemptId 一次派发身份 @param budget 本次预算准入需求 @param events 与派发同事务保存的安全开始事实 */
        record Dispatch(Prepared action, String attemptId, ExecutionBudgetPort.Demand budget, VerifiedIdentity actor,
                Optional<ExecutionStore.WaitRef> continuation, List<Event> events) implements Step {
            /** 拒绝缺失准备或预算；身份跨字段约束在 Store.Dispatch 再核验。 */
            public Dispatch { Objects.requireNonNull(action); text(attemptId); Objects.requireNonNull(budget); Objects.requireNonNull(actor); Objects.requireNonNull(continuation); events = List.copyOf(events); }
        }
        /** @param status 已确定的结束状态 @param events 同事务提交的安全公开事件 */
        record Finish(RunStatus status, List<Event> events, Optional<Material> terminalContext) implements Step {
            public Finish(RunStatus status, List<Event> events) { this(status, events, Optional.empty()); }
            /** 终态合法性由 Store.Finish 校验，不自行改写运行状态。 */
            public Finish { Objects.requireNonNull(status); events = List.copyOf(events); Objects.requireNonNull(terminalContext); }
        }
        /** @param reason 当前没有可推进动作的明确原因，不表示已保存人工等待 */
        record Yield(String reason) implements Step {
            /** 等待原因不能缺失，不以 null 隐式表示挂起。 */
            public Yield { text(reason); }
        }
    }

    /** 实际回调或精确保存结果，不借用另一个Attempt的调用权。 */
    public record BatchCall(Prepared action, String attemptId, CapabilityResult result) {
        public BatchCall { Objects.requireNonNull(action); text(attemptId); Objects.requireNonNull(result); }
        @Override public String toString() { return "ExecutionBatchCall[REDACTED]"; }
    }
    /** 当前原生槽位的真实物理结果或精确历史复用；复用不加载原结果正文或取得发送权。 */
    public sealed interface BatchOutcome {
        int slot();
        record Actual(int slot, BatchCall call) implements BatchOutcome {
            public Actual { batchSlot(slot); Objects.requireNonNull(call); }
        }
        record Reused(int slot, ExecutionStore.BatchMember.Reused source) implements BatchOutcome {
            public Reused { batchSlot(slot); Objects.requireNonNull(source); if (source.slot() != slot) throw new IllegalArgumentException("execution_batch_slot_mismatch"); }
        }
    }
    /** 外部回复只读取原等待保存的精确材料；actor由Store逐条恢复并重新授权。 */
    public record BatchResponse(ExecutionStore.Waiting waiting, Material response) {
        public BatchResponse { Objects.requireNonNull(waiting); Objects.requireNonNull(response); }
    }
    /** 续跑仅发送当前已接受等待；不能终结时用专门原子终止提交保存真实次数。 */
    public sealed interface BatchPlan<M> {
        record Resume<M>(List<Step.Dispatch> calls) implements BatchPlan<M> {
            public Resume { calls = List.copyOf(calls); if (calls.isEmpty() || calls.size() > com.skillforge.runtime.api.ToolCallLimits.READ_ONLY_BATCH) throw new IllegalArgumentException("execution_batch_resume_invalid"); }
        }
        record Terminate<M>(Adoption<M> adoption, RunStatus status) implements BatchPlan<M> {
            public Terminate { Objects.requireNonNull(adoption); Objects.requireNonNull(status); }
        }
    }
    private static void batchSlot(int slot) { if (slot < 1 || slot > com.skillforge.runtime.api.ToolCallLimits.READ_ONLY_BATCH) throw new IllegalArgumentException("execution_batch_slot_invalid"); }
    public record BatchResult(Optional<ExecutionStore.WaitDeclaration> waiting, List<Event> events) {
        public BatchResult { Objects.requireNonNull(waiting); events = List.copyOf(events); }
    }

    /**
     * 实际能力结果，材料尚未由 ExecutionStore 保存。
     * @param kind 确定成功、失败、未知或需要工具输入
     * @param result 实际原结果材料
     * @param feedback 安全模型反馈材料；缺失不等于空正文
     * @param externalOperation 已有供应商原操作身份，缺失时不自行生成
     * @param usage 实际已报告用量，未知值保持缺报
     */
    public record CapabilityResult(ResultKind kind, Material result, Optional<Material> feedback,
            Optional<String> externalOperation, ExecutionBudgetPort.Usage usage) {
        /** 固定结构使用明确类型，原结果和安全反馈不得缺失其封套。 */
        public CapabilityResult {
            Objects.requireNonNull(kind); Objects.requireNonNull(result); Objects.requireNonNull(feedback);
            Objects.requireNonNull(externalOperation); Objects.requireNonNull(usage);
        }
        /** 原结果正文和外部操作身份不进入普通日志。 */
        @Override public String toString() { return "ExecutionCapabilityResult[kind=" + kind + ",REDACTED]"; }
    }

    /**
     * 结果提交候选，内存材料不能在事务提交前采用。
     * @param <M> 明确的活跃材料类型
     * @param materials 保存后直接继续使用的新材料
     * @param next 新进度及精确上下文引用
     * @param context 只保存必要增量和精确引用，不递归拷贝 Run 或每轮完整历史
     * @param events 已由实际结果生成的安全公开事件
     */
    public record Adoption<M>(M materials, Progress next, Material context, List<Event> events, Optional<ExecutionStore.WaitDeclaration> waiting) {
        /** 上下文引用必须与下一进度完全相同，禁止指向尚未提交的其他材料。 */
        public Adoption {
            Objects.requireNonNull(materials); Objects.requireNonNull(next); Objects.requireNonNull(context); Objects.requireNonNull(waiting);
            events = List.copyOf(events);
            if (!next.context().equals(context.reference())) throw new IllegalArgumentException("execution_context_reference_mismatch");
        }
        /** 活跃模型上下文不进入普通对象日志。 */
        @Override public String toString() { return "ExecutionAdoption[REDACTED]"; }
    }

    /** 明确区分已提交终态、等待、无结果及失去写权，不把退出统一报告为成功。 */
    public enum Stop { NOT_CLAIMED, BUSY, FINISHED, CANCELLED, WAITING, UNKNOWN, OWNERSHIP_LOST, REJECTED, YIELDED }

    /** @param stop 本次驱动停止分类 @param run 当前已提交状态；未领取时缺失 @param reason 固定诊断原因 */
    public record Outcome(Stop stop, Optional<Run> run, String reason) {
        /** 不构造不存在的 Run，也不以空原因掩盖拒绝或未知。 */
        public Outcome { Objects.requireNonNull(stop); Objects.requireNonNull(run); text(reason); }
    }

    /** 当前恢复只携带原动作请求和原已保存结果，不产生新的派发身份。 */
    private record Saved(Prepared prepared, Attempt attempt, CapabilityResult outcome) { }

    /** 将持久提交和候选材料显式隔离，调用方必须先检查 advanced。 */
    private record Adopted<M>(ExecutionStore.CompletionResult committed, Adoption<M> adoption) { }

    /** 业务身份和固定原因必须显式存在。 */
    private static void text(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("execution_text_required");
    }
}
