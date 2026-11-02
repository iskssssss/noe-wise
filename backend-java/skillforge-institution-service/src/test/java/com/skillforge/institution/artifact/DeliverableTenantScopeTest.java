package com.skillforge.institution.artifact;

import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.common.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 成果后台入口和嵌套预览读取必须在异常路径恢复调用者租户。 */
class DeliverableTenantScopeTest {
    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    void nestedFailureRestoresBothTenantScopes() {
        TenantContext.setTenantId("1");
        try (var outer = DeliverableTenantScope.open("1")) {
            assertEquals("1", TenantContext.getTenantId());
            assertThrows(IllegalStateException.class, () -> {
                try (var inner = DeliverableTenantScope.open("1")) {
                    assertEquals("1", TenantContext.getTenantId());
                    throw new IllegalStateException("failed artifact read");
                }
            });
            assertEquals("1", TenantContext.getTenantId());
        }
        assertEquals("1", TenantContext.getTenantId());
    }

    @Test
    void emptyCallerIsClearedAndInvalidTenantDoesNotChangeContext() {
        try (var scope = DeliverableTenantScope.open("1")) {
            assertThrows(IllegalArgumentException.class, () -> DeliverableTenantScope.open(" "));
            assertThrows(SecurityException.class, () -> DeliverableTenantScope.open("2"));
            assertEquals("1", TenantContext.getTenantId());
        }
        assertNull(TenantContext.getTenantId());
    }
}
