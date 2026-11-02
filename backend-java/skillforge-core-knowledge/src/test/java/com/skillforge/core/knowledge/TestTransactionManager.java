package com.skillforge.core.knowledge;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/** 仅用于 Mapper 单元夹具，执行正式 TransactionTemplate 的提交/回滚同步生命周期。 */
final class TestTransactionManager extends AbstractPlatformTransactionManager {
    private final ThreadLocal<Boolean> active = ThreadLocal.withInitial(() -> false);
    @Override protected Object doGetTransaction() { return new Object(); }
    @Override protected boolean isExistingTransaction(Object transaction) { return active.get(); }
    @Override protected void doBegin(Object transaction, TransactionDefinition definition) { active.set(true); }
    @Override protected void doCommit(DefaultTransactionStatus status) { }
    @Override protected void doRollback(DefaultTransactionStatus status) { }
    @Override protected Object doSuspend(Object transaction) { boolean previous = active.get(); active.set(false); return previous; }
    @Override protected void doResume(Object transaction, Object resources) { active.set((Boolean) resources); }
    @Override protected void doCleanupAfterCompletion(Object transaction) { active.remove(); }
}
