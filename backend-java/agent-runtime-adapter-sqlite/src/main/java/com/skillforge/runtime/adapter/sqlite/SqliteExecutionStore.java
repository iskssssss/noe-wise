package com.skillforge.runtime.adapter.sqlite;

import com.skillforge.runtime.adapter.sqlite.budget.SqliteExecutionBudgets;

import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.RunStatus;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.ActionRef;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.port.LeaseFence;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;

/**
 * 内存连续推进主链的 SQLite 业务提交边界；只有此组件写新 Run/Action/Attempt。
 *
 * <p>一个业务提交只使用一个已绑定租户的 SqlSession，预算和运行状态共同提交或回滚。
 * IMMEDIATE 事务串行写入，CAS 与租约仍作为最终写入条件。外调由已提交的 Registered 返回值在事务外发起，
 * Existing 与结果缺失的 Attempt 均不授予重放权。</p>
 */
public final class SqliteExecutionStore implements ExecutionStore, AutoCloseable {
    private final SqlSessionFactory sessions;
    private final SqlSessionFactory reads;
    private final SqliteExecutionBudgets budgets;
    private final DispatchAuthorization authorization;
    private final ControlAuthorization controlAuthorization;
    private final ThreadLocal<Boolean> transactionScope = new ThreadLocal<>();

    /**
     * 当前派发授权与挂载检查；实现只能使用传入的租户 SqlSession，不另开连接或切换租户。
     * 返回 false 时不登记派发；不得在此执行 Provider、工具、媒体或其他外部调用。
     */
    @FunctionalInterface
    private interface DispatchAuthorization {
        boolean allowed(SqlSession session, Run run, VerifiedIdentity actor, Prepared prepared);
    }

    private enum Control { RESUME, STEER, CANCEL }

    /** 桌面可信入口的个人归属校验；机构 IAM 与代办身份不在此适配器装配。 */
    @FunctionalInterface
    private interface ControlAuthorization {
        boolean allowed(SqlSession session, Run run, VerifiedIdentity actor, Control operation, InputId input);
    }
    private final SqliteDatabase database;
    /** 独立数据库文件由调用方选择；所有身份必须来自本地可信入口，构造不触及远程服务。 */
    public SqliteExecutionStore(java.nio.file.Path databaseFile, java.time.Clock clock) {
        database = new SqliteDatabase(databaseFile, clock);
        budgets = new SqliteExecutionBudgets(database.source());
        authorization = (session, run, actor, prepared) -> run.identity().owner().equals(actor.owner())
                && (prepared.kind() == Kind.MODEL || prepared.kind() == Kind.TOOL);
        controlAuthorization = (session, run, actor, operation, input) -> run.identity().owner().equals(actor.owner());
        var config = SqliteDatabase.configuration(database.source());
        SqliteExecutionBudgets.configure(config);
        SqliteIdentities.configure(config);
        ExecutionSupplementMapper.configure(config);
        String resource = "mapper/SqliteExecutionStoreMapper.xml";
        try (InputStream xml = SqliteExecutionStore.class.getClassLoader().getResourceAsStream(resource)) {
            if (xml == null) throw new IllegalStateException("execution_store_mapper_missing");
            new XMLMapperBuilder(xml, config, resource, config.getSqlFragments()).parse();
        } catch (java.io.IOException failure) { throw new IllegalStateException("execution_store_initialization_failed", failure); }
        sessions = new SqlSessionFactoryBuilder().build(config);
        var readConfig = SqliteDatabase.configuration(database.readSource());
        ExecutionSupplementMapper.configure(readConfig);
        SqliteDatabase.load(readConfig, resource);
        reads = new SqlSessionFactoryBuilder().build(readConfig);
    }
    /** 与执行状态共用数据库的真实预算账本；Host 负责记录实际驱动活动区间。 */
    public SqliteExecutionBudgets budgets() { return budgets; }
    /** 按可信租户和 Session 查询导航记录，调用方仍需核对本地会话权限。 */
    public List<Run> runsForSession(SessionRef owner) {
        return read(owner.tenantId(), (session, mapper) -> mapper.runsForSession(owner.tenantId().value(), owner.sessionId())
                .stream().map(ExecutionRunRow::run).toList());
    }
    /** 只计调用方提供的本机导航会话；WAITING 和 UNKNOWN 同样占排队位置。 */
    public long queuedTaskCount(TenantId tenant, List<String> sessionIds) {
        var requested = List.copyOf(sessionIds);
        if (requested.isEmpty()) return 0;
        return read(tenant, (session, mapper) -> mapper.queuedTaskCount(tenant.value(), requested));
    }
    /** 最早未完成任务拥有会话推进顺序；不跳过等待或结果不确定的队首。 */
    public Optional<Run> firstUnfinishedRun(SessionRef owner) {
        return read(owner.tenantId(), (session, mapper) -> Optional.ofNullable(mapper.firstUnfinishedRun(owner.tenantId().value(), owner.sessionId()))
                .map(ExecutionRunRow::run));
    }
    /** 新受理只读取最后一个前驱，复用完整 Run 映射供支持格式核验。 */
    public Optional<Run> latestRun(SessionRef owner) {
        return read(owner.tenantId(), (session, mapper) -> Optional.ofNullable(mapper.latestRun(owner.tenantId().value(), owner.sessionId()))
                .map(ExecutionRunRow::run));
    }
    /** 桌面展示的权威总量与可见窗口；材料只属于本次快照，不保存或缓存展示副本。 */
    public record DesktopProjection(long totalRuns, long totalMessages, long totalEvents, List<Run> runs,
            List<DesktopMessage> messageCandidates, List<SavedEvent> events, List<DesktopTool> tools,
            List<DesktopWait> waiting, java.util.Map<ResultRef, Material> materials) {
        public DesktopProjection {
            runs = List.copyOf(runs); messageCandidates = List.copyOf(messageCandidates); events = List.copyOf(events);
            tools = List.copyOf(tools); waiting = List.copyOf(waiting); materials = java.util.Map.copyOf(materials);
        }
    }
    public record DesktopMessage(Run run, Material input, Optional<SavedEvent> finished) { }
    public record DesktopTool(Run run, Action action, Attempt attempt) { }
    public record DesktopWait(Run run, Waiting waiting, Action action, Attempt attempt) { }

    /**
     * 所有事实来自执行数据库的同一 DEFERRED 快照；不读取导航文件，不改变模型恢复历史。
     * finishedId 必须是宿主现有的纯终态事件身份函数，不得执行外部操作。
     */
    public DesktopProjection desktopProjection(SessionRef owner, int runLimit, int messageLimit, int processLimit, int toolLimit,
            java.util.function.Function<RunRef, com.skillforge.runtime.api.EventId> finishedId) {
        Objects.requireNonNull(owner); Objects.requireNonNull(finishedId);
        bounded(runLimit); bounded(messageLimit); bounded(processLimit); bounded(toolLimit);
        if (runLimit < messageLimit) throw new IllegalArgumentException("desktop_projection_window_invalid");
        return read(owner.tenantId(), (session, mapper) -> {
            try {
                // 非池化连接随 SqlSession 关闭释放此纯函数；不复制 Session 所有的身份算法。
                org.sqlite.Function.create(session.getConnection(), "desktop_finished_event_id", new org.sqlite.Function() {
                    @Override protected void xFunc() throws java.sql.SQLException {
                        result(finishedId.apply(new RunRef(owner.tenantId(), value_text(0))).value());
                    }
                });
            } catch (java.sql.SQLException failure) { throw new IllegalStateException("desktop_projection_function_failed", failure); }
            String tenant = owner.tenantId().value(), id = owner.sessionId();
            var totals = mapper.desktopCounts(tenant, id);
            var recent = mapper.desktopRecentRuns(tenant, id, runLimit).reversed();
            var messageRows = recent.subList(Math.max(0, recent.size() - messageLimit), recent.size());
            var toolRows = mapper.desktopToolActions(tenant, id, toolLimit).reversed();
            var waitRows = mapper.desktopPendingWaits(tenant, id);
            var runRows = new java.util.LinkedHashMap<String, ExecutionRunRow>();
            for (var row : recent) runRows.put(row.runId, row);
            var missingRuns = new java.util.LinkedHashSet<String>();
            for (var row : toolRows) if (!runRows.containsKey(row.runId)) missingRuns.add(row.runId);
            for (var row : waitRows) if (!runRows.containsKey(row.runId)) missingRuns.add(row.runId);
            if (!missingRuns.isEmpty()) for (var row : mapper.desktopRuns(tenant, id, List.copyOf(missingRuns))) runRows.put(row.runId, row);
            var runs = new java.util.HashMap<String, Run>();
            for (var row : runRows.values()) runs.put(row.runId, row.run());
            var actionRows = new java.util.HashMap<String, ExecutionActionRow>();
            for (var row : toolRows) actionRows.put(row.actionId, row);
            var missingActions = waitRows.stream().map(row -> row.actionId).filter(action -> !actionRows.containsKey(action)).distinct().toList();
            if (!missingActions.isEmpty()) for (var row : mapper.desktopActions(tenant, missingActions)) actionRows.put(row.actionId, row);
            var attemptIds = new java.util.LinkedHashSet<String>();
            for (var row : toolRows) attemptIds.add(row.selectedAttempt == null ? row.currentAttempt : row.selectedAttempt);
            for (var row : waitRows) attemptIds.add(row.sourceAttempt);
            var attempts = new java.util.HashMap<String, ExecutionAttemptRow>();
            if (!attemptIds.isEmpty()) for (var row : mapper.desktopAttempts(tenant, List.copyOf(attemptIds))) attempts.put(row.attemptId, row);
            var references = new java.util.LinkedHashSet<String>();
            for (var row : runRows.values()) references.add(row.configurationRef);
            for (var row : messageRows) references.add(row.inputRef);
            for (var row : attempts.values()) {
                references.add(row.requestRef);
                if (row.feedbackRef != null) references.add(row.feedbackRef);
            }
            var materials = new java.util.HashMap<ResultRef, Material>();
            if (!references.isEmpty()) for (var row : mapper.materials(tenant, List.copyOf(references))) materials.put(new ResultRef(row.reference), row.material());
            var finished = new java.util.HashMap<String, SavedEvent>();
            var finishedIds = messageRows.stream().map(row -> finishedId.apply(new RunRef(owner.tenantId(), row.runId)).value()).toList();
            if (!finishedIds.isEmpty()) for (var row : mapper.desktopFinishedEvents(tenant, id, finishedIds)) finished.put(row.eventId, row.event());
            var messages = new java.util.ArrayList<DesktopMessage>();
            for (var row : messageRows) {
                var event = Optional.ofNullable(finished.get(finishedId.apply(new RunRef(owner.tenantId(), row.runId)).value()));
                if (event.isPresent() && !event.orElseThrow().run().runId().equals(row.runId)) throw conflict("execution_event_scope_mismatch");
                messages.add(new DesktopMessage(runs.get(row.runId), materials.get(new ResultRef(row.inputRef)), event));
            }
            var events = new java.util.ArrayList<SavedEvent>();
            for (var row : mapper.desktopEvents(tenant, id, false, processLimit).reversed()) events.add(row.event());
            int savedToolLimit = toolLimit - toolRows.size();
            if (savedToolLimit > 0) for (var row : mapper.desktopEvents(tenant, id, true, savedToolLimit).reversed()) events.add(row.event());
            var tools = new java.util.ArrayList<DesktopTool>();
            for (var row : toolRows) tools.add(new DesktopTool(runs.get(row.runId), row.action(), attempts.get(row.selectedAttempt == null ? row.currentAttempt : row.selectedAttempt).attempt()));
            var requests = new java.util.HashMap<WaitRef, java.util.Map<String, com.skillforge.runtime.api.port.ToolPortal.ToolInputMethod>>();
            if (!waitRows.isEmpty()) for (var row : mapper.desktopWaitRequests(tenant, id)) {
                var reference = new WaitRef(new ActionRef(owner.tenantId(), row.actionId), row.generation);
                requests.computeIfAbsent(reference, ignored -> new java.util.LinkedHashMap<>()).put(row.inputKey,
                        com.skillforge.runtime.api.port.ToolPortal.ToolInputMethod.fromWireName(row.method));
            }
            var waiting = new java.util.ArrayList<DesktopWait>();
            for (var row : waitRows) {
                var reference = new WaitRef(new ActionRef(owner.tenantId(), row.actionId), row.generation);
                waiting.add(new DesktopWait(runs.get(row.runId), row.waiting(requests.getOrDefault(reference, java.util.Map.of()), List.of()),
                        actionRows.get(row.actionId).action(), attempts.get(row.sourceAttempt).attempt()));
            }
            return new DesktopProjection(totals.runCount, totals.messageCount, totals.eventCount,
                    recent.stream().map(row -> runs.get(row.runId)).toList(), messages, events, tools, waiting, materials);
        });
    }
    @Override public void close() { database.close(); }

    /** 预算、原输入、初始上下文、运行状态和受理公开事件在一个业务提交内生效。 */
    @Override public AdmissionResult admit(Admission command) {
        Objects.requireNonNull(command);
        if (!command.budget().run().equals(command.budget().root())
                || command.budget().role() != ExecutionBudgetPort.Role.PRIMARY
                || command.budget().policy().scenario() != ExecutionBudgetPort.Scenario.STANDARD)
            throw conflict("desktop_execution_mode_unsupported");
        return transaction(command.run().runRef().tenantId(), (session, mapper) -> admit(session, mapper, command));
    }

    private AdmissionResult admit(SqlSession session, ExecutionStoreMapper mapper, Admission command) {
            // 受理也先取得预算根；重复输入的冲突不会留下独立提交的预算登记。
            SqliteIdentities.save(session, command.identity());
            budgets.register(session, command.budget());
            Instant now = mapper.now();
            var row = ExecutionRunRow.admitted(command, now);
            // Session归属是Workflow/DAG等保留领域的外键根，不依赖旧session.opened日志建立。
            mapper.ensureSessionOwner(row);
            var owner=mapper.lockSessionOwner(row);
            if(owner==null || !owner.equals(row.subjectKind + ":" + row.subjectId))throw conflict("execution_session_owner_mismatch");
            mapper.ensureSession(row);
            if (!mapper.sessionOwned(row)) throw conflict("execution_session_owner_mismatch");
            saveMaterial(mapper, row.tenantId, command.input());
            saveMaterial(mapper, row.tenantId, command.context());
            if (mapper.insertRun(row) == 1) {
                for (var material : command.configurationMaterials()) saveMaterial(mapper, row.tenantId, material);
                appendEvents(mapper, row, command.events(), now);
                return new AdmissionResult(row.run(), true);
            }
            var existing = mapper.lockRun(row.tenantId, row.runId);
            if (existing == null) existing = mapper.inputRun(row);
            if (existing == null || !existing.run().identity().equals(command.run())
                    || !existing.admissionFingerprint.equals(command.fingerprint())
                    || !existing.configurationRef.equals(command.configuration().value())
                    || !existing.inputRef.equals(command.input().reference().value())
                    || !existing.initialContextRef.equals(command.context().reference().value()))
                throw conflict("execution_admission_conflict");
            // 原受理事件由相同业务版本核对，不能在输入重放时追加任意新事件。
            var events = mapper.admissionEvents(row.tenantId, row.runId).stream().map(event -> event.event().event()).toList();
            if (!events.equals(command.events())) throw conflict("execution_admission_events_conflict");
            for (var material : command.configurationMaterials())
                if (!requireMaterial(mapper, row.tenantId, material.reference()).equals(material)) throw conflict("execution_material_conflict");
            return new AdmissionResult(existing.run(), false);
    }

    /** 仅首次领取、恢复或冲突时加载当前结构化状态。 */
    @Override public Optional<Run> load(RunRef run) {
        return read(run.tenantId(), (session, mapper) -> Optional.ofNullable(mapper.run(tenant(run), run.runId())).map(ExecutionRunRow::run));
    }

    /** 领取时在 Run 锁内递增 fence；活动租约不会被另一推进者覆盖。 */
    @Override public Optional<Run> claim(Claim command) {
        return transaction(command.run().tenantId(), (session, mapper) -> {
            var row = mapper.lockRun(tenant(command.run()), command.run().runId());
            if (row == null || terminal(row) || row.cancelled || quiescent(row)) return Optional.empty();
            Instant now = mapper.now();
            if (row.leaseClaimId != null && leaseUntil(row).isAfter(now)) {
                return row.leaseClaimId.equals(command.claimId()) && row.leaseWorkerId.equals(command.workerId())
                        ? Optional.of(row.run()) : Optional.empty();
            }
            long previous = row.revision;
            row.fence = Math.incrementExact(row.fence); row.leaseClaimId = command.claimId(); row.leaseWorkerId = command.workerId();
            row.leaseUntil(now.plus(command.duration())); row.due(leaseUntil(row));
            if (row.phase.equals(Phase.READY.name())) row.status = RunStatus.RUNNING.name();
            advance(mapper, row, previous, now);
            return Optional.of(row.run());
        });
    }

    /** 当前权限、租约、版本与预算通过后原子登记派发；提交完成才返回一次发送权。 */
    @Override public DispatchResult dispatch(Dispatch command) {
        try {
            return transaction(command.run().tenantId(), (session, mapper) -> {
                lockBudget(session, command.run());

                var row = requireRun(mapper, command.run());
                if (!row.run().identity().owner().equals(command.actor().owner()))
                    return new DispatchResult.Rejected("execution_dispatch_owner_mismatch");
                var prior = mapper.attempt(tenant(command.run()), command.run().runId(), command.attemptId());
                var action = mapper.action(tenant(command.run()), command.action().action().actionId());
                if (prior != null) {
                    requireRepeatedDispatch(mapper, row, action, prior, command);
                    if (budgets.reserve(session, command.budget()) instanceof ExecutionBudgetPort.Reservation.Denied)
                        throw conflict("execution_dispatch_budget_conflict");
                    return new DispatchResult.Existing(row.run(), action.action(), prior.attempt());
                }
                Instant now = mapper.now();
                if (terminal(row) || row.cancelled) return new DispatchResult.Rejected("execution_run_finished");
                if (!current(row, command.revision(), command.lease(), now))
                    return new DispatchResult.Rejected("execution_lease_or_revision_changed");
                if (!row.phase.equals(Phase.READY.name())) return new DispatchResult.Rejected("execution_run_not_ready");
                if (!row.actorVerificationRef.equals(command.actor().verificationRef().value()))
                    return new DispatchResult.Rejected("execution_dispatch_actor_changed");
                ExecutionWaitRow continuation = null;
                if (command.continuation().isPresent()) {
                    if (action == null) throw conflict("execution_continuation_action_missing");
                    action = mapper.lockAction(row.tenantId, action.actionId);
                    requireActionIdentity(action, command.action());
                    var reference = command.continuation().orElseThrow();
                    continuation = requireWaiting(mapper, row, reference);
                    boolean validKind = command.action().kind() == Kind.TOOL && WaitKind.TOOL.name().equals(continuation.kind)
                            || command.action().kind() == Kind.WORKFLOW && WaitKind.WORKFLOW.name().equals(continuation.kind);
                    if (!validKind || !Objects.equals(row.currentActionId, action.actionId)
                            || !Objects.equals(row.waitingActionId, reference.action().actionId())
                            || !Objects.equals(row.waitingGeneration, reference.generation())
                            || continuation.inputId == null || continuation.continuationAttempt != null || continuation.consumedRevision != null
                            || !continuation.sourceAttempt.equals(action.currentAttempt)
                            || !waitSourceMatches(continuation, action, mapper.attempt(row.tenantId, row.runId, continuation.sourceAttempt)))
                        throw conflict("execution_continuation_not_ready");
                } else if (action != null || row.currentActionId != null || row.waitingActionId != null || row.batchSourceAttempt != null) {
                    throw conflict("execution_action_conflict");
                }
                if (!authorization.allowed(session, row.run(), command.actor(), command.action()))
                    throw new DispatchRejected("execution_dispatch_unauthorized");
                var reservation = budgets.reserve(session, command.budget());
                if (reservation instanceof ExecutionBudgetPort.Reservation.Denied denied)
                    throw new DispatchRejected(denied.reasonCode());
                if (!budgets.markSent(session, command.run(), command.attemptId()))
                    throw new DispatchRejected("execution_budget_send_rejected");
                saveRequest(mapper, row.tenantId, command.action());
                var actionRow = action == null ? ExecutionActionRow.prepared(command.run(), command.action()) : action;
                actionRow.currentAttempt = command.attemptId();
                actionRow.status = ActionStatus.DISPATCHED.name(); actionRow.selectedAttempt = null;
                if (action == null) {
                    if (mapper.insertAction(actionRow) != 1) throw conflict("execution_action_conflict");
                } else if (mapper.updateAction(actionRow) != 1) throw conflict("execution_action_conflict");
                var attempt = new ExecutionAttemptRow(); attempt.tenantId = row.tenantId; attempt.runId = row.runId;
                attempt.actionId = actionRow.actionId; attempt.attemptId = command.attemptId(); attempt.fence = row.fence;
                attempt.requestRef = command.action().request().reference().value(); attempt.fingerprint = command.action().fingerprint();
                attempt.continuationGeneration = command.continuation().map(WaitRef::generation).orElse(null);
                if (mapper.insertAttempt(attempt) != 1) throw conflict("execution_attempt_conflict");
                if (continuation != null) {
                    continuation.continuationAttempt = attempt.attemptId;
                    if (mapper.consumeWaitResponse(continuation) != 1) throw conflict("execution_response_already_consumed");
                }
                long previous = row.revision;
                row.currentActionId = actionRow.actionId; row.phase = Phase.AWAITING_RESULT.name(); row.due(leaseUntil(row));
                row.revision = Math.incrementExact(previous); row.touch(now);
                appendEvents(mapper, row, command.events(), now);
                // 权限/预算SQL可能跨过租约期限；最终条件用DB时钟核验，失败回滚全部登记与预留。
                if (mapper.updateDispatchRun(row, previous) != 1)
                    throw new DispatchRejected("execution_lease_or_revision_changed");
                return new DispatchResult.Registered(row.run(), actionRow.action(), attempt.attempt(), command.actor());
            });
        } catch (DispatchRejected rejected) { return new DispatchResult.Rejected(rejected.getMessage()); }
    }

    /** 新调用与原安全结果按native槽位一起登记；只给本次fresh调用发送权。 */
    @Override public BatchDispatchResult dispatchBatch(BatchDispatch command) {
        try {
            return transaction(command.run().tenantId(), (session, mapper) -> {
                lockBudget(session, command.run());
                var row = requireRun(mapper, command.run());
                var reference = new BatchRef(command.run(),command.sourceAttempt(),command.requests().size());
                if (!row.run().identity().owner().equals(command.actor().owner())) return new BatchDispatchResult.Rejected("execution_dispatch_owner_mismatch");
                var existing = mapper.batchMembers(row.tenantId,row.runId,command.sourceAttempt());
                if (!existing.isEmpty()) {
                    if (existing.size()!=reference.size()) throw conflict("execution_batch_conflict");
                    for (int index=0;index<existing.size();index++) {
                        var member = existing.get(index); var supplied = command.requests().get(index);
                        if (member.slot!=supplied.slot() || member.size!=reference.size()) throw conflict("execution_batch_conflict");
                        var action = mapper.action(row.tenantId,member.actionId);
                        var attempt = mapper.attempt(row.tenantId,row.runId,member.firstAttemptId);
                        if (supplied instanceof BatchMember.Fresh fresh) {
                            var request = fresh.request();
                            if (member.reused || !member.firstAttemptId.equals(request.attemptId())) throw conflict("execution_batch_conflict");
                            requireRepeatedDispatch(mapper,row,action,attempt,new Dispatch(command.run(),command.revision(),command.lease(),
                                    request.action(),request.attemptId(),request.budget(),command.actor(),Optional.empty(), List.of()));
                            if (budgets.reserve(session,request.budget()) instanceof ExecutionBudgetPort.Reservation.Denied)
                                throw conflict("execution_dispatch_budget_conflict");
                        } else requireReused(mapper,row,member,action,attempt,(BatchMember.Reused)supplied);
                    }
                    return new BatchDispatchResult.Existing(row.run(),batchRows(mapper,reference,false).entries());
                }
                Instant now=mapper.now();
                if (terminal(row)||row.cancelled) return new BatchDispatchResult.Rejected("execution_run_finished");
                if (!current(row,command.revision(),command.lease(),now)) return new BatchDispatchResult.Rejected("execution_lease_or_revision_changed");
                if (!row.phase.equals(Phase.READY.name())||row.currentActionId!=null||row.batchSourceAttempt!=null||row.waitingActionId!=null)
                    return new BatchDispatchResult.Rejected("execution_run_not_ready");
                if (!row.actorVerificationRef.equals(command.actor().verificationRef().value())) return new BatchDispatchResult.Rejected("execution_dispatch_actor_changed");
                var source=mapper.attempt(row.tenantId,row.runId,command.sourceAttempt());
                var model=source==null?null:mapper.action(row.tenantId,source.actionId);
                if (source==null||model==null||!source.adopted||!ResultKind.SUCCEEDED.name().equals(source.resultKind)||source.uncertaintyRef!=null
                        ||!Kind.MODEL.name().equals(model.kind)||!source.attemptId.equals(model.selectedAttempt)
                        ||model.ordinal!=mapper.latestActionOrdinal(row.tenantId,row.runId)) throw conflict("execution_batch_source_invalid");
                var members=new java.util.ArrayList<ExecutionBatchMemberRow>();
                var actions=new java.util.ArrayList<ExecutionActionRow>(); var attempts=new java.util.ArrayList<ExecutionAttemptRow>();
                for (var supplied:command.requests()) {
                    var member=new ExecutionBatchMemberRow(); member.tenantId=row.tenantId;member.runId=row.runId;
                    member.sourceAttempt=reference.sourceAttempt();member.slot=supplied.slot();member.size=reference.size();
                    if (supplied instanceof BatchMember.Reused reused) {
                        member.reused=true;member.actionId=reused.action().actionId();member.firstAttemptId=reused.attemptId();
                        member.reusedResultRef=reused.result().value();member.reusedFeedbackRef=reused.feedback().value();
                        var action=mapper.lockAction(row.tenantId,member.actionId); var attempt=mapper.lockAttempt(row.tenantId,row.runId,member.firstAttemptId);
                        requireReused(mapper,row,member,action,attempt,reused); actions.add(action);attempts.add(attempt);
                    } else {
                        var request=((BatchMember.Fresh)supplied).request();
                        if (mapper.action(row.tenantId,request.action().action().actionId())!=null||mapper.attempt(row.tenantId,row.runId,request.attemptId())!=null)
                            throw conflict("execution_batch_conflict");
                        if (!authorization.allowed(session,row.run(),command.actor(),request.action())) throw new DispatchRejected("execution_dispatch_unauthorized");
                        if (budgets.reserve(session,request.budget()) instanceof ExecutionBudgetPort.Reservation.Denied denied)
                            throw new DispatchRejected(denied.reasonCode());
                        if (!budgets.markSent(session,command.run(),request.attemptId())) throw new DispatchRejected("execution_budget_send_rejected");
                        saveRequest(mapper,row.tenantId,request.action());
                        var action=ExecutionActionRow.prepared(command.run(),request.action());action.currentAttempt=request.attemptId();
                        action.batchSourceAttempt=reference.sourceAttempt();action.batchSize=reference.size();action.batchSlot=supplied.slot();
                        if (mapper.insertAction(action)!=1) throw conflict("execution_action_conflict");
                        var attempt=new ExecutionAttemptRow();attempt.tenantId=row.tenantId;attempt.runId=row.runId;attempt.actionId=action.actionId;
                        attempt.attemptId=request.attemptId();attempt.fence=row.fence;attempt.requestRef=request.action().request().reference().value();attempt.fingerprint=request.action().fingerprint();
                        if (mapper.insertAttempt(attempt)!=1) throw conflict("execution_attempt_conflict");
                        member.actionId=action.actionId;member.firstAttemptId=attempt.attemptId;actions.add(action);attempts.add(attempt);
                    }
                    if (mapper.insertBatchMember(member)!=1) throw conflict("execution_batch_conflict");members.add(member);
                }
                long previous=row.revision;row.batchSourceAttempt=reference.sourceAttempt();row.batchSize=reference.size();row.phase=Phase.AWAITING_RESULT.name();
                row.revision=Math.incrementExact(previous);row.touch(now);row.due(leaseUntil(row));
                if (mapper.updateDispatchRun(row,previous)!=1) throw new DispatchRejected("execution_lease_or_revision_changed");
                var rows=new BatchRows(reference,members,actions,attempts);var permits=new java.util.ArrayList<DispatchResult.Registered>();
                for(int index=0;index<members.size();index++) if(!members.get(index).reused)
                    permits.add(new DispatchResult.Registered(row.run(),actions.get(index).action(),attempts.get(index).attempt(),command.actor()));
                return new BatchDispatchResult.Registered(row.run(),rows.entries(),permits);
            });
        } catch(DispatchRejected rejected) { return new BatchDispatchResult.Rejected(rejected.getMessage()); }
    }

    /** 所有当前等待均已响应后原子消费；各响应者复验与本次drive actor派发权限分别核实。 */
    @Override public BatchDispatchResult dispatchBatchContinuations(BatchContinue command) {
        try {
            return transaction(command.run().tenantId(),(session,mapper)->{
                lockBudget(session,command.run());
                var row=requireRun(mapper,command.run());var rows=batchRows(mapper,command.batch(),true);
                boolean repeated=command.continuations().stream().allMatch(value->mapper.attempt(row.tenantId,row.runId,value.attemptId())!=null);
                if(repeated) {
                    for(var value:command.continuations()) {
                        int index=value.slot()-1;var action=rows.actions().get(index);var attempt=mapper.attempt(row.tenantId,row.runId,value.attemptId());
                        requireRepeatedDispatch(mapper,row,action,attempt,new Dispatch(command.run(),command.revision(),command.lease(),value.action(),value.attemptId(),value.budget(),command.actor(),Optional.of(value.waiting()), List.of()));
                        var wait=requireWaiting(mapper,row,value.waiting());
                        if(rows.members().get(index).reused||!Objects.equals(wait.continuationAttempt,value.attemptId())
                                ||budgets.reserve(session,value.budget()) instanceof ExecutionBudgetPort.Reservation.Denied) throw conflict("execution_batch_continuation_conflict");
                    }
                    return new BatchDispatchResult.Existing(row.run(),rows.entries());
                }
                if(terminal(row)||row.cancelled) return new BatchDispatchResult.Rejected("execution_run_finished");
                if(!sameBatch(row,command.batch())||!row.phase.equals(Phase.READY.name())||!current(row,command.revision(),command.lease(),mapper.now()))
                    return new BatchDispatchResult.Rejected("execution_lease_or_revision_changed");
                if(!row.run().identity().owner().equals(command.actor().owner())||!row.actorVerificationRef.equals(command.actor().verificationRef().value()))
                    return new BatchDispatchResult.Rejected("execution_dispatch_actor_changed");
                var waits=activeBatchWaits(mapper,rows);
                if(waits.size()!=command.continuations().size()||waits.isEmpty()||!settled(rows)) throw conflict("execution_batch_wait_changed");
                var newAttempts=new java.util.ArrayList<ExecutionAttemptRow>();var changed=new java.util.ArrayList<ExecutionActionRow>();
                for(int index=0;index<command.continuations().size();index++) {
                    var value=command.continuations().get(index);var wait=waits.get(index);int slot=value.slot()-1;
                    var action=rows.actions().get(slot);var source=rows.attempts().get(slot);
                    if(rows.members().get(slot).reused||!wait.actionId.equals(action.actionId)||!waiting(mapper,wait).reference().equals(value.waiting())
                            ||!wait.sourceAttempt.equals(source.attemptId)||wait.inputId==null||!source.adopted||!waitSourceMatches(wait,action,source)
                            ||mapper.attempt(row.tenantId,row.runId,value.attemptId())!=null) throw conflict("execution_batch_wait_changed");
                    requireActionIdentity(action,value.action());
                    var responder=SqliteIdentities.readOriginalVerification(session,row.run().identity().owner(),new ResultRef(wait.actorRef))
                            .orElseThrow(()->conflict("execution_response_actor_missing"));
                    if(!controlAuthorization.allowed(session,row.run(),responder,Control.RESUME,new InputId(wait.inputId))) throw new DispatchRejected("execution_resume_unauthorized");
                    if(!authorization.allowed(session,row.run(),command.actor(),value.action())) throw new DispatchRejected("execution_dispatch_unauthorized");
                    if(budgets.reserve(session,value.budget()) instanceof ExecutionBudgetPort.Reservation.Denied denied)
                        throw new DispatchRejected(denied.reasonCode());
                    if(!budgets.markSent(session,command.run(),value.attemptId())) throw new DispatchRejected("execution_budget_send_rejected");
                    saveRequest(mapper,row.tenantId,value.action());
                    var attempt=new ExecutionAttemptRow();attempt.tenantId=row.tenantId;attempt.runId=row.runId;attempt.actionId=action.actionId;attempt.attemptId=value.attemptId();
                    attempt.fence=row.fence;attempt.requestRef=value.action().request().reference().value();attempt.fingerprint=value.action().fingerprint();attempt.continuationGeneration=wait.generation;
                    if(mapper.insertAttempt(attempt)!=1) throw conflict("execution_attempt_conflict");
                    wait.continuationAttempt=attempt.attemptId;if(mapper.consumeWaitResponse(wait)!=1) throw conflict("execution_response_already_consumed");
                    action.currentAttempt=attempt.attemptId;action.status=ActionStatus.DISPATCHED.name();action.selectedAttempt=null;
                    if(mapper.updateAction(action)!=1) throw conflict("execution_action_conflict");
                    rows.attempts().set(slot,attempt);newAttempts.add(attempt);changed.add(action);
                }
                Instant now=mapper.now();long previous=row.revision;row.phase=Phase.AWAITING_RESULT.name();row.status=RunStatus.RUNNING.name();
                row.revision=Math.incrementExact(previous);row.touch(now);row.due(leaseUntil(row));
                if(mapper.updateDispatchRun(row,previous)!=1) throw new DispatchRejected("execution_lease_or_revision_changed");
                var permits=new java.util.ArrayList<DispatchResult.Registered>();
                for(int index=0;index<newAttempts.size();index++) permits.add(new DispatchResult.Registered(row.run(),changed.get(index).action(),newAttempts.get(index).attempt(),command.actor()));
                return new BatchDispatchResult.Registered(row.run(),rows.entries(),permits);
            });
        } catch(DispatchRejected rejected) {return new BatchDispatchResult.Rejected(rejected.getMessage());}
    }

    /**
     * 结果及用量先按原 Attempt 幂等保存，只有当前租约与版本才采纳并追加公开事件。
     * 迟到结果与 UNKNOWN 不创建后续动作，预算缺报维度继续保留预留。
     */
    @Override public CompletionResult complete(Completion command) {
        return transaction(command.run().tenantId(), (session, mapper) -> {
            lockBudget(session, command.run());

            var row = requireRun(mapper, command.run());
            var found = mapper.attempt(row.tenantId, row.runId, command.attemptId());
            if (found == null) throw conflict("execution_attempt_missing");
            var action = mapper.lockAction(row.tenantId, found.actionId);
            var attempt = mapper.lockAttempt(row.tenantId, row.runId, command.attemptId());
            if (action == null || attempt == null) throw conflict("execution_action_or_attempt_missing");
            if (command.waitDeclaration().isPresent()) {
                var declaration = command.waitDeclaration().orElseThrow();
                Kind expectedKind = switch (declaration.kind()) {
                    case TOOL -> Kind.TOOL; case HUMAN, PLAN -> Kind.MODEL; case DELEGATION -> Kind.DELEGATION; case WORKFLOW -> Kind.WORKFLOW;
                };
                if (!declaration.reference().action().actionId().equals(action.actionId) || !action.kind.equals(expectedKind.name())
                        || declaration.kind() == WaitKind.TOOL && command.kind() != ResultKind.INPUT_REQUIRED
                        || declaration.kind() != WaitKind.TOOL && command.kind() != ResultKind.SUCCEEDED)
                    throw conflict("execution_wait_action_mismatch");
                var existingWait = mapper.waiting(row.tenantId, row.runId, action.actionId, declaration.reference().generation());
                if (existingWait != null && (!existingWait.sourceAttempt.equals(attempt.attemptId)
                        || !existingWait.kind.equals(declaration.kind().name())
                        || !waiting(mapper, existingWait).internal().equals(declaration.internal())
                        || !waiting(mapper, existingWait).requested().equals(declaration.requested())))
                    throw conflict("execution_wait_conflict");
            }
            boolean newlySaved = saveFacts(session, mapper, row, attempt, command);
            Instant now = mapper.now();
            boolean adopt = !attempt.adopted && !terminal(row) && !row.cancelled
                    && current(row, command.revision(), command.lease(), now)
                    && Objects.equals(row.currentActionId, action.actionId)
                    && row.phase.equals(Phase.AWAITING_RESULT.name());
            if (adopt) {
                // 新上下文只在当前推进事务保存；迟到结果不得提交新的上下文或公开事件。
                saveMaterial(mapper, row.tenantId, command.context());
                requireMaterial(mapper, row.tenantId, command.next().context());
                if (command.next().decisions() < row.decisions || command.next().toolCalls() < row.toolCalls
                        || command.next().knowledgeCalls() < row.knowledgeCalls)
                    throw conflict("execution_progress_regressed");
                attempt.adopted = true;
                action.selectedAttempt = command.kind() == ResultKind.INPUT_REQUIRED ? null : attempt.attemptId;
                action.status = switch (command.kind()) {
                    case SUCCEEDED -> ActionStatus.SUCCEEDED.name(); case FAILED -> ActionStatus.FAILED.name();
                    case UNKNOWN -> ActionStatus.UNKNOWN.name(); case INPUT_REQUIRED -> ActionStatus.WAITING_INPUT.name();
                };
                long previous = row.revision;
                row.decisions = command.next().decisions(); row.toolCalls = command.next().toolCalls();
                row.knowledgeCalls = command.next().knowledgeCalls(); row.contextRef = command.next().context().value();
                if (command.waitDeclaration().isPresent()) {
                    var declaration = command.waitDeclaration().orElseThrow();
                    if (declaration.reference().generation() != mapper.nextWaitGeneration(row.tenantId, action.actionId))
                        throw conflict("execution_wait_generation_changed");
                    var waiting = new ExecutionWaitRow(); waiting.tenantId = row.tenantId; waiting.runId = row.runId;
                    waiting.actionId = action.actionId; waiting.generation = declaration.reference().generation(); waiting.kind = declaration.kind().name();
                    waiting.sourceAttempt = attempt.attemptId; waiting.declaredRevision = Math.incrementExact(previous);
                    waiting.internal(declaration);
                    if (mapper.insertWait(waiting) != 1) throw conflict("execution_wait_conflict");
                    saveWaitChildren(mapper, waiting, declaration);
                    declaration.requested().forEach((key, method) -> {
                        var request = new ExecutionWaitRequestRow(); request.tenantId = waiting.tenantId; request.actionId = waiting.actionId;
                        request.generation = waiting.generation; request.inputKey = key; request.method = method.wireName(); mapper.insertWaitRequest(request);
                    });
                    row.waitingActionId = action.actionId; row.waitingGeneration = waiting.generation;
                    row.phase = Phase.WAITING.name(); row.status = RunStatus.SUSPENDED.name(); row.due(null); clearLease(row);
                } else if (command.kind() == ResultKind.UNKNOWN) {
                    row.phase = Phase.UNCERTAIN.name(); row.status = RunStatus.SUSPENDED.name(); row.due(null); clearLease(row);
                } else {
                    row.phase = Phase.READY.name(); row.currentActionId = null; row.waitingActionId = null; row.waitingGeneration = null; row.due(now);
                }
                advance(mapper, row, previous, now);
                mapper.updateAction(action);
                appendEvents(mapper, row, command.events(), now);
            }
            if (mapper.updateAttempt(attempt) != 1) throw conflict("execution_attempt_update_failed");
            return new CompletionResult(row.run(), attempt.attempt(), newlySaved, adopt);
        });
    }

    /** 原调用结果独立保存；早card及响应不改变仍在途兄弟的原发送权。 */
    @Override public CompletionResult completeBatch(BatchCompletion command) {
        var result=command.result();var reference=command.batch();
        return transaction(result.run().tenantId(),(session,mapper)->{
            lockBudget(session,result.run());
            var row=requireRun(mapper,result.run());var rows=batchRows(mapper,reference,true);int index=-1;
            for(int slot=0;slot<rows.attempts().size();slot++) if(rows.attempts().get(slot).attemptId.equals(result.attemptId())) index=slot;
            if(index<0||rows.members().get(index).reused) throw conflict("execution_batch_attempt_missing");
            var action=rows.actions().get(index);var attempt=rows.attempts().get(index);
            boolean newlySaved=saveFacts(session,mapper,row,attempt,result);
            if(mapper.updateAttempt(attempt)!=1) throw conflict("execution_attempt_update_failed");
            // 等待声明是原真实结果的一部分；旧lease仅禁止推进，不应丢失精确source/keys。
            if(result.kind()==ResultKind.INPUT_REQUIRED&&!terminal(row)&&!row.cancelled&&sameBatch(row,reference))
                registerBatchWait(mapper,row,action,attempt,result.waitDeclaration().orElseThrow());
            boolean advanced=false;
            if(!terminal(row)&&!row.cancelled&&sameBatch(row,reference)&&current(row,result.revision(),result.lease(),mapper.now())&&!quiescent(row)) {
                if(result.kind()==ResultKind.UNKNOWN) {action.status=ActionStatus.UNKNOWN.name();mapper.updateAction(action);}
                appendEvents(mapper,row,result.events(),mapper.now());
                if(settled(rows)) {
                    if(!accurate(rows)) settleBatch(mapper,row,reference,rows);
                    else if(command.adoption().isPresent()) advanced=adoptBatchRows(mapper,row,reference,rows,result.revision(),result.lease(),command.adoption().orElseThrow());
                }
            }
            return new CompletionResult(row.run(),attempt.attempt(),newlySaved,advanced);
        });
    }

    /** 只供重启后所有结果已保存的恢复采纳；不授予发送权、不重新预留。 */
    @Override public BatchAdoptionResult adoptBatch(BatchAdopt command) {
        return transaction(command.run().tenantId(), (session, mapper) -> {
            lockBudget(session, command.run());
            var row = requireRun(mapper, command.run()); var rows = batchRows(mapper, command.batch(), true);
            boolean advanced = adoptBatchRows(mapper, row, command.batch(), rows, command.revision(), command.lease(), command.adoption());
            return new BatchAdoptionResult(row.run(), advanced);
        });
    }

    /** 精确恢复当前组的原槽位事实；没有结果的Attempt始终不能重发。 */
    @Override public List<BatchEntry> batch(BatchRef reference) {
        return read(reference.run().tenantId(), (session, mapper) -> batchRows(mapper, reference, false).entries());
    }

    /** 组内准确结果不补发；完整多等待统一决策，缺失或UNKNOWN只能静止。 */
    @Override public BatchState quiesceBatch(BatchQuiesce command) {
        var reference=command.batch();
        return transaction(reference.run().tenantId(),(session,mapper)->{
            var row=requireRun(mapper,reference.run());var rows=batchRows(mapper,reference,true);
            if(terminal(row)||row.cancelled||!sameBatch(row,reference)||accurate(rows)||quiescent(row)) return new BatchState(row.run(),rows.entries(),false);
            if(!current(row,command.revision(),command.lease(),mapper.now())) throw conflict("execution_lease_or_revision_changed");
            boolean changed;
            if(settled(rows)) changed=settleBatch(mapper,row,reference,rows);
            else {long previous=row.revision;row.phase=Phase.UNCERTAIN.name();row.status=RunStatus.SUSPENDED.name();row.due(null);clearLease(row);
                advance(mapper,row,previous,mapper.now());changed=true;}
            if(changed) appendEvents(mapper,row,command.events(),mapper.now());
            return new BatchState(row.run(),rows.entries(),changed);
        });
    }

    /** 只列当前manifest中尚未消费的精确等待，不返回旧generation或复用槽位的历史card。 */
    @Override public List<Waiting> batchWaits(BatchRef reference) {
        return transaction(reference.run().tenantId(),(session,mapper)->{
            var rows=batchRows(mapper,reference,false);
            return activeBatchWaits(mapper,rows).stream().map(value->waiting(mapper,value)).toList();
        });
    }

    /** 全已知事实的本地终止同时提交真实物理次数和专用context；不构造半个native历史。 */
    @Override public Run stopBatch(BatchStop command) {
        return transaction(command.run().tenantId(),(session,mapper)->{
            var row=requireRun(mapper,command.run());var rows=batchRows(mapper,command.batch(),true);
            requireBatchResults(rows,command.results());
            if(terminal(row)) {
                if(!row.status.equals(command.status().name())||!row.contextRef.equals(command.context().reference().value())
                        ||!requireMaterial(mapper,row.tenantId,command.context().reference()).equals(command.context())) throw conflict("execution_terminal_conflict");
                return row.run();
            }
            if(!sameBatch(row,command.batch())||!row.phase.equals(Phase.READY.name())||!current(row,command.revision(),command.lease(),mapper.now())
                    ||!settled(rows)||rows.attempts().stream().anyMatch(value->value.uncertaintyRef!=null||ResultKind.UNKNOWN.name().equals(value.resultKind)))
                throw conflict("execution_batch_not_settled");
            requireBatchProgress(mapper,row,command.batch(),command.next());saveMaterial(mapper,row.tenantId,command.context());
            long previous=row.revision;row.decisions=command.next().decisions();row.toolCalls=command.next().toolCalls();row.knowledgeCalls=command.next().knowledgeCalls();
            row.contextRef=command.context().reference().value();row.status=command.status().name();row.phase=Phase.FINISHED.name();
            row.cancelled=row.cancelled||command.status()==RunStatus.CANCELLED;row.batchSourceAttempt=null;row.batchSize=null;row.due(null);clearLease(row);
            advance(mapper,row,previous,mapper.now());appendEvents(mapper,row,command.events(),mapper.now());return row.run();
        });
    }

    /** 长MCP握手后仅复验原派发的发送权，不产生第二次派发、预留或计次。 */
    @Override public boolean authorizeSend(DispatchResult.Registered registered, Prepared prepared) {
        Objects.requireNonNull(registered); Objects.requireNonNull(prepared);
        var reference = registered.run().identity().runRef();
        return transaction(reference.tenantId(), (session, mapper) -> {
            var row = requireRun(mapper, reference);
            var action = mapper.lockAction(row.tenantId, registered.action().reference().actionId());
            var attempt = mapper.lockAttempt(row.tenantId, row.runId, registered.attempt().id());
            if (action == null || attempt == null || terminal(row) || row.cancelled
                    || !row.phase.equals(Phase.AWAITING_RESULT.name()) || !actionBelongsToCurrent(row, action)
                    || !Objects.equals(action.currentAttempt, attempt.attemptId) || !attempt.actionId.equals(action.actionId)
                    || !registered.run().identity().equals(row.run().identity())
                    || !row.actorVerificationRef.equals(registered.actor().verificationRef().value())
                    || !registered.actor().owner().equals(row.run().identity().owner())
                    || registered.run().lease().isEmpty() || !leaseMatches(row, registered.run().lease().orElseThrow(), mapper.now())
                    || attempt.fence != registered.attempt().fence() || attempt.fence != row.fence
                    || attempt.resultRef != null || attempt.uncertaintyRef != null || attempt.adopted
                    || !attempt.attempt().equals(registered.attempt()) || !action.action().equals(registered.action())) return false;
            if (!sameActionIdentity(action, prepared) || !attempt.requestRef.equals(prepared.request().reference().value()) || !attempt.fingerprint.equals(prepared.fingerprint())
                    || !requestMatches(mapper, row.tenantId, prepared)) return false;
            if (!authorization.allowed(session, row.run(), registered.actor(), prepared)) return false;
            return leaseMatches(row, registered.run().lease().orElseThrow(), mapper.now())
                    && mapper.authorizeOnce(row.tenantId, attempt.attemptId) == 1;
        });
    }

    /** 响应接受只锁Run及原动作；当前IAM、稳定输入与等待身份和状态一起提交。 */
    @Override public ResumeResult resume(Resume command) {
        return transaction(command.run().tenantId(), (session, mapper) -> resume(session, mapper, command));
    }
    private ResumeResult resume(SqlSession session, ExecutionStoreMapper mapper, Resume command) {
            var row = requireRun(mapper, command.run());
            if (!row.run().identity().owner().equals(command.actor().owner())) throw conflict("execution_resume_owner_mismatch");
            if (!controlAuthorization.allowed(session, row.run(), command.actor(), Control.RESUME, command.input()))
                throw conflict("execution_resume_unauthorized");
            requireControlInputKind(session, mapper, row, command.input(), Control.RESUME);
            var previousResponse = mapper.response(row.tenantId, row.runId, command.input().value());
            if (previousResponse != null) {
                var waiting = waiting(mapper, previousResponse);
                if (!waiting.reference().equals(command.waiting()) || !previousResponse.fingerprint.equals(command.fingerprint())
                        || !previousResponse.responseRef.equals(command.response().reference().value())
                        || !waiting.requested().equals(command.responseMethods())
                        || !requireMaterial(mapper, row.tenantId, command.response().reference()).equals(command.response()))
                    throw conflict("execution_response_conflict");
                return new ResumeResult(row.run(), waiting, false);
            }
            if (terminal(row) || row.cancelled) throw conflict("execution_run_finished");
            var action=mapper.lockAction(row.tenantId,command.waiting().action().actionId());
            var waiting=requireWaiting(mapper,row,command.waiting());
            boolean batch=row.batchSourceAttempt!=null;
            BatchRows batchRows=null;
            if(batch) {
                var reference=new BatchRef(command.run(),row.batchSourceAttempt,row.batchSize);batchRows=batchRows(mapper,reference,true);
                if((row.revision!=command.revision()&&waiting.declaredRevision!=command.revision())
                        ||!(row.phase.equals(Phase.WAITING.name())||row.phase.equals(Phase.AWAITING_RESULT.name())||row.phase.equals(Phase.READY.name()))
                        ||activeBatchWaits(mapper,batchRows).stream().noneMatch(value->value.actionId.equals(waiting.actionId)&&value.generation==waiting.generation))
                    throw conflict("execution_wait_changed");
            } else {
                if(row.revision!=command.revision()) throw conflict("execution_revision_changed");
                if(!row.phase.equals(Phase.WAITING.name())||!Objects.equals(row.waitingActionId,command.waiting().action().actionId())
                        ||!Objects.equals(row.waitingGeneration,command.waiting().generation())) throw conflict("execution_wait_changed");
            }
            var source=mapper.lockAttempt(row.tenantId,row.runId,waiting.sourceAttempt);
            if(action==null||source==null||!batch&&!Objects.equals(row.currentActionId,action.actionId)
                    ||!action.currentAttempt.equals(source.attemptId)||!source.adopted||!waitSourceMatches(waiting,action,source)
                    ||waiting.inputId!=null||waiting.continuationAttempt!=null||waiting.consumedRevision!=null) throw conflict("execution_wait_not_open");
            if (!waiting(mapper, waiting).requested().equals(command.responseMethods())) throw conflict("execution_response_methods_mismatch");
            if (WaitKind.DELEGATION.name().equals(waiting.kind) || WaitKind.WORKFLOW.name().equals(waiting.kind) || WaitKind.PLAN.name().equals(waiting.kind)) {
                throw conflict("desktop_wait_mode_unsupported");
            }
            SqliteIdentities.save(session, command.actor());
            saveMaterial(mapper, row.tenantId, command.response());
            long previous = row.revision; Instant now = mapper.now();
            waiting.inputId = command.input().value(); waiting.responseRef = command.response().reference().value();
            waiting.fingerprint = command.fingerprint(); waiting.actorRef = command.actor().verificationRef().value();
            waiting.acceptedRevision = batch ? previous : Math.incrementExact(previous);
            if (mapper.acceptWaitResponse(waiting) != 1) throw conflict("execution_response_conflict");
            if(batch) {
                // 原drive actor、lease与revision在兄弟在途时全部保持；精确响应单独拥有actor。
                if(!row.phase.equals(Phase.AWAITING_RESULT.name())) settleBatch(mapper,row,batchRows.reference(),batchRows);
            } else {
                row.actorVerificationRef = waiting.actorRef; row.phase = Phase.READY.name(); row.status = RunStatus.RESUMING.name(); row.due(now);
                advance(mapper,row,previous,now);
            }
            appendEvents(mapper,row,command.events(),now);
            return new ResumeResult(row.run(), waiting(mapper, waiting), true);
    }

    /** 补充输入只登记原Run的有序事实；当前外调的版本、租约和Attempt均不变。 */
    @Override public InputOffer offerInput(OfferInput command) {
        Objects.requireNonNull(command);
        return transaction(command.run().tenantId(), (session, mapper) -> {
            var row = requireRun(mapper, command.run());
            requireSupplementAuthorization(session, row, command.actor(), command.input());
            requireControlInputKind(session, mapper, row, command.input(), Control.STEER);
            var supplements = session.getMapper(ExecutionSupplementMapper.class);
            var existing = supplements.find(row.tenantId, row.runId, command.input().value());
            if (existing != null) {
                if (!existing.contentRef.equals(command.content().reference().value()) || !existing.fingerprint.equals(command.fingerprint())
                        || !SqliteIdentities.sameOriginalCaller(session, command.actor().owner(), new ResultRef(existing.actorRef), command.actor())
                        || !requireMaterial(mapper, row.tenantId, command.content().reference()).equals(command.content()))
                    throw conflict("execution_supplement_conflict");
                return new InputOffer(row.run(), supplementalInput(existing), false);
            }
            if (terminal(row) || row.cancelled) throw conflict("execution_run_finished");
            SqliteIdentities.save(session, command.actor());
            saveMaterial(mapper, row.tenantId, command.content());
            var input = new ExecutionSupplementRow(); input.tenantId = row.tenantId; input.runId = row.runId;
            input.inputId = command.input().value(); input.contentRef = command.content().reference().value();
            input.fingerprint = command.fingerprint(); input.actorRef = command.actor().verificationRef().value();
            input.ordinal = supplements.nextOrdinal(row.tenantId, row.runId);
            if (supplements.insert(input) != 1) throw conflict("execution_supplement_conflict");
            return new InputOffer(row.run(), supplementalInput(input), true);
        });
    }

    /** 有界读取原始输入顺序；pendingOnly只筛本地尚未采纳，不推测模型消费状态。 */
    @Override public List<SupplementalInput> inputs(RunRef run, long afterOrdinal, boolean pendingOnly, int limit) {
        Objects.requireNonNull(run); bounded(limit);
        if (afterOrdinal < 0) throw new IllegalArgumentException("execution_supplement_ordinal_invalid");
        return read(run.tenantId(), (session, mapper) -> {
            if (mapper.run(tenant(run), run.runId()) == null) throw conflict("execution_run_missing");
            return session.getMapper(ExecutionSupplementMapper.class).inputs(tenant(run), run.runId(), afterOrdinal, pendingOnly, limit)
                    .stream().map(SqliteExecutionStore::supplementalInput).toList();
        });
    }

    /** 安全READY边界按最早未应用顺序原子采纳；原提交者当前权限撤销后不能再应用。 */
    @Override public InputAcceptance acceptInput(AcceptInput command) {
        Objects.requireNonNull(command);
        return transaction(command.run().tenantId(), (session, mapper) -> {
            var row = requireRun(mapper, command.run()); var supplements = session.getMapper(ExecutionSupplementMapper.class);
            var input = supplements.find(row.tenantId, row.runId, command.input().value());
            if (input == null) throw conflict("execution_supplement_missing");
            var actor = SqliteIdentities.readOriginalVerification(session, row.run().identity().owner(), new ResultRef(input.actorRef))
                    .orElseThrow(() -> conflict("execution_supplement_unauthorized"));
            requireSupplementAuthorization(session, row, actor, command.input());
            if (input.appliedRevision != null) return new InputAcceptance(row.run(), supplementalInput(input), actor, false);
            if (terminal(row) || row.cancelled) throw conflict("execution_run_finished");
            Instant now = mapper.now();
            if (!current(row, command.revision(), command.lease(), now)) throw conflict("execution_lease_or_revision_changed");
            if (!Phase.READY.name().equals(row.phase) || row.currentActionId != null || row.batchSourceAttempt != null || row.waitingActionId != null)
                throw conflict("execution_supplement_not_ready");
            var pending = supplements.inputs(row.tenantId, row.runId, 0, true, 1);
            if (pending.isEmpty() || !pending.getFirst().inputId.equals(input.inputId)) throw conflict("execution_supplement_order_changed");
            if (command.next().decisions() != row.decisions || command.next().toolCalls() != row.toolCalls
                    || command.next().knowledgeCalls() != row.knowledgeCalls) throw conflict("execution_supplement_progress_changed");
            saveMaterial(mapper, row.tenantId, command.context()); requireMaterial(mapper, row.tenantId, command.next().context());
            long previous = row.revision; input.appliedRevision = Math.incrementExact(previous);
            if (supplements.apply(input) != 1) throw conflict("execution_supplement_already_applied");
            row.contextRef = command.next().context().value(); row.actorVerificationRef = input.actorRef;
            row.status = RunStatus.RUNNING.name(); row.due(now); row.revision = Math.incrementExact(previous); row.touch(now);
            if (mapper.updateLocalResponseRun(row, previous) != 1) throw conflict("execution_lease_or_revision_changed");
            appendEvents(mapper, row, command.events(), now);
            return new InputAcceptance(row.run(), supplementalInput(input), actor, true);
        });
    }

    /** owner只确定归属，当前IAM必须在同一业务事务内另行核验。 */
    private void requireSupplementAuthorization(SqlSession session, ExecutionRunRow row, VerifiedIdentity actor, InputId input) {
        if (!row.run().identity().owner().equals(actor.owner())) throw conflict("execution_supplement_owner_mismatch");
        if (!controlAuthorization.allowed(session, row.run(), actor, Control.STEER, input)) throw conflict("execution_supplement_unauthorized");
    }

    /** 同一原Run锁内核输入类别；同类原回执由所属账本继续精确核对，不创建第二控制账本。 */
    private static void requireControlInputKind(SqlSession session, ExecutionStoreMapper mapper,
            ExecutionRunRow run, InputId input, Control operation) {
        if (run.inputId.equals(input.value())
                || operation != Control.STEER && session.getMapper(ExecutionSupplementMapper.class)
                        .find(run.tenantId, run.runId, input.value()) != null
                || operation != Control.RESUME && mapper.response(run.tenantId, run.runId, input.value()) != null)
            throw conflict("execution_control_input_conflict");
    }

    private static SupplementalInput supplementalInput(ExecutionSupplementRow row) {
        return new SupplementalInput(new RunRef(new TenantId(row.tenantId), row.runId), new InputId(row.inputId), new ResultRef(row.contentRef),
                row.fingerprint, new ResultRef(row.actorRef), row.ordinal,
                row.appliedRevision == null ? java.util.OptionalLong.empty() : java.util.OptionalLong.of(row.appliedRevision));
    }

    /** 人类输入只推进本地上下文；Run锁后消费精确回执，不获取预算根或制造外调事实。 */
    @Override public LocalResponseResult acceptLocalResponse(AcceptLocalResponse command) {
        return transaction(command.run().tenantId(), (session, mapper) -> {
            var row = requireRun(mapper, command.run());
            var action = mapper.lockAction(row.tenantId, command.waiting().action().actionId());
            var waiting = requireWaiting(mapper, row, command.waiting());
            if (!WaitKind.HUMAN.name().equals(waiting.kind) && !WaitKind.DELEGATION.name().equals(waiting.kind) && !WaitKind.PLAN.name().equals(waiting.kind))
                throw conflict("execution_local_wait_kind_mismatch");
            if (!Objects.equals(waiting.inputId, command.input().value()) || waiting.responseRef == null || waiting.actorRef == null)
                throw conflict("execution_local_response_mismatch");
            if (waiting.consumedRevision != null) return new LocalResponseResult(row.run(), waiting(mapper, waiting), false);
            if (terminal(row) || row.cancelled) throw conflict("execution_run_finished");
            Instant now = mapper.now();
            if (!current(row, command.revision(), command.lease(), now)) throw conflict("execution_lease_or_revision_changed");
            if (!row.phase.equals(Phase.READY.name()) || !Objects.equals(row.currentActionId, command.waiting().action().actionId())
                    || !Objects.equals(row.waitingActionId, command.waiting().action().actionId())
                    || !Objects.equals(row.waitingGeneration, command.waiting().generation())
                    || !row.actorVerificationRef.equals(waiting.actorRef) || waiting.continuationAttempt != null)
                throw conflict("execution_local_wait_changed");
            var source = mapper.attempt(row.tenantId, row.runId, waiting.sourceAttempt);
            if (action == null || source == null || !source.adopted || !waitSourceMatches(waiting, action, source))
                throw conflict("execution_wait_source_mismatch");
            if (command.next().decisions() < row.decisions || command.next().toolCalls() < row.toolCalls
                    || command.next().knowledgeCalls() < row.knowledgeCalls) throw conflict("execution_progress_regressed");
            saveMaterial(mapper, row.tenantId, command.context());
            requireMaterial(mapper, row.tenantId, command.next().context());
            long previous = row.revision; waiting.consumedRevision = Math.incrementExact(previous);
            if (mapper.consumeLocalWaitResponse(waiting) != 1) throw conflict("execution_response_already_consumed");
            row.decisions = command.next().decisions(); row.toolCalls = command.next().toolCalls(); row.knowledgeCalls = command.next().knowledgeCalls();
            row.contextRef = command.next().context().value(); row.phase = Phase.READY.name(); row.status = RunStatus.RUNNING.name();
            row.waitingActionId = null; row.waitingGeneration = null; row.currentActionId = null; row.due(now);
            row.revision = Math.incrementExact(previous); row.touch(now);
            if (mapper.updateLocalResponseRun(row, previous) != 1) throw conflict("execution_lease_or_revision_changed");
            appendEvents(mapper, row, command.events(), now);
            return new LocalResponseResult(row.run(), waiting(mapper, waiting), true);
        });
    }

    /** 无结果Attempt只静止一次；准确结果已到达时返回原事实供当前持有者采纳。 */
    @Override public QuiescenceResult quiesceUnknown(QuiesceUnknown command) {
        return transaction(command.run().tenantId(), (session, mapper) -> {
            var row = requireRun(mapper, command.run());
            var found = mapper.attempt(row.tenantId, row.runId, command.attemptId());
            if (found == null) throw conflict("execution_attempt_missing");
            var action = mapper.lockAction(row.tenantId, found.actionId);
            var attempt = mapper.lockAttempt(row.tenantId, row.runId, found.attemptId);
            if (action == null || attempt == null) throw conflict("execution_action_or_attempt_missing");
            if (attempt.resultRef != null && !ResultKind.UNKNOWN.name().equals(attempt.resultKind)
                    || terminal(row) || row.cancelled || quiescent(row))
                return new QuiescenceResult(row.run(), attempt.attempt(), false);
            Instant now = mapper.now();
            if (!current(row, command.revision(), command.lease(), now)) throw conflict("execution_lease_or_revision_changed");
            if (!row.phase.equals(Phase.AWAITING_RESULT.name()) || !Objects.equals(row.currentActionId, action.actionId)
                    || !action.currentAttempt.equals(attempt.attemptId)) throw conflict("execution_attempt_changed");
            long previous = row.revision;
            row.phase = Phase.UNCERTAIN.name(); row.status = RunStatus.SUSPENDED.name(); row.due(null); clearLease(row);
            action.status = ActionStatus.UNKNOWN.name(); action.selectedAttempt = null;
            advance(mapper, row, previous, now); mapper.updateAction(action); appendEvents(mapper, row, command.events(), now);
            return new QuiescenceResult(row.run(), attempt.attempt(), true);
        });
    }

    /** 本地结束只锁 Run；终态与事件一次提交，已在途结果仍可单独落账。 */
    @Override public Run finish(Finish command) {
        return transaction(command.run().tenantId(), (session, mapper) -> {
            var row = requireRun(mapper, command.run());
            if (terminal(row)) {
                if (!row.status.equals(command.status().name())) throw conflict("execution_terminal_conflict");
                if (command.terminalContext().isPresent()) {
                    var context = command.terminalContext().orElseThrow();
                    var events = mapper.eventsAtRevision(row.tenantId, row.runId, Math.incrementExact(command.revision()))
                            .stream().map(event -> event.event().event()).toList();
                    if (row.revision != Math.incrementExact(command.revision()) || row.fence != command.lease().fencingToken()
                            || !row.contextRef.equals(context.reference().value())
                            || !requireMaterial(mapper, row.tenantId, context.reference()).equals(context)
                            || !events.equals(command.events())) throw conflict("execution_terminal_conflict");
                }
                return row.run();
            }
            Instant now = mapper.now();
            if (!current(row, command.revision(), command.lease(), now)) throw conflict("execution_lease_or_revision_changed");
            if (command.terminalContext().isPresent()) {
                var context = command.terminalContext().orElseThrow();
                if (row.contextRef.equals(context.reference().value())) throw conflict("execution_terminal_conflict");
                var eventIds = new java.util.HashSet<String>();
                for (var event : command.events()) {
                    if (!eventIds.add(event.id().value()) || mapper.event(row.tenantId, row.sessionId, event.id().value()) != null)
                        throw conflict("execution_event_conflict");
                }
                saveMaterial(mapper, row.tenantId, context);
                row.contextRef = context.reference().value();
            }
            long previous = row.revision; row.status = command.status().name(); row.phase = Phase.FINISHED.name();
            row.cancelled = row.cancelled || command.status() == RunStatus.CANCELLED; row.due(null); clearLease(row);
            advance(mapper, row, previous, now); appendEvents(mapper, row, command.events(), now); return row.run();
        });
    }

    /** 当前入口身份必须匹配原 owner；纯取消不反向获取预算根锁。 */
    @Override public Run cancel(Cancel command) {
        return transaction(command.run().tenantId(), (session, mapper) -> {
            var row = requireRun(mapper, command.run());
            if (!row.run().identity().owner().equals(command.identity().owner())) throw conflict("execution_cancel_owner_mismatch");
            if (!controlAuthorization.allowed(session, row.run(), command.identity(), Control.CANCEL, command.input()))
                throw conflict("execution_cancel_unauthorized");
            requireControlInputKind(session, mapper, row, command.input(), Control.CANCEL);
            if (terminal(row)) return row.run();
            Instant now = mapper.now(); long previous = row.revision;
            row.cancelled = true; row.actorVerificationRef = command.identity().verificationRef().value();
            row.status = RunStatus.CANCELLED.name(); row.phase = Phase.FINISHED.name(); row.due(null); clearLease(row);
            advance(mapper, row, previous, now); appendEvents(mapper, row, command.events(), now); return row.run();
        });
    }

    /** 活动控制只续当前租约，不改变业务 revision；旧 fence 不获得新期限。 */
    @Override public Run renew(RunRef run, LeaseFence lease, Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) throw new IllegalArgumentException("execution_duration_invalid");
        return transaction(run.tenantId(), (session, mapper) -> {
            lockBudget(session, run);

            var row = requireRun(mapper, run); Instant now = mapper.now();
            if (terminal(row) || row.cancelled) return row.run();
            if (!leaseMatches(row, lease, now)) throw conflict("execution_lease_changed");
            long expected = row.revision; row.leaseUntil(now.plus(duration)); row.due(leaseUntil(row)); row.touch(now);
            if (mapper.updateRun(row, expected) != 1) throw conflict("execution_revision_changed");
            return row.run();
        });
    }

    /** 只读不可变协议材料。 */
    /** 单次批读保持调用顺序、重复引用及缺失项；不按消息逐条发起 SQL。 */
    @Override public List<Optional<Material>> materials(TenantId tenant, List<ResultRef> references) {
        var requested = List.copyOf(references);
        if (requested.isEmpty()) return List.of();
        return read(tenant, (session, mapper) -> {
            var values = new java.util.HashMap<String, Material>();
            for (var row : mapper.materials(tenant.value(), requested.stream().map(ResultRef::value).distinct().toList()))
                values.put(row.reference, row.material());
            return requested.stream().map(reference -> Optional.ofNullable(values.get(reference.value()))).toList();
        });
    }
    @Override public Optional<Material> material(TenantId tenant, ResultRef reference) {
        return read(tenant, (session, mapper) -> Optional.ofNullable(mapper.material(tenant.value(), reference.value())).map(ExecutionMaterialRow::material));
    }
    /** 按完整租户和动作身份读取。 */
    @Override public Optional<Action> action(ActionRef reference) {
        return read(reference.tenantId(), (session, mapper) -> Optional.ofNullable(mapper.action(reference.tenantId().value(), reference.actionId())).map(ExecutionActionRow::action));
    }
    @Override public List<Action> actions(RunRef run, long afterOrdinal, int limit) {
        bounded(limit); if (afterOrdinal < 0) throw new IllegalArgumentException("execution_action_ordinal_invalid");
        return read(run.tenantId(), (session, mapper) -> mapper.actions(tenant(run), run.runId(), afterOrdinal, limit).stream()
                .map(ExecutionActionRow::action).toList());
    }
    /** 原Run全部真实MODEL Attempt的已报告下界；从未调用模型才是完整已知零。 */
    @Override public TokenUsage usage(RunRef run) {
        return read(run.tenantId(), (session, mapper) -> {
            if (mapper.run(tenant(run), run.runId()) == null) throw conflict("execution_run_missing");
            var usage = mapper.usage(tenant(run), run.runId()); return new TokenUsage(usage.reportedTokens, usage.complete);
        });
    }
    /** 按 Run 与 Attempt 读取，绝不因此重新授权发送。 */
    @Override public Optional<Attempt> attempt(RunRef run, String attemptId) {
        return read(run.tenantId(), (session, mapper) -> Optional.ofNullable(mapper.attempt(tenant(run), run.runId(), attemptId)).map(ExecutionAttemptRow::attempt));
    }
    /** 精确等待投影；不加载checkpoint或旧等待图。 */
    @Override public Optional<Waiting> waiting(RunRef run, WaitRef reference) {
        if (!run.tenantId().equals(reference.action().tenantId())) throw new IllegalArgumentException("execution_tenant_mismatch");
        return read(run.tenantId(), (session, mapper) -> Optional.ofNullable(
                mapper.waiting(tenant(run), run.runId(), reference.action().actionId(), reference.generation())).map(row -> waiting(mapper, row)));
    }
    /** 由原真实Attempt查精确等待，避免消费者从事件推导generation。 */
    @Override public Optional<Waiting> waiting(RunRef run, String sourceAttempt) {
        Objects.requireNonNull(sourceAttempt);
        if (sourceAttempt.isBlank()) throw new IllegalArgumentException("execution_text_required");
        return read(run.tenantId(), (session, mapper) -> Optional.ofNullable(
                mapper.sourceWaiting(tenant(run), run.runId(), sourceAttempt)).map(row -> waiting(mapper, row)));
    }
    /** 按真实稳定输入读取已接受回执，只返回该Run的精确等待。 */
    @Override public Optional<Waiting> response(RunRef run, InputId input) {
        return read(run.tenantId(), (session, mapper) -> Optional.ofNullable(
                mapper.response(tenant(run), run.runId(), input.value())).map(row -> waiting(mapper, row)));
    }
    /** 真实受理原input_ref的不可变内容；Host必须另行核owner/parent，不能凭RunRef获得业务授权。 */
    @Override public Optional<Material> originalInput(RunRef reference) {
        Objects.requireNonNull(reference);
        return read(reference.tenantId(),(session,mapper)->{
            var row=mapper.run(tenant(reference),reference.runId());
            return row==null?Optional.empty():Optional.of(requireMaterial(mapper,row.tenantId,new ResultRef(row.inputRef)));
        });
    }

    /** 按实际Run归属Session与事件身份读取原提交事实；同Session其他Run事件也不可冒用。 */
    @Override public Optional<SavedEvent> event(RunRef reference,com.skillforge.runtime.api.EventId event) {
        Objects.requireNonNull(reference);Objects.requireNonNull(event);
        return read(reference.tenantId(),(session,mapper)->{
            var run=mapper.run(tenant(reference),reference.runId());if(run==null)return Optional.empty();
            var found=mapper.event(run.tenantId,run.sessionId,event.value());
            return found==null||!found.runId.equals(reference.runId())?Optional.empty():Optional.of(found.event());
        });
    }

    /** 只定位当前Session原事件；业务披露继续由Host按来源Run与当前身份校验。 */
    @Override public Optional<SavedEvent> event(SessionRef reference,com.skillforge.runtime.api.EventId event) {
        Objects.requireNonNull(reference);Objects.requireNonNull(event);
        return read(reference.tenantId(),(session,mapper)->Optional.ofNullable(
                mapper.event(reference.tenantId().value(),reference.sessionId(),event.value())).map(ExecutionEventRow::event));
    }

    /** 有界到期补扫；正常活跃循环依赖直接唤醒。 */
    @Override public List<RunRef> due(TenantId tenant, int limit) {
        bounded(limit);
        return read(tenant, (session, mapper) -> {
            Instant now = mapper.now();
            return mapper.due(tenant.value(), now.getEpochSecond(), now.getNano(), limit).stream().map(id -> new RunRef(tenant, id)).toList();
        });
    }
    /** 最近实际可调度时间，包括活动租约到期，不创建第二队列。 */
    @Override public Optional<Instant> nextDueAt(TenantId tenant) {
        return read(tenant, (session, mapper) -> Optional.ofNullable(mapper.nextDueAt(tenant.value())).map(ExecutionDueRow::instant));
    }
    /** SSE 与页面直接消费已提交事件，查询不加载完整 Run。 */
    @Override public List<SavedEvent> events(SessionRef session, long afterSequence, int limit) {
        bounded(limit);
        if (afterSequence < 0) throw new IllegalArgumentException("execution_event_sequence_negative");
        return read(session.tenantId(), (sql, mapper) -> mapper.events(session.tenantId().value(), session.sessionId(), afterSequence, limit).stream().map(ExecutionEventRow::event).toList());
    }
    /** 只读取指定序列窗口内的已提交事件。 */
    @Override public List<SavedEvent> eventsInRange(SessionRef session, long afterSequence, long throughSequence, int limit) {
        bounded(limit);
        if (afterSequence < 0 || throughSequence < 0) throw new IllegalArgumentException("execution_event_sequence_negative");
        return read(session.tenantId(), (sql, mapper) -> mapper.eventsInRange(session.tenantId().value(), session.sessionId(), afterSequence, throughSequence, limit).stream().map(ExecutionEventRow::event).toList());
    }

    /** 固定根在 Run 锁之前加锁，预算资源仍归原账本所有。 */
    private void lockBudget(SqlSession session, RunRef run) {
        budgets.lockRoot(session, budgets.root(session, run).orElseThrow(() -> conflict("budget_scope_missing")));
    }
    /** 取得唯一 Run 推进锁。 */
    private static ExecutionRunRow requireRun(ExecutionStoreMapper mapper, RunRef run) {
        var row = mapper.lockRun(tenant(run), run.runId());
        if (row == null) throw conflict("execution_run_missing");
        return row;
    }
    /** 重复派发只返回已存在事实，任何参数或正文冲突拒绝。 */
    private static void requireRepeatedDispatch(ExecutionStoreMapper mapper, ExecutionRunRow run,
            ExecutionActionRow action, ExecutionAttemptRow attempt, Dispatch command) {
        var prepared = command.action();
        if (action == null || attempt == null || !attempt.actionId.equals(action.actionId) || !action.runId.equals(run.runId)
                || !attempt.requestRef.equals(prepared.request().reference().value()) || !attempt.fingerprint.equals(prepared.fingerprint())
                || !Objects.equals(attempt.continuationGeneration, command.continuation().map(WaitRef::generation).orElse(null)))
            throw conflict("execution_dispatch_conflict");
        requireActionIdentity(action, prepared);
        if (!requestMatches(mapper, run.tenantId, prepared)) throw conflict("execution_material_conflict");
    }
    /** 逻辑动作的目标与序号固定；continuation只改变新Attempt的实际请求。 */
    private static void requireActionIdentity(ExecutionActionRow action, Prepared prepared) {
        if (!sameActionIdentity(action, prepared)) throw conflict("execution_dispatch_conflict");
    }
    private static boolean sameActionIdentity(ExecutionActionRow action, Prepared prepared) {
        return action.tenantId.equals(prepared.action().tenantId().value()) && action.actionId.equals(prepared.action().actionId()) && action.ordinal == prepared.ordinal()
                && action.kind.equals(prepared.kind().name()) && action.target.equals(prepared.target())
                && action.capabilityRef.equals(prepared.capability().value());
    }
    /** 父派发仍是当前真实外调，持有父Run锁即可核对其不可变Action/Attempt来源。 */
    private static void requireParentDispatch(ExecutionStoreMapper mapper, ExecutionRunRow row, DispatchResult.Registered parent, Prepared prepared) {
        var action = mapper.action(row.tenantId, parent.action().reference().actionId());
        var attempt = mapper.attempt(row.tenantId, row.runId, parent.attempt().id());
        if (action == null || attempt == null || terminal(row) || row.cancelled || !Phase.AWAITING_RESULT.name().equals(row.phase)
                || !Objects.equals(row.currentActionId, action.actionId) || !parent.run().identity().equals(row.run().identity())
                || !row.actorVerificationRef.equals(parent.actor().verificationRef().value()) || !action.action().equals(parent.action())
                || !attempt.attempt().equals(parent.attempt()) || attempt.resultRef != null || attempt.uncertaintyRef != null || attempt.adopted
                || attempt.fence != row.fence || parent.run().lease().isEmpty() || !leaseMatches(row, parent.run().lease().orElseThrow(), mapper.now())
                || !sameActionIdentity(action, prepared) || !attempt.requestRef.equals(prepared.request().reference().value())
                || !attempt.fingerprint.equals(prepared.fingerprint()) || !requestMatches(mapper, row.tenantId, prepared))
            throw conflict("execution_child_parent_changed");
    }
    private static void saveChildOrigin(ExecutionStoreMapper mapper, ChildAdmission command) {
        var row = ExecutionChildRow.from(command); var origins = mapper.childOrigins(row.tenantId, row.childRunId);
        for (var original : origins) if (!original.sameSource(row)) throw conflict("execution_child_origin_conflict");
        if (mapper.insertChildOrigin(row) == 0 && origins.stream().noneMatch(value -> value.parentActionId.equals(row.parentActionId)))
            throw conflict("execution_child_origin_conflict");
    }
    /** Run锁拥有等待修改权；此处只读取精确原等待及其请求身份。 */
    private static ExecutionWaitRow requireWaiting(ExecutionStoreMapper mapper, ExecutionRunRow run, WaitRef reference) {
        var waiting = mapper.waiting(run.tenantId, run.runId, reference.action().actionId(), reference.generation());
        if (waiting == null) throw conflict("execution_wait_missing"); return waiting;
    }
    private static Waiting waiting(ExecutionStoreMapper mapper, ExecutionWaitRow row) {
        var methods = new java.util.LinkedHashMap<String, com.skillforge.runtime.api.port.ToolPortal.ToolInputMethod>();
        for (var request : mapper.waitRequests(row.tenantId, row.actionId, row.generation))
            methods.put(request.inputKey, com.skillforge.runtime.api.port.ToolPortal.ToolInputMethod.fromWireName(request.method));
        return row.waiting(methods, mapper.waitChildren(row.tenantId, row.actionId, row.generation));
    }
    private static void saveWaitChildren(ExecutionStoreMapper mapper, ExecutionWaitRow waiting, WaitDeclaration declaration) {
        if (declaration.internal().orElse(null) instanceof DelegationWait dependency) {
            for (int index = 0; index < dependency.children().size(); index++) {
                var child = dependency.children().get(index);
                if (!child.tenantId().value().equals(waiting.tenantId)) throw conflict("execution_tenant_mismatch");
                var row = new ExecutionWaitChildRow(); row.tenantId = waiting.tenantId; row.actionId = waiting.actionId; row.generation = waiting.generation;
                row.ordinal = index + 1; row.delegationId = dependency.delegationIds().get(index); row.childRunId = child.runId();
                if (mapper.insertWaitChild(row) != 1) throw conflict("execution_wait_conflict");
            }
        }
    }
    /** 工具等待来自原INPUT_REQUIRED，人类等待来自原模型成功决定，二者不混用结果类别。 */
    private static boolean waitSourceMatches(ExecutionWaitRow waiting, ExecutionActionRow action, ExecutionAttemptRow source) {
        if (source == null || !source.actionId.equals(action.actionId) || !action.currentAttempt.equals(source.attemptId)) return false;
        if (WaitKind.TOOL.name().equals(waiting.kind))
            return Kind.TOOL.name().equals(action.kind) && ResultKind.INPUT_REQUIRED.name().equals(source.resultKind)
                    && ActionStatus.WAITING_INPUT.name().equals(action.status) && action.selectedAttempt == null;
        boolean expectedKind = (WaitKind.HUMAN.name().equals(waiting.kind) || WaitKind.PLAN.name().equals(waiting.kind)) && Kind.MODEL.name().equals(action.kind)
                || WaitKind.DELEGATION.name().equals(waiting.kind) && Kind.DELEGATION.name().equals(action.kind)
                || WaitKind.WORKFLOW.name().equals(waiting.kind) && Kind.WORKFLOW.name().equals(action.kind);
        return expectedKind
                && ResultKind.SUCCEEDED.name().equals(source.resultKind) && ActionStatus.SUCCEEDED.name().equals(action.status)
                && source.attemptId.equals(action.selectedAttempt);
    }
    /** 当前并行组以原模型Attempt拥有所有槽位，不让单currentAction冒充兄弟动作。 */
    private static boolean actionBelongsToCurrent(ExecutionRunRow run, ExecutionActionRow action) {
        return run.batchSourceAttempt == null ? Objects.equals(run.currentActionId, action.actionId)
                : run.runId.equals(action.runId) && run.batchSourceAttempt.equals(action.batchSourceAttempt)
                    && Objects.equals(run.batchSize, action.batchSize);
    }
    private static boolean sameBatch(ExecutionRunRow run, BatchRef batch) {
        return run.runId.equals(batch.run().runId()) && run.tenantId.equals(batch.run().tenantId().value())
                && Objects.equals(run.batchSourceAttempt, batch.sourceAttempt()) && Objects.equals(run.batchSize, batch.size());
    }
    /** manifest拥有当前native顺序，旧Action归属仍保持第一次派发事实。 */
    private static BatchRows batchRows(ExecutionStoreMapper mapper,BatchRef reference,boolean lock) {
        String tenant=tenant(reference.run()),run=reference.run().runId();var members=mapper.batchMembers(tenant,run,reference.sourceAttempt());
        var actions=lock?mapper.lockBatchActions(tenant,run,reference.sourceAttempt()):mapper.batchActions(tenant,run,reference.sourceAttempt());
        var attempts=lock?mapper.lockBatchAttempts(tenant,run,reference.sourceAttempt()):mapper.batchAttempts(tenant,run,reference.sourceAttempt());
        if(members.size()!=reference.size()||actions.size()!=reference.size()||attempts.size()!=reference.size()) throw conflict("execution_batch_incomplete");
        for(int index=0;index<members.size();index++) {
            var member=members.get(index);var action=actions.get(index);var attempt=attempts.get(index);
            if(member.slot!=index+1||member.size!=reference.size()||!member.actionId.equals(action.actionId)||!action.actionId.equals(attempt.actionId)
                    ||!action.currentAttempt.equals(attempt.attemptId)) throw conflict("execution_batch_conflict");
            if(member.reused) requireSavedReused(member,action,attempt);
            else if(!Objects.equals(action.batchSize,reference.size())||!Objects.equals(action.batchSlot,index+1)||!reference.sourceAttempt().equals(action.batchSourceAttempt))
                throw conflict("execution_batch_conflict");
        }
        return new BatchRows(reference,members,actions,new java.util.ArrayList<>(attempts));
    }
    /** 同事务局部材料视图；只有fresh当前Attempt允许被本组更新。 */
    private record BatchRows(BatchRef reference,List<ExecutionBatchMemberRow> members,List<ExecutionActionRow> actions,List<ExecutionAttemptRow> attempts) {
        List<BatchEntry> entries() {
            var values=new java.util.ArrayList<BatchEntry>();
            for(int index=0;index<actions.size();index++) values.add(new BatchEntry(new BatchSlot(reference,index+1),actions.get(index).action(),attempts.get(index).attempt(),members.get(index).reused));
            return List.copyOf(values);
        }
    }
    /** 原已采纳准确结果与安全反馈必须完全同源，不能靠调用者引用赋予复用资格。 */
    private static void requireSavedReused(ExecutionBatchMemberRow member,ExecutionActionRow action,ExecutionAttemptRow attempt) {
        if(action==null||attempt==null||!member.runId.equals(action.runId)||!member.actionId.equals(action.actionId)||!member.firstAttemptId.equals(attempt.attemptId)
                ||!attempt.adopted||attempt.uncertaintyRef!=null||!attempt.attemptId.equals(action.selectedAttempt)||!attempt.attemptId.equals(action.currentAttempt)
                ||!Objects.equals(member.reusedResultRef,attempt.resultRef)||!Objects.equals(member.reusedFeedbackRef,attempt.feedbackRef)
                ||!Kind.TOOL.name().equals(action.kind)||!(ResultKind.SUCCEEDED.name().equals(attempt.resultKind)||ResultKind.FAILED.name().equals(attempt.resultKind)))
            throw conflict("execution_batch_reused_conflict");
    }
    private static void requireReused(ExecutionStoreMapper mapper,ExecutionRunRow run,ExecutionBatchMemberRow member,ExecutionActionRow action,ExecutionAttemptRow attempt,BatchMember.Reused supplied) {
        if(!member.reused||!member.actionId.equals(supplied.action().actionId())||!member.firstAttemptId.equals(supplied.attemptId())
                ||!Objects.equals(member.reusedResultRef,supplied.result().value())||!Objects.equals(member.reusedFeedbackRef,supplied.feedback().value())) throw conflict("execution_batch_reused_conflict");
        requireSavedReused(member,action,attempt);
        if(!attempt.requestRef.equals(supplied.request().value())||!run.runId.equals(attempt.runId)) throw conflict("execution_batch_reused_conflict");
        requireMaterial(mapper,run.tenantId,supplied.request());requireMaterial(mapper,run.tenantId,supplied.result());requireMaterial(mapper,run.tenantId,supplied.feedback());
    }
    private static boolean accurate(BatchRows rows) {
        return rows.attempts().stream().allMatch(value->value.resultRef!=null&&value.uncertaintyRef==null
                &&(ResultKind.SUCCEEDED.name().equals(value.resultKind)||ResultKind.FAILED.name().equals(value.resultKind)));
    }
    private static boolean settled(BatchRows rows) {return rows.attempts().stream().allMatch(value->value.resultRef!=null);}
    /** sourceAttempt必须仍是该fresh逻辑动作当前派发，旧轮card不参与后继等待集合。 */
    private static List<ExecutionWaitRow> activeBatchWaits(ExecutionStoreMapper mapper,BatchRows rows) {
        var waits=new java.util.ArrayList<ExecutionWaitRow>();
        for(int index=0;index<rows.members().size();index++) if(!rows.members().get(index).reused) {
            var source=rows.attempts().get(index);var wait=mapper.sourceWaiting(source.tenantId,source.runId,source.attemptId);
            if(wait!=null&&wait.continuationAttempt==null&&wait.consumedRevision==null) waits.add(wait);
        }
        return waits;
    }
    /** 所有原发送已返回后才改变phase/lease/version；早响应不影响合法兄弟握手。 */
    private static boolean settleBatch(ExecutionStoreMapper mapper,ExecutionRunRow run,BatchRef reference,BatchRows rows) {
        if(!settled(rows)||!sameBatch(run,reference)) return false;
        boolean unknown=rows.attempts().stream().anyMatch(value->value.uncertaintyRef!=null||ResultKind.UNKNOWN.name().equals(value.resultKind));
        var waits=activeBatchWaits(mapper,rows);String phase;
        if(unknown) phase=Phase.UNCERTAIN.name();
        else if(waits.isEmpty()) return false;
        else phase=waits.stream().allMatch(value->value.inputId!=null)?Phase.READY.name():Phase.WAITING.name();
        if(run.phase.equals(phase)) return false;
        long previous=run.revision;run.phase=phase;
        if(phase.equals(Phase.READY.name())) {run.status=RunStatus.RESUMING.name();run.due(mapper.now());}
        else {run.status=RunStatus.SUSPENDED.name();run.due(null);clearLease(run);}
        advance(mapper,run,previous,mapper.now());return true;
    }
    private static void requireBatchResults(BatchRows rows,List<BatchResultRef> supplied) {
        if(supplied.size()!=rows.members().size()) throw conflict("execution_batch_adoption_mismatch");
        for(int index=0;index<supplied.size();index++) {
            var action=rows.actions().get(index);var attempt=rows.attempts().get(index);var selected=supplied.get(index);
            if(!action.action().reference().equals(selected.action())||!attempt.attemptId.equals(selected.attemptId())||!Objects.equals(attempt.resultRef,selected.result().value()))
                throw conflict("execution_batch_adoption_mismatch");
        }
    }
    /** 真实fresh物理Attempt才增加调用次数，reused不会重复计数。 */
    private static void requireBatchProgress(ExecutionStoreMapper mapper,ExecutionRunRow run,BatchRef reference,Progress next) {
        long calls=mapper.batchPhysicalCalls(run.tenantId,run.runId,reference.sourceAttempt());
        if(next.decisions()!=run.decisions||next.knowledgeCalls()!=run.knowledgeCalls||next.toolCalls()!=Math.addExact(run.toolCalls,Math.toIntExact(calls)))
            throw conflict("execution_batch_progress_mismatch");
    }
    /** 正常最后准确结果已在所属事务保存后，一次按原槽位核对并采纳全组。 */
    private boolean adoptBatchRows(ExecutionStoreMapper mapper, ExecutionRunRow run, BatchRef reference, BatchRows rows,
            long revision, LeaseFence lease, BatchAdoption adoption) {
        Instant now = mapper.now();
        if (terminal(run) || run.cancelled || !sameBatch(run, reference) || quiescent(run)
                || !current(run, revision, lease, now) || !accurate(rows)) return false;
        requireBatchResults(rows,adoption.results());
        requireBatchProgress(mapper,run,reference,adoption.next());
        saveMaterial(mapper, run.tenantId, adoption.context()); requireMaterial(mapper, run.tenantId, adoption.next().context());
        for (int index = 0; index < reference.size(); index++) {
            if(rows.members().get(index).reused) continue;
            var action = rows.actions().get(index); var attempt = rows.attempts().get(index);
            attempt.adopted = true; action.selectedAttempt = attempt.attemptId;
            action.status = ResultKind.SUCCEEDED.name().equals(attempt.resultKind) ? ActionStatus.SUCCEEDED.name() : ActionStatus.FAILED.name();
            if (mapper.updateAction(action) != 1 || mapper.updateAttempt(attempt) != 1) throw conflict("execution_batch_adoption_failed");
        }
        long previous = run.revision; run.decisions = adoption.next().decisions(); run.toolCalls = adoption.next().toolCalls();
        run.knowledgeCalls = adoption.next().knowledgeCalls(); run.contextRef = adoption.next().context().value();
        run.batchSourceAttempt = null; run.batchSize = null; run.phase = Phase.READY.name(); run.due(now);
        advance(mapper, run, previous, now); appendEvents(mapper, run, adoption.events(), now); return true;
    }
    /** 意外产生真实输入请求时保存原精确等待；多等待恢复由后继业务边界推进。 */
    private static void registerBatchWait(ExecutionStoreMapper mapper, ExecutionRunRow run, ExecutionActionRow action,
            ExecutionAttemptRow attempt, WaitDeclaration declaration) {
        if (declaration.kind() != WaitKind.TOOL || !declaration.reference().action().equals(action.action().reference()))
            throw conflict("execution_wait_action_mismatch");
        var existing = mapper.waiting(run.tenantId, run.runId, action.actionId, declaration.reference().generation());
        if (existing != null) {
            if (!existing.sourceAttempt.equals(attempt.attemptId) || !waiting(mapper, existing).requested().equals(declaration.requested()))
                throw conflict("execution_wait_conflict");
            return;
        }
        if (declaration.reference().generation() != mapper.nextWaitGeneration(run.tenantId, action.actionId))
            throw conflict("execution_wait_generation_changed");
        var waiting = new ExecutionWaitRow(); waiting.tenantId = run.tenantId; waiting.runId = run.runId; waiting.actionId = action.actionId;
        waiting.generation = declaration.reference().generation(); waiting.kind = WaitKind.TOOL.name(); waiting.sourceAttempt = attempt.attemptId; waiting.declaredRevision = run.revision;
        if (mapper.insertWait(waiting) != 1) throw conflict("execution_wait_conflict");
        declaration.requested().forEach((key, method) -> {
            var request = new ExecutionWaitRequestRow(); request.tenantId = run.tenantId; request.actionId = action.actionId;
            request.generation = waiting.generation; request.inputKey = key; request.method = method.wireName(); mapper.insertWaitRequest(request);
        });
        attempt.adopted = true; action.status = ActionStatus.WAITING_INPUT.name(); action.selectedAttempt = null;
        if (mapper.updateAction(action) != 1 || mapper.updateAttempt(attempt) != 1) throw conflict("execution_wait_save_failed");
    }
    /** 保存原Attempt结果和已有预算账本；单动作与并行结果都不能出现半结算。 */
    private boolean saveFacts(SqlSession session, ExecutionStoreMapper mapper, ExecutionRunRow run,
            ExecutionAttemptRow attempt, Completion command) {
        boolean knownAfterUncertainty = attempt.resultRef != null && requireRepeatedResult(attempt, command);
        boolean newlySaved = attempt.resultRef == null || knownAfterUncertainty;
        saveMaterial(mapper, run.tenantId, command.result());
        command.feedback().ifPresent(feedback -> saveMaterial(mapper, run.tenantId, feedback));
        boolean reported = command.usage().inputTokens().isPresent() || command.usage().outputTokens().isPresent() || command.usage().totalTokens().isPresent();
        if (command.kind() == ResultKind.UNKNOWN && newlySaved) {
            budgets.saveResult(session, command.run(), command.attemptId(), ExecutionBudgetPort.Usage.unreported(), command.result().reference().value());
        } else if (command.kind() != ResultKind.UNKNOWN && (newlySaved || reported)) {
            budgets.saveAndSettleResult(session, command.run(), command.attemptId(), command.usage(), command.result().reference().value());
        }
        if (command.kind() == ResultKind.UNKNOWN && attempt.uncertaintyRef == null) attempt.uncertaintyRef = command.result().reference().value();
        attempt.resultKind = command.kind().name(); attempt.resultRef = command.result().reference().value();
        attempt.feedbackRef = command.feedback().map(value -> value.reference().value()).orElse(null);
        attempt.externalOperation = command.externalOperation().orElse(null);
        return newlySaved;
    }
    /**
     * 同结果重放须全值一致；UNKNOWN后取得原调用的确定结果可补事实，原不确定材料仍保留。
     * 该补事实本身不重新打开等待或终态Run，也不授予新的外调权。
     */
    private static boolean requireRepeatedResult(ExecutionAttemptRow attempt, Completion command) {
        if (attempt.resultKind.equals(ResultKind.UNKNOWN.name())
                && (command.kind() == ResultKind.SUCCEEDED || command.kind() == ResultKind.FAILED)) {
            if (attempt.externalOperation != null && !command.externalOperation().equals(Optional.of(attempt.externalOperation)))
                throw conflict("execution_result_conflict");
            return true;
        }
        if (!attempt.resultKind.equals(command.kind().name()) || !attempt.resultRef.equals(command.result().reference().value())
                || !Objects.equals(attempt.feedbackRef, command.feedback().map(value -> value.reference().value()).orElse(null))
                || !Objects.equals(attempt.externalOperation, command.externalOperation().orElse(null)))
            throw conflict("execution_result_conflict");
        return false;
    }
    /** 请求根和一层正文叶与 Attempt 共用派发事务；批量写入后核对同引用精确正文。 */
    private static void saveRequest(ExecutionStoreMapper mapper, String tenant, Prepared prepared) {
        if (prepared.requestParts().isEmpty()) { saveMaterial(mapper, tenant, prepared.request()); return; }
        var rows = new java.util.ArrayList<ExecutionMaterialRow>();
        var parts = new java.util.ArrayList<>(prepared.requestParts()); parts.add(prepared.request());
        for (var material : parts) {
            var row = new ExecutionMaterialRow(); row.tenantId = tenant; row.reference = material.reference().value();
            row.schemaId = material.content().schemaId(); row.canonicalJson = material.content().canonicalJson(); rows.add(row);
        }
        mapper.insertMaterials(rows);
        if (!requestMatches(mapper, tenant, prepared)) throw conflict("execution_material_conflict");
    }
    /** Existing 仅核对已有根与叶，不能用重复命令补写或覆盖请求材料。 */
    private static boolean requestMatches(ExecutionStoreMapper mapper, String tenant, Prepared prepared) {
        if (prepared.requestParts().isEmpty()) return requireMaterial(mapper, tenant, prepared.request().reference()).equals(prepared.request());
        var expected = new java.util.ArrayList<>(prepared.requestParts()); expected.add(prepared.request());
        var saved = new java.util.HashMap<String, Material>();
        for (var row : mapper.materials(tenant, expected.stream().map(value -> value.reference().value()).distinct().toList()))
            saved.put(row.reference, row.material());
        return expected.stream().allMatch(value -> value.equals(saved.get(value.reference().value())));
    }
    /** 不可变正文引用第一次保存，重复写入仅接受完整相同内容。 */
    private static void saveMaterial(ExecutionStoreMapper mapper, String tenant, Material material) {
        var row = new ExecutionMaterialRow(); row.tenantId = tenant; row.reference = material.reference().value();
        row.schemaId = material.content().schemaId(); row.canonicalJson = material.content().canonicalJson();
        if (mapper.insertMaterial(row) == 0 && !requireMaterial(mapper, tenant, material.reference()).equals(material))
            throw conflict("execution_material_conflict");
    }
    /** 悬空材料引用不能成为已提交进度。 */
    private static Material requireMaterial(ExecutionStoreMapper mapper, String tenant, ResultRef reference) {
        var row = mapper.material(tenant, reference.value());
        if (row == null) throw conflict("execution_material_missing"); return row.material();
    }
    /** Run 锁内分配游标并按事件身份去重，同身份异内容拒绝整个业务提交。 */
    private void appendEvents(ExecutionStoreMapper mapper, ExecutionRunRow run, List<Event> events, Instant now) {
        for (var event : events) {
            var previous = mapper.event(run.tenantId, run.sessionId, event.id().value());
            if (previous != null) {
                if (!previous.runId.equals(run.runId) || !previous.event().event().equals(event)) throw conflict("execution_event_conflict");
                continue;
            }
            var row = new ExecutionEventRow(); row.tenantId = run.tenantId; row.sessionId = run.sessionId; row.runId = run.runId;
            row.eventId = event.id().value(); row.type = event.type(); row.schemaId = event.data().schemaId();
            row.canonicalJson = event.data().canonicalJson(); row.sequence = mapper.nextSequence(run.tenantId, run.sessionId);
            row.revision = run.revision; row.occurredSecond = now.getEpochSecond(); row.occurredNano = now.getNano();
            if (mapper.insertEvent(row) != 1) throw conflict("execution_event_insert_failed");
        }
    }
    /** CAS 是最终写入条件，锁或调用前检查不替代版本约束。 */
    private static void advance(ExecutionStoreMapper mapper, ExecutionRunRow row, long expected, Instant now) {
        row.revision = Math.incrementExact(expected); row.touch(now);
        if (mapper.updateRun(row, expected) != 1) throw conflict("execution_revision_changed");
    }
    /** 匹配当前提交版本和当前未过期租约。 */
    private static boolean current(ExecutionRunRow row, long revision, LeaseFence lease, Instant now) {
        return row.revision == revision && leaseMatches(row, lease, now);
    }
    /** 续租不会令原持有者的 fence 失效；派发不能以传入的旧到期时间延长授权。 */
    private static boolean leaseMatches(ExecutionRunRow row, LeaseFence lease, Instant now) {
        return row.tenantId.equals(lease.tenantId().value()) && row.leaseClaimId != null
                && row.leaseClaimId.equals(lease.claimId()) && row.leaseWorkerId.equals(lease.workerId())
                && row.fence == lease.fencingToken() && leaseUntil(row).isAfter(now);
    }
    /** 读取结构化租约时间。 */
    private static Instant leaseUntil(ExecutionRunRow row) { return Instant.ofEpochSecond(row.leaseUntilSecond, row.leaseUntilNano); }
    /** 释放租约时保留已递增 fence，接管不能复用旧代。 */
    private static void clearLease(ExecutionRunRow row) { row.leaseClaimId = null; row.leaseWorkerId = null; row.leaseUntil(null); }
    /** 终态只由 Run 状态判定。 */
    private static boolean terminal(ExecutionRunRow row) { return row.phase.equals(Phase.FINISHED.name()); }
    /** 等待输入和派发不确定均静止，不领取、不补扫、不自动重放。 */
    private static boolean quiescent(ExecutionRunRow row) {
        return row.phase.equals(Phase.WAITING.name()) || row.phase.equals(Phase.UNCERTAIN.name());
    }
    /** 统一有界查询，错误限额显式拒绝。 */
    private static void bounded(int limit) { if (limit < 1 || limit > 100) throw new IllegalArgumentException("execution_query_limit_invalid"); }
    /** 提取显式可信租户，不读取线程残留或连接残留。 */
    private static String tenant(RunRef run) { return run.tenantId().value(); }
    /** 创建稳定业务拒绝；原 SQL 故障仍由事务包装保留原因。 */
    private static Rejection conflict(String reason) { return new Rejection(reason); }
    /** 纯查询在同一 DEFERRED 快照中读取多张表，不登记预算写作用域。 */
    private <T> T read(TenantId tenant, Work<T> work) {
        if (transactionScope.get() != null) throw new IllegalStateException("execution_transaction_scope_required");
        try (var session = reads.openSession(false)) {
            var mapper = session.getMapper(ExecutionStoreMapper.class);
            mapper.bindTenant(tenant.value());
            return work.execute(session, mapper);
        } catch (org.apache.ibatis.exceptions.PersistenceException failure) {
            throw new IllegalStateException("execution_storage_failed", failure);
        }
    }
    /** 同一业务边界使用 IMMEDIATE 写事务并显式限定租户，失败回滚全部状态、材料、预算和事件。 */
    private <T> T transaction(TenantId tenant, Work<T> work) {
        if (transactionScope.get() != null) throw new IllegalStateException("execution_transaction_scope_required");
        T result;
        try (var session = sessions.openSession(false)) {
            transactionScope.set(Boolean.TRUE);
            try {
                var mapper = session.getMapper(ExecutionStoreMapper.class); mapper.bindTenant(tenant.value());
                result = budgets.participate(session, tenant, () -> work.execute(session, mapper)); session.commit(true);
            } catch (RuntimeException failure) {
                try { session.rollback(true); } catch (RuntimeException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            } finally { transactionScope.remove(); }
        } catch (org.apache.ibatis.exceptions.PersistenceException failure) {
            // 提交返回未知时不重放外调；调用方按原Attempt读取已提交事实。
            throw new IllegalStateException("execution_storage_failed", failure);
        }
        return result;
    }
    /** 事务内纯存储工作；不允许返回尚未提交的发送许可给外部调用者。 */
    @FunctionalInterface private interface Work<T> { T execute(SqlSession session, ExecutionStoreMapper mapper); }
    /** 在预留之后拒绝发送时触发整个业务事务回滚。 */
    private static final class DispatchRejected extends RuntimeException {
        /** 保留拒绝原因供事务外映射为稳定业务结果。 */
        private DispatchRejected(String reason) { super(reason); }
    }
}
