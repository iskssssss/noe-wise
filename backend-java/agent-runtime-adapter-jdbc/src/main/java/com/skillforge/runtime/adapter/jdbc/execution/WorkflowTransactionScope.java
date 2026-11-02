package com.skillforge.runtime.adapter.jdbc.execution;

import com.skillforge.runtime.api.TenantId;
import java.util.Objects;
import org.apache.ibatis.session.SqlSession;

/** 包内一次性借用事务；租户在协调器中绑定，不允许在嵌套账本调用中改写。 */
final class WorkflowTransactionScope implements AutoCloseable {
    private final TenantId tenant;
    private final ExecutionLedgerMapper mapper;
    private final Thread ownerThread = Thread.currentThread();
    private boolean active = true;

    WorkflowTransactionScope(SqlSession session, TenantId tenant) {
        this.tenant = Objects.requireNonNull(tenant);
        this.mapper = Objects.requireNonNull(session).getMapper(ExecutionLedgerMapper.class);
    }

    ExecutionLedgerMapper mapper(TenantId requestedTenant) {
        if (!active || Thread.currentThread() != ownerThread || !tenant.equals(requestedTenant)) {
            throw new IllegalStateException("workflow_transaction_scope_invalid");
        }
        return mapper;
    }

    @Override public void close() { active = false; }
}
