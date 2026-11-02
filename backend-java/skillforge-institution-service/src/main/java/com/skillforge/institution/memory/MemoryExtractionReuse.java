package com.skillforge.institution.memory;

import com.skillforge.runtime.adapter.jdbc.memory.JdbcMemoryPort;
import com.skillforge.runtime.api.MemoryFact;
import com.skillforge.runtime.api.GroupBindingRef;
import com.skillforge.runtime.api.port.MemoryPort.MemoryFactDraft;
import com.skillforge.runtime.api.port.PortCallContext;
import com.skillforge.runtime.api.port.SessionLogPort;
import com.skillforge.runtime.memory.MemoryAccessPolicy;
import com.skillforge.runtime.memory.MemorySourcePolicy;
import com.skillforge.runtime.memory.MemoryTypeRegistry;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.time.Instant;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.MemoryOwner;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.FactStatus;
import com.skillforge.runtime.api.MemoryTypeRef;
import com.skillforge.runtime.api.MemoryScope;
import com.skillforge.runtime.api.MemoryFactRef;
import com.skillforge.institution.memory.MemoryExtractionMaterial.FactAlias;
import com.skillforge.runtime.api.port.CommittedSessionEvent;
import com.skillforge.runtime.api.port.IdempotencyKey;
import org.springframework.stereotype.Component;

/** 自动入站偏好精确复用的宿主入口；原事实不变，新来源独立保存，不伪装 Runtime CREATE。 */
@Component
final class MemoryExtractionReuse {
    private final JdbcMemoryPort memories;
    private final MemoryAccessPolicy access;
    private final MemorySourcePolicy sources;
    private final MemoryTypeRegistry types;
    private final SessionLogPort log;

    /** 复用与常规写入共用生产策略及已提交 SessionLog，缺少任何策略不激活能力。 */
    MemoryExtractionReuse(JdbcMemoryPort memories, MemoryAccessPolicy access, MemorySourcePolicy sources,
            MemoryTypeRegistry types, SessionLogPort log) {
        this.memories = memories;
        this.access = access;
        this.sources = sources;
        this.types = types;
        this.log = log;
    }

    /** 草稿及 memory.written 使用原始事件时间冻结，重新领取不能改变幂等命令的时间字段。 */
    Instant sourceTime(PortCallContext context, EventId eventId) {
        return source(context, eventId).occurredAt();
    }

    /** 只读原命令journal；读失败不能被当成“未写入”而重新提交或重新治理。 */
    boolean hasRecordedMutation(PortCallContext context, IdempotencyKey key) {
        try {
            return memories.hasRecordedMutation(context, key);
        } catch (RuntimeException unavailable) {
            throw new IllegalStateException("memory_extraction_journal_read_failed", unavailable);
        }
    }

    /** 冻结别名只读原immutable revision，当前head变化不能替换已受理的治理对象。 */
    MemoryFact reference(PortCallContext context, FactAlias alias) {
        var ref = new MemoryFactRef(context.runIdentity().owner().sessionRef().tenantId(), alias.factId());
        MemoryFact fact = memories.findRevision(context, ref, alias.revision())
                .orElseThrow(() -> rejected("memory_relation_target_missing"));
        if (!fact.ref().equals(ref) || fact.revision() != alias.revision()
                || !Set.of(MemoryScope.USER, MemoryScope.USER_AGENT, MemoryScope.GROUP, MemoryScope.GROUP_MEMBER)
                        .contains(fact.scope())) {
            throw rejected("memory_relation_target_mismatch");
        }
        return fact;
    }

    /** 模型恢复仍核当前查询权限，精确历史读取本身不构成向模型披露的授权。 */
    MemoryFact modelReference(PortCallContext context, FactAlias alias) {
        MemoryFact fact = reference(context, alias);
        var permission = access.authorize(new MemoryAccessPolicy.Request(context, fact.owner(),
                MemoryAccessPolicy.Operation.QUERY, Set.of(fact.scope()), Set.of(fact.type()), Optional.empty()));
        if (!(permission instanceof MemoryAccessPolicy.Decision.Allowed)) {
            throw rejected("memory_extraction_reference_access_denied");
        }
        return fact;
    }

    /** 冻结前只读已提交正式来源，不允许任务中携带的候选正文自证来源。 */
    CommittedSessionEvent source(PortCallContext context, EventId eventId) {
        var event = log.findByEvent(context.runIdentity().owner(), eventId)
                .orElseThrow(() -> rejected("memory_source_event_missing"));
        if (!event.owner().equals(context.runIdentity().owner()) || !event.eventId().equals(eventId)) {
            throw rejected("memory_extraction_source_owner_invalid");
        }
        return event;
    }

    /** 本次模型可见原事实有界且逐归属授权；只返回当前合法个人事实。 */
    List<MemoryFact> related(PortCallContext context, long endUserId, String subject) {
        var tenant = context.runIdentity().owner().sessionRef().tenantId();
        var owners = List.<MemoryOwner>of(new MemoryOwner.UserAgent(tenant, new EndUserId(endUserId),
                context.runIdentity().agentId()), new MemoryOwner.User(tenant, new EndUserId(endUserId)));
        return related(context, owners.stream().map(owner -> new RelatedOwner(owner, subject)).toList());
    }

    /** 群别名与服务 Agent 无关；成员对象来自本次冻结可信成员集合。 */
    List<MemoryFact> relatedGroup(PortCallContext context, GroupBindingRef group, List<Long> subjects) {
        var owners = new java.util.ArrayList<RelatedOwner>();
        owners.add(new RelatedOwner(new MemoryOwner.Group(group), "group:" + group.groupBindingId()));
        for (Long subject : subjects) owners.add(new RelatedOwner(
                new MemoryOwner.GroupMember(group, new EndUserId(subject)), "user:" + subject));
        return related(context, owners);
    }

    private List<MemoryFact> related(PortCallContext context, List<RelatedOwner> owners) {
        var result = new java.util.ArrayList<MemoryFact>();
        Instant now = Instant.now();
        for (RelatedOwner item : owners) {
            MemoryOwner owner = item.owner();
            String subject = item.subject();
            for (String name : List.of("preference", "episode")) {
                var type = new MemoryTypeRef(name);
                var permission = access.authorize(new MemoryAccessPolicy.Request(context, owner,
                        MemoryAccessPolicy.Operation.QUERY, Set.of(owner.scope()), Set.of(type), Optional.empty()));
                if (!(permission instanceof MemoryAccessPolicy.Decision.Allowed)) continue;
                for (MemoryFact fact : memories.relatedCurrentFacts(context, owner, type, subject, 64)) {
                    if (!fact.owner().equals(owner) || !fact.type().equals(type) || !fact.subject().equals(subject)
                            || fact.status() != FactStatus.ACTIVE || !"v2".equals(fact.schemaVersion())
                            || fact.validFrom().isAfter(now) || fact.validTo().filter(end -> !end.isAfter(now)).isPresent()
                            || fact.expiresAt().filter(end -> !end.isAfter(now)).isPresent()) continue;
                    var currentPermission = access.authorize(new MemoryAccessPolicy.Request(context, fact.owner(),
                            MemoryAccessPolicy.Operation.QUERY, Set.of(fact.scope()), Set.of(fact.type()), Optional.empty()));
                    if (currentPermission instanceof MemoryAccessPolicy.Decision.Allowed) result.add(fact);
                    if (result.size() == 64) return List.copyOf(result);
                }
            }
        }
        return List.copyOf(result);
    }

    private record RelatedOwner(MemoryOwner owner, String subject) {}

    /** 模型已结束后执行授权与存储串行区；callback 仅允许常规 Runtime 存储创建。 */
    <T> T reuseOrCreate(PortCallContext context, MemoryFactDraft draft,
            Function<Optional<MemoryFact>, T> callback) {
        var registration = types.find(draft.type()).orElseThrow(() -> rejected("memory_type_not_registered"));
        var schema = registration.schema(draft.schemaVersion()).orElseThrow(() -> rejected("memory_schema_not_registered"));
        if (!registration.type().equals(draft.type()) || !registration.allowedScopes().contains(draft.scope())
                || !registration.retentionClass().equals(draft.retentionClass())
                || !schema.payloadSchemaId().equals(draft.object().schemaId()) || registration.forceReview()
                || !(registration.validator().validate(draft.schemaVersion(), draft.object())
                        instanceof MemoryTypeRegistry.Validation.Allowed)) {
            throw rejected("memory_extraction_reuse_type_denied");
        }
        var permission = access.authorize(new MemoryAccessPolicy.Request(context, draft.owner(), MemoryAccessPolicy.Operation.CREATE,
                Set.of(draft.scope()), Set.of(draft.type()), Optional.empty()));
        if (!(permission instanceof MemoryAccessPolicy.Decision.Allowed allowed)) {
            throw rejected("memory_extraction_reuse_access_denied");
        }
        var read = access.authorize(new MemoryAccessPolicy.Request(context, draft.owner(), MemoryAccessPolicy.Operation.QUERY,
                Set.of(draft.scope()), Set.of(draft.type()), Optional.empty()));
        if (!(read instanceof MemoryAccessPolicy.Decision.Allowed)) throw rejected("memory_extraction_reuse_read_denied");
        if (draft.sourceEventIds().size() != 1 || draft.exportPath().isPresent()
                || !draft.sourceSessionRef().equals(context.runIdentity().owner().sessionRef())) {
            throw rejected("memory_extraction_reuse_source_invalid");
        }
        var event = log.findByEvent(context.runIdentity().owner(), draft.sourceEventIds().getFirst())
                .orElseThrow(() -> rejected("memory_source_event_missing"));
        if (!event.owner().equals(context.runIdentity().owner())
                || !event.eventId().equals(draft.sourceEventIds().getFirst())
                || !event.executionRef().equals(draft.sourceExecutionRef())
                || !"inbound.message".equals(event.eventType()) || event.sessionSequence() <= 0) {
            throw rejected("memory_extraction_reuse_source_invalid");
        }
        var source = sources.authorize(new MemorySourcePolicy.Request(context, draft, List.of(event)));
        if (!(source instanceof MemorySourcePolicy.Decision.Allowed sourceAllowed)) {
            throw rejected("memory_extraction_reuse_source_denied");
        }
        return memories.reuseOrCreateExtractedPreference(context, draft,
                allowed.auditRef(), sourceAllowed.auditRef(), callback);
    }

    /** 稳定拒绝码不携带模型正文或来源全文。 */
    private static IllegalArgumentException rejected(String reason) { return new IllegalArgumentException(reason); }
}
