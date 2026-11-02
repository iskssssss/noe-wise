package com.skillforge.core.model.invocation;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.access.PlatformObservationAccess;
import com.skillforge.runtime.api.TypedPayload;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 本领域拥有的窄材料存储，不推进执行、不重发调用，也不签发会话读取权。 */
@Service
public class ModelCallRecords implements ModelCallRecordReader {
    private final ModelCallRecordMapper mapper;
    private final ModelCallRecordCodec codec = new ModelCallRecordCodec();

    public ModelCallRecords(ModelCallRecordMapper mapper) { this.mapper = mapper; }

    /** 独立短事务先保存实际请求；正文持久化不占用网络调用事务。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRequest(long tenant, String invocation, String attempt, TypedPayload request) {
        requireIdentity(tenant, invocation, attempt);
        requireTenant(tenant);
        codec.decodeRequest(request);
        if (mapper.insertRequest(tenant, invocation, attempt, request.schemaId(), request.canonicalJson()) == 0) {
            var existing = material(mapper.find(tenant, invocation, attempt));
            if (existing.isEmpty() || !existing.orElseThrow().request().equals(request)) throw conflict();
        }
    }

    /** 原返回只保存一次，同身份异内容拒绝；调用方保留供应商原执行语义。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordOutcome(long tenant, String invocation, String attempt, TypedPayload outcome) {
        requireIdentity(tenant, invocation, attempt);
        requireTenant(tenant);
        codec.decodeOutcome(outcome);
        if (mapper.saveOutcome(tenant, invocation, attempt, outcome.schemaId(), outcome.canonicalJson()) == 0) {
            var existing = material(mapper.find(tenant, invocation, attempt));
            if (existing.isEmpty() || !existing.orElseThrow().outcome().equals(Optional.of(outcome))) throw conflict();
        }
    }

    /** 平台仅消费已建立的显式目标读窗；机构保留当前Tenant，不切换读取身份。 */
    @Override
    @Transactional(readOnly = true)
    public Map<Key, ModelCallRecordStatus> statuses(long tenantId, List<Key> keys) {
        keys = List.copyOf(keys);
        for (var key : keys) requireIdentity(tenantId, key.invocationId(), key.attemptId());
        requireReadScope(tenantId);
        if (keys.isEmpty()) return Map.of();
        var unique = keys.stream().distinct().toList();
        var result = new LinkedHashMap<Key, ModelCallRecordStatus>();
        for (var row : mapper.statuses(tenantId, unique, ModelCallRecordCodec.REQUEST_SCHEMA, ModelCallRecordCodec.OUTCOME_SCHEMA))
            result.put(new Key(row.invocationId(), row.attemptId()), new ModelCallRecordStatus(row.requestRecorded(), row.outcomeRecorded()));
        return Map.copyOf(result);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ModelCallRecord> find(long tenantId, String invocationId, String attemptId) {
        requireReadScope(tenantId, invocationId, attemptId);
        var result = material(mapper.find(tenantId, invocationId, attemptId));
        if (result.isPresent()) {
            var value = result.orElseThrow();
            if (value.tenantId() != tenantId || !value.invocationId().equals(invocationId) || !value.attemptId().equals(attemptId)) throw conflict();
            codec.decodeRequest(value.request());
            value.outcome().ifPresent(codec::decodeOutcome);
        }
        return result;
    }

    private static void requireReadScope(long tenantId, String invocationId, String attemptId) {
        requireIdentity(tenantId, invocationId, attemptId);
        requireReadScope(tenantId);
    }

    private static void requireReadScope(long tenantId) {
        if (tenantId <= 0) throw new IllegalArgumentException("model_call_identity_invalid");
        String current = TenantContext.getTenantId();
        if (current == null) {
            var target = PlatformObservationAccess.currentAuthorization();
            if (target == null || !target.tenantIds().equals(Set.of(tenantId))) throw scope();
        } else if (!current.equals(Long.toString(tenantId))) throw scope();
    }

    private static Optional<ModelCallRecord> material(ModelCallRecordMapper.Row row) {
        if (row == null) return Optional.empty();
        if ((row.outcomeSchema == null) != (row.outcomeJson == null)) throw conflict();
        return Optional.of(new ModelCallRecord(row.tenantId, row.invocationId, row.attemptId,
                new TypedPayload(row.requestSchema, row.requestJson), row.outcomeSchema == null ? Optional.empty()
                : Optional.of(new TypedPayload(row.outcomeSchema, row.outcomeJson))));
    }
    private static void requireTenant(long tenant) {
        if (!Long.toString(tenant).equals(TenantContext.getTenantId())) throw scope();
    }
    private static void requireIdentity(long tenant, String invocation, String attempt) {
        if (tenant <= 0 || !identifier(invocation, 128) || !identifier(attempt, 160) || invocation.equals(attempt))
            throw new IllegalArgumentException("model_call_identity_invalid");
    }
    private static boolean identifier(String value, int length) {
        return value != null && !value.isBlank() && value.length() <= length && value.chars().noneMatch(Character::isISOControl);
    }
    private static SecurityException scope() { return new SecurityException("model_call_tenant_scope_invalid"); }
    private static IllegalStateException conflict() { return new IllegalStateException("model_call_material_conflict"); }
}
