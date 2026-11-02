package com.skillforge.institution.memory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.skillforge.runtime.adapter.jdbc.memory.JdbcMemoryPort;
import com.skillforge.runtime.api.FactStatus;
import com.skillforge.runtime.api.MemoryFact;
import com.skillforge.runtime.api.MemoryFactRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.MemoryPort.MemoryCommand;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import javax.sql.DataSource;
import org.apache.ibatis.binding.MapperProxy;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;

/** 仅验证真实MyBatis装配及会话/连接身份；JDBC为Mock，不声明真实数据库锁并发通过。 */
class MemoryDecayTransitionGuardTest {
    private MemoryCommand.Transition command() {
        var command = mock(MemoryCommand.Transition.class);
        when(command.reasonCode()).thenReturn(MemoryDecayTransitionGuard.TIME_DECAY_REASON);
        when(command.target()).thenReturn(FactStatus.DECAYED);
        return command;
    }

    private MemoryFact current() {
        var current = mock(MemoryFact.class);
        when(current.status()).thenReturn(FactStatus.ACTIVE);
        when(current.ref()).thenReturn(new MemoryFactRef(new TenantId("tenant"), "fact"));
        return current;
    }

    @Test
    void acceptedRejectedAndMissingProjectionNeverTakeSessionOwnership() {
        for (Boolean ready : new Boolean[]{true, false, null}) {
            var session = mock(SqlSession.class);
            var mapper = mock(MemoryDecayMapper.class);
            when(session.getMapper(MemoryDecayMapper.class)).thenReturn(mapper);
            when(mapper.decayReady("tenant", "fact")).thenReturn(ready);
            assertEquals(Boolean.TRUE.equals(ready), new MemoryDecayTransitionGuard().allows(session, command(), current()));
            verify(session).getMapper(MemoryDecayMapper.class);
            verifyNoMoreInteractions(session);
        }
    }

    @Test
    void mapperFailurePreservesCauseAndDoesNotCommitRollbackOrClose() {
        var session = mock(SqlSession.class);
        var mapper = mock(MemoryDecayMapper.class);
        var cause = new IllegalStateException("driver failure");
        when(session.getMapper(MemoryDecayMapper.class)).thenReturn(mapper);
        when(mapper.decayReady("tenant", "fact")).thenThrow(cause);
        var failure = assertThrows(IllegalStateException.class,
                () -> new MemoryDecayTransitionGuard().allows(session, command(), current()));
        assertEquals("memory_decay_guard_read_failure", failure.getMessage());
        assertSame(cause, failure.getCause());
        verify(session).getMapper(MemoryDecayMapper.class);
        verifyNoMoreInteractions(session);
    }

    @Test
    void regularGovernanceDoesNotReadHostProjection() {
        var session = mock(SqlSession.class);
        var command = mock(MemoryCommand.Transition.class);
        when(command.reasonCode()).thenReturn("manual-governance");
        assertTrue(new MemoryDecayTransitionGuard().allows(session, command, mock(MemoryFact.class)));
        verifyNoInteractions(session);
    }

    @Test
    void wrongTargetOrCurrentStateFailsBeforeProjectionRead() {
        var session = mock(SqlSession.class);
        var command = command();
        when(command.target()).thenReturn(FactStatus.ARCHIVED);
        assertFalse(new MemoryDecayTransitionGuard().allows(session, command, current()));
        when(command.target()).thenReturn(FactStatus.DECAYED);
        var current = current();
        when(current.status()).thenReturn(FactStatus.DECAYED);
        assertFalse(new MemoryDecayTransitionGuard().allows(session, command, current));
        verifyNoInteractions(session);
    }

    @Test
    void runtimeFactoryLoadsHostXmlAndUsesTheSameSessionAndConnection() throws Exception {
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        when(source.getConnection()).thenReturn(connection);
        List<PreparedStatement> projections = new ArrayList<>();
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            boolean projection = sql.contains("projected_decay_score");
            var statement = mock(PreparedStatement.class);
            var rows = mock(ResultSet.class);
            var metadata = mock(ResultSetMetaData.class);
            when(statement.execute()).thenReturn(true);
            when(statement.getResultSet()).thenReturn(rows);
            when(rows.next()).thenReturn(true, false);
            when(rows.getMetaData()).thenReturn(metadata);
            when(metadata.getColumnCount()).thenReturn(1);
            when(metadata.getColumnLabel(1)).thenReturn(projection ? "ready" : "set_config");
            when(metadata.getColumnName(1)).thenReturn(projection ? "ready" : "set_config");
            when(metadata.getColumnType(1)).thenReturn(projection ? Types.BOOLEAN : Types.VARCHAR);
            when(metadata.getColumnClassName(1)).thenReturn(projection ? Boolean.class.getName() : String.class.getName());
            when(rows.getString(anyString())).thenReturn("tenant");
            when(rows.getBoolean(anyString())).thenReturn(false);
            if (projection) {
                assertTrue(sql.trim().endsWith("for update"));
                assertTrue(sql.contains("tenant_id=?") && sql.contains("fact_id=?"));
                projections.add(statement);
            }
            return statement;
        });
        var guard = new MemoryDecayTransitionGuard();
        var port = new JdbcMemoryPort(source, guard, ignored -> {
            throw new AssertionError("decay-guard wiring fixture must not project Memory bodies");
        });
        verifyNoInteractions(source);
        var transaction = JdbcMemoryPort.class.getDeclaredMethod(
                "transactionWithSession", TenantId.class, BiFunction.class);
        transaction.setAccessible(true);
        var sessionField = MapperProxy.class.getDeclaredField("sqlSession");
        sessionField.setAccessible(true);
        BiFunction<Object, SqlSession, Boolean> work = (runtimeMapper, session) -> {
            try {
                assertSame(session, sessionField.get(Proxy.getInvocationHandler(runtimeMapper)));
                var projectionMapper = session.getMapper(MemoryDecayMapper.class);
                assertSame(session, sessionField.get(Proxy.getInvocationHandler(projectionMapper)));
                assertSame(connection, session.getConnection());
                assertFalse(guard.allows(session, command(), current()));
                verify(connection, never()).commit();
                verify(connection, never()).rollback();
                verify(connection, never()).close();
                return false;
            } catch (ReflectiveOperationException | java.sql.SQLException failure) {
                throw new AssertionError(failure);
            }
        };
        assertEquals(false, transaction.invoke(port, new TenantId("tenant"), work));
        assertEquals(1, projections.size());
        verify(projections.getFirst()).setString(1, "tenant");
        verify(projections.getFirst()).setString(2, "fact");
        verify(source, times(1)).getConnection();
        verify(connection, times(1)).close();
    }
}
