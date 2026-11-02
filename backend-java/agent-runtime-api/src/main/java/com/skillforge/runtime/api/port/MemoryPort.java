package com.skillforge.runtime.api.port;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.FactStatus;
import com.skillforge.runtime.api.MemoryFact;
import com.skillforge.runtime.api.MemoryFactRef;
import com.skillforge.runtime.api.MemoryQuery;
import com.skillforge.runtime.api.MemoryRetrievalDiagnostics;
import com.skillforge.runtime.api.MemoryScope;
import com.skillforge.runtime.api.MemoryOwner;
import com.skillforge.runtime.api.SessionSubject;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.runtime.api.MemoryTypeRef;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RetentionClassRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TypedPayload;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 隔离 Core 与 Memory 存储/策略实现，并发布查询及受 revision CAS 保护的变更命令。 */
public interface MemoryPort {

    /** 在当前调用身份与时态边界内查询 ACTIVE Memory 事实。 */
    MemoryQueryOutcome query(MemoryQueryCall query);

    /** 提交创建、取代或状态迁移命令。 */
    MemoryMutationResult submit(MemoryCommand command);

    /**
     * 按各请求的 owner 读取当前 revision；结果与输入一一对应，缺失或不可见为 empty。
     * 保留重复请求及所有治理状态，空批次不访问存储；损坏或技术失败必须显式失败。
     * 该来源读取不替代 Runtime 每次操作的 AccessPolicy/SourcePolicy 授权。
     */
    List<Optional<MemoryFact>> findCurrent(CurrentFactReadBatch batch);

    /** 一个精确事实引用与它自己的访问上下文。 */
    record CurrentFactRead(PortCallContext context, MemoryFactRef fact) {
        /** 引用与访问者必须属于同一租户，用户、助手和 Session 由存储逐项核验。 */
        public CurrentFactRead {
            context = PortContracts.requireNonNull(context, "context");
            fact = requireCurrentTenant(context, fact, "fact");
        }
    }

    /** 治理当前事实的窄读取批次；同租户、最多 200 项，顺序与重复项均保留。 */
    record CurrentFactReadBatch(List<CurrentFactRead> reads) {
        /** 防御复制并拒绝空元素、跨租户批次或超出治理页容量的请求。 */
        public CurrentFactReadBatch {
            reads = List.copyOf(PortContracts.requireNonNull(reads, "reads"));
            if (reads.size() > 200) {
                throw new IllegalArgumentException("current fact batch must contain at most 200 reads");
            }
            if (!reads.isEmpty()) {
                var tenant = reads.getFirst().fact().tenantId();
                for (CurrentFactRead read : reads) {
                    PortContracts.requireSameTenant(tenant, read.fact().tenantId(), "reads.fact.tenantId");
                }
            }
        }
    }

    /** 将公开 MemoryQuery 绑定到当前 Port 调用上下文。 */
    record MemoryQueryCall(PortCallContext context, MemoryQuery query) {

        /** 拒绝查询身份、租户或 Session 与当前调用上下文漂移。 */
        public MemoryQueryCall {
            context = PortContracts.requireNonNull(context, "context");
            query = PortContracts.requireNonNull(query, "query");
            requireQueryOwner(context, query);
        }
    }

    /** 描述尚未分配 id/revision/createdAt 的新 Memory 事实内容。 */
    record MemoryFactDraft(
            MemoryOwner owner,
            MemoryTypeRef type,
            RetentionClassRef retentionClass,
            String schemaVersion,
            String subject,
            String predicate,
            TypedPayload object,
            List<EventId> sourceEventIds,
            SessionRef sourceSessionRef,
            Optional<ExecutionRef> sourceExecutionRef,
            Optional<String> exportPath,
            String extractorVersion,
            Instant validFrom,
            Optional<Instant> validTo,
            Optional<Instant> expiresAt,
            double confidence,
            double importance,
            boolean reviewRequired,
            Optional<MemoryFactRef> derivedFrom,
            FactStatus initialStatus) {

        /** 拒绝无来源、跨租户、非法作用域/时态/评分与非法初始状态。 */
        public MemoryFactDraft {
            owner = PortContracts.requireNonNull(owner, "owner");
            type = PortContracts.requireNonNull(type, "type");
            retentionClass = PortContracts.requireNonNull(retentionClass, "retentionClass");
            schemaVersion = PortContracts.requireText(schemaVersion, "schemaVersion");
            subject = PortContracts.requireText(subject, "subject");
            predicate = PortContracts.requireText(predicate, "predicate");
            object = PortContracts.requireNonNull(object, "object");
            sourceEventIds = PortContracts.immutableDistinctList(sourceEventIds, "sourceEventIds");
            sourceSessionRef = PortContracts.requireNonNull(sourceSessionRef, "sourceSessionRef");
            sourceExecutionRef = PortContracts.requireOptional(sourceExecutionRef, "sourceExecutionRef");
            exportPath = requireOptionalText(exportPath, "exportPath");
            extractorVersion = PortContracts.requireText(extractorVersion, "extractorVersion");
            validFrom = PortContracts.requireNonNull(validFrom, "validFrom");
            validTo = requireNotBefore(validTo, validFrom, "validTo");
            expiresAt = requireNotBefore(expiresAt, validFrom, "expiresAt");
            confidence = PortContracts.requireUnitInterval(confidence, "confidence");
            importance = PortContracts.requireUnitInterval(importance, "importance");
            derivedFrom = PortContracts.requireOptional(derivedFrom, "derivedFrom");
            initialStatus = requireInitialStatus(initialStatus);
            if (sourceEventIds.isEmpty()) {
                throw new IllegalArgumentException("sourceEventIds must not be empty");
            }
            validateDraftTenantClosure(
                    owner, sourceSessionRef, sourceExecutionRef, derivedFrom);
            if (owner instanceof MemoryOwner.Session session && !session.sessionRef().equals(sourceSessionRef)) {
                throw new IllegalArgumentException("SESSION fact must bind sourceSessionRef");
            }
        }

        /** 作用域由封闭归属分支唯一确定。 */
        public MemoryScope scope() { return owner.scope(); }
    }

    /** 保存一次 Memory 查询的候选事实、结果引用与评估时间。 */
    record MemoryQueryResult(
            String queryId,
            List<MemoryFact> candidates,
            ResultRef resultRef,
            Instant evaluatedAt,
            MemoryRetrievalDiagnostics diagnostics) {

        /** 拒绝空白查询身份、重复事实身份或缺失结果事实。 */
        public MemoryQueryResult {
            queryId = PortContracts.requireText(queryId, "queryId");
            candidates = PortContracts.immutableDistinctList(candidates, "candidates");
            resultRef = PortContracts.requireNonNull(resultRef, "resultRef");
            evaluatedAt = PortContracts.requireNonNull(evaluatedAt, "evaluatedAt");
            diagnostics = PortContracts.requireNonNull(diagnostics, "diagnostics");
            if (!diagnostics.budgetSelectedIds().equals(candidates.stream().map(fact -> fact.ref().factId()).toList())) {
                throw new IllegalArgumentException("diagnostic budget selections must match result facts");
            }
            for (MemoryFact fact : candidates) {
                if (diagnostics.candidates().stream().noneMatch(candidate -> candidate.fact().equals(fact.ref()) && candidate.revision() == fact.revision())) {
                    throw new IllegalArgumentException("selected facts must retain exact diagnostic revision");
                }
            }
            HashSet<MemoryFactRef> candidateRefs = new HashSet<>();
            // 同一稳定事实身份只能出现一个 revision，避免查询结果携带相互竞争的事实版本。
            for (MemoryFact candidate : candidates) {
                if (!candidateRefs.add(candidate.ref())) {
                    throw new IllegalArgumentException(
                            "candidates must not contain duplicate MemoryFactRef");
                }
            }
        }
    }

    /** 表达 Memory 查询成功或确定失败。 */
    sealed interface MemoryQueryOutcome
            permits MemoryQueryOutcome.Retrieved, MemoryQueryOutcome.Failed {

        /** 包装一次完整的查询结果。 */
        record Retrieved(MemoryQueryResult result) implements MemoryQueryOutcome {

            /** 拒绝缺失查询结果。 */
            public Retrieved {
                result = PortContracts.requireNonNull(result, "result");
            }
        }

        /** 表示策略或存储读取已确定失败。 */
        record Failed(PortFailure failure) implements MemoryQueryOutcome {

            /** 只允许读取边界使用 NONE 或稳定键幂等重试。 */
            public Failed {
                failure = requireReadRetryMode(failure);
            }
        }
    }

    /** 定义 Memory 创建、直接取代、双版本接受修订与治理状态迁移的封闭命令。 */
    sealed interface MemoryCommand
            permits MemoryCommand.Create, MemoryCommand.Supersede,
                    MemoryCommand.Transition, MemoryCommand.AcceptRevision {

        /** 创建一个由稳定幂等键标识的新事实。 */
        record Create(
                PortCallContext context,
                IdempotencyKey idempotencyKey,
                MemoryFactDraft fact) implements MemoryCommand {

            /** 拒绝缺失字段以及与当前调用 owner 不一致的事实草稿。 */
            public Create {
                context = PortContracts.requireNonNull(context, "context");
                idempotencyKey = PortContracts.requireNonNull(idempotencyKey, "idempotencyKey");
                fact = PortContracts.requireNonNull(fact, "fact");
                requireDraftOwner(context, fact);
            }
        }

        /** 以 revision CAS 原子创建替代事实并推进当前事实演进链。 */
        record Supersede(
                PortCallContext context,
                IdempotencyKey idempotencyKey,
                MemoryFactRef current,
                long expectedRevision,
                MemoryFactDraft replacement) implements MemoryCommand {

            /** 拒绝跨租户引用、非正 revision 与 owner 不一致的替代草稿。 */
            public Supersede {
                context = PortContracts.requireNonNull(context, "context");
                idempotencyKey = PortContracts.requireNonNull(idempotencyKey, "idempotencyKey");
                current = requireCurrentTenant(context, current, "current");
                expectedRevision = PortContracts.requirePositive(expectedRevision, "expectedRevision");
                replacement = PortContracts.requireNonNull(replacement, "replacement");
                requireDraftOwner(context, replacement);
            }
        }

        /**
         * 以两个 expected revision 原子接受已保存的修订；归档 ACTIVE current 并激活 PENDING_CONFIRM proposal。
         * 两事实必须同归属/类型且 proposal.derivedFrom=current；任何拒绝或 CAS 冲突不推进任一事实。
         * proposal 必须在提交时已经生效且仍有效；未来生效提案保留待审，不提前关闭旧事实。
         * Applied 返回被激活的原 proposal（revision+1）与原 current 快照，不分配第三个事实；同键重放先返回原结果。
         */
        record AcceptRevision(PortCallContext context, IdempotencyKey idempotencyKey,
                MemoryFactRef current, long expectedRevision,
                MemoryFactRef proposal, long expectedProposalRevision) implements MemoryCommand {
            /** 拒绝空上下文、空幂等键、跨租户或相同事实引用，以及非正 revision。 */
            public AcceptRevision {
                context = PortContracts.requireNonNull(context, "context");
                idempotencyKey = PortContracts.requireNonNull(idempotencyKey, "idempotencyKey");
                current = requireCurrentTenant(context, current, "current");
                proposal = requireCurrentTenant(context, proposal, "proposal");
                expectedRevision = PortContracts.requirePositive(expectedRevision, "expectedRevision");
                expectedProposalRevision = PortContracts.requirePositive(expectedProposalRevision, "expectedProposalRevision");
                if (current.equals(proposal)) throw new IllegalArgumentException("revision proposal must be a different fact");
            }
        }

        /** 以 revision CAS 推进既有事实的治理状态。 */
        record Transition(
                PortCallContext context,
                IdempotencyKey idempotencyKey,
                MemoryFactRef fact,
                long expectedRevision,
                FactStatus target,
                String reasonCode) implements MemoryCommand {

            /** 拒绝跨租户引用、非正 revision、空白原因与非法目标状态。 */
            public Transition {
                context = PortContracts.requireNonNull(context, "context");
                idempotencyKey = PortContracts.requireNonNull(idempotencyKey, "idempotencyKey");
                fact = requireCurrentTenant(context, fact, "fact");
                expectedRevision = PortContracts.requirePositive(expectedRevision, "expectedRevision");
                target = requireTransitionTarget(target);
                reasonCode = PortContracts.requireText(reasonCode, "reasonCode");
            }
        }
    }

    /** 表达 Memory 写入已应用、确定拒绝或 revision CAS 冲突。 */
    sealed interface MemoryMutationResult
            permits MemoryMutationResult.Applied, MemoryMutationResult.Rejected,
                    MemoryMutationResult.Conflict {

        /** 返回新事实或同键同指纹重放得到的原事实。 */
        record Applied(MemoryFact fact, Optional<MemoryFact> before, boolean replayed) implements MemoryMutationResult {

            /** 拒绝缺失事实。 */
            public Applied {
                fact = PortContracts.requireNonNull(fact, "fact");
                before = PortContracts.requireOptional(before, "before");
                if (before.isPresent()) {
                    PortContracts.requireSameTenant(fact.ref().tenantId(), before.orElseThrow().ref().tenantId(), "before.tenantId");
                }
            }
        }

        /** 表示写命令已确定拒绝。 */
        record Rejected(PortFailure failure) implements MemoryMutationResult {

            /** 拒绝缺失的稳定失败事实。 */
            public Rejected {
                failure = PortContracts.requireNonNull(failure, "failure");
            }
        }

        /** 返回发生 CAS 冲突的事实、当前 revision 与审计引用。 */
        record Conflict(
                MemoryFactRef fact,
                long currentRevision,
                ResultRef auditRef) implements MemoryMutationResult {

            /** 拒绝缺失事实、非正当前 revision 或缺失审计引用。 */
            public Conflict {
                fact = PortContracts.requireNonNull(fact, "fact");
                currentRevision = PortContracts.requirePositive(currentRevision, "currentRevision");
                auditRef = PortContracts.requireNonNull(auditRef, "auditRef");
            }
        }
    }

    /** 在检索之前封闭归属与当前 Session，当前角色由 AccessPolicy 继续校验。 */
    private static void requireQueryOwner(PortCallContext context, MemoryQuery query) {
        requireOwner(context, query.owner());
        if (context.runIdentity().owner().subject() instanceof SessionSubject.Group
                && query.owner() instanceof MemoryOwner.Session
                && !query.scopes().equals(Set.of(MemoryScope.SESSION))) {
            throw new IllegalArgumentException("group Session query must not read personal scopes");
        }
        if (query.owner() instanceof MemoryOwner.Group && query.scopes().contains(MemoryScope.GROUP_MEMBER)
                && query.subjectEndUserId().isEmpty()
                && !(context.runIdentity().requester() instanceof RunRequester.EndUser)) {
            throw new IllegalArgumentException("system group member query requires explicit subject");
        }
    }

    /** 来源必须是本次调用的 Session；描述对象不等同于请求者。 */
    private static void requireDraftOwner(PortCallContext context, MemoryFactDraft draft) {
        requireOwner(context, draft.owner());
        if (!draft.sourceSessionRef().equals(context.runIdentity().owner().sessionRef())) {
            throw new IllegalArgumentException("memory fact source Session must match call context");
        }
    }

    private static void requireOwner(PortCallContext context, MemoryOwner owner) {
        SessionOwner session = context.runIdentity().owner();
        PortContracts.requireSameTenant(session.sessionRef().tenantId(), owner.tenantId(), "owner.tenantId");
        boolean matches = switch (owner) {
            case MemoryOwner.Session value -> value.sessionRef().equals(session.sessionRef());
            case MemoryOwner.UserAgent value -> session.subject() instanceof SessionSubject.User user
                    && value.endUserId().equals(user.endUserId())
                    && value.agentId().equals(context.runIdentity().agentId());
            case MemoryOwner.User value -> session.subject() instanceof SessionSubject.User user
                    && value.endUserId().equals(user.endUserId());
            case MemoryOwner.Group value -> session.subject() instanceof SessionSubject.Group group
                    && value.groupBindingRef().equals(group.groupBindingRef());
            case MemoryOwner.GroupMember value -> session.subject() instanceof SessionSubject.Group group
                    && value.groupBindingRef().equals(group.groupBindingRef());
        };
        if (!matches) throw new IllegalArgumentException("memory owner must match call context");
    }

    /** 校验事实引用属于调用上下文的租户。 */
    private static MemoryFactRef requireCurrentTenant(
            PortCallContext context,
            MemoryFactRef fact,
            String field) {
        fact = PortContracts.requireNonNull(fact, field);
        PortContracts.requireSameTenant(
                context.runIdentity().owner().sessionRef().tenantId(),
                fact.tenantId(),
                field + ".tenantId");
        return fact;
    }

    /** 校验草稿所有可定位引用都封闭在来源 Session 的租户内。 */
    private static void validateDraftTenantClosure(
            MemoryOwner owner,
            SessionRef sourceSessionRef,
            Optional<ExecutionRef> sourceExecutionRef,
            Optional<MemoryFactRef> derivedFrom) {
        PortContracts.requireSameTenant(sourceSessionRef.tenantId(), owner.tenantId(), "owner.tenantId");
        sourceExecutionRef.ifPresent(value -> PortContracts.requireSameTenant(
                sourceSessionRef.tenantId(), value.tenantId(), "sourceExecutionRef.tenantId"));
        derivedFrom.ifPresent(value -> PortContracts.requireSameTenant(
                sourceSessionRef.tenantId(), value.tenantId(), "derivedFrom.tenantId"));
    }

    /** 校验可选文本存在时非空白。 */
    private static Optional<String> requireOptionalText(Optional<String> value, String field) {
        value = PortContracts.requireOptional(value, field);
        value.ifPresent(text -> PortContracts.requireText(text, field));
        return value;
    }

    /** 校验可选结束时间不早于开始时间。 */
    private static Optional<Instant> requireNotBefore(
            Optional<Instant> value,
            Instant start,
            String field) {
        value = PortContracts.requireOptional(value, field);
        if (value.filter(end -> end.isBefore(start)).isPresent()) {
            throw new IllegalArgumentException(field + " must not be before validFrom");
        }
        return value;
    }

    /** 限定创建和取代草稿只能从 ACTIVE 或 PENDING_CONFIRM 开始。 */
    private static FactStatus requireInitialStatus(FactStatus status) {
        status = PortContracts.requireNonNull(status, "initialStatus");
        if (status != FactStatus.ACTIVE && status != FactStatus.PENDING_CONFIRM) {
            throw new IllegalArgumentException("initialStatus must be ACTIVE or PENDING_CONFIRM");
        }
        return status;
    }

    /** 冻结 §6.4 中可作为迁移目标的六态集合，未来加枚举时默认拒绝。 */
    private static FactStatus requireTransitionTarget(FactStatus target) {
        target = PortContracts.requireNonNull(target, "target");
        if (!Set.of(
                        FactStatus.ACTIVE,
                        FactStatus.DECAYED,
                        FactStatus.PENDING_CONFIRM,
                        FactStatus.REJECTED,
                        FactStatus.ARCHIVED,
                        FactStatus.FORGOTTEN)
                .contains(target)) {
            throw new IllegalArgumentException("target is not a legal MemoryFact transition target");
        }
        return target;
    }

    /** 校验 Memory 读取失败只声明停止或稳定键幂等重试。 */
    private static PortFailure requireReadRetryMode(PortFailure failure) {
        failure = PortContracts.requireNonNull(failure, "failure");
        if (failure.retryMode() != RetryMode.NONE
                && failure.retryMode() != RetryMode.IDEMPOTENT_BY_STABLE_KEY) {
            throw new IllegalArgumentException("Memory query retry mode is not supported");
        }
        return failure;
    }
}
