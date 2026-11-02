package com.skillforge.runtime.session.spi;

import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.RunStatus;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.dag.DagWork;
import com.skillforge.runtime.api.dag.DagPlan;
import com.skillforge.runtime.api.port.ActionRef;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.port.LeaseFence;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.skillforge.runtime.api.port.ToolPortal.ToolInputMethod;
import com.skillforge.runtime.api.port.WorkflowNodeCallIdentity;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionLease;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Agent 执行的业务提交边界。成功提交直接返回当前状态，活跃执行不重新加载 checkpoint。
 * 实现拥有事务、租户绑定、CAS 和预算根锁；这些基础设施不进入调用方签名。
 */
public interface ExecutionStore {
    AdmissionResult admit(Admission command);
    Optional<Run> load(RunRef run);
    Optional<Run> claim(Claim command);
    DispatchResult dispatch(Dispatch command);
    BatchDispatchResult dispatchBatch(BatchDispatch command);
    BatchDispatchResult dispatchBatchContinuations(BatchContinue command);
    List<Waiting> batchWaits(BatchRef reference);
    Run stopBatch(BatchStop command);
    boolean authorizeSend(DispatchResult.Registered registered, Prepared prepared);
    CompletionResult complete(Completion command);
    CompletionResult completeBatch(BatchCompletion command);
    BatchAdoptionResult adoptBatch(BatchAdopt command);
    BatchState quiesceBatch(BatchQuiesce command);
    List<BatchEntry> batch(BatchRef reference);
    ResumeResult resume(Resume command);
    LocalResponseResult acceptLocalResponse(AcceptLocalResponse command);
    InputOffer offerInput(OfferInput command);
    List<SupplementalInput> inputs(RunRef run, long afterOrdinal, boolean pendingOnly, int limit);
    InputAcceptance acceptInput(AcceptInput command);
    QuiescenceResult quiesceUnknown(QuiesceUnknown command);
    Run finish(Finish command);
    Run cancel(Cancel command);
    Run renew(RunRef run, LeaseFence lease, Duration duration);
    Optional<Material> material(TenantId tenant, ResultRef reference);
    /** 精确材料集合读取；保留顺序与重复项，缺失逐项返回empty。 */
    List<Optional<Material>> materials(TenantId tenant, List<ResultRef> references);
    Optional<Action> action(ActionRef reference);
    List<Action> actions(RunRef run, long afterOrdinal, int limit);
    TokenUsage usage(RunRef run);
    Optional<Attempt> attempt(RunRef run, String attemptId);
    Optional<Waiting> waiting(RunRef run, WaitRef reference);
    Optional<Waiting> waiting(RunRef run, String sourceAttempt);
    Optional<Waiting> response(RunRef run, InputId input);
    /** 读取真实准入input_ref原材料；本方法不授予业务权限，Host必须核原Run owner与来源。 */
    Optional<Material> originalInput(RunRef run);
    /** 精确查询该Run原提交事件；不授予业务读取权限，不扫描Session历史。 */
    Optional<SavedEvent> event(RunRef run,EventId event);
    /** 精确定位该Session已提交来源；不授予读取权限，Host仍须核来源Run完整owner与公开范围。 */
    Optional<SavedEvent> event(SessionRef session,EventId event);
    List<RunRef> due(TenantId tenant, int limit);
    Optional<Instant> nextDueAt(TenantId tenant);
    List<SavedEvent> events(SessionRef session, long afterSequence, int limit);
    /** 读取同租户同会话的有界事件窗口，序列下界不含、上界包含；不授予业务读取权限。 */
    List<SavedEvent> eventsInRange(SessionRef session, long afterSequence, long throughSequence, int limit);

    enum Phase { READY, AWAITING_RESULT, WAITING, UNCERTAIN, FINISHED }
    enum Kind { MODEL, TOOL, KNOWLEDGE, WORKFLOW, DELEGATION }
    enum ActionStatus { DISPATCHED, SUCCEEDED, FAILED, UNKNOWN, WAITING_INPUT }
    enum ResultKind { SUCCEEDED, FAILED, UNKNOWN, INPUT_REQUIRED }
    enum WaitKind { TOOL, HUMAN, DELEGATION, WORKFLOW, PLAN }

    /** 可预期的业务提交拒绝；技术数据库故障不得包装成该类型或改报为业务冲突。 */
    final class Rejection extends IllegalStateException {
        /** 由状态、授权或幂等规则拥有者给出的固定原因，不含SQL或用户正文。 */
        private final String reason;
        /** 只接受明确原因，HTTP等宿主边界可据此映射拒绝，不解析普通异常文案。 */
        public Rejection(String reason) { super(reason); text(reason); this.reason = reason; }
        /** 返回本次失败的稳定业务分类。 */
        public String reason() { return reason; }
    }

    /** 当前进度只含计数和材料引用；模型历史、请求及结果正文不嵌入 Run。 */
    record Progress(long decisions, long toolCalls, long knowledgeCalls, ResultRef context) {
        public Progress {
            if (decisions < 0 || toolCalls < 0 || knowledgeCalls < 0)
                throw new IllegalArgumentException("execution_progress_negative");
            Objects.requireNonNull(context, "context");
        }
    }

    /** 唯一可变运行状态；fence 在租约释放后仍保留，接管只能递增。 */
    record Run(RunIdentity identity, ResultRef configuration, RunStatus status, long revision,
               long fence, boolean cancelled, Phase phase, Progress progress,
               Optional<LeaseFence> lease, Optional<ActionRef> currentAction,
               Optional<BatchRef> currentBatch,
               Optional<WaitRef> waiting, ResultRef actorVerificationRef,
               Optional<Instant> nextDueAt, Instant createdAt, Instant updatedAt) {
        public Run {
            Objects.requireNonNull(identity); Objects.requireNonNull(configuration); Objects.requireNonNull(status);
            Objects.requireNonNull(phase); Objects.requireNonNull(progress); Objects.requireNonNull(lease);
            Objects.requireNonNull(currentAction); Objects.requireNonNull(nextDueAt);
            Objects.requireNonNull(currentBatch);
            Objects.requireNonNull(waiting); Objects.requireNonNull(actorVerificationRef);
            Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt);
            if (revision <= 0 || fence < 0 || updatedAt.isBefore(createdAt))
                throw new IllegalArgumentException("execution_run_invalid");
            lease.ifPresent(value -> {
                if (!value.tenantId().equals(identity.runRef().tenantId()) || value.fencingToken() != fence)
                    throw new IllegalArgumentException("execution_lease_mismatch");
            });
            currentAction.ifPresent(value -> sameTenant(identity.runRef(), value.tenantId()));
            currentBatch.ifPresent(value -> {
                if (!value.run().equals(identity.runRef()) || currentAction.isPresent())
                    throw new IllegalArgumentException("execution_batch_owner_mismatch");
            });
            waiting.ifPresent(value -> {
                sameTenant(identity.runRef(), value.action().tenantId());
                if (!currentAction.equals(Optional.of(value.action())))
                    throw new IllegalArgumentException("execution_wait_action_mismatch");
            });
            if (phase == Phase.WAITING && waiting.isEmpty() && currentBatch.isEmpty())
                throw new IllegalArgumentException("execution_wait_missing");
            if ((phase == Phase.WAITING || phase == Phase.UNCERTAIN) && (lease.isPresent() || nextDueAt.isPresent()))
                throw new IllegalArgumentException("execution_quiescent_lease_invalid");
            if ((phase == Phase.FINISHED) != terminal(status))
                throw new IllegalArgumentException("execution_terminal_phase_mismatch");
        }
        @Override public String toString() { return "ExecutionRun[status=" + status + ",revision=" + revision + "]"; }
    }

    /** 原始协议材料按租户不可变保存一次，引用不能更换内容。不得序列化整个 Run。 */
    record Material(ResultRef reference, TypedPayload content) {
        public Material { Objects.requireNonNull(reference); Objects.requireNonNull(content); }
        @Override public String toString() { return "ExecutionMaterial[REDACTED]"; }
    }
    /** 只汇总真实已报告Tokens下界；缺报、UNKNOWN及无结果调用明确保留complete=false。 */
    record TokenUsage(long reportedTokens, boolean complete) {
        public TokenUsage { if (reportedTokens < 0) throw new IllegalArgumentException("execution_usage_negative"); }
    }

    record Action(ActionRef reference, RunRef run, long ordinal, Kind kind, String target,
                  ResultRef capability, ResultRef request, String fingerprint, ActionStatus status,
                  String currentAttempt, Optional<String> selectedAttempt, Optional<BatchSlot> batchSlot) {
        public Action {
            Objects.requireNonNull(reference); Objects.requireNonNull(run); Objects.requireNonNull(kind);
            Objects.requireNonNull(capability); Objects.requireNonNull(request); Objects.requireNonNull(status);
            Objects.requireNonNull(selectedAttempt); text(currentAttempt); text(target); text(fingerprint);
            Objects.requireNonNull(batchSlot);
            batchSlot.ifPresent(value -> {
                if (!value.batch().run().equals(run) || kind != Kind.TOOL)
                    throw new IllegalArgumentException("execution_batch_action_mismatch");
            });
            sameTenant(run, reference.tenantId());
            if (ordinal <= 0) throw new IllegalArgumentException("execution_action_ordinal_invalid");
        }
        @Override public String toString() { return "ExecutionAction[kind=" + kind + ",status=" + status + "]"; }
    }

    /** 登记派发即跨过不可自动重放的边界；没有结果的登记不能重新签发调用权。 */
    record Attempt(String id, ActionRef action, RunRef run, long fence, ResultRef request,
                   Optional<WaitRef> continuation,
                   Optional<ResultKind> resultKind, Optional<ResultRef> result,
                   Optional<ResultRef> uncertainty, Optional<ResultRef> feedback, Optional<String> externalOperation,
                   boolean adopted) {
        public Attempt {
            text(id); Objects.requireNonNull(action); Objects.requireNonNull(run);
            Objects.requireNonNull(request); Objects.requireNonNull(continuation);
            Objects.requireNonNull(resultKind); Objects.requireNonNull(result); Objects.requireNonNull(uncertainty); Objects.requireNonNull(feedback);
            Objects.requireNonNull(externalOperation); sameTenant(run, action.tenantId());
            continuation.ifPresent(value -> {
                if (!value.action().equals(action)) throw new IllegalArgumentException("execution_continuation_action_mismatch");
            });
            if (fence <= 0 || resultKind.isPresent() != result.isPresent() || adopted && result.isEmpty())
                throw new IllegalArgumentException("execution_attempt_invalid");
        }
    }

    /** 入口已核实的归属与受理数据；预算登记随 Run 和输入在同一事务生效。 */
    record Admission(VerifiedIdentity identity, RunIdentity run, String fingerprint,
                     ResultRef configuration, Material input, Material context,
                     ExecutionBudgetPort.Registration budget, List<Event> events, List<Material> configurationMaterials) {
        public Admission {
            Objects.requireNonNull(identity); Objects.requireNonNull(run); text(fingerprint);
            Objects.requireNonNull(configuration); Objects.requireNonNull(input); Objects.requireNonNull(context);
            Objects.requireNonNull(budget); events = List.copyOf(events);
            configurationMaterials = List.copyOf(configurationMaterials);
            if (!identity.owner().equals(run.owner()) || !budget.run().equals(run.runRef()))
                throw new IllegalArgumentException("execution_admission_owner_mismatch");
        }
    }
    record AdmissionResult(Run run, boolean created) {
        public AdmissionResult { Objects.requireNonNull(run); }
    }
    /** 子Run来源拥有者保留自己的业务权威；这里仅传递精确来源，不引入通用操作日志。 */
    sealed interface ChildOrigin permits DelegatedChild, WorkflowNodeChild { }
    record DelegatedChild(String delegationId) implements ChildOrigin {
        public DelegatedChild { text(delegationId); }
    }
    record WorkflowNodeChild(WorkflowNodeCallIdentity node, ExecutionLease lease, long sourceRevision,
                             String checkpointFingerprint) implements ChildOrigin {
        public WorkflowNodeChild {
            Objects.requireNonNull(node); Objects.requireNonNull(lease); expected(sourceRevision); text(checkpointFingerprint);
            if (!node.executionRef().equals(lease.executionRef())) throw new IllegalArgumentException("execution_child_source_mismatch");
        }
    }
    record ChildAdmission(DispatchResult.Registered parent, Prepared prepared, ChildOrigin origin, Admission child) {
        public ChildAdmission {
            Objects.requireNonNull(parent); Objects.requireNonNull(prepared); Objects.requireNonNull(origin); Objects.requireNonNull(child);
            var run = parent.run().identity().runRef();
            sameTenant(run, child.run().runRef().tenantId());
            if (run.equals(child.run().runRef()) || !parent.action().reference().equals(prepared.action())
                    || origin instanceof DelegatedChild && prepared.kind() != Kind.DELEGATION
                    || origin instanceof WorkflowNodeChild && prepared.kind() != Kind.WORKFLOW)
                throw new IllegalArgumentException("execution_child_source_mismatch");
        }
    }
    /** run_code一次真实sandbox RPC的原父TOOL来源；ordinal不冒充Workflow node或DAG permit。 */
    record ProgramChildSource(ActionRef action,String attemptId,int ordinal) {
        public ProgramChildSource {
            Objects.requireNonNull(action);text(attemptId);
            if(ordinal<1||ordinal>com.skillforge.runtime.api.ToolCallLimits.PROGRAM_CALLS) throw new IllegalArgumentException("execution_program_child_ordinal_invalid");
        }
    }
    /** 程序RPC独立受理真实MEMBER Run，原父TOOL仍在途，不在同Run插入第二currentAction。 */
    record ProgramChildAdmission(DispatchResult.Registered parent,Prepared prepared,ProgramChildSource source,Admission child) {
        public ProgramChildAdmission {
            Objects.requireNonNull(parent);Objects.requireNonNull(prepared);Objects.requireNonNull(source);Objects.requireNonNull(child);
            if(prepared.kind()!=Kind.TOOL||!parent.action().reference().equals(source.action())||!parent.attempt().id().equals(source.attemptId())
                    ||!prepared.action().equals(source.action())||parent.run().identity().runRef().equals(child.run().runRef())||!parent.run().identity().runRef().tenantId().equals(child.run().runRef().tenantId()))
                throw new IllegalArgumentException("execution_program_child_source_mismatch");
        }
    }

    /** 真实已批准Plan的成员准入及其当次业务容量许可；不借用Provider Attempt。 */
    record PlanChildSource(DagWork.Key work, DagWork.Binding binding, DagWork.Permit permit) {
        public PlanChildSource {
            Objects.requireNonNull(work); Objects.requireNonNull(binding); Objects.requireNonNull(permit);
            sameTenant(binding.runRef(),work.owner().sessionRef().tenantId());
            if (binding.task().executorType() != DagPlan.ExecutorType.MEMBER) throw new IllegalArgumentException("execution_plan_child_member_required");
        }
    }
    record PlanChildAdmission(RunRef parent, PlanChildSource source, Admission child) {
        public PlanChildAdmission {
            Objects.requireNonNull(parent); Objects.requireNonNull(source); Objects.requireNonNull(child);
            sameTenant(parent,source.work().owner().sessionRef().tenantId()); sameTenant(parent,child.run().runRef().tenantId());
            if (parent.equals(child.run().runRef()) || !source.binding().runRef().equals(child.run().runRef())
                    || !source.binding().task().agentId().equals(child.run().agentId())) throw new IllegalArgumentException("execution_plan_child_source_mismatch");
        }
    }

    /** 只为已有执行槽位领取一个 Run；未领取工作仍保留在 Run 的到期字段。 */
    record Claim(RunRef run, String workerId, String claimId, Duration duration) {
        public Claim {
            Objects.requireNonNull(run); text(workerId); text(claimId); positive(duration);
        }
    }

    /** 已准备的真实请求；冻结能力和请求各只有一个引用，不保存来源图或内部阶段票据。 */
    record Prepared(ActionRef action, long ordinal, Kind kind, String target,
                    ResultRef capability, Material request, String fingerprint, List<Material> requestParts) {
        public Prepared(ActionRef action, long ordinal, Kind kind, String target,
                ResultRef capability, Material request, String fingerprint) {
            this(action, ordinal, kind, target, capability, request, fingerprint, List.of());
        }
        public Prepared {
            Objects.requireNonNull(action); Objects.requireNonNull(kind); text(target);
            Objects.requireNonNull(capability); Objects.requireNonNull(request); text(fingerprint);
            requestParts = List.copyOf(requestParts);
            if (ordinal <= 0) throw new IllegalArgumentException("execution_action_ordinal_invalid");
        }
    }

    /** 派发状态与该次真实动作开始的安全事件同事务保存；事件不证明供应商已收到请求或产生首token。 */
    record Dispatch(RunRef run, long revision, LeaseFence lease, Prepared action,
                    String attemptId, ExecutionBudgetPort.Demand budget, VerifiedIdentity actor,
                    Optional<WaitRef> continuation, List<Event> events) {
        public Dispatch {
            Objects.requireNonNull(run); Objects.requireNonNull(lease); Objects.requireNonNull(action);
            text(attemptId); Objects.requireNonNull(budget); Objects.requireNonNull(actor); expected(revision);
            Objects.requireNonNull(continuation); events = List.copyOf(events);
            sameTenant(run, lease.tenantId()); sameTenant(run, action.action().tenantId());
            continuation.ifPresent(value -> {
                if (!value.action().equals(action.action())) throw new IllegalArgumentException("execution_continuation_action_mismatch");
            });
            if (!budget.run().equals(run) || !budget.attemptId().equals(attemptId)
                    || !budget.fingerprint().equals(action.fingerprint()))
                throw new IllegalArgumentException("execution_dispatch_budget_mismatch");
        }
    }

    /** 仅 Registered 可发起一次外调。重复命令返回 Existing，不再次授予发送权。 */
    sealed interface DispatchResult {
        record Registered(Run run, Action action, Attempt attempt, VerifiedIdentity actor) implements DispatchResult {
            public Registered { Objects.requireNonNull(actor); }
        }
        record Existing(Run run, Action action, Attempt attempt) implements DispatchResult { }
        record Rejected(String reason) implements DispatchResult {
            public Rejected { text(reason); }
        }
    }
    /** 一次真实模型tool_calls决定拥有整组；不另建批次快照或队列身份。 */
    record BatchRef(RunRef run, String sourceAttempt, int size) {
        public BatchRef {
            Objects.requireNonNull(run); text(sourceAttempt);
            if (size < 2 || size > com.skillforge.runtime.api.ToolCallLimits.READ_ONLY_BATCH) throw new IllegalArgumentException("execution_batch_size_invalid");
        }
    }
    record BatchSlot(BatchRef batch, int slot) {
        public BatchSlot {
            Objects.requireNonNull(batch);
            if (slot <= 0 || slot > batch.size()) throw new IllegalArgumentException("execution_batch_slot_invalid");
        }
    }
    record BatchRequest(Prepared action, String attemptId, ExecutionBudgetPort.Demand budget) {
        public BatchRequest {
            Objects.requireNonNull(action); text(attemptId); Objects.requireNonNull(budget);
            if (action.kind() != Kind.TOOL || !attemptId.equals(budget.attemptId()) || !action.fingerprint().equals(budget.fingerprint())
                    || budget.category() != ExecutionBudgetPort.Category.TOOL)
                throw new IllegalArgumentException("execution_batch_request_mismatch");
        }
    }
    /** 原native槽位的一次新调用或原已采纳安全结果，复用不改变旧Action的原批次归属。 */
    sealed interface BatchMember {
        int slot();
        record Fresh(int slot, BatchRequest request) implements BatchMember {
            public Fresh { if (slot < 1) throw new IllegalArgumentException("execution_batch_slot_invalid"); Objects.requireNonNull(request); }
        }
        record Reused(int slot, ActionRef action, String attemptId, ResultRef request, ResultRef result, ResultRef feedback) implements BatchMember {
            public Reused {
                if (slot < 1) throw new IllegalArgumentException("execution_batch_slot_invalid"); Objects.requireNonNull(action); text(attemptId);
                Objects.requireNonNull(request); Objects.requireNonNull(result); Objects.requireNonNull(feedback);
            }
        }
    }
    record BatchDispatch(RunRef run, long revision, LeaseFence lease, String sourceAttempt,
                         List<BatchMember> requests, VerifiedIdentity actor) {
        public BatchDispatch {
            Objects.requireNonNull(run); expected(revision); Objects.requireNonNull(lease); text(sourceAttempt);
            requests = List.copyOf(requests); Objects.requireNonNull(actor); new BatchRef(run, sourceAttempt, requests.size());
            sameTenant(run, lease.tenantId());
            var actions = new java.util.HashSet<ActionRef>(); var attempts = new java.util.HashSet<String>();
            long ordinal = 0; int fresh = 0, slot = 0;
            for (var member : requests) {
                if (member.slot() != ++slot) throw new IllegalArgumentException("execution_batch_identity_mismatch");
                var action = member instanceof BatchMember.Fresh value ? value.request().action().action() : ((BatchMember.Reused)member).action();
                String attempt = member instanceof BatchMember.Fresh value ? value.request().attemptId() : ((BatchMember.Reused)member).attemptId();
                sameTenant(run, action.tenantId());
                if (!actions.add(action) || !attempts.add(attempt))
                    throw new IllegalArgumentException("execution_batch_identity_mismatch");
                if (member instanceof BatchMember.Fresh value) {
                    var request = value.request(); fresh++;
                    if (!request.budget().run().equals(run) || ordinal != 0 && request.action().ordinal() != ordinal+1)
                        throw new IllegalArgumentException("execution_batch_identity_mismatch");
                    ordinal = request.action().ordinal();
                }
            }
            if (fresh == 0) throw new IllegalArgumentException("execution_batch_fresh_required");
        }
    }
    sealed interface BatchDispatchResult {
        record Registered(Run run, List<BatchEntry> entries, List<DispatchResult.Registered> permits) implements BatchDispatchResult {
            public Registered { Objects.requireNonNull(run); entries = List.copyOf(entries); permits = List.copyOf(permits); }
        }
        record Existing(Run run, List<BatchEntry> entries) implements BatchDispatchResult {
            public Existing { Objects.requireNonNull(run); entries = List.copyOf(entries); }
        }
        record Rejected(String reason) implements BatchDispatchResult { public Rejected { text(reason); } }
    }
    record BatchEntry(BatchSlot slot, Action action, Attempt attempt, boolean reused) {
        public BatchEntry {
            Objects.requireNonNull(slot); Objects.requireNonNull(action); Objects.requireNonNull(attempt);
            if (!slot.batch().run().equals(action.run()) || !action.reference().equals(attempt.action()) || !action.currentAttempt().equals(attempt.id())
                    || !reused && !action.batchSlot().equals(Optional.of(slot)))
                throw new IllegalArgumentException("execution_batch_entry_mismatch");
        }
    }
    record BatchContinuation(int slot, WaitRef waiting, Prepared action, String attemptId, ExecutionBudgetPort.Demand budget) {
        public BatchContinuation {
            if (slot < 1) throw new IllegalArgumentException("execution_batch_slot_invalid"); Objects.requireNonNull(waiting);
            new BatchRequest(action,attemptId,budget);
            if (!waiting.action().equals(action.action())) throw new IllegalArgumentException("execution_batch_identity_mismatch");
        }
    }
    record BatchContinue(RunRef run, long revision, LeaseFence lease, BatchRef batch, List<BatchContinuation> continuations, VerifiedIdentity actor) {
        public BatchContinue {
            Objects.requireNonNull(run); expected(revision); Objects.requireNonNull(lease); Objects.requireNonNull(batch); continuations = List.copyOf(continuations); Objects.requireNonNull(actor);
            sameTenant(run,lease.tenantId());
            if (!batch.run().equals(run) || continuations.isEmpty() || continuations.size()>batch.size()) throw new IllegalArgumentException("execution_batch_identity_mismatch");
            int previous = 0;
            for (var value : continuations) {
                if (value.slot()<=previous || value.slot()>batch.size() || !value.budget().run().equals(run)) throw new IllegalArgumentException("execution_batch_identity_mismatch");
                previous = value.slot(); sameTenant(run,value.action().action().tenantId());
            }
        }
    }
    record BatchResultRef(ActionRef action, String attemptId, ResultRef result) {
        public BatchResultRef { Objects.requireNonNull(action); text(attemptId); Objects.requireNonNull(result); }
    }
    /** 原tool_calls槽位顺序的完整结果清单与一次性上下文采纳。 */
    record BatchAdoption(List<BatchResultRef> results, Progress next, Material context, List<Event> events) {
        public BatchAdoption {
            results = List.copyOf(results); Objects.requireNonNull(next); Objects.requireNonNull(context); events = List.copyOf(events);
            if (results.size() < 2 || !next.context().equals(context.reference())) throw new IllegalArgumentException("execution_batch_adoption_invalid");
        }
    }
    record BatchCompletion(BatchRef batch, Completion result, Optional<BatchAdoption> adoption) {
        public BatchCompletion {
            Objects.requireNonNull(batch); Objects.requireNonNull(result); Objects.requireNonNull(adoption);
            if (!batch.run().equals(result.run())) throw new IllegalArgumentException("execution_batch_owner_mismatch");
        }
    }
    record BatchAdopt(RunRef run, BatchRef batch, long revision, LeaseFence lease, BatchAdoption adoption) {
        public BatchAdopt {
            Objects.requireNonNull(run); Objects.requireNonNull(batch); expected(revision); Objects.requireNonNull(lease); Objects.requireNonNull(adoption);
            if (!batch.run().equals(run)) throw new IllegalArgumentException("execution_batch_owner_mismatch");
            sameTenant(run, lease.tenantId());
        }
    }
    record BatchAdoptionResult(Run run, boolean advanced) { public BatchAdoptionResult { Objects.requireNonNull(run); } }
    record BatchQuiesce(BatchRef batch, long revision, LeaseFence lease, List<Event> events) {
        public BatchQuiesce {
            Objects.requireNonNull(batch); expected(revision); Objects.requireNonNull(lease); events = List.copyOf(events);
            sameTenant(batch.run(), lease.tenantId());
        }
    }
    record BatchState(Run run, List<BatchEntry> entries, boolean changed) {
        public BatchState { Objects.requireNonNull(run); entries = List.copyOf(entries); }
    }
    /** 组内全真实事实已静止时终止；保存准确计数与专用context，不给模型追加半个native回合。 */
    record BatchStop(RunRef run, BatchRef batch, long revision, LeaseFence lease, List<BatchResultRef> results,
                     Progress next, Material context, RunStatus status, List<Event> events) {
        public BatchStop {
            Objects.requireNonNull(run); Objects.requireNonNull(batch); expected(revision); Objects.requireNonNull(lease); results = List.copyOf(results);
            Objects.requireNonNull(next); Objects.requireNonNull(context); Objects.requireNonNull(status); events = List.copyOf(events); sameTenant(run,lease.tenantId());
            if (!batch.run().equals(run) || results.size()!=batch.size() || !next.context().equals(context.reference()) || !terminal(status))
                throw new IllegalArgumentException("execution_batch_stop_invalid");
        }
    }

    /** 结果与安全反馈分别保存；迟到结果允许落账，但不得重新打开终态或获得推进权。 */
    record Completion(RunRef run, String attemptId, ResultKind kind, Material result,
                      Optional<Material> feedback, Optional<String> externalOperation,
                      ExecutionBudgetPort.Usage usage, long revision, LeaseFence lease,
                      Progress next, Material context, List<Event> events, Optional<WaitDeclaration> waitDeclaration) {
        public Completion {
            Objects.requireNonNull(run); text(attemptId); Objects.requireNonNull(kind); Objects.requireNonNull(result);
            Objects.requireNonNull(feedback); Objects.requireNonNull(externalOperation); Objects.requireNonNull(usage);
            expected(revision); Objects.requireNonNull(lease); Objects.requireNonNull(next);
            Objects.requireNonNull(context); events = List.copyOf(events);
            Objects.requireNonNull(waitDeclaration);
            if (kind == ResultKind.INPUT_REQUIRED && (waitDeclaration.isEmpty() || waitDeclaration.orElseThrow().kind() != WaitKind.TOOL))
                throw new IllegalArgumentException("execution_wait_result_mismatch");
            waitDeclaration.ifPresent(value -> {
                if (value.kind() == WaitKind.TOOL && kind != ResultKind.INPUT_REQUIRED
                        || value.kind() != WaitKind.TOOL && kind != ResultKind.SUCCEEDED)
                    throw new IllegalArgumentException("execution_wait_result_mismatch");
            });
            waitDeclaration.ifPresent(value -> sameTenant(run, value.reference().action().tenantId()));
            sameTenant(run, lease.tenantId());
            if (!next.context().equals(context.reference()))
                throw new IllegalArgumentException("execution_context_reference_mismatch");
        }
    }
    record CompletionResult(Run run, Attempt attempt, boolean newlySaved, boolean advanced) {
        public CompletionResult { Objects.requireNonNull(run); Objects.requireNonNull(attempt); }
    }

    /** 原逻辑动作内单调递增的一轮输入等待，不把租约或checkpoint版本当作等待身份。 */
    record WaitRef(ActionRef action, long generation) {
        public WaitRef { Objects.requireNonNull(action); expected(generation); }
    }
    /** 请求正文与opaque continuation state仍由原INPUT_REQUIRED结果材料拥有。 */
    record WaitDeclaration(WaitRef reference, WaitKind kind, Map<String, ToolInputMethod> requested, Optional<InternalWait> internal) {
        public WaitDeclaration {
            Objects.requireNonNull(reference); Objects.requireNonNull(kind); requested = methods(requested);
            Objects.requireNonNull(internal);
            if (kind != WaitKind.TOOL && !requested.isEmpty()) throw new IllegalArgumentException("execution_human_wait_methods_invalid");
            if ((kind == WaitKind.DELEGATION || kind == WaitKind.WORKFLOW || kind == WaitKind.PLAN) != internal.isPresent()
                    || internal.isPresent() && internal.orElseThrow().kind() != kind) throw new IllegalArgumentException("execution_wait_internal_mismatch");
        }
    }
    sealed interface InternalWait permits DelegationWait, WorkflowWait, PlanWait { WaitKind kind(); }
    record DelegationWait(String businessWaitId, List<String> delegationIds, List<RunRef> children) implements InternalWait {
        public DelegationWait {
            text(businessWaitId); delegationIds = List.copyOf(delegationIds); children = List.copyOf(children); delegationIds.forEach(ExecutionStore::text);
            if (delegationIds.isEmpty() || delegationIds.size() != children.size() || new java.util.HashSet<>(delegationIds).size() != delegationIds.size()
                    || new java.util.HashSet<>(children).size() != children.size()) throw new IllegalArgumentException("execution_delegation_wait_invalid");
        }
        @Override public WaitKind kind() { return WaitKind.DELEGATION; }
    }
    record WorkflowWait(ExecutionRef execution, String suspendRef, String actionKey, long executionRevision) implements InternalWait {
        public WorkflowWait { Objects.requireNonNull(execution); text(suspendRef); text(actionKey); expected(executionRevision); }
        @Override public WaitKind kind() { return WaitKind.WORKFLOW; }
    }
    record PlanWait(String planId, long planRevision, String businessWaitId) implements InternalWait {
        public PlanWait { text(planId); expected(planRevision); text(businessWaitId); }
        @Override public WaitKind kind() { return WaitKind.PLAN; }
    }
    /** 已接受输入的精确回执；acceptedRevision只表示该次接受原子生效的业务版本。 */
    record AcceptedResponse(InputId input, ResultRef response, String fingerprint,
                            ResultRef actorRef, long acceptedRevision) {
        public AcceptedResponse {
            Objects.requireNonNull(input); Objects.requireNonNull(response); text(fingerprint);
            Objects.requireNonNull(actorRef); expected(acceptedRevision);
        }
    }
    /** 一轮真实等待及其响应消费事实；只允许一个新Attempt消费已接受响应。 */
    record Waiting(WaitRef reference, WaitKind kind, RunRef run, String sourceAttempt,
                   Map<String, ToolInputMethod> requested, Optional<InternalWait> internal, Optional<AcceptedResponse> acceptedResponse,
                   Optional<String> continuationAttempt, OptionalLong consumedRevision) {
        public Waiting {
            Objects.requireNonNull(reference); Objects.requireNonNull(kind); Objects.requireNonNull(run); text(sourceAttempt);
            requested = methods(requested); Objects.requireNonNull(acceptedResponse); Objects.requireNonNull(continuationAttempt);
            Objects.requireNonNull(internal);
            Objects.requireNonNull(consumedRevision);
            continuationAttempt.ifPresent(ExecutionStore::text); sameTenant(run, reference.action().tenantId());
            if ((continuationAttempt.isPresent() || consumedRevision.isPresent()) && acceptedResponse.isEmpty())
                throw new IllegalArgumentException("execution_wait_unaccepted_consumption");
            if (kind != WaitKind.TOOL && !requested.isEmpty()
                    || (kind == WaitKind.HUMAN || kind == WaitKind.DELEGATION || kind == WaitKind.PLAN) && continuationAttempt.isPresent()
                    || (kind == WaitKind.TOOL || kind == WaitKind.WORKFLOW) && consumedRevision.isPresent())
                throw new IllegalArgumentException("execution_wait_consumption_kind_mismatch");
            if ((kind == WaitKind.DELEGATION || kind == WaitKind.WORKFLOW || kind == WaitKind.PLAN) != internal.isPresent()
                    || internal.isPresent() && internal.orElseThrow().kind() != kind) throw new IllegalArgumentException("execution_wait_internal_mismatch");
            if (consumedRevision.isPresent() && consumedRevision.getAsLong() <= acceptedResponse.orElseThrow().acceptedRevision())
                throw new IllegalArgumentException("execution_wait_consumed_revision_invalid");
        }
    }
    /** 当前入口核实的主体提交对精确等待的响应；本边界不预留预算或调用Provider。 */
    record Resume(VerifiedIdentity actor, RunRef run, WaitRef waiting, long revision, InputId input,
                  Material response, String fingerprint, Map<String, ToolInputMethod> responseMethods, List<Event> events) {
        public Resume {
            Objects.requireNonNull(actor); Objects.requireNonNull(run); Objects.requireNonNull(waiting); expected(revision);
            Objects.requireNonNull(input); Objects.requireNonNull(response); text(fingerprint);
            responseMethods = methods(responseMethods); events = List.copyOf(events);
            sameTenant(run, waiting.action().tenantId()); sameTenant(run, actor.owner().sessionRef().tenantId());
        }
    }
    record ResumeResult(Run run, Waiting waiting, boolean accepted) {
        public ResumeResult { Objects.requireNonNull(run); Objects.requireNonNull(waiting); }
    }
    /** 本地采纳真实人类响应，不产生Provider Attempt或任何预算登记。 */
    record AcceptLocalResponse(RunRef run, WaitRef waiting, InputId input, long revision, LeaseFence lease,
                               Progress next, Material context, List<Event> events) {
        public AcceptLocalResponse {
            Objects.requireNonNull(run); Objects.requireNonNull(waiting); Objects.requireNonNull(input); expected(revision);
            Objects.requireNonNull(lease); Objects.requireNonNull(next); Objects.requireNonNull(context); events = List.copyOf(events);
            sameTenant(run, waiting.action().tenantId()); sameTenant(run, lease.tenantId());
            if (!next.context().equals(context.reference())) throw new IllegalArgumentException("execution_context_reference_mismatch");
        }
    }
    record LocalResponseResult(Run run, Waiting waiting, boolean advanced) {
        public LocalResponseResult { Objects.requireNonNull(run); Objects.requireNonNull(waiting); }
    }
    /** 原Run的有序补充输入；appliedRevision仅表示本地context采纳，不证明模型已消费。 */
    record SupplementalInput(RunRef run, InputId input, ResultRef content, String fingerprint,
                             ResultRef actorRef, long ordinal, OptionalLong appliedRevision) {
        public SupplementalInput {
            Objects.requireNonNull(run); Objects.requireNonNull(input); Objects.requireNonNull(content); text(fingerprint);
            Objects.requireNonNull(actorRef); expected(ordinal); Objects.requireNonNull(appliedRevision);
            if (appliedRevision.isPresent()) expected(appliedRevision.getAsLong());
        }
    }
    /** 经当前IAM核实后追加到原Run，不中断或替换已派发的真实请求。 */
    record OfferInput(VerifiedIdentity actor, RunRef run, InputId input, Material content, String fingerprint) {
        public OfferInput {
            Objects.requireNonNull(actor); Objects.requireNonNull(run); Objects.requireNonNull(input); Objects.requireNonNull(content); text(fingerprint);
            sameTenant(run, actor.owner().sessionRef().tenantId());
        }
    }
    record InputOffer(Run run, SupplementalInput input, boolean created) {
        public InputOffer { Objects.requireNonNull(run); Objects.requireNonNull(input); }
    }
    /** 安全推进点按原顺序采纳最早未应用输入；不新增Provider Attempt或预算登记。 */
    record AcceptInput(RunRef run, InputId input, long revision, LeaseFence lease,
                       Progress next, Material context, List<Event> events) {
        public AcceptInput {
            Objects.requireNonNull(run); Objects.requireNonNull(input); expected(revision); Objects.requireNonNull(lease);
            Objects.requireNonNull(next); Objects.requireNonNull(context); events = List.copyOf(events); sameTenant(run, lease.tenantId());
            if (!next.context().equals(context.reference())) throw new IllegalArgumentException("execution_context_reference_mismatch");
        }
    }
    /**
     * 同事务核实的补充提交者随提交状态返回，活跃驱动不再重新读取身份材料。
     * @param run 当前已提交Run；幂等返回时可已采纳后续输入
     * @param input 本次指定的原持久输入
     * @param actor 该输入的原持久提交者；仅advanced时与当前Run操作者一致
     * @param advanced 本次确实采纳输入；false时不得更新内存材料或活动帧身份
     */
    record InputAcceptance(Run run, SupplementalInput input, VerifiedIdentity actor, boolean advanced) {
        public InputAcceptance {
            Objects.requireNonNull(run); Objects.requireNonNull(input); Objects.requireNonNull(actor);
            if (!input.run().equals(run.identity().runRef()) || !actor.owner().equals(run.identity().owner())
                    || !actor.verificationRef().equals(input.actorRef())
                    || (advanced && !actor.verificationRef().equals(run.actorVerificationRef())))
                throw new IllegalArgumentException("execution_supplemental_actor_mismatch");
        }
    }
    /** 已准确保存的原MODEL决定发起本地Plan控制；请求正文由所属业务协议拥有。 */
    record PlanTransition(RunRef run, long revision, LeaseFence lease, ActionRef sourceModelAction, Material request) {
        public PlanTransition {
            Objects.requireNonNull(run); expected(revision); Objects.requireNonNull(lease); Objects.requireNonNull(sourceModelAction); Objects.requireNonNull(request);
            sameTenant(run, lease.tenantId()); sameTenant(run, sourceModelAction.tenantId());
        }
    }
    /** 真实Plan业务回执与pure上下文转换的完整同事务采纳候选。 */
    record PlanChange(Material actualResult, Progress next, Material context, List<Event> events, Optional<WaitDeclaration> waiting) {
        public PlanChange {
            Objects.requireNonNull(actualResult); Objects.requireNonNull(next); Objects.requireNonNull(context); events = List.copyOf(events); Objects.requireNonNull(waiting);
            if (!next.context().equals(context.reference())) throw new IllegalArgumentException("execution_context_reference_mismatch");
            if (waiting.isPresent() && waiting.orElseThrow().kind() != WaitKind.PLAN) throw new IllegalArgumentException("execution_plan_wait_kind_mismatch");
        }
    }
    record PlanTransitionResult(Run run, Material actualResult, boolean advanced) {
        public PlanTransitionResult { Objects.requireNonNull(run); Objects.requireNonNull(actualResult); }
    }
    /** 接管者只把无结果派发静止一次；不伪造结果、不释放原UNKNOWN预算。 */
    record QuiesceUnknown(RunRef run, String attemptId, long revision, LeaseFence lease, List<Event> events) {
        public QuiesceUnknown {
            Objects.requireNonNull(run); text(attemptId); expected(revision); Objects.requireNonNull(lease);
            events = List.copyOf(events); sameTenant(run, lease.tenantId());
        }
    }
    record QuiescenceResult(Run run, Attempt attempt, boolean changed) {
        public QuiescenceResult { Objects.requireNonNull(run); Objects.requireNonNull(attempt); }
    }

    /** 本地结束不获取预算锁，终态状态与公开事件一次提交。 */
    record Finish(RunRef run, long revision, LeaseFence lease, RunStatus status, List<Event> events,
                  Optional<Material> terminalContext) {
        public Finish(RunRef run, long revision, LeaseFence lease, RunStatus status, List<Event> events) {
            this(run, revision, lease, status, events, Optional.empty());
        }
        public Finish {
            Objects.requireNonNull(run); expected(revision); Objects.requireNonNull(lease);
            Objects.requireNonNull(status); events = List.copyOf(events); Objects.requireNonNull(terminalContext); sameTenant(run, lease.tenantId());
            if (!terminal(status)) throw new IllegalArgumentException("execution_finish_status_invalid");
        }
    }
    record Cancel(VerifiedIdentity identity, RunRef run, InputId input, List<Event> events) {
        public Cancel {
            Objects.requireNonNull(identity); Objects.requireNonNull(run); Objects.requireNonNull(input); events = List.copyOf(events);
            sameTenant(run, identity.owner().sessionRef().tenantId());
        }
    }

    /** 仅接收已安全转换的产品事件，不存 mutation、publication 或执行状态快照。 */
    record Event(EventId id, String type, TypedPayload data) {
        public Event { Objects.requireNonNull(id); text(type); Objects.requireNonNull(data); }
        @Override public String toString() { return "ExecutionEvent[type=" + type + ",REDACTED]"; }
    }
    record SavedEvent(RunRef run, long sequence, long revision, Instant occurredAt, Event event) {
        public SavedEvent {
            Objects.requireNonNull(run); Objects.requireNonNull(occurredAt); Objects.requireNonNull(event);
            expected(sequence); expected(revision);
        }
    }

    private static void text(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("execution_text_required");
    }
    private static Map<String, ToolInputMethod> methods(Map<String, ToolInputMethod> values) {
        Objects.requireNonNull(values); values.forEach((key, method) -> { text(key); Objects.requireNonNull(method); });
        return Map.copyOf(values);
    }
    private static void expected(long value) {
        if (value <= 0) throw new IllegalArgumentException("execution_revision_invalid");
    }
    private static void positive(Duration duration) {
        Objects.requireNonNull(duration);
        if (duration.isZero() || duration.isNegative()) throw new IllegalArgumentException("execution_duration_invalid");
    }
    private static void sameTenant(RunRef run, TenantId tenant) {
        if (!run.tenantId().equals(tenant)) throw new IllegalArgumentException("execution_tenant_mismatch");
    }
    private static boolean terminal(RunStatus status) {
        return switch (status) {
            case COMPLETED, TERMINATED, FAILED, CANCELLED -> true;
            default -> false;
        };
    }
}
