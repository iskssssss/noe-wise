package com.skillforge.platform.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.skillforge.core.billing.BillingStateProcessResponse;
import com.skillforge.core.billing.application.BillingStateService;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 固定扫描统计直接写入原回执；重放不重新扫描，提交失败不得返回成功回执。 */
class PlatformBillingScanServiceTest {
    private static final long ACTOR = 7;
    private static final String OPERATION = "b73ea3c1-3712-49fc-a496-f4a4e66a1974";
    private final BillingScanMapper mapper = mock(BillingScanMapper.class);
    private final BillingStateService billing = mock(BillingStateService.class);
    private final PlatformBillingScanService service = new PlatformBillingScanService(mapper, billing);

    @Test
    void firstClaimWritesExactTypedCountsAndReturnsCommittedReceipt() {
        var receipt = receipt();
        when(mapper.claim(ACTOR, OPERATION)).thenReturn(1);
        when(billing.processStates()).thenReturn(new BillingStateProcessResponse(5, 2, 1));
        when(mapper.complete(ACTOR, OPERATION, 5, 2, 1)).thenReturn(1);
        when(mapper.find(ACTOR, OPERATION)).thenReturn(receipt);

        assertEquals(receipt, service.process(ACTOR, OPERATION));

        verify(billing).processStates();
        verify(mapper).complete(ACTOR, OPERATION, 5, 2, 1);
        verify(mapper).find(ACTOR, OPERATION);
    }

    @Test
    void replayReturnsOriginalReceiptWithoutRescanning() {
        var receipt = receipt();
        when(mapper.claim(ACTOR, OPERATION)).thenReturn(0);
        when(mapper.find(ACTOR, OPERATION)).thenReturn(receipt);

        assertEquals(receipt, service.process(ACTOR, OPERATION));

        verifyNoInteractions(billing);
        verify(mapper, never()).complete(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void incompleteReceiptWriteKeepsFailureBoundary() {
        when(mapper.claim(ACTOR, OPERATION)).thenReturn(1);
        when(billing.processStates()).thenReturn(new BillingStateProcessResponse(5, 2, 1));
        when(mapper.complete(ACTOR, OPERATION, 5, 2, 1)).thenReturn(0);

        var failure = assertThrows(IllegalStateException.class, () -> service.process(ACTOR, OPERATION));

        assertEquals("扫描回执提交失败", failure.getMessage());
        verify(mapper, never()).find(ACTOR, OPERATION);
    }

    private static BillingScanReceipt receipt() {
        return new BillingScanReceipt(OPERATION, 5, 2, 1, Instant.parse("2026-10-03T01:02:03Z"));
    }
}
