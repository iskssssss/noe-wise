package com.skillforge.runtime.starter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class AgentRuntimeJdbcObservationTest {
    @Test
    void workerObservationKeepsResultFailureAndNestedScopeCounts() throws Exception {
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        var observed = AgentRuntimeJdbcObservation.observe(source);
        try (var scope = AgentRuntimeJdbcObservation.open()) {
            assertEquals(7, AgentRuntimeJdbcObservation.worker("test-poll", () -> {
                try (var wrapped = observed.getConnection(); var sql = wrapped.prepareStatement("private")) {
                    sql.execute(); return 7;
                } catch (SQLException failure) { throw new AssertionError(failure); }
            }));
            assertEquals("1", scope.fields().get("connections"));
            assertEquals("1", scope.fields().get("jdbcExecutions"));
        }
        var failure = new IllegalStateException("worker-failed");
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> AgentRuntimeJdbcObservation.worker("test-poll", () -> { throw failure; })));
    }
    @Test
    void countsOnlyActiveThreadScopesAndPreservesConnectionOwnership() throws Exception {
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        var measured = AgentRuntimeJdbcObservation.observe(source);
        try (var scope = AgentRuntimeJdbcObservation.open(); var wrapped = measured.getConnection()) {
            var sql = wrapped.prepareStatement("private SQL is never collected");
            assertSame(wrapped, sql.getConnection());
            assertSame(wrapped, wrapped.unwrap(Connection.class));
            assertSame(sql, sql.unwrap(PreparedStatement.class));
            sql.execute();
            try (var nested = AgentRuntimeJdbcObservation.open()) {
                sql.executeUpdate();
                assertEquals("1", nested.fields().get("jdbcExecutions"));
                assertEquals("0", nested.fields().get("connections"));
            }
            assertEquals("2", scope.fields().get("jdbcExecutions"));
            assertEquals("1", scope.fields().get("connections"));
            assertFalse(scope.fields().toString().contains("private SQL"));
            verify(connection, never()).close();
            wrapped.commit();
            verify(connection).commit();
            assertEquals("1", scope.fields().get("transactionControls"));
            assertTrue(Long.parseLong((String) scope.fields().get("transactionControlNanos")) >= 0);
        }
        verify(connection).close();
        try (var empty = AgentRuntimeJdbcObservation.open()) {
            assertEquals("0", empty.fields().get("connections"));
            assertEquals("0", empty.fields().get("jdbcExecutions"));
        }
    }

    @Test
    void transactionControlIncludesImplicitCommitAndPreservesFailureAndNestedScopes() throws Exception {
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        when(source.getConnection()).thenReturn(connection);
        var failure = new SQLException("control-failed", "08006");
        doThrow(failure).when(connection).rollback();
        try (var outer = AgentRuntimeJdbcObservation.open(); var wrapped = AgentRuntimeJdbcObservation.observe(source).getConnection()) {
            wrapped.setAutoCommit(false);
            try (var nested = AgentRuntimeJdbcObservation.open()) {
                wrapped.setAutoCommit(true);
                assertSame(failure, assertThrows(SQLException.class, wrapped::rollback));
                assertEquals("2", nested.fields().get("transactionControls"));
                assertEquals("0", nested.fields().get("jdbcExecutions"));
            }
            assertEquals("3", outer.fields().get("transactionControls"));
            verify(connection).setAutoCommit(false);
            verify(connection).setAutoCommit(true);
            verify(connection).rollback();
            verify(connection, never()).commit();
        }
        verify(connection).close();
    }

    @Test
    void preservesOriginalJdbcFailureAndCountsFailedAttempt() throws Exception {
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        var failure = new SQLException("safe-test-failure", "08006");
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.execute()).thenThrow(failure);
        try (var scope = AgentRuntimeJdbcObservation.open(); var wrapped = AgentRuntimeJdbcObservation.observe(source).getConnection()) {
            var sql = wrapped.prepareStatement("SELECT 1");
            assertSame(failure, assertThrows(SQLException.class, sql::execute));
            assertEquals("1", scope.fields().get("jdbcExecutions"));
        }
    }
}
