package com.skillforge.core.billing.metering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skillforge.core.billing.pricing.PriceCalculator;
import com.skillforge.core.billing.persistence.CostEventMapper;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CostRecorderReconciliationTest {

    @Test
    void appendsACompensationWithoutMutatingTheOriginalEvent() {
        CostEventMapper mapper = mock(CostEventMapper.class);
        CostRecorder recorder = new CostRecorder(mapper, mock(PriceCalculator.class));
        CostEventDO original = original();
        when(mapper.selectOne(any())).thenReturn(original, null);
        when(mapper.insert(any(CostEventDO.class))).thenReturn(1);
        UsageAdjustment adjustment = UsageAdjustment.adjustment(
                "adjustment-1", 101L, AmountStatus.PRICED, new BigDecimal("-1.25"), "CNY");

        CostEventDO appended = recorder.recordAdjustment("7", adjustment);

        ArgumentCaptor<CostEventDO> captor = ArgumentCaptor.forClass(CostEventDO.class);
        verify(mapper).insert(captor.capture());
        CostEventDO event = captor.getValue();
        assertEquals(appended, event);
        assertEquals("adjustment", event.getUsageEventKind());
        assertEquals("priced", event.getAmountStatus());
        assertEquals(101L, event.getAdjustmentOfEventId());
        assertEquals(new BigDecimal("-1.25"), event.getTotalAmount());
        assertEquals(-1L, event.getTotalAmountCents());
        assertEquals(101L, original.getId());
        assertEquals("price-v1", original.getPricingSnapshotVersion());
        assertEquals("tenant",event.getSourceOwnerKind());assertEquals(9L,event.getSourceOwnerTenantId());
        assertEquals(20L,event.getAgentDefinitionId());assertEquals("1.2.0",event.getAgentDefinitionVersion());
        assertEquals(50L,event.getAgentRevisionId());assertEquals(3,event.getAgentRevision());
        assertEquals(4L,event.getDeploymentRevision());assertEquals(80L,event.getGrantId());
        assertEquals(2L,event.getGrantRevision());assertEquals("call",event.getMeasurementUnit());
    }

    @Test
    void repeatsTheSameFactIdempotentlyButRejectsDifferentFactUnderTheSameKey() {
        CostEventMapper mapper = mock(CostEventMapper.class);
        CostRecorder recorder = new CostRecorder(mapper, mock(PriceCalculator.class));
        CostEventDO original = original();
        CostEventDO existing = new CostEventDO();
        existing.setAdjustmentOfEventId(101L);
        existing.setUsageEventKind("adjustment");
        existing.setAmountStatus("priced");
        existing.setTotalAmount(new BigDecimal("-1.25"));
        existing.setCurrency("CNY");
        when(mapper.selectOne(any())).thenReturn(original, existing);
        UsageAdjustment adjustment = UsageAdjustment.adjustment(
                "adjustment-1", 101L, AmountStatus.PRICED, new BigDecimal("-1.2500"), "CNY");

        assertEquals(existing, recorder.recordAdjustment("7", adjustment));
        verify(mapper, never()).insert(any(CostEventDO.class));

        CostEventDO conflict = new CostEventDO();
        conflict.setAdjustmentOfEventId(101L);
        conflict.setUsageEventKind("adjustment");
        conflict.setAmountStatus("priced");
        conflict.setTotalAmount(new BigDecimal("-2"));
        conflict.setCurrency("CNY");
        when(mapper.selectOne(any())).thenReturn(original, conflict);
        assertThrows(IllegalArgumentException.class, () -> recorder.recordAdjustment("7", adjustment));
    }

    @Test
    void refusesMissingOrCrossTenantOriginalBeforeWriting() {
        CostEventMapper mapper = mock(CostEventMapper.class);
        CostRecorder recorder = new CostRecorder(mapper, mock(PriceCalculator.class));
        when(mapper.selectOne(any())).thenReturn(null);
        UsageAdjustment adjustment = UsageAdjustment.adjustment(
                "adjustment-1", 101L, AmountStatus.UNRESOLVED, null, null);

        assertThrows(IllegalArgumentException.class, () -> recorder.recordAdjustment("7", adjustment));
        verify(mapper, never()).insert(any(CostEventDO.class));
    }

    private static CostEventDO original() {
        CostEventDO event = new CostEventDO();
        event.setId(101L);
        event.setTenantId(7L);
        event.setEventType("llm.chat");
        event.setProvider("openai_compatible");
        event.setModel("model-1");
        event.setQuantity(1);
        event.setMeasurementUnit("call");event.setSourceOwnerKind("tenant");event.setSourceOwnerTenantId(9L);
        event.setAgentDefinitionId(20L);event.setAgentDefinitionVersion("1.2.0");event.setAgentRevisionId(50L);
        event.setAgentRevision(3);event.setDeploymentId(90L);event.setDeploymentRevision(4L);
        event.setGrantId(80L);event.setGrantRevision(2L);
        event.setCurrency("CNY");
        event.setPricingSnapshotVersion("price-v1");
        event.setRequestId("root-1");
        event.setSessionId("session-1");
        return event;
    }

    @Test void unchangedNewBillObservationDoesNotInsertButRealCorrectionDoes() {
        CostEventMapper mapper = mock(CostEventMapper.class);
        CostRecorder recorder = new CostRecorder(mapper, mock(PriceCalculator.class));
        CostEventDO latest = new CostEventDO(); latest.setId(102L); latest.setAmountStatus("priced");
        latest.setCurrency("CNY"); latest.setTotalAmount(new BigDecimal("0.00"));
        when(mapper.latestReconciliation(7L, 101L)).thenReturn(latest);
        when(mapper.selectOne(any())).thenReturn(original(), null);
        var unchanged = new UsageAdjustment("new-bill-key", 101L, UsageEventKind.RECONCILIATION,
                AmountStatus.PRICED, null, null, BigDecimal.ZERO, null, null, "CNY", Map.of());
        assertEquals(latest, recorder.recordAdjustment("7", unchanged));
        verify(mapper, never()).insert(any(CostEventDO.class));
        verify(mapper).lockReconciliation(7L, 101L);

        when(mapper.selectOne(any())).thenReturn(original(), null);
        when(mapper.insert(any(CostEventDO.class))).thenReturn(1);
        var corrected = new UsageAdjustment("corrected-bill-key", 101L, UsageEventKind.RECONCILIATION,
                AmountStatus.PRICED, null, null, BigDecimal.ONE, null, null, "CNY", Map.of());
        assertEquals(BigDecimal.ONE, recorder.recordAdjustment("7", corrected).getTotalAmount());
        verify(mapper).insert(any(CostEventDO.class));
    }
}
