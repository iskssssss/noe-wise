package com.skillforge.core.billing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.persistence.ModelBillingMapper;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.shared.exception.ApiException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** 查询边界与公开精度；SQL聚合、过滤和RLS由真实数据库场景验证。 */
class ModelBillingServiceTest {
    private final ModelBillingMapper mapper = mock(ModelBillingMapper.class);
    private final FoundationAccess access = mock(FoundationAccess.class);
    private ModelBillingService service;
    private String previousTenant;

    @BeforeEach void setup() {
        previousTenant = TenantContext.getTenantId(); TenantContext.setTenantId("11");
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(true, null, "42"));
        service = new ModelBillingService(mapper, access,
                Clock.fixed(Instant.parse("2026-09-30T16:00:00Z"), ZoneOffset.UTC));
        var summary = new ModelBillingMapper.Summary(); summary.setKnownAmountCents(BigDecimal.ZERO);
        when(mapper.summarize(eq(11L), any(), any())).thenReturn(summary);
        when(mapper.list(eq(11L), any(), any(), anyLong(), anyInt())).thenReturn(List.of());
    }

    @AfterEach void identity() {
        if (previousTenant == null) TenantContext.clear(); else TenantContext.setTenantId(previousTenant);
    }

    @Test void defaultMonthAndPaginationUseTheBeijingMonthWithAnExplicitTenant() {
        var result = service.monthly(11L, null, null, null);
        assertThat(result.month()).isEqualTo("2026-10");
        assertThat(result.page()).isEqualTo(1); assertThat(result.pageSize()).isEqualTo(20);
        verify(mapper).summarize(11L, Instant.parse("2026-09-30T16:00:00Z"), Instant.parse("2026-10-31T16:00:00Z"));
        verify(mapper).list(11L, Instant.parse("2026-09-30T16:00:00Z"), Instant.parse("2026-10-31T16:00:00Z"), 0L, 20);
        verify(access).require("foundation.billing.read");
    }

    @Test void preciseCentsAndLargeIdentifiersDoNotBecomeRoundedBillsOrJavascriptNumbers() {
        var summary = new ModelBillingMapper.Summary(); summary.setModelCalls(2);
        summary.setKnownAmountCents(new BigDecimal("9223372036854775808.123456789012"));
        summary.setPromptTokens(new BigDecimal("18446744073709551614"));
        when(mapper.summarize(eq(11L), any(), any())).thenReturn(summary);
        var row = new ModelBillingMapper.Row(); row.setId(9_007_199_254_740_993L); row.setEventType("media.video");
        row.setQuantity(1); row.setImageCount(9_007_199_254_740_993L); row.setVideoCount(2L); row.setCharacters(3L);
        row.setDurationSeconds(new BigDecimal("2.1250000000001")); row.setPromptTokens(55L); row.setCompletionTokens(13L);
        row.setPricingModelId(9_007_199_254_740_994L); row.setPricingModelRevision(3L);
        row.setOriginalAmountCents(new BigDecimal("2.5")); row.setOriginalCurrency("USD");
        row.setAmountCents(new BigDecimal("18.125")); row.setCostStatus("priced");
        when(mapper.list(eq(11L), any(), any(), eq(200L), eq(200))).thenReturn(List.of(row));
        var result = service.monthly(11L, "2026-09", 2, 200);
        assertThat(result.totalAmount()).isEqualTo("92233720368547758.08123456789012");
        assertThat(result.knownAmount()).isEqualTo(result.totalAmount());
        assertThat(result.promptTokens()).isEqualTo("18446744073709551614");
        var line = result.items().getFirst();
        assertThat(line.id()).isEqualTo("9007199254740993"); assertThat(line.quantity()).isEqualTo("1");
        assertThat(line.imageCount()).isEqualTo("9007199254740993");
        assertThat(line.durationSeconds()).isEqualTo("2.1250000000001");
        assertThat(line.pricingModelId()).isEqualTo("9007199254740994");
        assertThat(line.originalAmount()).isEqualTo("0.025"); assertThat(line.originalCurrency()).isEqualTo("USD");
        assertThat(line.amount()).isEqualTo("0.18125");
    }

    @Test void missingCostsKeepTheKnownSubsetAndLeaveTheOverallTotalUnknown() {
        var summary = new ModelBillingMapper.Summary(); summary.setModelCalls(3);
        summary.setKnownAmountCents(new BigDecimal("0.125")); summary.setMissingCostCount(2);
        when(mapper.summarize(eq(11L), any(), any())).thenReturn(summary);
        var result = service.monthly(11L, "2026-09", 1, 20);
        assertThat(result.knownAmount()).isEqualTo("0.00125"); assertThat(result.totalAmount()).isNull();
        assertThat(result.promptTokens()).isNull(); assertThat(result.completionTokens()).isNull();
        assertThat(result.missingCostCount()).isEqualTo(2); assertThat(result.total()).isEqualTo(3);
    }

    @Test void revokedPermissionOrAnotherInstitutionCannotReachTheMapper() {
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "revoked")).when(access).require("foundation.billing.read");
        assertThatThrownBy(() -> service.monthly(11L, null, null, null)).isInstanceOf(ApiException.class);
        verifyNoInteractions(mapper);
    }

    @Test void platformAndInstitutionReadsMustMatchTheirExplicitTenantBinding() {
        assertThatThrownBy(() -> service.monthly(12L, null, null, null)).isInstanceOf(ApiException.class);
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false, "12", "42"));
        assertThatThrownBy(() -> service.monthly(11L, null, null, null)).isInstanceOf(ApiException.class);
        verifyNoInteractions(mapper);
    }

    @Test void invalidMonthAndPageBoundsFailBeforeAnyDatabaseQuery() {
        for (int[] page : List.of(new int[]{0, 20}, new int[]{1, 0}, new int[]{1, 201}))
            assertThatThrownBy(() -> service.monthly(11L, "2026-09", page[0], page[1])).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.monthly(11L, "2026-13", 1, 20)).isInstanceOf(ApiException.class);
        verifyNoInteractions(mapper);
    }
}
