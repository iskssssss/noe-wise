package com.skillforge.runtime.adapter.jdbc.execution;

import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.runtime.api.TenantId;
import java.lang.reflect.Proxy;
import java.util.concurrent.Executors;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;

/** 借用的原账本事务不能逸出本次调用或改换租户/线程。 */
class WorkflowTransactionScopeTest {
    @Test void scopeRejectsDifferentTenantThreadAndUseAfterClose() throws Exception {
        var mapper = (ExecutionLedgerMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ExecutionLedgerMapper.class}, (proxy, method, args) -> null);
        var session = (SqlSession) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{SqlSession.class}, (proxy, method, args) -> mapper);
        var tenant = new TenantId("tenant");
        var scope = new WorkflowTransactionScope(session, tenant);
        assertSame(mapper, scope.mapper(tenant));
        assertThrows(IllegalStateException.class, () -> scope.mapper(new TenantId("other")));
        try (var worker = Executors.newSingleThreadExecutor()) {
            assertEquals("workflow_transaction_scope_invalid", worker.submit(() ->
                    assertThrows(IllegalStateException.class, () -> scope.mapper(tenant)).getMessage()).get());
        }
        scope.close();
        assertThrows(IllegalStateException.class, () -> scope.mapper(tenant));
    }
}
