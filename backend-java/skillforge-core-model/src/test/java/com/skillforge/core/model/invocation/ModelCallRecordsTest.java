package com.skillforge.core.model.invocation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.skillforge.common.context.TenantContext;
import com.skillforge.runtime.adapter.model.provider.ModelProvider;
import com.skillforge.runtime.api.TypedPayload;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 读取保持原租户，写入幂等核正文，status不触碰正文读取。 */
class ModelCallRecordsTest {
    private final ModelCallRecordMapper mapper = mock(ModelCallRecordMapper.class);
    private final ModelCallRecords records = new ModelCallRecords(mapper);
    private final ModelCallRecordCodec codec = new ModelCallRecordCodec();
    private final TypedPayload request = codec.request("deepseek", ModelCallRecordCodecTest.actualRequest());
    private final TypedPayload outcome = codec.outcome(new ModelProvider.InvocationResult.Rejected("rejected"));

    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test void statusIsLazyAndRequiresExactCurrentTenant() {
        TenantContext.setTenantId("7");
        var key = new ModelCallRecordReader.Key("invocation", "attempt");
        when(mapper.statuses(7, List.of(key), ModelCallRecordCodec.REQUEST_SCHEMA, ModelCallRecordCodec.OUTCOME_SCHEMA))
                .thenReturn(List.of(new ModelCallRecordMapper.StatusRow("invocation", "attempt", true, false)));
        assertEquals(Map.of(key, new ModelCallRecordStatus(true, false)), records.statuses(7, List.of(key, key)));
        verify(mapper).statuses(7, List.of(key), ModelCallRecordCodec.REQUEST_SCHEMA, ModelCallRecordCodec.OUTCOME_SCHEMA);
        verify(mapper, never()).find(anyLong(), anyString(), anyString());
        assertThrows(SecurityException.class, () -> records.statuses(8, List.of(key)));
        TenantContext.clear();
        assertThrows(SecurityException.class, () -> records.statuses(7, List.of(key)));
    }

    @Test void emptyStatusBatchDoesNotQueryAndMissingOrUnsupportedRecordsKeepTheirAvailability() {
        TenantContext.setTenantId("7");
        assertTrue(records.statuses(7, List.of()).isEmpty());
        verifyNoInteractions(mapper);
        var recorded = new ModelCallRecordReader.Key("call-a", "attempt-a");
        var unsupported = new ModelCallRecordReader.Key("call-b", "attempt-b");
        var missing = new ModelCallRecordReader.Key("call-c", "attempt-c");
        when(mapper.statuses(7, List.of(recorded, unsupported, missing), ModelCallRecordCodec.REQUEST_SCHEMA, ModelCallRecordCodec.OUTCOME_SCHEMA))
                .thenReturn(List.of(new ModelCallRecordMapper.StatusRow("call-b", "attempt-b", false, false),
                        new ModelCallRecordMapper.StatusRow("call-a", "attempt-a", true, true)));
        assertEquals(Map.of(recorded, new ModelCallRecordStatus(true, true), unsupported, new ModelCallRecordStatus(false, false)),
                records.statuses(7, List.of(recorded, unsupported, missing)));
        verify(mapper, never()).find(anyLong(), anyString(), anyString());
    }

    @Test void exactReadPreservesMissingOutcomeAndRejectsWrongReturnedIdentity() {
        TenantContext.setTenantId("7");
        var row = row();
        when(mapper.find(7, "invocation", "attempt")).thenReturn(row);
        var value = records.find(7, "invocation", "attempt").orElseThrow();
        assertEquals(request, value.request());
        assertTrue(value.outcome().isEmpty());
        assertEquals("ModelCallRecord[REDACTED]", value.toString());
        row.invocationId = "other";
        assertThrows(IllegalStateException.class, () -> records.find(7, "invocation", "attempt"));
        assertThrows(SecurityException.class, () -> records.find(8, "invocation", "attempt"));
    }

    @Test void sameRequestIsIdempotentButDifferentMaterialOrAbsentExternalAttemptIsRejected() {
        TenantContext.setTenantId("7");
        when(mapper.find(7, "invocation", "attempt")).thenReturn(row());
        records.recordRequest(7, "invocation", "attempt", request);
        var different = codec.request("another-provider", ModelCallRecordCodecTest.actualRequest());
        assertThrows(IllegalStateException.class, () -> records.recordRequest(7, "invocation", "attempt", different));
        assertThrows(IllegalStateException.class, () -> records.recordRequest(7, "absent", "attempt", request));
    }

    @Test void outcomeOnlyAcceptsTheOriginalSavedReturn() {
        TenantContext.setTenantId("7");
        var row = row(); row.outcomeSchema = outcome.schemaId(); row.outcomeJson = outcome.canonicalJson();
        when(mapper.find(7, "invocation", "attempt")).thenReturn(row);
        records.recordOutcome(7, "invocation", "attempt", outcome);
        var different = codec.outcome(new ModelProvider.InvocationResult.OutcomeUnknown("unknown"));
        assertThrows(IllegalStateException.class, () -> records.recordOutcome(7, "invocation", "attempt", different));
        row.outcomeJson = null;
        assertThrows(IllegalStateException.class, () -> records.find(7, "invocation", "attempt"));
    }

    @Test void invalidIdentityAndWrongWriteTenantNeverReachTheMapper() {
        TenantContext.setTenantId("7");
        assertThrows(SecurityException.class, () -> records.recordRequest(8, "invocation", "attempt", request));
        assertThrows(IllegalArgumentException.class, () -> records.recordRequest(7, "same", "same", request));
        assertThrows(IllegalArgumentException.class, () -> records.statuses(7, List.of(new ModelCallRecordReader.Key("bad\nidentity", "attempt"))));
        verifyNoInteractions(mapper);
    }

    private ModelCallRecordMapper.Row row() {
        var row = new ModelCallRecordMapper.Row(); row.tenantId = 7; row.invocationId = "invocation"; row.attemptId = "attempt";
        row.requestSchema = request.schemaId(); row.requestJson = request.canonicalJson(); return row;
    }
}
