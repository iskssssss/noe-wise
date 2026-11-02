package com.skillforge.core.billing.invocation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skillforge.core.billing.persistence.ExternalInvocationMapper;
import com.skillforge.core.billing.metering.CostRecorder;
import com.skillforge.core.shared.tracing.ControlOperationContext;
import com.skillforge.core.shared.tracing.TraceContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;

/** ExternalInvocation 的开始、因果映射、去重和终态 CAS 契约。 */
class ExternalInvocationLedgerTest {

    private final ExternalInvocationMapper mapper = mock(ExternalInvocationMapper.class);
    private final CostRecorder costs=mock(CostRecorder.class);
    private final ExternalInvocationLedger ledger = new ExternalInvocationLedger(mapper,costs);

    /** 默认成功准入；个别测试显式模拟数据库唯一约束竞争的落败结果。 */
    @BeforeEach void successfulInsert() {when(mapper.insert(any(ExternalInvocationDO.class))).thenReturn(1);}

    @Test
    void startPersistsTraceContextAndStripsSensitiveMetadata() {
        when(mapper.selectOne(any())).thenReturn(null);
        ExternalInvocationRequest request = request(new TraceContext(
                "input-1", "root-1", "trace-1", "span-1", "event-1", "cause-1"),
                Map.of("region", "cn", "api_token", "must-not-persist"));
        Instant now = Instant.parse("2026-08-24T12:00:00Z");

        assertTrue(ledger.start("7", request, now));

        ArgumentCaptor<ExternalInvocationDO> capture = ArgumentCaptor.forClass(ExternalInvocationDO.class);
        verify(mapper).insert(capture.capture());
        ExternalInvocationDO row = capture.getValue();
        assertEquals("root-1", row.getRootInputId());
        assertEquals("event-1", row.getSourceEventId());
        assertEquals(ExternalInvocationStatus.STARTED.name(), row.getStatus());
        assertEquals(Map.of("region", "cn"), row.getMetadata());
        assertEquals(now, row.getStartedAt());
    }

    @Test
    void startRejectsExistingAttemptWithoutCallingProviderOrInsert() {
        when(mapper.selectOne(any())).thenReturn(new ExternalInvocationDO());

        assertFalse(ledger.start("7", request(trace()), Instant.now()));

        verify(mapper, never()).insert(any(ExternalInvocationDO.class));
    }

    @Test
    void startAcceptsControlOperationContext() {
        when(mapper.selectOne(any())).thenReturn(null);
        ControlOperationContext control = new ControlOperationContext(
                "operation-1", "root-operation-1", "trace-1", "span-1", "event-1", null);

        assertTrue(ledger.start("7", request(control), Instant.now()));

        ArgumentCaptor<ExternalInvocationDO> capture = ArgumentCaptor.forClass(ExternalInvocationDO.class);
        verify(mapper).insert(capture.capture());
        assertEquals("operation-1", capture.getValue().getOperationId());
        assertEquals("root-operation-1", capture.getValue().getRootOperationId());
    }

    @Test
    void auxiliaryAdmissionAddsUnknownButRuntimeAdmissionDoesNotDuplicateAudit() {
        CostRecorder costs=mock(CostRecorder.class);
        var actual=new ExternalInvocationLedger(mapper,costs);
        var request=request(trace());
        assertTrue(actual.start("7",request,Instant.now()));
        verify(costs,never()).recordAuxiliaryStarted(any(),any());
        assertTrue(actual.startAuxiliary("7",request,Instant.now()));
        verify(costs).recordAuxiliaryStarted("7",request);
    }

    @Test
    void auxiliaryAdmissionFailurePropagatesBeforeGuardCanSend() {
        CostRecorder costs=mock(CostRecorder.class);
        var actual=new ExternalInvocationLedger(mapper,costs);
        org.mockito.Mockito.doThrow(new IllegalStateException("unknown-fact-write-failed"))
                .when(costs).recordAuxiliaryStarted(any(),any());
        assertThrows(IllegalStateException.class,()->actual.startAuxiliary("7",request(trace()),Instant.now()));
    }

    @Test
    void concurrentDuplicateAdmissionDoesNotWriteUnknownOrThrowDatabaseError() {
        when(mapper.insert(any(ExternalInvocationDO.class))).thenReturn(0);
        assertFalse(ledger.startAuxiliary("7",request(trace()),Instant.now()));
        verify(costs,never()).recordAuxiliaryStarted(any(),any());
    }

    @Test
    void finishOnlyAllowsTerminalStates() {
        when(mapper.update(any(), any())).thenReturn(1);

        assertTrue(ledger.finish("7", "attempt-1", ExternalInvocationStatus.UNRESOLVED,
                null, "EXTERNAL_OUTCOME_UNKNOWN", Instant.now()));
        assertThrows(IllegalArgumentException.class,
                () -> ledger.finish("7", "attempt-1", ExternalInvocationStatus.STARTED,
                        null, null, Instant.now()));
    }

    @Test
    void ledgerHandlesDefaultTimestampsAllTerminalStatesAndBlankIdentifiers() {
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.update(any(), any())).thenReturn(0, 1);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(null, "ignored");
        metadata.put("safe", "kept");

        assertTrue(ledger.start("7", request(trace(), metadata), null));
        assertFalse(ledger.finish("7", "attempt-1", ExternalInvocationStatus.SUCCEEDED,
                "provider-request-1", "", null));
        assertTrue(ledger.finish("7", "attempt-1", ExternalInvocationStatus.FAILED,
                null, "EXTERNAL_CALL_FAILED", null));
        assertThrows(IllegalArgumentException.class,
                () -> ledger.start("", request(trace()), Instant.now()));
    }

    @Test
    void requestRequiresDistinctAttemptIdentityAndContext() {
        assertThrows(IllegalArgumentException.class, () -> new ExternalInvocationRequest(
                "same", "same", ExternalOperationType.MODEL_CHAT, "provider", "model", null,
                trace(), Map.of()));
        assertThrows(NullPointerException.class, () -> new ExternalInvocationRequest(
                "invocation", "attempt", ExternalOperationType.MODEL_CHAT, "provider", "model", null,
                null, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ExternalInvocationRequest(
                "", "attempt", ExternalOperationType.MODEL_CHAT, "provider", "model", null,
                trace(), null));
    }

    private ExternalInvocationRequest request(com.skillforge.core.shared.tracing.InvocationContext context) {
        return request(context, Map.of());
    }

    private ExternalInvocationRequest request(
            com.skillforge.core.shared.tracing.InvocationContext context, Map<String, Object> metadata) {
        return new ExternalInvocationRequest("invocation-1", "attempt-1", ExternalOperationType.MODEL_CHAT,
                "openai", "gpt-test", "idem-1", context, metadata);
    }

    private TraceContext trace() {
        return new TraceContext("input-1", "root-1", "trace-1", "span-1", "event-1", null);
    }
}
