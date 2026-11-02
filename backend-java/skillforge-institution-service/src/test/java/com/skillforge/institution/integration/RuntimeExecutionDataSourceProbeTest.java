package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.AbstractDataSource;

/** 计数器保留真实JDBC委托行为及原异常；不使用数据库或把代理调用误称为真实数据库事务验收。 */
class RuntimeExecutionDataSourceProbeTest {
    /** 代理identity可放入资源Map，原SQLException逐字传播，只统计成功commit而不修改事务设置。 */
    @Test
    void preservesIdentitySettingsExceptionsAndCountsOnlySuccessfulCommits() throws Exception {
        var raw = new Fixture(); var probe = new RuntimeExecutionDataSourceProbe();
        var source = (javax.sql.DataSource) probe.postProcessAfterInitialization(raw, "dataSource");
        var window = probe.begin();
        try (var connection = source.getConnection()) {
            assertTrue(connection.equals(connection)); assertFalse(connection.equals(raw.connection));
            assertEquals(System.identityHashCode(connection), connection.hashCode());
            assertTrue(connection.getAutoCommit()); assertEquals(0, raw.transactionSettings.get(), "probe不新增setAutoCommit");
            connection.setAutoCommit(false); assertEquals(1, raw.transactionSettings.get());
            try (var statement = connection.prepareStatement("select 1")) {
                assertTrue(statement.equals(statement)); assertEquals(System.identityHashCode(statement), statement.hashCode());
                assertSame(connection, statement.getConnection());
                assertEquals(1, statement.executeUpdate());
                raw.failSql.set(true);
                assertSame(raw.sqlFailure, assertThrows(SQLException.class, statement::executeUpdate));
            }
            connection.commit(); raw.failCommit.set(true);
            assertSame(raw.commitFailure, assertThrows(SQLException.class, connection::commit));
            connection.rollback();
        }
        var result = probe.end(window);
        assertEquals(1, result.connections()); assertEquals(0, result.acquireFailures());
        assertEquals(2, result.sqlExecuteCalls()); assertEquals(1, result.sqlFailures());
        assertEquals(1, result.successfulExplicitCommitCalls()); assertEquals(1, result.successfulRollbackCalls());
        assertEquals(1, result.connectionAcquisitionNanos().size());
        assertEquals(1,result.failures().size());assertEquals(0,result.omittedFailures());
        assertEquals(SQLException.class.getName(),result.failures().getFirst().type());assertEquals("40001",result.failures().getFirst().sqlState());
        assertTrue(result.failures().getFirst().applicationCallers().stream().allMatch(value->value.startsWith("com.skillforge.")));
        assertEquals(2, raw.commitCalls.get()); assertEquals(1, raw.rollbackCalls.get()); assertEquals(1, raw.closed.get());
    }

    /** 获取失败仍保留实际耗时及原SQLException，不产生伪成功连接或SQL记录。 */
    @Test
    void failedAcquisitionRetainsOriginalFailureAndWaitSample() {
        var failure = new SQLException("synthetic_acquisition_failure");
        var raw = new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException { throw failure; }
            @Override public Connection getConnection(String username, String password) throws SQLException { throw failure; }
        };
        var probe = new RuntimeExecutionDataSourceProbe();
        var source = (javax.sql.DataSource) probe.postProcessAfterInitialization(raw, "dataSource");
        var window = probe.begin();
        assertSame(failure, assertThrows(SQLException.class, source::getConnection));
        var result = probe.end(window);
        assertEquals(0, result.connections()); assertEquals(1, result.acquireFailures());
        assertEquals(1, result.connectionAcquisitionNanos().size()); assertTrue(result.connectionAcquisitionNanos().getFirst() >= 0);
        assertEquals(0, result.sqlExecuteCalls()); assertEquals(0, result.successfulExplicitCommitCalls());
        assertTrue(result.failures().isEmpty());assertEquals(0,result.omittedFailures(),"获取失败不冒充execute失败采证");
    }

    /** 失败计数不截断，诊断仅采前100项及每项20层应用栈，异常/SQL正文不进入metadata。 */
    @Test
    void capsFailureMetadataWhileKeepingExactFailureCountsAndSqlState() throws Exception {
        var raw=new Fixture();var frames=new java.util.ArrayList<StackTraceElement>();
        frames.add(new StackTraceElement("org.postgresql.Driver","execute","Driver.java",1));
        for(int index=0;index<30;index++)frames.add(new StackTraceElement("com.skillforge.synthetic.Component"+index,"execute","Component.java",index+1));
        raw.sqlFailure.setStackTrace(frames.toArray(StackTraceElement[]::new));
        var probe=new RuntimeExecutionDataSourceProbe();var source=(javax.sql.DataSource)probe.postProcessAfterInitialization(raw,"source");
        var window=probe.begin();
        try(var connection=source.getConnection();var statement=connection.prepareStatement("DO_NOT_CAPTURE_SQL_TEXT")) {
            statement.executeUpdate();raw.failSql.set(true);
            for(int index=0;index<103;index++)assertSame(raw.sqlFailure,assertThrows(SQLException.class,statement::executeUpdate));
        }
        var result=probe.end(window);assertEquals(104,result.sqlExecuteCalls());assertEquals(103,result.sqlFailures());
        assertEquals(100,result.failures().size());assertEquals(3,result.omittedFailures());
        for(var failure:result.failures()) {
            assertEquals("40001",failure.sqlState());assertEquals(SQLException.class.getName(),failure.type());
            assertEquals(20,failure.applicationCallers().size());
            assertTrue(failure.applicationCallers().stream().allMatch(value->value.startsWith("com.skillforge.")));
        }
        String metadata=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result.failures());
        assertFalse(metadata.contains("synthetic_execute_failure"));assertFalse(metadata.contains("DO_NOT_CAPTURE_SQL_TEXT"));
        assertFalse(metadata.contains("org.postgresql.Driver"));
    }

    /** 原始JDBC对象只模拟委托方法；计数器不能读取本夹具的字段推导执行事实。 */
    private static final class Fixture extends AbstractDataSource {
        final AtomicInteger transactionSettings = new AtomicInteger();
        final AtomicInteger commitCalls = new AtomicInteger();
        final AtomicInteger rollbackCalls = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();
        final AtomicBoolean failSql = new AtomicBoolean();
        final AtomicBoolean failCommit = new AtomicBoolean();
        final SQLException sqlFailure = new SQLException("synthetic_execute_failure","40001");
        final SQLException commitFailure = new SQLException("synthetic_commit_failure");
        final Connection connection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "equals" -> proxy == arguments[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "synthetic-raw-connection";
                    case "getAutoCommit" -> true;
                    case "setAutoCommit" -> { transactionSettings.incrementAndGet(); yield null; }
                    case "prepareStatement" -> statement();
                    case "commit" -> { commitCalls.incrementAndGet(); if (failCommit.get()) throw commitFailure; yield null; }
                    case "rollback" -> { rollbackCalls.incrementAndGet(); yield null; }
                    case "close" -> { closed.incrementAndGet(); yield null; }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        @Override public Connection getConnection() { return connection; }
        @Override public Connection getConnection(String username, String password) { return connection; }
        private PreparedStatement statement() {
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "equals" -> proxy == arguments[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> "synthetic-raw-statement";
                        case "getConnection" -> connection;
                        case "executeUpdate" -> { if (failSql.get()) throw sqlFailure; yield 1; }
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
