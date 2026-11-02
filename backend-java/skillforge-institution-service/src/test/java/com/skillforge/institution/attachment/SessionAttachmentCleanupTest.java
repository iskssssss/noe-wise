package com.skillforge.institution.attachment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.skillforge.common.context.TenantContext;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 清理调度的批次上界、续接和上下文恢复；不通过固定 sleep 验证时序。 */
class SessionAttachmentCleanupTest {
    @AfterEach void cleanup() { TenantContext.clear(); }

    @Test void backlogUsesEightBoundedBatchesThenContinuesFromRemainingRows() {
        var mapper = mock(SessionAttachmentBodyMapper.class);
        var reclamation = mock(SessionAttachmentReclamation.class);
        when(mapper.tenantPage("0", 32)).thenReturn(List.of("7"));
        var busy = result(100, 100, 101);
        when(reclamation.expiredBatch("7")).thenReturn(busy);
        var cleanup = new SessionAttachmentCleanup(mapper, reclamation);
        TenantContext.setTenantId("outer");
        cleanup.clean();
        verify(reclamation, times(8)).expiredBatch("7");
        assertEquals("outer", TenantContext.getTenantId());
        when(reclamation.expiredBatch("7")).thenReturn(result(1, 1, 0));
        cleanup.clean();
        verify(reclamation, times(9)).expiredBatch("7");
        cleanup.clean();
        verify(reclamation, times(9)).expiredBatch("7");
        assertEquals("outer", TenantContext.getTenantId());
    }
    @Test void failedTenantDoesNotStopNextTenantAndIsRevisitedWithoutClearingItsRows() {
        var mapper = mock(SessionAttachmentBodyMapper.class);
        var reclamation = mock(SessionAttachmentReclamation.class);
        when(mapper.tenantPage("0", 32)).thenReturn(List.of("7", "8"));
        when(reclamation.expiredBatch("7")).thenThrow(new IllegalStateException("storage unavailable"));
        when(reclamation.expiredBatch("8")).thenReturn(result(0, 0, 0));
        var cleanup = new SessionAttachmentCleanup(mapper, reclamation);
        cleanup.clean(); cleanup.clean();
        verify(reclamation, times(2)).expiredBatch("7");
        verify(reclamation, times(2)).expiredBatch("8");
        assertNull(TenantContext.getTenantId());
    }
    private static SessionAttachmentReclamation.BatchResult result(int released, int reclaimed, long rows) {
        return new SessionAttachmentReclamation.BatchResult(released, reclaimed,
                new SessionAttachmentBodyMapper.Backlog(rows, rows * 10, rows == 0 ? null : Instant.EPOCH, rows >= 101));
    }
}
