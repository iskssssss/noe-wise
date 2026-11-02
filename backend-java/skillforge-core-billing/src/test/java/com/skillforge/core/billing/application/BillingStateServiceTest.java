package com.skillforge.core.billing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.persistence.CostEventMapper;
import com.skillforge.core.billing.BillingStateProcessResponse;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.billing.persistence.PlanDO;
import com.skillforge.core.foundation.application.TenantBillingProfileService;
import com.skillforge.core.foundation.application.TenantBillingProfileService.BillingCandidate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/** 算法测试使用真实Spring事务同步；正式Mapper、RLS和平台回执由数据库组合测试覆盖。 */
class BillingStateServiceTest {
    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test void scansOverridesPlansAndTransitionsWithinTheBusinessMonthAndTenant() {
        var tenants = mock(TenantBillingProfileService.class);
        var plans = mock(PlanMapper.class); var costs = mock(CostEventMapper.class);
        var clock = Clock.fixed(Instant.parse("2026-10-31T18:00:00Z"), ZoneOffset.UTC);
        var service = new BillingStateService(tenants, plans, costs, clock);
        when(tenants.billingCandidates()).thenReturn(List.of(new BillingCandidate(1L, null, 3, "active"),
                new BillingCandidate(2L, 20L, null, "warning"),new BillingCandidate(3L, null, null, "active"),
                new BillingCandidate(4L, 99L, null, "active")));
        when(tenants.transitionBillingState(1L, "active", "warning")).thenAnswer(call -> {
            assertThat(TenantContext.getTenantId()).isEqualTo("1"); return 1;
        });
        when(tenants.transitionBillingState(2L, "warning", "active")).thenAnswer(call -> {
            assertThat(TenantContext.getTenantId()).isEqualTo("2"); return 1;
        });
        var plan = new PlanDO(); plan.setQuotaApiCallsPerMonth(10);
        when(plans.selectById(20L)).thenReturn(plan);
        when(costs.countRequestsForBilling(anyLong(),any(),any())).thenAnswer(call -> {
            assertThat(call.<Long>getArgument(0).toString()).isEqualTo(TenantContext.getTenantId());
            assertThat(call.<Instant>getArgument(1)).isEqualTo(Instant.parse("2026-10-31T16:00:00Z"));
            assertThat(call.<Instant>getArgument(2)).isEqualTo(Instant.parse("2026-11-30T16:00:00Z"));
            return 5L;
        });
        assertThat(scan(service)).isEqualTo(new BillingStateProcessResponse(4, 1, 1));
        assertThat(TenantContext.getTenantId()).isNull();
        verify(costs, times(2)).countRequestsForBilling(anyLong(),any(),any());
    }

    @Test void zeroUsageAndLostCasAreNotCountedAsTransitions() {
        var tenants = mock(TenantBillingProfileService.class); var plans = mock(PlanMapper.class);
        var costs = mock(CostEventMapper.class); var service = new BillingStateService(tenants, plans, costs);
        when(tenants.billingCandidates()).thenReturn(List.of(new BillingCandidate(1L, null, 10, "warning")));
        when(costs.countRequestsForBilling(anyLong(),any(),any())).thenReturn(0L);
        when(tenants.transitionBillingState(1L, "warning", "active")).thenReturn(0);
        assertThat(scan(service)).isEqualTo(new BillingStateProcessResponse(1, 0, 0));
        verify(tenants).transitionBillingState(1L, "warning", "active");
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test void failedUsageReadRestoresGlobalScopeWithoutAttemptingAStateChange() {
        var tenants = mock(TenantBillingProfileService.class); var plans = mock(PlanMapper.class);
        var costs = mock(CostEventMapper.class); var service = new BillingStateService(tenants, plans, costs);
        when(tenants.billingCandidates()).thenReturn(List.of(new BillingCandidate(1L, null, 10, "active")));
        when(costs.countRequestsForBilling(anyLong(),any(),any())).thenThrow(new IllegalStateException("usage unavailable"));
        assertThrows(IllegalStateException.class, () -> scan(service));
        assertThat(TenantContext.getTenantId()).isNull();
        verify(tenants, never()).transitionBillingState(anyLong(), any(), any());
    }

    @Test void tenantCallerCannotStartAGlobalScan() {
        var tenants=mock(TenantBillingProfileService.class);
        var service=new BillingStateService(tenants,mock(PlanMapper.class),mock(CostEventMapper.class));
        TenantContext.setTenantId("7");
        assertThrows(IllegalStateException.class,()->scan(service));
        verifyNoInteractions(tenants);
        assertThat(TenantContext.getTenantId()).isEqualTo("7");
    }

    private static BillingStateProcessResponse scan(BillingStateService service) {
        var manager=new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction(){return new Object();}
            @Override protected void doBegin(Object transaction,TransactionDefinition definition){ }
            @Override protected void doCommit(DefaultTransactionStatus status){ }
            @Override protected void doRollback(DefaultTransactionStatus status){ }
        };
        return new TransactionTemplate(manager).execute(status->service.processStates());
    }
}
