package com.skillforge.institution.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.memory.MemoryGovernanceRequest.Operation;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** 验证原请求身份、时间及独立事务路由；实际FK/RLS由数据库验收覆盖。 */
class MemoryGovernanceRequestsTest {
    private static final String TENANT = "101";
    private static final String FINGERPRINT = "a".repeat(64);
    private static final Instant ACCEPTED = Instant.parse("2026-10-23T01:02:03.123456Z");

    @Test
    void retryAfterRecreationKeepsOriginalDatabaseTimeAndUsesRequiresNew() {
        Fixture fixture = new Fixture();
        TenantContext.callWithTenant("outer-tenant", () -> {
            var first = fixture.store.accept(new MemoryGovernanceRequest(TENANT, "fact-1", 3, Operation.CORRECT, "original-key", FINGERPRINT));
            assertEquals(ACCEPTED, first.acceptedAt());
            var recreated = new MemoryGovernanceRequests(fixture.mapper, fixture.manager);
            var replay = recreated.accept(new MemoryGovernanceRequest(TENANT, "fact-1", 3, Operation.CORRECT, "original-key", FINGERPRINT));
            assertEquals(first, replay);
            assertEquals("outer-tenant", TenantContext.getTenantId());
            return null;
        });
        assertEquals(1, fixture.rows.size());
        assertEquals(2, fixture.begins.get());
    }

    @Test
    void omittedKeyUsesFullFingerprintAndReusesOriginalReceipt() {
        Fixture fixture = new Fixture();
        var first = fixture.store.accept(new MemoryGovernanceRequest(TENANT, "fact-1", 3, Operation.REVIEW, null, FINGERPRINT));
        var retry = fixture.store.accept(new MemoryGovernanceRequest(TENANT, "fact-1", 3, Operation.REVIEW, " ", FINGERPRINT));
        assertEquals("memory-governance:auto:" + FINGERPRINT, first.key());
        assertEquals(first, retry);
        assertEquals(1, fixture.rows.size());
    }

    @Test
    void existingKeyRejectsChangedFingerprintFactRevisionAndOperation() {
        Fixture fixture = new Fixture();
        var first = fixture.store.accept(new MemoryGovernanceRequest(TENANT, "fact-1", 3, Operation.REVIEW, "request-key", FINGERPRINT));
        assertConflict(() -> fixture.store.accept(new MemoryGovernanceRequest(TENANT, "fact-1", 3, Operation.REVIEW, "request-key", "b".repeat(64))));
        assertConflict(() -> fixture.store.accept(new MemoryGovernanceRequest(TENANT, "fact-2", 3, Operation.REVIEW, "request-key", FINGERPRINT)));
        assertConflict(() -> fixture.store.accept(new MemoryGovernanceRequest(TENANT, "fact-1", 4, Operation.REVIEW, "request-key", FINGERPRINT)));
        assertConflict(() -> fixture.store.accept(new MemoryGovernanceRequest(TENANT, "fact-1", 3, Operation.CORRECT, "request-key", FINGERPRINT)));
        assertEquals(first, fixture.store.accept(new MemoryGovernanceRequest(TENANT, "fact-1", 3, Operation.REVIEW, "request-key", FINGERPRINT)));
        assertEquals(1, fixture.rows.size());
    }

    @Test
    void tenantsKeepIndependentKeysAndMissingRevisionDoesNotInventAcceptedTime() {
        Fixture fixture = new Fixture();
        var first = fixture.store.accept(new MemoryGovernanceRequest(TENANT, "fact-1", 3, Operation.REVIEW, "shared-key", FINGERPRINT));
        var other = fixture.store.accept(new MemoryGovernanceRequest("102", "fact-2", 5, Operation.CORRECT, "shared-key", "b".repeat(64)));
        assertEquals(2, fixture.rows.size());
        assertEquals(FINGERPRINT, first.fingerprint());
        assertEquals("b".repeat(64), other.fingerprint());
        fixture.revisionExists = false;
        var failure = assertThrows(ApiException.class, () -> fixture.store.accept(new MemoryGovernanceRequest(TENANT, "missing", 99,
                Operation.CORRECT, "missing-key", FINGERPRINT)));
        assertEquals(HttpStatus.CONFLICT, failure.getStatus());
        assertEquals("memory_governance_revision_missing", failure.getDetail());
        assertEquals(2, fixture.rows.size());
    }

    private static void assertConflict(Runnable request) {
        var failure = assertThrows(ApiException.class, request::run);
        assertEquals(HttpStatus.CONFLICT, failure.getStatus());
        assertEquals("memory_governance_idempotency_conflict", failure.getDetail());
    }

    private static final class Fixture {
        final MemoryGovernanceMapper mapper = mock(MemoryGovernanceMapper.class);
        final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        final Map<String, MemoryGovernanceMapper.GovernanceReceiptRow> rows = new ConcurrentHashMap<>();
        final AtomicInteger begins = new AtomicInteger();
        final MemoryGovernanceRequests store;
        boolean revisionExists = true;

        Fixture() {
            when(manager.getTransaction(any())).thenAnswer(call -> {
                TransactionDefinition definition = call.getArgument(0);
                assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, definition.getPropagationBehavior());
                assertTrue(TenantContext.getTenantId().matches("10[12]"));
                begins.incrementAndGet();
                return new SimpleTransactionStatus();
            });
            when(mapper.insertGovernanceReceipt(any(MemoryGovernanceRequest.class), anyString()))
                    .thenAnswer(call -> {
                        MemoryGovernanceRequest request = call.getArgument(0);
                        String key = call.getArgument(1);
                        assertEquals(request.tenantId(), TenantContext.getTenantId());
                        if (!revisionExists) return 0;
                        var row = new MemoryGovernanceMapper.GovernanceReceiptRow(key, request.factId(),
                                request.expectedRevision(), request.operation(), request.fingerprint(), ACCEPTED.plusSeconds(rows.size()));
                        return rows.putIfAbsent(request.tenantId() + ":" + key, row) == null ? 1 : 0;
                    });
            when(mapper.governanceReceipt(anyString(), anyString())).thenAnswer(call ->
                    rows.get(call.<String>getArgument(0) + ":" + call.<String>getArgument(1)));
            store = new MemoryGovernanceRequests(mapper, manager);
        }
    }
}
