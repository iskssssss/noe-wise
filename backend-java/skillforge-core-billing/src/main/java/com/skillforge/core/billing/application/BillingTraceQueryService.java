package com.skillforge.core.billing.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.skillforge.core.billing.application.BillingTraceFacts.Invocation;
import com.skillforge.core.billing.application.BillingTraceFacts.Usage;
import com.skillforge.core.billing.application.BillingTraceFacts.EffectiveCost;
import com.skillforge.core.billing.application.BillingTraceFacts.ReadScope;
import com.skillforge.core.billing.invocation.ExternalInvocationDO;
import com.skillforge.core.billing.metering.CostEventDO;
import com.skillforge.core.billing.persistence.CostEventMapper;
import com.skillforge.core.billing.persistence.ExternalInvocationMapper;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 计费领域拥有轨迹关联所需的账本查询；调用方传递已授权的范围，不传入存储查询条件。 */
@Service
@Transactional(readOnly = true)
public class BillingTraceQueryService {
    private final CostEventMapper usage;
    private final ExternalInvocationMapper invocations;

    public BillingTraceQueryService(CostEventMapper usage, ExternalInvocationMapper invocations) {
        this.usage = usage;
        this.invocations = invocations;
    }

    /** 保留当前会话的有效用量事实及账本原有排序，不把调整记录重复累计。 */
    public List<Usage> effectiveUsageForSession(long tenantId, String sessionId) {
        return usage.listEffectiveBySessionId(tenantId, sessionId).stream().map(BillingTraceQueryService::usage).toList();
    }

    /** 根输入关联沿原账本查询读取；可空用量、计价状态和来源不在此层重解释。 */
    public List<Usage> usageForRootInput(long tenantId, String rootInputId) {
        return usage.listByRootInputId(tenantId, rootInputId).stream().map(BillingTraceQueryService::usage).toList();
    }

    /**
     * 按当前已授权的完整来源元组读取有效成本；空集合不查，SQL 每批不超过固定配额。
     * 只按原 measured event 去重，不把同 attempt 的独立计费事件或对账增减再次累计。
     */
    public List<EffectiveCost> effectiveCostsForScopes(long tenantId, List<ReadScope> scopes) {
        if (tenantId <= 0) throw new IllegalArgumentException("billing_cost_tenant_invalid");
        var selected = List.copyOf(new LinkedHashSet<>(Objects.requireNonNull(scopes, "scopes")));
        if (selected.isEmpty()) return List.of();
        var allowed = new LinkedHashSet<>(selected);
        var result = new LinkedHashMap<String, EffectiveCost>();
        for (int start = 0; start < selected.size(); start += BillingTraceFacts.COST_SCOPE_BATCH_SIZE) {
            var batch = selected.subList(start, Math.min(selected.size(), start + BillingTraceFacts.COST_SCOPE_BATCH_SIZE));
            for (var fact : usage.listEffectiveCostsByScopes(tenantId, batch)) {
                if (fact == null || fact.tenantId() != tenantId || !allowed.contains(fact.scope())
                        || fact.eventId() == null || fact.eventId().isBlank())
                    throw new IllegalStateException("billing_cost_scope_mismatch");
                var previous = result.putIfAbsent(fact.eventId(), fact);
                if (previous != null && !previous.equals(fact))
                    throw new IllegalStateException("billing_cost_event_conflict");
            }
        }
        return List.copyOf(result.values());
    }

    /** 空尝试集合不扫描账本；返回顺序保留原查询结果，消费者仍按原尝试身份关联。 */
    public List<Invocation> invocationsForAttempts(long tenantId, List<String> attemptIds) {
        if (attemptIds.isEmpty()) return List.of();
        return invocations.selectList(new QueryWrapper<ExternalInvocationDO>()
                .eq("tenant_id", tenantId).in("attempt_id", attemptIds)).stream()
                .map(BillingTraceQueryService::invocation).toList();
    }

    /** 根输入的尝试按实际开始时间和原账本位置稳定排序。 */
    public List<Invocation> invocationsForRootInput(long tenantId, String rootInputId) {
        return invocations.selectList(new QueryWrapper<ExternalInvocationDO>()
                .eq("tenant_id", tenantId).eq("root_input_id", rootInputId)
                .orderByAsc("started_at").orderByAsc("id")).stream()
                .map(BillingTraceQueryService::invocation).toList();
    }

    private static Usage usage(CostEventDO event) {
        Object trace = event.getMeta() == null ? null : event.getMeta().get("trace_id");
        return new Usage(event.getEventId(), event.getEventType(), event.getProvider(), event.getModel(),
                event.getUsageSource(), event.getEstimatorVersion(), event.getPricingSnapshotVersion(),
                event.getTokens(), event.getPromptTokens(), event.getCompletionTokens(),
                event.getCachedInputTokens(), event.getCacheWriteInputTokens(), event.getReasoningOutputTokens(),
                event.getRootInputId(), event.getRunId(), event.getInvocationId(), event.getAttemptId(),
                event.getCallPoint(), event.getAmountStatus(), event.getTotalAmountCents(), event.getCurrency(),
                event.getOccurredAt(), event.getParentEventId(), trace == null ? null : String.valueOf(trace));
    }

    private static Invocation invocation(ExternalInvocationDO invocation) {
        return new Invocation(invocation.getInvocationId(), invocation.getAttemptId(), invocation.getOperationType(),
                invocation.getProvider(), invocation.getService(), invocation.getStatus(), invocation.getTraceId(),
                invocation.getErrorCode(), invocation.getStartedAt(), invocation.getEndedAt());
    }
}
