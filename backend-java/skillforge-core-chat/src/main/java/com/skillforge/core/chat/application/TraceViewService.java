package com.skillforge.core.chat.application;

import com.skillforge.core.billing.application.BillingTraceFacts.Invocation;
import com.skillforge.core.billing.invocation.ExternalInvocationStatus;
import com.skillforge.core.billing.application.BillingTraceFacts.Usage;
import com.skillforge.core.billing.metering.UsageSource;
import com.skillforge.core.billing.application.BillingTraceQueryService;
import com.skillforge.core.chat.persistence.SessionOutboxMapper;
import com.skillforge.core.chat.persistence.SessionOutboxMapper.TraceEntry;
import com.skillforge.core.chat.tracing.TraceQuery;
import com.skillforge.core.chat.tracing.TraceView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 问题到结果的持久轨迹查询。只组合权威账本，不保存第五套事实；OpenTelemetry
 * 或投影缓存丢失时仍可从 Usage、Execution 和 Inbox 重建。
 */
@Service
public class TraceViewService {

    private final BillingTraceQueryService billingTrace;
    private final SessionOutboxMapper sessionOutboxMapper;

    public TraceViewService(
            BillingTraceQueryService billingTrace,
            SessionOutboxMapper sessionOutboxMapper) {
        this.billingTrace = billingTrace;
        this.sessionOutboxMapper = sessionOutboxMapper;
    }

    public TraceView query(String tenantId, TraceQuery query) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        long tenant = Long.parseLong(tenantId);
        List<TraceView.TraceEntry> timeline = new ArrayList<>();
        List<Usage> usage = billingTrace.usageForRootInput(tenant, query.rootInputId());
        List<Invocation> invocations = billingTrace.invocationsForRootInput(tenant, query.rootInputId());
        List<TraceEntry> outbox = sessionOutboxMapper.traceEntries(tenant, query.rootInputId());
        for (Usage row : usage) {
            if (inWindow(row.getOccurredAt(), query)) {
                timeline.add(new TraceView.TraceEntry(
                        "usage_ledger", row.getEventId(), row.getEventType(), "recorded",
                        traceId(row), row.getOccurredAt(), row.getOccurredAt(), List.of(row.getEventId()),
                        usageDetail(row, query)));
            }
        }
        for (Invocation row : invocations) {
            if (inWindow(row.getStartedAt(), query)) {
                timeline.add(new TraceView.TraceEntry(
                        "external_invocation", row.getAttemptId(), row.getOperationType(), row.getStatus(),
                        row.getTraceId(), row.getStartedAt(), row.getEndedAt(),
                        usageRefsForAttempt(usage, row.getAttemptId()), invocationDetail(row, query)));
            }
        }
        for (TraceEntry row : outbox) {
            if (inWindow(row.createdAt(), query)) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("sessionSequence", row.sessionSequence() == null ? 0 : row.sessionSequence());
                if (query.audience() != TraceQuery.Audience.USER) {
                    detail.put("sourceEventId", row.sourceEventId() == null ? "" : row.sourceEventId());
                }
                timeline.add(new TraceView.TraceEntry(
                        "session_outbox", row.eventId(), row.eventType(), "replay_available",
                        row.traceId(), row.createdAt(), row.createdAt(), List.of(), detail));
            }
        }
        timeline.sort(Comparator.comparing(TraceView.TraceEntry::startedAt,
                Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(TraceView.TraceEntry::sourceDomain)
                .thenComparing(TraceView.TraceEntry::sourceEventId));
        return new TraceView(query.rootInputId(), timeline, summarize(usage),
                unresolved(usage, invocations));
    }

    private TraceView.UsageSummary summarize(List<Usage> events) {
        long calls = events.stream().filter(event -> !"request".equals(event.getEventType())).count();
        String tokens = sumTokens(events, Usage::getTokens);
        String prompt = sumTokens(events, Usage::getPromptTokens);
        String completion = sumTokens(events, Usage::getCompletionTokens);
        Map<String, Long> amounts = new LinkedHashMap<>();
        for (Usage event : events) {
            if (event.getTotalAmountCents() != null) {
                amounts.merge(event.getCurrency() == null ? "UNKNOWN" : event.getCurrency(),
                        event.getTotalAmountCents(), Long::sum);
            }
        }
        long unknownUsageCalls = events.stream()
                .filter(event -> !"request".equals(event.getEventType()))
                .filter(event -> !hasKnownUsage(event))
                .count();
        return new TraceView.UsageSummary(calls, tokens, prompt, completion, amounts, unknownUsageCalls);
    }

    /** 聚合保留未知与零，避免多个合法 bigint 相加溢出。 */
    private String sumTokens(List<Usage> events, java.util.function.Function<Usage, Long> field) {
        java.math.BigInteger total = null;
        for (Usage event : events) {
            Long value = hasKnownUsage(event) ? field.apply(event) : null;
            if (value != null) total = (total == null ? java.math.BigInteger.ZERO : total)
                    .add(java.math.BigInteger.valueOf(value));
        }
        return total == null ? null : total.toString();
    }

    private Map<String, Object> usageDetail(Usage event, TraceQuery query) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("eventType", event.getEventType());
        detail.put("provider", event.getProvider() == null ? "" : event.getProvider());
        detail.put("model", event.getModel() == null ? "" : event.getModel());
        detail.put("usageSource", usageSource(event).name());
        if (hasKnownUsage(event) && event.getTokens() != null) {
            detail.put("tokens", event.getTokens().toString());
        }
        if (query.audience() != TraceQuery.Audience.USER) {
            detail.put("callPoint", event.getCallPoint() == null ? "" : event.getCallPoint());
            detail.put("parentEventId", event.getParentEventId() == null ? "" : event.getParentEventId());
            detail.put("invocationId", event.getInvocationId() == null ? "" : event.getInvocationId());
            detail.put("attemptId", event.getAttemptId() == null ? "" : event.getAttemptId());
        }
        return detail;
    }

    private Map<String, Object> invocationDetail(Invocation invocation, TraceQuery query) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operationType", invocation.getOperationType() == null ? "" : invocation.getOperationType());
        if (query.audience() != TraceQuery.Audience.USER) {
            detail.put("invocationId", invocation.getInvocationId() == null ? "" : invocation.getInvocationId());
            detail.put("provider", invocation.getProvider() == null ? "" : invocation.getProvider());
            detail.put("service", invocation.getService() == null ? "" : invocation.getService());
            detail.put("errorCode", invocation.getErrorCode() == null ? "" : invocation.getErrorCode());
        }
        return detail;
    }

    private List<String> usageRefsForAttempt(List<Usage> events, String attemptId) {
        if (attemptId == null || attemptId.isBlank()) {
            return List.of();
        }
        return events.stream()
                .filter(event -> attemptId.equals(event.getAttemptId()))
                .map(Usage::getEventId)
                .filter(eventId -> eventId != null && !eventId.isBlank())
                .toList();
    }

    private List<String> unresolved(
            List<Usage> events,
            List<Invocation> invocations) {
        List<String> unresolved = new ArrayList<>(events.stream()
                .filter(event -> !"request".equals(event.getEventType()))
                .filter(event -> !hasKnownUsage(event) || event.getTotalAmountCents() == null)
                .map(event -> "usage:" + event.getEventId())
                .toList());
        invocations.stream()
                .filter(invocation -> ExternalInvocationStatus.UNRESOLVED.name().equals(invocation.getStatus()))
                .map(Invocation::getAttemptId)
                .filter(attemptId -> attemptId != null && !attemptId.isBlank())
                .map(attemptId -> "external_invocation:" + attemptId)
                .forEach(unresolved::add);
        return List.copyOf(unresolved);
    }

    private boolean hasKnownUsage(Usage event) {
        return usageSource(event) == UsageSource.PROVIDER_REPORTED;
    }

    private UsageSource usageSource(Usage event) {
        try {
            return event.getUsageSource() == null ? UsageSource.UNKNOWN
                    : UsageSource.valueOf(event.getUsageSource());
        } catch (IllegalArgumentException ignored) {
            return UsageSource.UNKNOWN;
        }
    }

    private String traceId(Usage event) {
        return event.getTraceId();
    }

    private boolean inWindow(Instant value, TraceQuery query) {
        return value != null
                && (query.from() == null || !value.isBefore(query.from()))
                && (query.to() == null || value.isBefore(query.to()));
    }

    private long value(Number number) {
        return number == null ? 0L : number.longValue();
    }
}
