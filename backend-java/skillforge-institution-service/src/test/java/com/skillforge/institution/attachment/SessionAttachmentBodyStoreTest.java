package com.skillforge.institution.attachment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 预算、字节一致性与事务前置条件；真实锁/RLS/并发仍由 PostgreSQL Mapper 测试验证。 */
class SessionAttachmentBodyStoreTest {
    private final SessionAttachmentBodyMapper mapper = mock(SessionAttachmentBodyMapper.class);
    private final SessionAttachmentBodyStore store = new SessionAttachmentBodyStore(mapper, 10, 20);
    private final byte[] bytes = {1, 2, 3};
    private String digest;

    @BeforeEach void setup() throws Exception {
        digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        TenantContext.setTenantId("7");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(mapper.lockTenant("7")).thenReturn("7");
        when(mapper.capacity(anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(new SessionAttachmentBodyMapper.Capacity(0, 0, false));
        when(mapper.insert(eq("7"), eq(digest), eq(3L), any())).thenReturn(1);
    }
    @AfterEach void cleanup() {
        TenantContext.clear();
        TransactionSynchronizationManager.clear();
    }
    @Test void newBodyIsWrittenOnceAndExactExistingBytesSkipInsert() {
        store.register("7", "product", digest, bytes);
        verify(mapper).insert("7", digest, 3, bytes);
        reset(mapper);
        when(mapper.lockTenant("7")).thenReturn("7");
        when(mapper.find("7", digest, 3)).thenReturn(new SessionAttachmentBodyMapper.StoredBody(bytes.clone()));
        when(mapper.capacity("7", "product", digest, 3)).thenReturn(new SessionAttachmentBodyMapper.Capacity(3, 3, true));
        store.register("7", "product", digest, bytes);
        verify(mapper, never()).insert(anyString(), anyString(), anyLong(), any());
    }
    @Test void equalDigestCannotHideDifferentStoredBytesOrCrossTenantIdentity() {
        when(mapper.find("7", digest, 3)).thenReturn(new SessionAttachmentBodyMapper.StoredBody(new byte[]{3, 2, 1}));
        assertThrows(IllegalStateException.class, () -> store.register("7", "product", digest, bytes));
        verify(mapper, never()).insert(anyString(), anyString(), anyLong(), any());
        assertThrows(SecurityException.class, () -> store.register("8", "product", digest, bytes));
        verify(mapper, never()).lockTenant("8");
    }
    @Test void sessionAndTenantBudgetsChargeOnlyNewOwnershipAndDoNotBreakExactReplay() {
        when(mapper.capacity("7", "product", digest, 3)).thenReturn(new SessionAttachmentBodyMapper.Capacity(8, 0, false));
        assertEquals("attachment_session_capacity_exceeded", assertThrows(ApiException.class,
                () -> store.register("7", "product", digest, bytes)).getCode());
        when(mapper.capacity("7", "product", digest, 3)).thenReturn(new SessionAttachmentBodyMapper.Capacity(0, 18, false));
        assertEquals("attachment_tenant_capacity_exceeded", assertThrows(ApiException.class,
                () -> store.register("7", "product", digest, bytes)).getCode());
        when(mapper.find("7", digest, 3)).thenReturn(new SessionAttachmentBodyMapper.StoredBody(bytes));
        when(mapper.capacity("7", "product", digest, 3)).thenReturn(new SessionAttachmentBodyMapper.Capacity(30, 40, true));
        assertDoesNotThrow(() -> store.register("7", "product", digest, bytes));
        verify(mapper, never()).insert(anyString(), anyString(), anyLong(), any());
    }
    @Test void missingBudgetsAreReportedAsUnconfiguredAndKeepExistingAdmissionLimits() {
        var unconfigured = new SessionAttachmentBodyStore(mapper, 0, 0);
        when(mapper.capacity(anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(new SessionAttachmentBodyMapper.Capacity(30, 40, false));
        assertDoesNotThrow(() -> unconfigured.register("7", "product", digest, bytes));
        when(mapper.sessionBytes("7", "product")).thenReturn(30L);
        var observed = unconfigured.inspectSession("7", "product");
        assertEquals("30", observed.sessionBytes());
        assertFalse(observed.sessionConfigured());
    }
    @Test void writesRequireActualWritableTransactionAndExistingTenantMainRow() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThrows(IllegalStateException.class, () -> store.register("7", "product", digest, bytes));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThrows(IllegalStateException.class, () -> store.register("7", "product", digest, bytes));
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        when(mapper.lockTenant("7")).thenReturn(null);
        assertThrows(SecurityException.class, () -> store.register("7", "product", digest, bytes));
        verify(mapper, never()).insert(anyString(), anyString(), anyLong(), any());
    }
}
