package com.skillforge.runtime.adapter.sqlite;

import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

/** 新执行主链的关系行映射；正文只通过材料引用传递，不编码整个运行对象。 */
final class ExecutionRunRow {
    /** 显式租户、Run、Session、会话目标、实际Agent与原因输入身份。 */
    public String tenantId, runId, sessionId, targetKind, targetId, agentId, inputId;
    /** 原受理归属的终端用户，必须大于零。 */
    public Long endUserId, requesterEndUserId;
    public String subjectKind, subjectId, requesterKind, requesterSystemPrincipalId, sourceSessionId, sourceMessageId, originRef;
    /** 原追踪链与已核实身份引用；causedByEventId可为空。 */
    public String traceId, spanId, rootInputId, causedByEventId, identityRef, actorVerificationRef;
    /** 冻结配置、原输入和原上下文引用，以及输入去重指纹。 */
    public String configurationRef, inputRef, initialContextRef, admissionFingerprint;
    /** 当前生命周期、进度引用、待结果动作及可空租约持有者。 */
    public String status, phase, contextRef, currentActionId, leaseWorkerId, leaseClaimId;
    /** 当前一轮真实等待；响应接受后仍保留到该工具动作完成。 */
    public String waitingActionId;
    public Long waitingGeneration;
    /** 当前并行组直接锚定原模型Attempt；不另存批次快照。 */
    public String batchSourceAttempt;
    public Integer batchSize;
    /** CAS版本、单调租约代数及已采纳的进度计数；不是预算余额。 */
    public long revision, fence, decisions, toolCalls, knowledgeCalls;
    /** 已提交取消，阻止后续派发和结果采纳。 */
    public boolean cancelled;
    /** 可空租约与补扫到期时间的秒分量，与对应nano成对保存。 */
    public Long leaseUntilSecond, nextDueSecond;
    /** 到期时间纳秒分量，保留Instant精度。 */
    public Integer leaseUntilNano, nextDueNano;
    /** 数据库采样的首次受理和最近提交时间秒分量。 */
    public long createdSecond, updatedSecond;
    /** 首次受理和最近提交时间纳秒分量。 */
    public int createdNano, updatedNano;

    /** 还原精确身份与单一进度，不读取历史 checkpoint 或来源图。 */
    ExecutionStore.Run run() {
        var tenant = new TenantId(tenantId);
        var subject = switch (subjectKind) {
            case "USER" -> SessionSubject.user(new EndUserId(endUserId));
            case "GROUP" -> SessionSubject.group(new GroupBindingRef(tenant, subjectId));
            default -> throw new IllegalStateException("execution_subject_kind_invalid");
        };
        var owner = new SessionOwner(new SessionRef(tenant, sessionId), subject,
                new SessionTarget(SessionTarget.Kind.valueOf(targetKind), targetId));
        RunRequester requester = switch (requesterKind) {
            case "END_USER" -> new RunRequester.EndUser(new EndUserId(requesterEndUserId));
            case "SYSTEM" -> new RunRequester.System(new PrincipalRef.SystemPrincipal(tenant, requesterSystemPrincipalId));
            default -> throw new IllegalStateException("execution_requester_kind_invalid");
        };
        var identity = new RunIdentity(owner, new AgentId(agentId), new InputId(inputId),
                new RunRef(tenant, runId), new TraceEnvelope(traceId, spanId, new InputId(rootInputId),
                Optional.ofNullable(causedByEventId).map(EventId::new)), new ResultRef(identityRef), requester,
                sourceMessageId == null ? Optional.empty() : Optional.of(new SourceMessageRef(new SessionRef(tenant, sourceSessionId), sourceMessageId)),
                new RunOriginRef(tenant, originRef));
        var lease = leaseClaimId == null ? Optional.<LeaseFence>empty() : Optional.of(new LeaseFence(
                tenant, leaseClaimId, leaseWorkerId, fence, instant(leaseUntilSecond, leaseUntilNano)));
        return new ExecutionStore.Run(identity, new ResultRef(configurationRef), RunStatus.valueOf(status),
                revision, fence, cancelled, ExecutionStore.Phase.valueOf(phase),
                new ExecutionStore.Progress(decisions, toolCalls, knowledgeCalls, new ResultRef(contextRef)),
                lease, Optional.ofNullable(currentActionId).map(id -> new ActionRef(tenant, id)),
                batchSourceAttempt == null ? Optional.empty() : Optional.of(new ExecutionStore.BatchRef(new RunRef(tenant, runId), batchSourceAttempt, batchSize)),
                waitingActionId == null ? Optional.empty() : Optional.of(new ExecutionStore.WaitRef(new ActionRef(tenant, waitingActionId), waitingGeneration)),
                new ResultRef(actorVerificationRef),
                nextDueSecond == null ? Optional.empty() : Optional.of(instant(nextDueSecond, nextDueNano)),
                instant(createdSecond, createdNano), instant(updatedSecond, updatedNano));
    }

    /** 将已验证受理身份与初始进度展开为关系字段；空闲工作立即到期。 */
    static ExecutionRunRow admitted(ExecutionStore.Admission command, Instant now) {
        var row = new ExecutionRunRow();
        var identity = command.run(); var owner = identity.owner();
        row.tenantId = identity.runRef().tenantId().value(); row.runId = identity.runRef().runId();
        row.sessionId = owner.sessionRef().sessionId(); row.targetKind = owner.target().kind().name();
        row.targetId = owner.target().id();
        switch (owner.subject()) {
            case SessionSubject.User user -> { row.subjectKind = "USER"; row.subjectId = Long.toString(user.endUserId().value()); row.endUserId = user.endUserId().value(); }
            case SessionSubject.Group group -> { row.subjectKind = "GROUP"; row.subjectId = group.groupBindingRef().groupBindingId(); }
        }
        switch (identity.requester()) {
            case RunRequester.EndUser endUser -> { row.requesterKind = "END_USER"; row.requesterEndUserId = endUser.endUserId().value(); }
            case RunRequester.System system -> { row.requesterKind = "SYSTEM"; row.requesterSystemPrincipalId = system.principal().principalId(); }
        }
        row.sourceSessionId = identity.sourceMessageRef().map(source -> source.sessionRef().sessionId()).orElse(null);
        row.sourceMessageId = identity.sourceMessageRef().map(SourceMessageRef::messageId).orElse(null);
        row.originRef = identity.originRef().originId();
        row.agentId = identity.agentId().value(); row.inputId = identity.causationInputId().value();
        row.traceId = identity.trace().traceId(); row.spanId = identity.trace().spanId();
        row.rootInputId = identity.trace().rootInputId().value();
        row.causedByEventId = identity.trace().causedByEventId().map(EventId::value).orElse(null);
        row.identityRef = identity.causationIdentityRef().value(); row.configurationRef = command.configuration().value();
        row.actorVerificationRef = command.identity().verificationRef().value();
        row.inputRef = command.input().reference().value(); row.initialContextRef = command.context().reference().value();
        row.admissionFingerprint = command.fingerprint(); row.contextRef = row.initialContextRef;
        row.status = RunStatus.QUEUED.name(); row.phase = ExecutionStore.Phase.READY.name(); row.revision = 1;
        row.createdSecond = now.getEpochSecond(); row.createdNano = now.getNano(); row.touch(now); row.due(now);
        return row;
    }

    /** 更新本次业务提交的时间。 */
    void touch(Instant now) { updatedSecond = now.getEpochSecond(); updatedNano = now.getNano(); }
    /** 保存精确租约到期时间，NULL 表示释放租约。 */
    void leaseUntil(Instant until) {
        leaseUntilSecond = until == null ? null : until.getEpochSecond(); leaseUntilNano = until == null ? null : until.getNano();
    }
    /** 保存最近应处理时间，NULL 表示当前不参加补扫。 */
    void due(Instant when) { nextDueSecond = when == null ? null : when.getEpochSecond(); nextDueNano = when == null ? null : when.getNano(); }
    /** 将 epochSecond/nano 还原为无损时间。 */
    static Instant instant(long second, int nano) { return Instant.ofEpochSecond(second, nano); }
}

/** 一个逻辑动作的权威行；选定 Attempt 与动作状态原子写入。 */
final class ExecutionActionRow {
    /** 原归属、冻结目标和请求、动作状态、已登记派发及可空结果选定身份。 */
    public String tenantId, actionId, runId, kind, target, capabilityRef, requestRef, fingerprint, status, currentAttempt, selectedAttempt;
    public String batchSourceAttempt;
    public Integer batchSize, batchSlot;
    /** Run内唯一且大于零的逻辑动作序号。 */
    public long ordinal;
    /** 还原类型化动作。 */
    ExecutionStore.Action action() {
        var tenant = new TenantId(tenantId);
        return new ExecutionStore.Action(new ActionRef(tenant, actionId), new RunRef(tenant, runId), ordinal,
                ExecutionStore.Kind.valueOf(kind), target, new ResultRef(capabilityRef), new ResultRef(requestRef),
                fingerprint, ExecutionStore.ActionStatus.valueOf(status), currentAttempt, Optional.ofNullable(selectedAttempt),
                batchSourceAttempt == null ? Optional.empty() : Optional.of(new ExecutionStore.BatchSlot(
                        new ExecutionStore.BatchRef(new RunRef(tenant, runId), batchSourceAttempt, batchSize), batchSlot)));
    }
    /** 从派发前准备创建唯一逻辑动作。 */
    static ExecutionActionRow prepared(RunRef run, ExecutionStore.Prepared prepared) {
        var row = new ExecutionActionRow(); row.tenantId = run.tenantId().value(); row.runId = run.runId();
        row.actionId = prepared.action().actionId(); row.ordinal = prepared.ordinal(); row.kind = prepared.kind().name();
        row.target = prepared.target(); row.capabilityRef = prepared.capability().value();
        row.requestRef = prepared.request().reference().value(); row.fingerprint = prepared.fingerprint();
        row.status = ExecutionStore.ActionStatus.DISPATCHED.name(); return row;
    }
}

/** 一次不可自动重放的派发及其实际结果；用量仍由既有预算账本拥有。 */
final class ExecutionAttemptRow {
    /** 原派发归属与可空实际结果、反馈和外部操作身份；NULL结果不代表未发送。 */
    public String tenantId, attemptId, actionId, runId, requestRef, fingerprint, resultKind, resultRef, uncertaintyRef, feedbackRef, externalOperation;
    /** 原一轮等待的消费身份，初始派发为空。 */
    public Long continuationGeneration;
    /** 登记派发时的原租约代数，接管不覆盖。 */
    public long fence;
    /** 已由当前推进者选定结果；迟到保存不自动改变此值。 */
    public boolean adopted;
    /** 还原派发或已保存结果，空结果始终表示不确定派发而不是可重放。 */
    ExecutionStore.Attempt attempt() {
        var tenant = new TenantId(tenantId);
        return new ExecutionStore.Attempt(attemptId, new ActionRef(tenant, actionId), new RunRef(tenant, runId), fence,
                new ResultRef(requestRef), continuationGeneration == null ? Optional.empty()
                        : Optional.of(new ExecutionStore.WaitRef(new ActionRef(tenant, actionId), continuationGeneration)),
                Optional.ofNullable(resultKind).map(ExecutionStore.ResultKind::valueOf),
                Optional.ofNullable(resultRef).map(ResultRef::new), Optional.ofNullable(uncertaintyRef).map(ResultRef::new),
                Optional.ofNullable(feedbackRef).map(ResultRef::new),
                Optional.ofNullable(externalOperation), adopted);
    }
}

/** 真正的输入等待业务事实，不保存执行快照或通用操作日志。 */
final class ExecutionWaitRow {
    public String tenantId, runId, actionId, kind, sourceAttempt, inputId, responseRef, fingerprint, actorRef, continuationAttempt;
    /** 原card建立时业务版本；并行早响应不借猜测当前版本。 */
    public long generation, declaredRevision;
    public Long acceptedRevision, consumedRevision;
    public String businessWaitId, executionId, suspendRef, actionKey, planId;
    public Long executionRevision, planRevision;
    void internal(ExecutionStore.WaitDeclaration declaration) {
        if (declaration.internal().isEmpty()) return;
        var internal = declaration.internal().orElseThrow();
        if (internal instanceof ExecutionStore.DelegationWait dependency) businessWaitId = dependency.businessWaitId();
        else if (internal instanceof ExecutionStore.WorkflowWait workflow) {
            executionId = workflow.execution().executionId(); suspendRef = workflow.suspendRef(); actionKey = workflow.actionKey(); executionRevision = workflow.executionRevision();
        } else {
            var plan = (ExecutionStore.PlanWait) internal; planId = plan.planId(); planRevision = plan.planRevision(); businessWaitId = plan.businessWaitId();
        }
    }
    ExecutionStore.Waiting waiting(java.util.Map<String, ToolPortal.ToolInputMethod> requested, java.util.List<ExecutionWaitChildRow> children) {
        var tenant = new TenantId(tenantId);
        var accepted = inputId == null ? Optional.<ExecutionStore.AcceptedResponse>empty()
                : Optional.of(new ExecutionStore.AcceptedResponse(new InputId(inputId), new ResultRef(responseRef), fingerprint,
                        new ResultRef(actorRef), acceptedRevision));
        Optional<ExecutionStore.InternalWait> internal = switch (ExecutionStore.WaitKind.valueOf(kind)) {
            case DELEGATION -> Optional.of(new ExecutionStore.DelegationWait(businessWaitId, children.stream().map(row -> row.delegationId).toList(),
                    children.stream().map(row -> new RunRef(tenant, row.childRunId)).toList()));
            case WORKFLOW -> Optional.of(new ExecutionStore.WorkflowWait(new ExecutionRef(tenant, executionId), suspendRef, actionKey, executionRevision));
            case PLAN -> Optional.of(new ExecutionStore.PlanWait(planId, planRevision, businessWaitId));
            default -> Optional.empty();
        };
        return new ExecutionStore.Waiting(new ExecutionStore.WaitRef(new ActionRef(tenant, actionId), generation), ExecutionStore.WaitKind.valueOf(kind),
                new RunRef(tenant, runId), sourceAttempt, requested, internal, accepted, Optional.ofNullable(continuationAttempt),
                consumedRevision == null ? OptionalLong.empty() : OptionalLong.of(consumedRevision));
    }
}

/** 精确委派等待的原业务Entry与子Run清单，槽位保持原声明顺序。 */
final class ExecutionWaitChildRow {
    public String tenantId, actionId, delegationId, childRunId;
    public long generation;
    public int ordinal;
}

/** 等待请求的精确key与method；正文仍由原工具结果材料拥有。 */
final class ExecutionWaitRequestRow {
    public String tenantId, actionId, inputKey, method;
    public long generation;
}

/** 只读最近持久到期时间，不恢复Run或读取正文。 */
final class ExecutionDueRow {
    public long second;
    public int nano;
    Instant instant() { return Instant.ofEpochSecond(second, nano); }
}
/** 真实预算报告的只读汇总，不把reserved或maximum当作发生过的使用量。 */
final class ExecutionTokenUsageRow {
    public long reportedTokens;
    public boolean complete;
}

/** 父逻辑动作与真实子Run的purpose-specific来源；不拥有第二套执行或预算状态。 */
final class ExecutionChildRow {
    public String tenantId, parentRunId, parentActionId, parentAttemptId, childRunId, originKind, delegationId, executionId, nodeId, checkpointFingerprint;
    public Integer nodeVisit, nodeCall;
    public Long sourceRevision, sourceFence;
    static ExecutionChildRow from(ExecutionStore.ChildAdmission command) {
        var row = new ExecutionChildRow(); var parent = command.parent();
        row.tenantId = parent.run().identity().runRef().tenantId().value(); row.parentRunId = parent.run().identity().runRef().runId();
        row.parentActionId = parent.action().reference().actionId(); row.parentAttemptId = parent.attempt().id(); row.childRunId = command.child().run().runRef().runId();
        if (command.origin() instanceof ExecutionStore.DelegatedChild delegation) {
            row.originKind = "DELEGATION"; row.delegationId = delegation.delegationId();
        } else {
            var node = (ExecutionStore.WorkflowNodeChild) command.origin(); row.originKind = "WORKFLOW_NODE";
            row.executionId = node.node().executionRef().executionId(); row.nodeId = node.node().nodeId(); row.nodeVisit = node.node().visit(); row.nodeCall = node.node().call();
            row.checkpointFingerprint = node.checkpointFingerprint(); row.sourceRevision = node.sourceRevision(); row.sourceFence = node.lease().fence().fencingToken();
        }
        return row;
    }
    boolean sameSource(ExecutionChildRow other) {
        return tenantId.equals(other.tenantId) && parentRunId.equals(other.parentRunId) && childRunId.equals(other.childRunId) && originKind.equals(other.originKind)
                && java.util.Objects.equals(delegationId, other.delegationId) && java.util.Objects.equals(executionId, other.executionId)
                && java.util.Objects.equals(nodeId, other.nodeId) && java.util.Objects.equals(nodeVisit, other.nodeVisit)
                && java.util.Objects.equals(nodeCall, other.nodeCall) && java.util.Objects.equals(checkpointFingerprint, other.checkpointFingerprint);
    }
}

/** 调用方已形成的不可变协议正文，SQL 边界不再反射重建对象。 */
final class ExecutionMaterialRow {
    /** 显式租户、不可变引用及调用方提供的schema和规范协议正文。 */
    public String tenantId, reference, schemaId, canonicalJson;
    /** 还原原始 schema 与规范正文。 */
    ExecutionStore.Material material() { return new ExecutionStore.Material(new ResultRef(reference), new TypedPayload(schemaId, canonicalJson)); }
}

/** 原MODEL决定拥有的一次本地Plan控制回执，不承担Provider调用、预算或通用操作日志。 */
final class ExecutionPlanRow {
    public String tenantId, runId, sourceActionId, sourceAttemptId, requestRef, resultRef;
    public long adoptedRevision;
}

/** 已批准Plan成员与真实child Run的原准入关系；许可变化不改写第一次受理事实。 */
final class ExecutionPlanChildRow {
    public String tenantId,parentRunId,childRunId,workSessionId,rootInputId,taskId,admissionId;
    public long planRevision,permitFence;
    static ExecutionPlanChildRow from(ExecutionStore.PlanChildAdmission command) {
        var row = new ExecutionPlanChildRow(); var source = command.source();
        row.tenantId = command.parent().tenantId().value(); row.parentRunId = command.parent().runId(); row.childRunId = command.child().run().runRef().runId();
        row.workSessionId = source.work().owner().sessionRef().sessionId(); row.rootInputId = source.work().rootInputId().value();
        row.taskId = source.binding().task().id(); row.admissionId = source.binding().admissionId(); row.planRevision = source.binding().planRevision(); row.permitFence = source.permit().fence();
        return row;
    }
    boolean sameSource(ExecutionPlanChildRow other) {
        return tenantId.equals(other.tenantId) && parentRunId.equals(other.parentRunId) && childRunId.equals(other.childRunId)
                && workSessionId.equals(other.workSessionId) && rootInputId.equals(other.rootInputId) && taskId.equals(other.taskId)
                && admissionId.equals(other.admissionId) && planRevision == other.planRevision;
    }
}

/** 已提交产品事件的独立游标行；事件查询不加载运行状态。 */
final class ExecutionEventRow {
    /** 安全事件的归属、业务身份、类型和原规范正文。 */
    public String tenantId, sessionId, runId, eventId, type, schemaId, canonicalJson;
    /** Session内消息游标、原子提交的Run版本及事件时间秒分量。 */
    public long sequence, revision, occurredSecond;
    /** 事件时间纳秒分量。 */
    public int occurredNano;
    /** 还原安全产品事件及其提交版本。 */
    ExecutionStore.SavedEvent event() {
        return new ExecutionStore.SavedEvent(new RunRef(new TenantId(tenantId), runId), sequence, revision,
                Instant.ofEpochSecond(occurredSecond, occurredNano), new ExecutionStore.Event(new EventId(eventId), type,
                new TypedPayload(schemaId, canonicalJson)));
    }
}

/** 原成功MODEL拥有的不可变native槽位清单；复用槽位不改写旧Action原批次归属。 */
final class ExecutionBatchMemberRow {
    public String tenantId, runId, sourceAttempt, actionId, firstAttemptId, reusedResultRef, reusedFeedbackRef;
    public int slot, size;
    public boolean reused;
}

/** 原程序TOOL Attempt内的真实RPC ordinal与唯一child准入关系，不保存第二请求快照。 */
final class ExecutionProgramChildRow {
    public String tenantId,parentRunId,parentActionId,parentAttemptId,childRunId;
    public int ordinal;
    static ExecutionProgramChildRow from(ExecutionStore.ProgramChildAdmission command) {
        var row=new ExecutionProgramChildRow();var parent=command.parent();
        row.tenantId=parent.run().identity().runRef().tenantId().value();row.parentRunId=parent.run().identity().runRef().runId();
        row.parentActionId=command.source().action().actionId();row.parentAttemptId=command.source().attemptId();row.ordinal=command.source().ordinal();
        row.childRunId=command.child().run().runRef().runId();return row;
    }
    boolean sameSource(ExecutionProgramChildRow other) {
        return tenantId.equals(other.tenantId)&&parentRunId.equals(other.parentRunId)&&parentActionId.equals(other.parentActionId)
                &&parentAttemptId.equals(other.parentAttemptId)&&ordinal==other.ordinal&&childRunId.equals(other.childRunId);
    }
}

/** 同一桌面展示快照的权威数量，不把窗口行数当成历史总量。 */
final class DesktopProjectionCountsRow {
    public long runCount, messageCount, eventCount, toolCount;
}
