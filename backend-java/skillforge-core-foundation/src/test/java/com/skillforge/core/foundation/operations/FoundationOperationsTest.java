package com.skillforge.core.foundation.operations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.operations.OperationsContracts.AuditQuery;
import com.skillforge.core.foundation.operations.OperationsContracts.CleanupRun;
import com.skillforge.core.foundation.operations.persistence.OperationsMapper;
import com.skillforge.core.foundation.operations.persistence.OperationsRows;
import com.skillforge.core.foundation.persistence.AuditLogMapper;
import com.skillforge.core.foundation.persistence.AuditLogDO;
import com.skillforge.core.foundation.application.AuditService;
import com.skillforge.core.shared.exception.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** 验证公开授权入口、冻结预览和秘密过滤；真实RLS/触发器另由隔离PostgreSQL演练覆盖。 */
class FoundationOperationsTest {
    private final FoundationAccess access = mock(FoundationAccess.class);
    private final OperationsMapper mapper = mock(OperationsMapper.class);
    private final AuditService audit = mock(AuditService.class);
    private final FoundationAccess.Subject subject = new FoundationAccess.Subject(false, "7", "42");
    /** 清理测试线程身份，避免后续测试借用本例租户。 */
    @AfterEach void clear() { TenantContext.clear(); }

    /** 无租户平台事实不会再静默丢失，payload秘密递归遮盖。 */
    @Test void platformEventIsRecordedAndSecretsAreRedacted() {
        AuditLogMapper sink = mock(AuditLogMapper.class); when(sink.insert(any(AuditLogDO.class))).thenReturn(1);
        new AuditService(sink).record(null, "42", "auth.login", "platform_account", "42", "登录成功",
                Map.of("password", "do-not-store", "nested", Map.of("access_token", "do-not-store", "result", "ok")));
        ArgumentCaptor<AuditLogDO> record = ArgumentCaptor.forClass(AuditLogDO.class); verify(sink).insert(record.capture());
        assertNull(record.getValue().getTenantId()); assertEquals("authentication", record.getValue().getCategory());
        assertEquals("[REDACTED]", record.getValue().getPayload().get("password"));
        assertFalse(record.getValue().getPayload().toString().contains("do-not-store"));
    }

    /** 拒绝事实的独立事务不能被登录事务回滚吞掉。 */
    @Test void rejectionUsesIndependentTransactionAndRejectsSuccessOutcome() throws Exception {
        var method = AuditService.class.getMethod("recordDeniedSecurity", String.class, String.class, String.class,
                String.class, String.class, String.class, String.class, Map.class);
        assertEquals(Propagation.REQUIRES_NEW, method.getAnnotation(Transactional.class).propagation());
        AuditLogMapper sink = mock(AuditLogMapper.class);
        assertThrows(IllegalArgumentException.class, () -> new AuditService(sink).recordDeniedSecurity(null, null,
                "auth.login", "account", null, "success", "非法拒绝事实", Map.of())); verifyNoInteractions(sink);
    }

    /** 权限拒绝发生在查询前，结果范围绑定可信主体。 */
    @Test void auditReadRejectsBeforeMapperAndBindsSubject() {
        FoundationAuditService service = new FoundationAuditService(access, mapper, audit);
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "拒绝")).when(access).require(FoundationAuditService.READ);
        assertThrows(ApiException.class, () -> service.query(null)); verifyNoInteractions(mapper);
        reset(access); when(access.currentSubject()).thenReturn(subject);
        when(access.currentSnapshot()).thenReturn(new FoundationAccess.Snapshot(List.of(), List.of(FoundationAuditService.READ), 3));
        when(mapper.insertReadBatch(any())).thenReturn(1);
        service.query(null);
        verify(mapper).audits(eq("7"), any(AuditQuery.class), eq(50), eq(0L));
        verify(audit).recordReadBatch(anyLong(), eq("7"), eq("42"), matches("[0-9a-f]{64}"));
    }

    /** 同范围分页刷新只保留首次安全事实；每次查询仍重新校验当前权限。 */
    @Test void auditRefreshDoesNotAppendRepeatedFacts() {
        when(access.currentSubject()).thenReturn(subject);
        when(access.currentSnapshot()).thenReturn(new FoundationAccess.Snapshot(List.of(), List.of(FoundationAuditService.READ), 3));
        when(mapper.insertReadBatch(any())).thenReturn(1, 0, 0);
        FoundationAuditService service = new FoundationAuditService(access, mapper, audit);
        for (int page = 1; page <= 3; page++) service.query(new AuditQuery(null,null,null,null,null,null,null,null,page,50));
        verify(access, times(3)).require(FoundationAuditService.READ);
        verify(audit, times(1)).recordReadBatch(anyLong(), eq("7"), eq("42"), anyString());
        verify(audit, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    /** 初次检查后再次读到撤权投影时，查询及读取批次不得继续写入。 */
    @Test void authorizationSnapshotRevocationRejectsBeforeReadOrBatch() {
        when(access.currentSubject()).thenReturn(subject);
        when(access.currentSnapshot()).thenReturn(new FoundationAccess.Snapshot(List.of(), List.of(), 4));
        assertThrows(ApiException.class, () -> new FoundationAuditService(access, mapper, audit).query(null));
        verifyNoInteractions(mapper, audit);
    }

    @Test void summaryReadSharesAuthorizationBatchAndPreservesExactPageWithoutFullPayloadRead() {
        when(access.currentSubject()).thenReturn(subject);
        when(access.currentSnapshot()).thenReturn(new FoundationAccess.Snapshot(List.of(), List.of(FoundationAuditService.READ), 3));
        when(mapper.insertReadBatch(any())).thenReturn(1, 0);
        when(mapper.auditCount(eq("7"), any())).thenReturn(137L);
        var row = new com.skillforge.core.foundation.operations.persistence.AuditSummaryRow(9007199254740993L,
                null, "auth.login", "authentication", "failure", null, "说明", null, Instant.parse("2026-10-29T12:00:00Z"));
        when(mapper.auditSummaries(eq("7"), any(), eq(50), eq(50L))).thenReturn(List.of(row));
        var service = new FoundationAuditService(access, mapper, audit);
        var query = new AuditQuery("auth.login", null, null, null, null, null, null, null, 2, 50);
        var result = service.summaries(query);
        assertEquals(137L, result.total()); assertEquals(2, result.page()); assertEquals(50, result.pageSize());
        assertEquals("9007199254740993", result.items().getFirst().id());
        assertNull(result.items().getFirst().actorId());
        verify(mapper, never()).audits(any(), any(), anyInt(), anyLong());
        service.query(query);
        var batches = ArgumentCaptor.forClass(AuditReadBatch.class);
        verify(mapper, times(2)).insertReadBatch(batches.capture());
        assertEquals(batches.getAllValues().get(0).queryDigest(), batches.getAllValues().get(1).queryDigest());
        assertEquals(3, batches.getValue().authorizationVersion());
        verify(audit, times(1)).recordReadBatch(anyLong(), eq("7"), eq("42"), anyString());
        when(access.currentSnapshot()).thenReturn(new FoundationAccess.Snapshot(List.of(), List.of(), 4));
        clearInvocations(mapper, audit);
        assertThrows(ApiException.class, () -> service.summaries(query));
        verifyNoInteractions(mapper, audit);
    }

    @Test void summaryReadRejectsPermissionBeforeAnyDatabaseWork() {
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "拒绝")).when(access).require(FoundationAuditService.READ);
        assertThrows(ApiException.class, () -> new FoundationAuditService(access, mapper, audit).summaries(null));
        verifyNoInteractions(mapper, audit);
    }

    /** 同步导出超限失败，公式前缀和引号被转义。 */
    @Test void exportRejectsOverflowAndEscapesSpreadsheetFormula() {
        when(access.currentSubject()).thenReturn(subject);
        FoundationAuditService service = new FoundationAuditService(access, mapper, audit);
        when(mapper.auditCount(eq("7"), any())).thenReturn(10001L);
        assertThrows(ApiException.class, () -> service.export(null)); verify(mapper, never()).auditExportRows(any(), any(), anyInt());
        when(mapper.auditCount(eq("7"), any())).thenReturn(1L);
        var row = new com.skillforge.core.foundation.operations.persistence.AuditExportRow(
                1L, 7L, null, "auth.login", "authentication", "failure", null, null,
                " =HYPERLINK(\"host\")", null, Instant.now());
        when(mapper.auditExportRows(eq("7"), any(), eq(10001))).thenReturn(List.of(row));
        String csv = new String(service.export(null).content(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csv.contains("' =HYPERLINK(\"\"host\"\")"));
        verify(mapper, never()).audits(any(), any(), anyInt(), anyLong());
    }

    /** 计数后增加的第10001行仍必须拒绝，不能截断为看似完整的导出。 */
    @Test void exportRejectsRowsThatGrowAfterCountWithoutRecordingSuccess() {
        when(access.currentSubject()).thenReturn(subject);
        when(mapper.auditCount(eq("7"), any())).thenReturn(10000L);
        var row = new com.skillforge.core.foundation.operations.persistence.AuditExportRow(
                1L, 7L, "42", "operation", "operation", "success", null, null, "", null, Instant.now());
        when(mapper.auditExportRows(eq("7"), any(), eq(10001)))
                .thenReturn(java.util.Collections.nCopies(10001, row));
        var failure = assertThrows(ApiException.class, () -> new FoundationAuditService(access, mapper, audit).export(null));
        assertEquals(HttpStatus.CONFLICT, failure.getStatus());
        verifyNoInteractions(audit);
    }

    /** 导出权限拒绝发生在任何计数或数据读取前。 */
    @Test void deniedExportDoesNotReadAuditRows() {
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "denied")).when(access).require(FoundationAuditService.EXPORT);
        assertThrows(ApiException.class, () -> new FoundationAuditService(access, mapper, audit).export(null));
        verifyNoInteractions(mapper, audit);
    }

    /** 拒绝分页和时间错误，而不是静默夹紧。 */
    @Test void invalidQueriesDoNotBecomeValidDefaults() {
        assertThrows(ApiException.class, () -> FoundationAuditService.validate(new AuditQuery(null,null,null,null,null,null,null,null,0,100)));
        assertThrows(ApiException.class, () -> FoundationAuditService.validate(new AuditQuery(null,null,null,null,null,null,null,null,1,101)));
        assertThrows(ApiException.class, () -> FoundationAuditService.validate(new AuditQuery(null,null,"unknown",null,null,null,null,null,1,20)));
    }

    /** 已提交回执幂等回读，旧策略预览在删除前拒绝。 */
    @Test void cleanupReceiptIsIdempotentAndPolicyChangePreventsDeletion() {
        when(access.currentSubject()).thenReturn(subject);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        FoundationRetentionService service = new FoundationRetentionService(access, mapper, audit, new ObjectMapper(), manager);
        OperationsRows.Run run = new OperationsRows.Run(); run.setId(99L); run.setScope("7"); run.setStatus("succeeded");
        run.setResource("audit"); run.setDeletedCount(2L);
        when(mapper.lockRun("7", 99L)).thenReturn(run);
        assertEquals(2L, service.execute("99").deletedCount()); verify(mapper, never()).deleteAudits(any(), anyList(), any());
        run.setStatus("ready"); run.setExpiresAt(Instant.now().plusSeconds(300)); run.setPolicyVersion(1);
        OperationsRows.Policy policy = new OperationsRows.Policy(); policy.setEnabled(true); policy.setVersion(2);
        when(mapper.policy("7", "audit")).thenReturn(policy);
        assertEquals("operations.retention.policy_changed", assertThrows(ApiException.class, () -> service.execute("99")).getCode());
        verify(mapper, never()).deleteAudits(any(), anyList(), any());
    }

    /** 首次成功和重放逐字段相等，12个冻结候选仅删除未保护的9个。 */
    @Test void cleanupSuccessReturnsCompleteAuthoritativeReceiptAndReplaysExactly() {
        when(access.currentSubject()).thenReturn(subject);
        PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(call->new SimpleTransactionStatus());
        FoundationRetentionService service=new FoundationRetentionService(access,mapper,audit,new ObjectMapper(),manager);
        Instant expiresAt=Instant.now().plusSeconds(300);
        Instant completedAt=Instant.parse("2026-10-13T00:10:00.234567Z");
        OperationsRows.Run ready=cleanupRun("ready",0,expiresAt,null);
        OperationsRows.Run completed=cleanupRun("succeeded",9,expiresAt,completedAt);
        when(mapper.lockRun("7",99L)).thenReturn(ready,completed,completed);
        OperationsRows.Policy policy=new OperationsRows.Policy();policy.setEnabled(true);policy.setVersion(1);
        when(mapper.policy("7","audit")).thenReturn(policy);
        List<Long> frozen=List.of(1L,2L,3L,4L,5L,6L,7L,8L,9L,10L,11L,12L);
        when(mapper.protectedCount("7",frozen)).thenReturn(3L);
        when(mapper.deleteAudits("7",frozen,ready.getCutoff())).thenReturn(9);
        when(mapper.finishRun("7",99L,"succeeded",9,3,null)).thenReturn(1);

        CleanupRun expected=new CleanupRun("99","audit",ready.getCutoff(),12,3,9,"succeeded",
                expiresAt,ready.getCreatedAt(),completedAt,null);
        CleanupRun first=service.execute("99");
        assertEquals(expected,first);
        assertEquals(first,service.execute("99"));
        verify(mapper).deleteAudits("7",frozen,ready.getCutoff());
        verify(mapper).finishRun("7",99L,"succeeded",9,3,null);
        for(long candidate:frozen)verify(mapper).lockAudit("7",candidate);
    }

    /** 到期回执也使用数据库完整终态，首次和重放精确一致且不触发删除。 */
    @Test void expiredCleanupReturnsCompleteAuthoritativeReceiptWithoutDeletion() {
        when(access.currentSubject()).thenReturn(subject);
        PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(call->new SimpleTransactionStatus());
        FoundationRetentionService service=new FoundationRetentionService(access,mapper,audit,new ObjectMapper(),manager);
        Instant expiresAt=Instant.parse("2020-01-01T00:15:00.123456Z");
        Instant completedAt=Instant.parse("2026-10-13T00:10:00.234567Z");
        OperationsRows.Run ready=cleanupRun("ready",0,expiresAt,null);
        OperationsRows.Run completed=cleanupRun("expired",0,expiresAt,completedAt);
        when(mapper.lockRun("7",99L)).thenReturn(ready,completed,completed);
        when(mapper.finishRun("7",99L,"expired",0,3,null)).thenReturn(1);

        CleanupRun expected=new CleanupRun("99","audit",ready.getCutoff(),12,3,0,"expired",
                expiresAt,ready.getCreatedAt(),completedAt,null);
        CleanupRun first=service.execute("99");
        assertEquals(expected,first);
        assertEquals(first,service.execute("99"));
        verify(mapper).finishRun("7",99L,"expired",0,3,null);
        verify(mapper,never()).deleteAudits(any(),anyList(),any());
        verifyNoInteractions(audit);
    }

    /** 完整持久回执fixture保留微秒、冻结集合及全部公开字段。 */
    private static OperationsRows.Run cleanupRun(String status,long deleted,Instant expiresAt,Instant completedAt) {
        OperationsRows.Run run=new OperationsRows.Run();run.setId(99L);run.setScope("7");run.setActorId("42");
        run.setResource("audit");run.setPolicyVersion(1);
        run.setCutoff(Instant.parse("2026-09-01T08:00:00.654321Z"));
        run.setCandidateIds("[1,2,3,4,5,6,7,8,9,10,11,12]");run.setCandidates(12);run.setProtectedCount(3);
        run.setDeletedCount(deleted);run.setStatus(status);run.setExpiresAt(expiresAt);
        run.setCreatedAt(Instant.parse("2026-10-01T08:00:00.123456Z"));run.setCompletedAt(completedAt);
        return run;
    }

    /** 即使上游有事务，失败删除也先回滚独立清理事务，再提交失败回执。 */
    @Test void cleanupFailureRollsBackBeforeIndependentFailureReceipt() {
        when(access.currentSubject()).thenReturn(subject);
        PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(call->new SimpleTransactionStatus());
        FoundationRetentionService service=new FoundationRetentionService(access,mapper,audit,new ObjectMapper(),manager);
        OperationsRows.Run run=new OperationsRows.Run();run.setId(99L);run.setScope("7");run.setStatus("ready");
        run.setCandidateIds("[1,2]");run.setCutoff(Instant.now().minusSeconds(200L*86400));run.setExpiresAt(Instant.now().plusSeconds(300));
        when(mapper.lockRun("7",99L)).thenReturn(run);
        OperationsRows.Policy policy=new OperationsRows.Policy();policy.setEnabled(true);policy.setVersion(0);
        when(mapper.policy("7","audit")).thenReturn(policy);
        when(mapper.deleteAudits(eq("7"),eq(List.of(1L,2L)),any())).thenThrow(new IllegalStateException("fixture database failure"));
        assertThrows(ApiException.class,()->service.execute("99"));
        var order=inOrder(manager,mapper);
        order.verify(manager).getTransaction(any());order.verify(mapper).deleteAudits(eq("7"),eq(List.of(1L,2L)),any());
        order.verify(manager).rollback(any());order.verify(manager).getTransaction(any());
        order.verify(mapper).finishRun("7",99L,"failed",0,-1,"cleanup_database_failure");order.verify(manager).commit(any());
        ArgumentCaptor<TransactionDefinition> definitions=ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(manager,times(2)).getTransaction(definitions.capture());
        assertTrue(definitions.getAllValues().stream().allMatch(value->value.getPropagationBehavior()==TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        verifyNoInteractions(audit);
    }

    /** 跨租户日志写入不能污染既有线程身份。 */
    @Test void auditMismatchDoesNotDispatchAndRestoresContext() {
        TenantContext.setTenantId("7"); AuditLogMapper sink = mock(AuditLogMapper.class);
        assertThrows(IllegalArgumentException.class, () -> new AuditService(sink).record("8", "42", "permission.change", "role", "1", "变更", Map.of()));
        verifyNoInteractions(sink); assertEquals("7", TenantContext.getTenantId());
    }
}
