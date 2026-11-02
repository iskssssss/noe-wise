package com.skillforge.runtime.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 表示带来源、双时态、治理状态与演进引用的不可变 Memory 事实。 */
public record MemoryFact(
        MemoryFactRef ref,
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
        long revision,
        Instant createdAt,
        Instant validFrom,
        Optional<Instant> validTo,
        Optional<Instant> expiresAt,
        double confidence,
        double importance,
        boolean reviewRequired,
        Optional<MemoryFactRef> derivedFrom,
        Optional<MemoryFactRef> supersededBy,
        double decayScore,
        FactStatus status) {

    /** 拒绝跨租户引用、非法作用域、空 Optional、无效时态、评分或 revision。 */
    public MemoryFact {
        ref = ApiContracts.requireNonNull(ref, "ref");
        owner = ApiContracts.requireNonNull(owner, "owner");
        type = ApiContracts.requireNonNull(type, "type");
        retentionClass = ApiContracts.requireNonNull(retentionClass, "retentionClass");
        schemaVersion = ApiContracts.requireText(schemaVersion, "schemaVersion");
        subject = ApiContracts.requireText(subject, "subject");
        predicate = ApiContracts.requireText(predicate, "predicate");
        object = ApiContracts.requireNonNull(object, "object");
        sourceEventIds = ApiContracts.immutableDistinctList(sourceEventIds, "sourceEventIds");
        sourceSessionRef = ApiContracts.requireNonNull(sourceSessionRef, "sourceSessionRef");
        sourceExecutionRef = ApiContracts.requireOptional(sourceExecutionRef, "sourceExecutionRef");
        exportPath = requireOptionalText(exportPath, "exportPath");
        extractorVersion = ApiContracts.requireText(extractorVersion, "extractorVersion");
        revision = ApiContracts.requirePositive(revision, "revision");
        createdAt = ApiContracts.requireNonNull(createdAt, "createdAt");
        validFrom = ApiContracts.requireNonNull(validFrom, "validFrom");
        validTo = ApiContracts.requireNotBefore(validTo, validFrom, "validTo");
        expiresAt = ApiContracts.requireNotBefore(expiresAt, validFrom, "expiresAt");
        confidence = ApiContracts.requireUnitInterval(confidence, "confidence");
        importance = ApiContracts.requireUnitInterval(importance, "importance");
        derivedFrom = ApiContracts.requireOptional(derivedFrom, "derivedFrom");
        supersededBy = ApiContracts.requireOptional(supersededBy, "supersededBy");
        decayScore = ApiContracts.requireUnitInterval(decayScore, "decayScore");
        status = ApiContracts.requireNonNull(status, "status");

        // MemoryFact 必须保留至少一个已提交来源事件，不能成为无来源事实。
        if (sourceEventIds.isEmpty()) {
            throw new IllegalArgumentException("sourceEventIds must not be empty");
        }

        validateTenantClosure(ref, owner, sourceSessionRef, sourceExecutionRef, derivedFrom, supersededBy);
        if (owner instanceof MemoryOwner.Session session && !session.sessionRef().equals(sourceSessionRef)) {
            throw new IllegalArgumentException("SESSION fact must bind sourceSessionRef");
        }
    }

    /** 校验可选文本存在时非空白，同时拒绝 null Optional。 */
    private static Optional<String> requireOptionalText(Optional<String> value, String field) {
        value = ApiContracts.requireOptional(value, field);
        // Optional 存在值时沿用公开文本的非空白约束。
        if (value.isPresent()) {
            ApiContracts.requireText(value.orElseThrow(), field);
        }
        return value;
    }

    /** 校验 Fact、Session、Execution 与演进引用均属于同一租户。 */
    private static void validateTenantClosure(
            MemoryFactRef ref,
            MemoryOwner owner,
            SessionRef sourceSessionRef,
            Optional<ExecutionRef> sourceExecutionRef,
            Optional<MemoryFactRef> derivedFrom,
            Optional<MemoryFactRef> supersededBy) {
        TenantId tenantId = ref.tenantId();
        ApiContracts.requireSameTenant(tenantId, sourceSessionRef.tenantId(), "sourceSessionRef.tenantId");
        ApiContracts.requireSameTenant(tenantId, owner.tenantId(), "owner.tenantId");
        // 来源 Execution 是可选因果引用，不能越过事实租户。
        sourceExecutionRef.ifPresent(value -> ApiContracts.requireSameTenant(
                tenantId, value.tenantId(), "sourceExecutionRef.tenantId"));
        // 派生与取代链始终封闭在当前事实租户内。
        derivedFrom.ifPresent(value -> ApiContracts.requireSameTenant(
                tenantId, value.tenantId(), "derivedFrom.tenantId"));
        supersededBy.ifPresent(value -> ApiContracts.requireSameTenant(
                tenantId, value.tenantId(), "supersededBy.tenantId"));
        // 演进链不能把当前事实声明为自己的来源或后继。
        if (derivedFrom.filter(ref::equals).isPresent() || supersededBy.filter(ref::equals).isPresent()) {
            throw new IllegalArgumentException("memory fact must not reference itself");
        }
    }

    /** 作用域由封闭归属分支唯一确定。 */
    public MemoryScope scope() { return owner.scope(); }
}
