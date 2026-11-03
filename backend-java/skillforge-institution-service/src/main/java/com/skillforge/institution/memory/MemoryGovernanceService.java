package com.skillforge.institution.memory;

import static com.skillforge.institution.memory.MemoryGovernanceRequest.fingerprint;

import com.skillforge.core.agent.memory.MemoryContent;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.runtime.adapter.jdbc.memory.JdbcMemoryPort;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.FactStatus;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.MemoryFact;
import com.skillforge.runtime.api.MemoryFactRef;
import com.skillforge.runtime.api.MemoryScope;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.InvocationControl;
import com.skillforge.runtime.api.port.IdempotencyKey;
import com.skillforge.runtime.api.port.MemoryPort.CurrentFactRead;
import com.skillforge.runtime.api.port.MemoryPort.CurrentFactReadBatch;
import com.skillforge.runtime.api.port.MemoryPort.MemoryFactDraft;
import com.skillforge.runtime.api.port.PortCallContext;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.skillforge.runtime.memory.MemoryRuntime;
import com.skillforge.institution.memory.MemoryGovernanceMapper.Owner;
import com.skillforge.institution.memory.MemoryGovernanceMapper.PageOwner;
import com.skillforge.institution.memory.MemoryGovernanceRequest.Operation;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Memory 当前事实查询及审核、纠正、遗忘、恢复的受控产品入口。 */
@Service
public class MemoryGovernanceService {
    private static final Logger log = LoggerFactory.getLogger(MemoryGovernanceService.class);
    private final MemoryGovernanceMapper mapper;
    private final JdbcMemoryPort memories;
    private final MemoryRuntime runtime;
    private final MemoryGovernanceRequests requests;

    public MemoryGovernanceService(
            MemoryGovernanceMapper mapper, JdbcMemoryPort memories, MemoryRuntime runtime,
            MemoryGovernanceRequests requests) {
        this.mapper = mapper;
        this.memories = memories;
        this.runtime = runtime;
        this.requests = requests;
    }

    @Transactional(readOnly = true)
    public List<MemoryFact> list(String tenantId, String status, int limit) {
        return list(tenantId, new ListQuery(status, null, null, null, null, limit));
    }

    /**
     * 按可选 scope/type/owner 读取当前事实。非法过滤值失败关闭，不把未知层当成空结果。
     */
    @Transactional(readOnly = true)
    public List<MemoryFact> list(String tenantId, ListQuery query) {
        log.debug("记忆治理查询开始 tenant={} limit={}", tenantId, query.limit());
        int bounded = Math.max(1, Math.min(query.limit(), 200));
        ListQuery boundedQuery = new ListQuery(query.status(), query.scope(), query.type(),
                query.agentId(), query.endUserId(), bounded);
        List<Owner> owners = owners(tenantId, boundedQuery);
        List<CurrentFactRead> reads = owners.stream().map(owner -> new CurrentFactRead(
                context(tenantId, owner), new MemoryFactRef(new TenantId(tenantId), owner.factId()))).toList();
        List<Optional<MemoryFact>> current = memories.findCurrent(new CurrentFactReadBatch(reads));
        if (current.size() != owners.size()) throw new IllegalStateException("memory_current_batch_size_mismatch");
        List<MemoryFact> result = new ArrayList<>(owners.size());
        for (int index = 0; index < owners.size(); index++) {
            Owner owner = owners.get(index);
            current.get(index).map(fact -> withDecay(fact, owner.decayScore())).ifPresent(result::add);
        }
        log.debug("记忆治理查询完成 tenant={} count={}", tenantId, result.size());
        return List.copyOf(result);
    }

    /** 治理列表过滤；空白字段表示不限制。 */
    public record ListQuery(String status, String scope, String type, String agentId,
            Long endUserId, int limit) { }

    /** 治理搜索使用权威事实正文；每次至多扫描 1000 条，可通过游标继续。 */
    @Transactional(readOnly = true)
    public Page page(String tenantId, ListQuery query, String search, String cursor) {
        validate(query);
        int limit = Math.max(1, Math.min(query.limit(), 200));
        String status = text(query.status());
        String keyword = text(search);
        if (keyword != null) {
            if (keyword.length() > 256) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "memory_query_invalid");
            keyword = keyword.toLowerCase(Locale.ROOT);
        }
        Cursor position = decodeCursor(cursor);
        List<MemoryFact> items = new ArrayList<>(limit);
        int scanned = 0;
        while (scanned < 1000) {
            int chunk = Math.min(200, 1000 - scanned);
            List<PageOwner> rows = mapper.pageOwners(tenantId, status, text(query.scope()),
                    text(query.type()), text(query.agentId()), query.endUserId(),
                    position == null ? null : position.second(), position == null ? null : position.nano(),
                    position == null ? null : position.factId(), chunk + 1);
            int count = Math.min(chunk, rows.size());
            List<CurrentFactRead> reads = rows.subList(0, count).stream().map(row -> new CurrentFactRead(
                    context(tenantId, row.owner()), new MemoryFactRef(new TenantId(tenantId), row.factId()))).toList();
            List<Optional<MemoryFact>> current = memories.findCurrent(new CurrentFactReadBatch(reads));
            if (current.size() != count) throw new IllegalStateException("memory_current_batch_size_mismatch");
            for (int index = 0; index < count; index++) {
                PageOwner row = rows.get(index);
                position = new Cursor(row.createdEpochSecond(), row.createdNano(), row.factId());
                scanned++;
                Optional<MemoryFact> value = current.get(index);
                if (value.isPresent()) {
                    MemoryFact fact = withDecay(value.get(), row.decayScore());
                    // 权威内容由独立连接读取；若并发治理已改变状态，不把它放入旧状态筛选结果。
                    if ((status == null || status.equals(fact.status().name()))
                            && (keyword == null || (fact.subject() + fact.predicate() + fact.object().canonicalJson())
                            .toLowerCase(Locale.ROOT).contains(keyword))) items.add(fact);
                }
                if (items.size() == limit)
                    return new Page(List.copyOf(items), index + 1 < rows.size() ? encodeCursor(position) : null);
            }
            if (rows.size() <= count) return new Page(List.copyOf(items), null);
        }
        return new Page(List.copyOf(items), encodeCursor(position));
    }

    public record Page(List<MemoryFact> items, @JsonProperty("next_cursor") String nextCursor) { }
    private record Cursor(long second, int nano, String factId) { }

    private static String encodeCursor(Cursor cursor) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (cursor.second() + ":" + cursor.nano() + ":" + cursor.factId()).getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decodeCursor(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            if (value.length() > 4096) throw new IllegalArgumentException();
            String[] parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split(":", 3);
            if (parts.length != 3 || parts[2].isBlank()) throw new IllegalArgumentException();
            long second = Long.parseLong(parts[0]);
            int nano = Integer.parseInt(parts[1]);
            if (nano < 0 || nano > 999999999) throw new IllegalArgumentException();
            return new Cursor(second, nano, parts[2]);
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "memory_cursor_invalid");
        }
    }

    /** 通过 Runtime 提交状态迁移；宿主触发器与 head 原子更新衰减，不追加独立写入。 */
    @Transactional(rollbackFor = Exception.class)
    public MemoryFact transition(
            String tenantId, String factId, long expectedRevision,
            FactStatus target, String reason, String idempotencyKey) {
        log.debug("记忆治理变更开始 tenant={} expectedRevision={}", tenantId, expectedRevision);
        Owner owner = requireOwner(tenantId, factId);
        MemoryFact current = current(context(tenantId, owner), tenantId, factId);
        if (current.scope() == MemoryScope.USER && current.status() == FactStatus.PENDING_CONFIRM
                && target == FactStatus.ACTIVE) {
            throw userScopeConfirmationRequired();
        }
        var request = requests.accept(new MemoryGovernanceRequest(tenantId, factId, expectedRevision, Operation.TRANSITION,
                idempotencyKey, fingerprint(Operation.TRANSITION.name(), factId, Long.toString(expectedRevision), target.name(), reason)));
        return transition(context(tenantId, owner, request.key()), current, expectedRevision,
                target, reason, request);
    }

    /**
     * 审核共享候选时要求操作者明确确认 USER 范围；范围与事实一起受 revision/CAS 约束。
     * 同一回执重投仍交给 Runtime 核对原操作，不因当前状态已变化而提前抹掉幂等结果。
     */
    @Transactional(rollbackFor = Exception.class)
    public MemoryFact review(String tenantId, String factId, long expectedRevision,
            boolean approved, MemoryScope confirmedScope, String idempotencyKey) {
        Owner owner = requireOwner(tenantId, factId);
        MemoryFact current = current(context(tenantId, owner), tenantId, factId);
        if (confirmedScope != null && confirmedScope != current.scope()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "memory_review_scope_mismatch");
        }
        if (approved && current.scope() == MemoryScope.USER && confirmedScope != MemoryScope.USER) {
            throw userScopeConfirmationRequired();
        }
        String reason = !approved ? "memory_review_rejected"
                : current.scope() == MemoryScope.USER ? "memory_user_scope_review_approved" : "memory_review_approved";
        var request = requests.accept(new MemoryGovernanceRequest(tenantId, factId, expectedRevision, Operation.REVIEW,
                idempotencyKey, fingerprint(Operation.REVIEW.name(), factId, Long.toString(expectedRevision), Boolean.toString(approved),
                        confirmedScope == null ? null : confirmedScope.name())));
        PortCallContext context = context(tenantId, owner, request.key());
        var proposal = revisionProposal(current);
        if (approved && proposal.isPresent()) {
            MemoryContent.RevisionProposal target = proposal.orElseThrow();
            requireRevisionBinding(tenantId, current, target);
            var mutation = new MemoryRuntime.Mutation.AcceptRevision(
                    new IdempotencyKey(request.key()), new MemoryFactRef(new TenantId(tenantId), target.factId()),
                    target.expectedRevision(), current.ref(), expectedRevision, current.scope(), current.type());
            return recorded(runtime.mutate(new MemoryRuntime.MutationCall(context, mutation, request.acceptedAt())));
        }
        return transition(context, current, expectedRevision,
                approved ? FactStatus.ACTIVE : FactStatus.REJECTED, reason, request);
    }

    /** 唯一状态提交点；调用方先完成对应操作的共享确认，再由 Runtime 原子校验 CAS 与幂等。 */
    private MemoryFact transition(PortCallContext context, MemoryFact current, long expectedRevision,
            FactStatus target, String reason, MemoryGovernanceRequests.Receipt request) {
        var mutation = new MemoryRuntime.Mutation.Transition(
                new IdempotencyKey(request.key()), current.ref(), expectedRevision,
                current.scope(), current.type(), target, reason);
        MemoryFact result = recorded(runtime.mutate(new MemoryRuntime.MutationCall(
                context, mutation, request.acceptedAt())));
        log.debug("记忆治理状态迁移完成 status={} revision={}", result.status(), result.revision());
        return result;
    }

    /** 校验当前所有者后提交纠正；revision 竞争和治理拒绝映射为稳定业务异常。 */
    @Transactional(rollbackFor = Exception.class)
    public MemoryFact correct(
            String tenantId, String factId, long expectedRevision,
            String subject, String predicate, String objectJson,
            double confidence, double importance, String idempotencyKey) {
        log.debug("记忆治理变更开始 tenant={} expectedRevision={}", tenantId, expectedRevision);
        Owner owner = requireOwner(tenantId, factId);
        MemoryFact current = current(context(tenantId, owner), tenantId, factId);
        // 纠正会创建 ACTIVE replacement，不能把待确认共享候选借此激活。
        if (current.scope() == MemoryScope.USER && current.status() == FactStatus.PENDING_CONFIRM) {
            throw userScopeConfirmationRequired();
        }
        if (current.status() == FactStatus.PENDING_CONFIRM && revisionProposal(current).isPresent()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "memory_revision_review_required");
        }
        MemoryContent.Content content = correctionContent(objectJson);
        String payload = MemoryContent.encode(content);
        var temporal = content.temporal();
        Instant validFrom = "interval".equals(temporal.kind()) ? temporal.from() : current.validFrom();
        Optional<Instant> validTo = "interval".equals(temporal.kind())
                ? Optional.ofNullable(temporal.to()) : Optional.empty();
        var request = requests.accept(new MemoryGovernanceRequest(tenantId, factId, expectedRevision, Operation.CORRECT,
                idempotencyKey, fingerprint(Operation.CORRECT.name(), factId, Long.toString(expectedRevision), subject, predicate, payload,
                        Double.toString(confidence), Double.toString(importance))));
        PortCallContext context = context(tenantId, owner, request.key());
        MemoryFactDraft replacement = new MemoryFactDraft(
                current.owner(),
                current.type(), current.retentionClass(), current.schemaVersion(),
                textOr(subject, current.subject()), textOr(predicate, current.predicate()),
                new TypedPayload(current.object().schemaId(), payload),
                current.sourceEventIds(), current.sourceSessionRef(),
                current.sourceExecutionRef(), current.exportPath(), "governance-v1",
                validFrom, validTo, current.expiresAt(),
                confidence, importance, false, Optional.of(current.ref()), FactStatus.ACTIVE);
        var mutation = new MemoryRuntime.Mutation.Supersede(
                new IdempotencyKey(request.key()), current.ref(), expectedRevision, replacement);
        return recorded(runtime.mutate(new MemoryRuntime.MutationCall(
                context, mutation, request.acceptedAt())));
    }

    private MemoryFact current(PortCallContext context, String tenantId, String factId) {
        return memories.findCurrent(context,
                new MemoryFactRef(new TenantId(tenantId), factId)).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "Memory 事实不存在"));
    }

    /** 对外使用稳定原因码，不把缺失共享确认转换为默认允许。 */
    private static ApiException userScopeConfirmationRequired() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "memory_user_scope_confirmation_required");
    }

    private MemoryFact recorded(MemoryRuntime.MutationResult result) {
        if (result instanceof MemoryRuntime.MutationResult.Recorded recorded) {
            return recorded.fact();
        }
        if (result instanceof MemoryRuntime.MutationResult.Conflict conflict) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Memory revision 已变化，当前 revision=" + conflict.currentRevision());
        }
        String reason = switch (result) {
            case MemoryRuntime.MutationResult.Rejected rejected -> rejected.failure().reasonCode();
            case MemoryRuntime.MutationResult.Pending pending -> pending.failure().reasonCode();
            case MemoryRuntime.MutationResult.Uncertain uncertain -> uncertain.failure().reasonCode();
            case MemoryRuntime.MutationResult.Stopped stopped -> stopped.failure().reasonCode();
            case MemoryRuntime.MutationResult.Recorded ignored -> throw new IllegalStateException();
            case MemoryRuntime.MutationResult.Conflict ignored -> throw new IllegalStateException();
        };
        HttpStatus status = result instanceof MemoryRuntime.MutationResult.Pending
                || result instanceof MemoryRuntime.MutationResult.Uncertain
                ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.UNPROCESSABLE_ENTITY;
        throw new ApiException(status, reason);
    }

    private List<Owner> owners(String tenantId, ListQuery query) {
        validate(query);
        return mapper.owners(tenantId, text(query.status()), text(query.scope()), text(query.type()),
                text(query.agentId()), query.endUserId(), query.limit());
    }

    private static void validate(ListQuery query) {
        String scope = text(query.scope());
        if (scope != null && !Set.of("SESSION", "USER_AGENT", "USER").contains(scope)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "memory_scope_invalid");
        }
        String type = text(query.type());
        if (type != null && !Set.of("preference", "episode").contains(type)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "memory_type_invalid");
        }
        if (query.endUserId() != null && query.endUserId() <= 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "memory_end_user_invalid");
        }
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Owner requireOwner(String tenantId, String factId) {
        return mapper.owner(tenantId, factId);
    }

    private PortCallContext context(String tenantId, Owner value) {
        return context(tenantId, value, "read:" + SnowflakeIds.nextId());
    }

    /** 写入身份绑定已持久受理键；动态 deadline 不改变原命令或 SessionLog 身份。 */
    private PortCallContext context(String tenantId, Owner value, String requestKey) {
        if (value == null) throw new ApiException(HttpStatus.NOT_FOUND, "Memory 事实不存在");
        TenantId tenant = new TenantId(tenantId);
        SessionOwner owner = SessionOwner.user(new SessionRef(tenant, value.sessionId()),
                new AgentId(value.agentId()), new EndUserId(value.endUserId()));
        String suffix = fingerprint(tenantId, value.factId(), requestKey);
        InputId input = new InputId("memory-governance:" + suffix);
        RunIdentity run = new RunIdentity(owner, new AgentId(value.agentId()), input,
                new RunRef(tenant, "memory-governance:" + suffix),
                new TraceEnvelope("memory-governance:" + suffix, "memory-governance:" + suffix,
                        input, Optional.empty()),
                new ResultRef("memory-governance-identity:" + suffix),
                new RunRequester.System(
                        new PrincipalRef.SystemPrincipal(tenant, "memory-governance")),
                Optional.empty(), new RunOriginRef(tenant, "memory-governance:" + suffix));
        VerifiedIdentity identity = new VerifiedIdentity(
                new PrincipalRef.SystemPrincipal(tenant, "memory-governance"), owner,
                Set.of("memory:manage"), 1,
                new ResultRef("memory-governance-verified:" + suffix));
        return new PortCallContext(run, identity,
                new InvocationControl(Instant.now().plusSeconds(30), () -> false));
    }

    /** 修订目标的固定关联来自候选准备记录；模型或普通 payload 不能自行签发关系。 */
    private void requireRevisionBinding(String tenantId, MemoryFact proposal,
            MemoryContent.RevisionProposal expected) {
        if (!proposal.derivedFrom().equals(Optional.of(new MemoryFactRef(new TenantId(tenantId), expected.factId())))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "memory_revision_proposal_invalid");
        }
        var bindings = mapper.revisionTargets(tenantId, proposal.ref().factId());
        if (bindings.size() != 1) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "memory_revision_proposal_not_prepared");
        }
        var binding = bindings.getFirst();
        if (!binding.targetFactId().equals(expected.factId()) || binding.targetRevision() != expected.expectedRevision()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "memory_revision_proposal_invalid");
        }
    }

    private static Optional<MemoryContent.RevisionProposal> revisionProposal(MemoryFact fact) {
        try {
            return MemoryContent.revisionProposal(MemoryContent.decode(fact.object().canonicalJson()));
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "memory_payload_rejected");
        }
    }

    /** 手工纠正只消费共享正文协议实体，移除宿主提取链授予的修订控制信息。 */
    private static MemoryContent.Content correctionContent(String content) {
        try {
            return MemoryContent.withoutRevisionProposal(MemoryContent.decode(content));
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "memory_payload_rejected");
        }
    }

    private static String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static MemoryFact withDecay(MemoryFact fact, double decay) {
        return new MemoryFact(fact.ref(), fact.owner(),
                fact.type(), fact.retentionClass(), fact.schemaVersion(), fact.subject(),
                fact.predicate(), fact.object(), fact.sourceEventIds(), fact.sourceSessionRef(),
                fact.sourceExecutionRef(), fact.exportPath(), fact.extractorVersion(), fact.revision(),
                fact.createdAt(), fact.validFrom(), fact.validTo(), fact.expiresAt(), fact.confidence(),
                fact.importance(), fact.reviewRequired(), fact.derivedFrom(), fact.supersededBy(),
                Math.max(0, Math.min(1, decay)), fact.status());
    }

}
