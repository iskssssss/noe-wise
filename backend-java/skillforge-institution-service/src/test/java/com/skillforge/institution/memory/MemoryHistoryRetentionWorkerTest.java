package com.skillforge.institution.memory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.skillforge.common.context.TenantContext;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/** 后台回收使用数据库截止点、实际受影响行数及租户事务；SQL引用保护另由真实PG契约验证。 */
class MemoryHistoryRetentionWorkerTest {
    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    void approvedMinimumAndDatabaseCutoffAreEnforcedAndTenantIsRestored() {
        var mapper = mock(MemoryHistoryRetentionMapper.class);
        var tenants = mock(ActiveMemoryTenantMapper.class);
        var manager = mock(PlatformTransactionManager.class);
        assertThrows(IllegalArgumentException.class, () -> new MemoryHistoryRetentionWorker(mapper, manager, tenants, 29));
        when(tenants.after(0)).thenReturn(List.of("1"));
        var cutoff = Instant.parse("2026-09-01T00:00:00Z");
        when(mapper.cutoff(30)).thenReturn(cutoff);
        var candidates = List.of(new MemoryHistoryRetentionMapper.Revision("retired", 2));
        when(mapper.candidates("1", cutoff, "", 0, 200)).thenAnswer(call -> {
            assertEquals("1", TenantContext.getTenantId());
            return candidates;
        });
        when(mapper.deleteUnprotected("1", cutoff, candidates)).thenReturn(0);
        TenantContext.setTenantId("caller");
        new MemoryHistoryRetentionWorker(mapper, manager, tenants, 30).poll();
        verify(mapper).bindCutoff(cutoff);
        verify(mapper).deleteUnprotected("1", cutoff, candidates);
        assertEquals("caller", TenantContext.getTenantId());
    }
}
