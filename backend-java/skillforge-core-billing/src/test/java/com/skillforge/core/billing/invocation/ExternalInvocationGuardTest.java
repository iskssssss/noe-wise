package com.skillforge.core.billing.invocation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import com.skillforge.core.shared.tracing.TraceContext;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 守卫先登记、唯一执行、并将已知与未知失败映射到不同的可审计终态。 */
class ExternalInvocationGuardTest {

    private final ExternalInvocationLedger ledger = mock(ExternalInvocationLedger.class);
    private final ExternalInvocationGuard guard = new ExternalInvocationGuard(ledger, org.mockito.Mockito.mock(com.skillforge.core.billing.budget.AuxiliaryModelBudgets.class));

    @AfterEach
    void clearInvocationScope() {
        ExternalInvocationScope.clear();
    }

    @Test
    void successStartsBeforeCallAndFinishesSucceeded() throws Exception {
        ExternalInvocationRequest request = request();
        when(ledger.startAuxiliary(eq("7"), eq(request), any())).thenReturn(true);

        String result = guard.invoke("7", request, () -> {
            assertSame(request, ExternalInvocationScope.current());
            return "ok";
        });

        assertEquals("ok", result);
        assertNull(ExternalInvocationScope.current());
        verify(ledger).finish(eq("7"), eq("attempt-1"), eq(ExternalInvocationStatus.SUCCEEDED),
                eq(null), eq(null), any());
    }

    @Test
    void duplicateAttemptDoesNotExecuteExternalCall() {
        ExternalInvocationRequest request = request();
        when(ledger.startAuxiliary(eq("7"), eq(request), any())).thenReturn(false);

        assertThrows(ExternalInvocationGuard.DuplicateAttemptException.class,
                () -> guard.invoke("7", request, () -> { throw new AssertionError("must not execute"); }));

        verify(ledger, never()).finish(any(), any(), any(), any(), any(), any());
    }

    @Test
    void knownFailureRecordsFailedWithStableCode() {
        ExternalInvocationRequest request = request();
        when(ledger.startAuxiliary(eq("7"), eq(request), any())).thenReturn(true);

        assertThrows(IllegalStateException.class,
                () -> guard.invoke("7", request, () -> { throw new IllegalStateException("secret response"); }));

        verify(ledger).finish(eq("7"), eq("attempt-1"), eq(ExternalInvocationStatus.FAILED),
                eq(null), eq("EXTERNAL_CALL_FAILED"), any());
    }

    @Test
    void unknownOutcomeRecordsUnresolvedWithoutFakingFailure() {
        ExternalInvocationRequest request = request();
        when(ledger.startAuxiliary(eq("7"), eq(request), any())).thenReturn(true);

        assertThrows(ExternalInvocationGuard.OutcomeUnknownException.class,
                () -> guard.invoke("7", request, () -> {
                    throw new ExternalInvocationGuard.OutcomeUnknownException("timeout", null);
                }));

        verify(ledger).finish(eq("7"), eq("attempt-1"), eq(ExternalInvocationStatus.UNRESOLVED),
                eq(null), eq("EXTERNAL_OUTCOME_UNKNOWN"), any());
    }

    @Test
    void unknownOutcomeRemainsPrimaryWhenLedgerFinishFails() {
        ExternalInvocationRequest request = request();
        when(ledger.startAuxiliary(eq("7"), eq(request), any())).thenReturn(true);
        var ledgerFailure = new IllegalStateException("ledger unavailable");
        doThrow(ledgerFailure).when(ledger).finish(eq("7"), eq("attempt-1"),
                eq(ExternalInvocationStatus.UNRESOLVED), eq(null), eq("EXTERNAL_OUTCOME_UNKNOWN"), any());
        var unknown = new ExternalInvocationGuard.OutcomeUnknownException("timeout", null);

        assertSame(unknown, assertThrows(ExternalInvocationGuard.OutcomeUnknownException.class,
                () -> guard.invoke("7", request, () -> { throw unknown; })));
        assertSame(ledgerFailure, unknown.getSuppressed()[0]);
    }

    @Test
    void closingAnOuterScopeCannotClearAnActiveNestedAttempt() {
        ExternalInvocationRequest outerRequest = request();
        ExternalInvocationRequest nestedRequest = new ExternalInvocationRequest(
                "invocation-2", "attempt-2", ExternalOperationType.MODEL_EMBEDDING,
                "openai", "embedding-test", "idem-2",
                new TraceContext("input-1", "root-1", "trace-1", "span-1", "event-1", null), Map.of());
        try (ExternalInvocationScope outer = ExternalInvocationScope.open(outerRequest);
             ExternalInvocationScope nested = ExternalInvocationScope.open(nestedRequest)) {
            outer.close();
            assertSame(nestedRequest, ExternalInvocationScope.current());
        }
        assertNull(ExternalInvocationScope.current());
    }

    private ExternalInvocationRequest request() {
        return new ExternalInvocationRequest("invocation-1", "attempt-1", ExternalOperationType.MODEL_CHAT,
                "openai", "gpt-test", "idem-1",
                new TraceContext("input-1", "root-1", "trace-1", "span-1", "event-1", null), Map.of());
    }
}
