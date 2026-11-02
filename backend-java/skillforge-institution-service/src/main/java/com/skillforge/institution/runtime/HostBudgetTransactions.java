package com.skillforge.institution.runtime;

import java.sql.Connection;
import javax.sql.DataSource;
import org.apache.ibatis.session.TransactionIsolationLevel;
import org.apache.ibatis.transaction.Transaction;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 受理时加入宿主事务，worker 无宿主事务时保持真实 JDBC 原子操作与根锁。 */
final class HostBudgetTransactions implements TransactionFactory {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(HostBudgetTransactions.class);
    private final TransactionFactory local = new JdbcTransactionFactory();
    private final TransactionFactory joined = new SpringManagedTransactionFactory();

    /** 显式连接归其调用方；该分支保持 MyBatis 原有 JDBC 事务语义。 */
    @Override
    public Transaction newTransaction(Connection connection) {
        LOG.debug("创建显式连接预算事务");
        var transaction = local.newTransaction(connection);
        LOG.debug("创建显式连接预算事务完成");
        return transaction;
    }

    /** 只有已绑定的数据源事务可由 Spring 管理；不能忽略 standalone 的 autoCommit=false。 */
    @Override
    public Transaction newTransaction(DataSource source, TransactionIsolationLevel isolation, boolean autoCommit) {
        boolean bound = TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.hasResource(source);
        LOG.debug("创建预算事务 springBound={} autoCommit={} isolation={}", bound, autoCommit, isolation);
        var transaction = (bound ? joined : local).newTransaction(source, isolation, autoCommit);
        LOG.debug("创建预算事务完成 springBound={}", bound);
        return transaction;
    }
}
