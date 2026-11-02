package com.skillforge.core.billing.quota;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import com.skillforge.core.foundation.application.TenantBillingProfileService;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.billing.persistence.CostEventMapper;
import com.skillforge.core.billing.persistence.PlanDO;
import com.skillforge.core.billing.metering.CostEventDO;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

/** 配额自然月窗口边界测试。 */
class QuotaServiceTest {

    @Test
    void quotaExhaustionUsesTheSameBeijingWindowAndExposesRetryAfter() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), CostEventDO.class);
        var tenants = mock(TenantBillingProfileService.class);
        var plans = mock(PlanMapper.class);
        var costs = mock(CostEventMapper.class);
        var tenant = new TenantBillingProfileService.QuotaProfile(8L, null);
        var plan = new PlanDO(); plan.setQuotaApiCallsPerMonth(1);
        when(tenants.quotaProfile(7L)).thenReturn(tenant);
        when(plans.selectById(8L)).thenReturn(plan);
        when(costs.selectCount(any())).thenReturn(1L);
        var service = new QuotaService(tenants, plans, costs,
                Clock.fixed(Instant.parse("2026-12-31T15:59:59Z"), ZoneOffset.UTC));
        var failure = assertThrows(QuotaExceededException.class, () -> service.check("7"));
        assertEquals(Instant.parse("2026-12-31T16:00:00Z"), failure.resetsAt());
        assertEquals(1L, failure.retryAfterSeconds());
        var response = new com.skillforge.core.shared.web.HostExceptionHandler(new com.fasterxml.jackson.databind.ObjectMapper()) {}.handle(
                failure, new org.springframework.mock.web.MockHttpServletResponse());
        assertEquals(429, response.getStatusCode().value());
        assertEquals("1", response.getHeaders().getFirst("Retry-After"));
        assertEquals(QuotaExceededException.CODE, response.getBody().code());
    }

    @Test
    void monthStartUsesBeijingMidnightEvenBeforeUtcMonthChanges() {
        assertEquals(Instant.parse("2026-08-31T16:00:00Z"),
                QuotaService.monthStart(Instant.parse("2026-09-01T11:22:33.456Z")));
        assertEquals(Instant.parse("2026-08-31T16:00:00Z"),
                QuotaService.monthStart(Instant.parse("2026-08-31T23:59:59.999Z")));
    }
}
