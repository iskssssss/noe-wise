package com.skillforge.core.billing.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.skillforge.core.billing.invocation.ExternalInvocationDO;
import com.skillforge.core.billing.invocation.ExternalInvocationStatus;
import com.skillforge.core.billing.metering.CostEventDO;
import com.skillforge.core.billing.metering.UsageSource;
import com.skillforge.core.billing.persistence.CostEventMapper;
import com.skillforge.core.billing.persistence.ExternalInvocationMapper;
import com.skillforge.core.billing.application.BillingTraceFacts.ReadScope;
import com.skillforge.core.billing.application.BillingTraceFacts.EffectiveCost;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 账本查询保留原关联范围、排序和 UNKNOWN/null，且不向消费者泄漏可变持久对象。 */
class BillingTraceQueryServiceTest {
    private final CostEventMapper usage = mock(CostEventMapper.class);
    private final ExternalInvocationMapper invocations = mock(ExternalInvocationMapper.class);
    private final BillingTraceQueryService service = new BillingTraceQueryService(usage, invocations);

    @Test void exactCostScopesAreBatchedAndOnlyMeasuredEventIdentityDeduplicatesAmounts() {
        assertTrue(service.effectiveCostsForScopes(7L, List.of()).isEmpty());
        verifyNoInteractions(usage);
        var scopes = java.util.stream.IntStream.range(0, 201)
                .mapToObj(index -> new ReadScope("session", "run-" + index, "root")).toList();
        var first = new EffectiveCost("first", "same-attempt", 7L, "session", "run-0", "root", "priced", new BigDecimal("0.00125"));
        var second = new EffectiveCost("second", "same-attempt", 7L, "session", "run-0", "root", "unresolved", null);
        when(usage.listEffectiveCostsByScopes(eq(7L), anyList())).thenAnswer(call -> {
            List<ReadScope> batch = call.getArgument(1);
            assertTrue(batch.size() <= BillingTraceFacts.COST_SCOPE_BATCH_SIZE);
            return batch.contains(scopes.getFirst()) ? List.of(first, first, second) : List.of();
        });
        var selected = new java.util.ArrayList<>(scopes); selected.add(scopes.getFirst());
        var result = service.effectiveCostsForScopes(7L, selected);
        assertEquals(List.of(first, second), result);
        assertEquals("CNY", first.currency()); assertNull(second.confirmedCostCents());
        verify(usage, times(2)).listEffectiveCostsByScopes(eq(7L), anyList());
        assertThrows(UnsupportedOperationException.class, result::clear);
    }

    @Test void aMapperResultOutsideTheExactTargetOrScopeFailsClosed() {
        var scope = new ReadScope("session", "run", "root");
        for (var outside : List.of(
                new EffectiveCost("event", "attempt", 8L, "session", "run", "root", "priced", BigDecimal.ONE),
                new EffectiveCost("event", "attempt", 7L, "session", "another-run", "root", "priced", BigDecimal.ONE),
                new EffectiveCost("event", "attempt", 7L, "session", "run", "another-root", "priced", BigDecimal.ONE))) {
            when(usage.listEffectiveCostsByScopes(7L, List.of(scope))).thenReturn(List.of(outside));
            assertThrows(IllegalStateException.class, () -> service.effectiveCostsForScopes(7L, List.of(scope)));
        }
        assertThrows(IllegalArgumentException.class, () -> service.effectiveCostsForScopes(0, List.of(scope)));
    }

    @Test void usageScopeAndNullableFactsRemainExactAfterTheStorageObjectChanges() {
        var row = new CostEventDO(); row.setEventId("usage"); row.setRootInputId("root"); row.setAttemptId("attempt");
        row.setUsageSource(UsageSource.UNKNOWN.name()); row.setPromptTokens(0L); row.setTokens(null);
        row.setOccurredAt(Instant.EPOCH); row.setMeta(Map.of("trace_id", "trace", "private", "not-public"));
        when(usage.listEffectiveBySessionId(7L, "session")).thenReturn(List.of(row));
        when(usage.listByRootInputId(7L, "root")).thenReturn(List.of(row));

        var snapshot = service.effectiveUsageForSession(7L, "session");
        assertEquals(snapshot, service.usageForRootInput(7L, "root"));
        row.setTokens(99L); row.setPromptTokens(5L); row.setMeta(Map.of());
        assertNull(snapshot.getFirst().tokens()); assertEquals(0L, snapshot.getFirst().promptTokens());
        assertEquals(UsageSource.UNKNOWN.name(), snapshot.getFirst().usageSource());
        assertEquals("attempt", snapshot.getFirst().attemptId()); assertEquals("root", snapshot.getFirst().rootInputId());
        assertEquals("trace", snapshot.getFirst().traceId());
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
        verify(usage).listEffectiveBySessionId(7L, "session"); verify(usage).listByRootInputId(7L, "root");
    }

    @Test void attemptsKeepTenantScopeAndRootInputUsesStableInvocationOrdering() {
        var row = new ExternalInvocationDO(); row.setAttemptId("attempt");
        row.setStatus(ExternalInvocationStatus.UNRESOLVED.name()); row.setStartedAt(Instant.EPOCH);
        when(invocations.selectList(any())).thenAnswer(call -> {
            QueryWrapper<ExternalInvocationDO> query = call.getArgument(0);
            var sql = query.getSqlSegment();
            assertTrue(query.getParamNameValuePairs().containsValue(7L));
            if (sql.contains("root_input_id")) {
                assertTrue(query.getParamNameValuePairs().containsValue("root"));
                assertTrue(sql.indexOf("started_at ASC") < sql.indexOf("id ASC"));
            } else {
                assertTrue(query.getParamNameValuePairs().containsValue("attempt"));
            }
            return List.of(row);
        });
        assertTrue(service.invocationsForAttempts(7L, List.of()).isEmpty());
        verifyNoInteractions(invocations);
        var attempts = service.invocationsForAttempts(7L, List.of("attempt"));
        assertEquals(attempts, service.invocationsForRootInput(7L, "root"));
        row.setStatus(ExternalInvocationStatus.SUCCEEDED.name());
        assertEquals(ExternalInvocationStatus.UNRESOLVED.name(), attempts.getFirst().status());
        assertNull(attempts.getFirst().endedAt());
        verify(invocations, times(2)).selectList(any());
    }
}
