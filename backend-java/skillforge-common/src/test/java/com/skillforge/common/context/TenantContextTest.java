package com.skillforge.common.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 租户作用域只绑定当前线程，嵌套调用和异常退出均不得污染外层身份。 */
class TenantContextTest {
    /** 每个用例释放自己绑定的线程身份。 */
    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    /** 新线程式空上下文执行结束后恢复为空，而非留下操作租户。 */
    @Test
    void unboundContextIsClearedAfterReturningTheOriginalResult() {
        Object result = new Object();
        assertSame(result, TenantContext.callWithTenant("7", () -> {
            assertEquals("7", TenantContext.getTenantId());
            return result;
        }));
        assertNull(TenantContext.getTenantId());
    }

    /** 嵌套宿主操作逐层恢复，不能清空外层作用域。 */
    @Test
    void nestedOperationsRestoreBothOuterTenants() {
        TenantContext.setTenantId("5");
        TenantContext.runWithTenant("7", () -> {
            TenantContext.runWithTenant("9", () -> assertEquals("9", TenantContext.getTenantId()));
            assertEquals("7", TenantContext.getTenantId());
        });
        assertEquals("5", TenantContext.getTenantId());
    }

    /** 异常保持原身份与原异常，不将失败转换成成功。 */
    @Test
    void failureRestoresThePreviousContextAndPropagatesUnchanged() {
        TenantContext.setTenantId("5");
        var failure = new IllegalStateException("write_failed");
        assertSame(failure, assertThrows(IllegalStateException.class, () ->
                TenantContext.runWithTenant("7", () -> {
                    assertEquals("7", TenantContext.getTenantId());
                    throw failure;
                })));
        assertEquals("5", TenantContext.getTenantId());
    }

    /** 缺少可信租户时在执行操作之前拒绝，保留外层身份。 */
    @Test
    void missingTenantDoesNotExecuteOrAlterTheContext() {
        TenantContext.setTenantId("5");
        assertThrows(NullPointerException.class, () -> TenantContext.runWithTenant(null, () -> {
            throw new AssertionError("must_not_execute");
        }));
        assertEquals("5", TenantContext.getTenantId());
    }
}
