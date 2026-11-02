package com.skillforge.core.foundation.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.skillforge.common.context.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.ResultSet;
import java.util.Map;
import java.util.Set;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;
import org.mybatis.spring.transaction.SpringManagedTransaction;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** 验证 JDBC 会话绑定的生命周期；实际 PostgreSQL RLS 与缓存隔离由集成测试验证。 */
class TenantRlsInterceptorTest {
    private TenantRlsInterceptor interceptor = new TenantRlsInterceptor(java.util.List.of(new com.skillforge.core.foundation.access.FoundationAuthorizedSqlRule(), new com.skillforge.core.agent.AgentAuthorizedSqlRule(), new com.skillforge.core.capability.CapabilityAuthorizedSqlRule()));
    private final Executor executor = mock(Executor.class);
    private final Transaction transaction = mock(Transaction.class);
    private final Connection connection = mock(Connection.class);
    private final PreparedStatement binding = mock(PreparedStatement.class);
    private final PreparedStatement clearing = mock(PreparedStatement.class);
    private final MappedStatement mapped = mock(MappedStatement.class);
    private final Object parameter = new Object();
    private String previousTenant;
    private boolean synchronizationStarted;

    @BeforeEach
    void setup() throws Exception {
        previousTenant = TenantContext.getTenantId();
        TenantContext.setTenantId("11");
        when(executor.getTransaction()).thenReturn(transaction);
        when(transaction.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT set_config('app.tenant_id', ?, ?)"))
                .thenReturn(binding, clearing);
        when(executor.update(mapped, parameter)).thenReturn(7);
    }

    @AfterEach
    void restoreContext() {
        completeSpringTransaction();
        if (previousTenant == null) TenantContext.clear(); else TenantContext.setTenantId(previousTenant);
    }

    @Test
    void autoCommitBindsSessionBeforeSqlAndClearsItBeforeReturning() throws Throwable {
        when(connection.getAutoCommit()).thenReturn(true);
        assertThat(interceptor.intercept(invocation())).isEqualTo(7);
        var order = inOrder(binding, executor, clearing);
        order.verify(binding).setString(1, "11");
        order.verify(binding).setBoolean(2, false);
        order.verify(binding).execute();
        order.verify(binding).close();
        order.verify(executor).update(mapped, parameter);
        order.verify(clearing).setString(1, "");
        order.verify(clearing).setBoolean(2, false);
        order.verify(clearing).execute();
        order.verify(clearing).close();
        verify(connection, never()).commit();
        verify(connection, never()).rollback();
    }

    @Test
    void explicitTransactionUsesLocalBindingAndDoesNotAlterTransactionLifecycle() throws Throwable {
        when(connection.getAutoCommit()).thenReturn(false);
        assertThat(interceptor.intercept(invocation())).isEqualTo(7);
        var order = inOrder(binding, executor);
        order.verify(binding).setString(1, "11");
        order.verify(binding).setBoolean(2, true);
        order.verify(binding).execute();
        order.verify(binding).close();
        order.verify(executor).update(mapped, parameter);
        verifyNoInteractions(clearing);
        verify(connection, times(1)).prepareStatement(anyString());
        verify(connection, never()).commit();
        verify(connection, never()).rollback();
        verify(connection, never()).setAutoCommit(anyBoolean());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cursorQueryThroughActualPluginBindsTenantBeforeExecution(boolean autoCommit) throws Exception {
        when(connection.getAutoCommit()).thenReturn(autoCommit);
        @SuppressWarnings("unchecked")
        Cursor<Object> cursor = mock(Cursor.class);
        when(executor.queryCursor(mapped, parameter, RowBounds.DEFAULT)).thenReturn(cursor);
        Executor guarded = (Executor) interceptor.plugin(executor);

        assertThat(guarded.queryCursor(mapped, parameter, RowBounds.DEFAULT)).isSameAs(cursor);

        var order = inOrder(binding, executor, clearing);
        order.verify(binding).setString(1, "11");
        order.verify(binding).setBoolean(2, !autoCommit);
        order.verify(binding).execute();
        order.verify(binding).close();
        order.verify(executor).queryCursor(mapped, parameter, RowBounds.DEFAULT);
        if (autoCommit) {
            order.verify(clearing).setString(1, "");
            order.verify(clearing).setBoolean(2, false);
            order.verify(clearing).execute();
            order.verify(clearing).close();
        } else {
            verifyNoInteractions(clearing);
        }
        verify(cursor, never()).close();
        verify(connection, never()).commit();
        verify(connection, never()).rollback();
    }

    @ParameterizedTest
    @NullAndEmptySource
    void missingContextClearsPriorTenantInsteadOfSkippingBinding(String tenant) throws Throwable {
        if (tenant == null) TenantContext.clear(); else TenantContext.setTenantId(tenant);
        when(connection.getAutoCommit()).thenReturn(true);
        interceptor.intercept(invocation());
        verify(binding).setString(1, "");
        verify(binding).setBoolean(2, false);
        verify(binding).execute();
        verify(clearing).setString(1, "");
        verify(clearing).execute();
    }

    @Test
    void missingContextWithinTransactionClearsItsLocalIdentity() throws Throwable {
        TenantContext.clear();
        when(connection.getAutoCommit()).thenReturn(false);
        interceptor.intercept(invocation());
        verify(binding).setString(1, "");
        verify(binding).setBoolean(2, true);
        verify(binding).execute();
        verifyNoInteractions(clearing);
    }

    @Test
    void failedAutoCommitStatementStillClearsSessionIdentityAndClosesStatements() throws Exception {
        when(connection.getAutoCommit()).thenReturn(true);
        SQLException failure = new SQLException("simulated business SQL failure");
        when(executor.update(mapped, parameter)).thenThrow(failure);
        assertThatThrownBy(() -> interceptor.intercept(invocation())).hasRootCause(failure);
        var order = inOrder(binding, executor, clearing);
        order.verify(binding).execute();
        order.verify(binding).close();
        order.verify(executor).update(mapped, parameter);
        order.verify(clearing).setString(1, "");
        order.verify(clearing).setBoolean(2, false);
        order.verify(clearing).execute();
        order.verify(clearing).close();
    }

    @Test
    void failedTransactionStatementLeavesRollbackToTransactionOwner() throws Exception {
        when(connection.getAutoCommit()).thenReturn(false);
        SQLException failure = new SQLException("simulated transactional SQL failure");
        when(executor.update(mapped, parameter)).thenThrow(failure);
        assertThatThrownBy(() -> interceptor.intercept(invocation())).hasRootCause(failure);
        verify(binding).setBoolean(2, true);
        verify(binding).close();
        verifyNoInteractions(clearing);
        verify(connection, never()).rollback();
        verify(connection, never()).commit();
    }

    @Test
    void springTransactionBindsSameConnectionOnceAndChecksTenantBeforeEveryMapper() throws Throwable {
        springTransaction();
        interceptor.intercept(invocation()); interceptor.intercept(invocation());
        verify(binding, times(1)).execute(); verifyNoInteractions(clearing);
        TenantContext.setTenantId("12");
        assertThatThrownBy(() -> interceptor.intercept(invocation()))
                .isInstanceOf(IllegalStateException.class).hasMessage("tenant_transaction_scope_mismatch");
        verify(executor, times(2)).update(mapped, parameter);
        verify(connection, times(1)).prepareStatement(anyString());
        verify(connection, never()).commit(); verify(connection, never()).rollback();
    }

    @Test
    void anonymousLookupCanEstablishOneTenantAndClearingCannotEstablishAnother() throws Throwable {
        springTransaction(); TenantContext.clear();
        interceptor.intercept(invocation());
        TenantContext.setTenantId("11"); interceptor.intercept(invocation());
        TenantContext.clear(); interceptor.intercept(invocation());
        TenantContext.setTenantId("12");
        assertThatThrownBy(() -> interceptor.intercept(invocation())).hasMessage("tenant_transaction_scope_mismatch");
        verify(connection, times(3)).prepareStatement(anyString());
        verify(executor, times(3)).clearLocalCache();
        verify(executor, times(3)).update(mapped, parameter);
    }

    @Test
    void eachSpringTransactionBindsAgainEvenWhenTheConnectionIsReused() throws Throwable {
        springTransaction(); interceptor.intercept(invocation()); completeSpringTransaction();
        TenantContext.setTenantId("12"); springTransaction(); interceptor.intercept(invocation());
        verify(connection, times(2)).prepareStatement(anyString());
        verify(binding).setString(1,"11"); verify(clearing).setString(1,"12");
    }

    @Test
    void distinctConnectionsWithinSpringScopeEachNeedTheirOwnBinding() throws Throwable {
        springTransaction(); interceptor.intercept(invocation());
        var other=mock(Connection.class); var otherBinding=mock(PreparedStatement.class);
        when(other.prepareStatement(anyString())).thenReturn(otherBinding);
        when(executor.getTransaction().getConnection()).thenReturn(other);
        interceptor.intercept(invocation()); interceptor.intercept(invocation());
        verify(binding,times(1)).execute(); verify(otherBinding,times(1)).execute();
        verify(otherBinding).setString(1,"11");
    }

    @Test
    void explicitSettingMapperInvalidatesTheConnectionBindingProof() throws Throwable {
        springTransaction();
        var configuration=new org.apache.ibatis.session.Configuration();
        var read=new BoundSql(configuration,"SELECT count(*) FROM tenant_probe",java.util.List.of(),parameter);
        var setting=new BoundSql(configuration,"SELECT set_config('app.tenant_id', ?, true)",java.util.List.of(),parameter);
        when(mapped.getBoundSql(parameter)).thenReturn(read,setting,read,read);
        interceptor.intercept(invocation()); interceptor.intercept(invocation());
        interceptor.intercept(invocation()); interceptor.intercept(invocation());
        verify(connection,times(3)).prepareStatement(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings={"/* binder */ SET LOCAL app.tenant_id='12'", "-- binder\nRESET app.tenant_id",
            "/* outer /* nested */ binder */ SET LOCAL app.tenant_id='12'"})
    void commentedSettingMapperInvalidatesTheConnectionBindingProof(String sql) throws Throwable {
        springTransaction();
        var configuration=new org.apache.ibatis.session.Configuration();
        var read=new BoundSql(configuration,"SELECT count(*) FROM tenant_probe",java.util.List.of(),parameter);
        var setting=new BoundSql(configuration,sql,java.util.List.of(),parameter);
        when(mapped.getBoundSql(parameter)).thenReturn(read,setting,read,read);
        interceptor.intercept(invocation()); interceptor.intercept(invocation());
        interceptor.intercept(invocation()); interceptor.intercept(invocation());
        verify(connection,times(3)).prepareStatement(anyString());
    }

    @Test
    void ordinaryUpdateSetDoesNotInvalidateTheConnectionBindingProof() throws Throwable {
        springTransaction();
        var sql=new BoundSql(new org.apache.ibatis.session.Configuration(),
                "/* ordinary update */ UPDATE tenant_probe SET label='set' WHERE tenant_id=?",java.util.List.of(),parameter);
        when(mapped.getBoundSql(parameter)).thenReturn(sql);
        interceptor.intercept(invocation()); interceptor.intercept(invocation());
        verify(connection,times(1)).prepareStatement(anyString());
    }

    @Test
    void savepointRollbackInvalidatesBindingWithoutAllowingAnotherTenant() throws Throwable {
        springTransaction(); interceptor.intercept(invocation());
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.savepointRollback(new Object()));
        interceptor.intercept(invocation()); TenantContext.setTenantId("12");
        assertThatThrownBy(() -> interceptor.intercept(invocation())).hasMessage("tenant_transaction_scope_mismatch");
        verify(connection,times(2)).prepareStatement(anyString());
    }

    @Test void missingDomainContributionRejectsAuthorizedWindowBeforeBusinessSql() throws Exception {
        interceptor = new TenantRlsInterceptor();
        platformReadTransaction();
        assertThatThrownBy(() -> TenantRlsInterceptor.withDefinitionImpactTargets(31, Set.of(7L), () -> {
            readImpact(7, "SELECT revision FROM nw_agent_deployments");
            return null;
        })).hasMessage("definition_impact_statement_not_allowed");
        verify(executor, never()).query(any(), any(), any(), any());
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test void definitionImpactReadsTwoExplicitTargetsAndDoesNotRelaxTheOrdinaryTransactionAfterward() throws Throwable {
        var reset=platformReadTransaction();
        TenantRlsInterceptor.withDefinitionImpactTargets(31,Set.of(7L,8L),()->{
            readImpact(7,"SELECT revision FROM nw_agent_deployments");
            readImpact(8,"SELECT revision FROM nw_agent_deployments");return null;
        });
        assertThat(TenantContext.getTenantId()).isNull();verify(reset).execute();
        TenantContext.setTenantId("7");interceptor.intercept(invocation());TenantContext.setTenantId("8");
        assertThatThrownBy(()->interceptor.intercept(invocation())).hasMessage("tenant_transaction_scope_mismatch");
    }

    @Test void definitionImpactRejectsUnlistedTenantBusinessWriteAndWritableSelectBeforeSql() throws Exception {
        platformReadTransaction();
        assertThatThrownBy(()->TenantRlsInterceptor.withDefinitionImpactTargets(31,Set.of(7L),()->{
            readImpact(8,"SELECT revision FROM nw_agent_deployments");return null;
        })).hasMessage("definition_impact_target_mismatch");
        assertThatThrownBy(()->TenantRlsInterceptor.withDefinitionImpactTargets(31,Set.of(7L),()->{
            invokeUnchecked(invocationUnchecked());return null;
        })).hasMessage("definition_impact_business_write_forbidden");
        for(String sql:java.util.List.of("WITH changed AS (DELETE FROM nw_agent_deployments RETURNING *) SELECT * FROM changed",
                "SELECT 1; DELETE FROM nw_agent_deployments", "SELECT * FROM nw_agent_deployments FOR UPDATE")) {
            assertThatThrownBy(()->TenantRlsInterceptor.withDefinitionImpactTargets(31,Set.of(7L),()->{readImpact(7,sql);return null;}))
                    .hasMessage("definition_impact_business_write_forbidden");
        }
        verify(executor,never()).query(any(),any(),any(),any());verify(executor,never()).update(any(),any());
    }

    @Test void definitionImpactCannotReplaceEstablishedOrdinaryTenantOrEnterWithoutActualReadOnlyTransaction() throws Throwable {
        assertThatThrownBy(()->TenantRlsInterceptor.withDefinitionImpactTargets(31,Set.of(7L),()->null))
                .hasMessage("definition_impact_requires_platform_read_only_transaction");
        springTransaction();interceptor.intercept(invocation());TenantContext.clear();
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThatThrownBy(()->TenantRlsInterceptor.withDefinitionImpactTargets(31,Set.of(7L),()->null))
                .hasMessage("definition_impact_cannot_replace_tenant_transaction");
    }

    @Test void definitionImpactOnlyAllowsExistingBinderKeysAndCannotEnablePlatformBypass() throws Exception {
        platformReadTransaction();
        for(var parameters:java.util.List.of(Map.of("key","search_path","value","public"),
                Map.of("key","app.platform_admin","value","true"),Map.of("key","app.tenant_id","value","8"))) {
            assertThatThrownBy(()->TenantRlsInterceptor.withDefinitionImpactTargets(31,Set.of(7L),()->{
                TenantContext.setTenantId("7");
                var statement=mock(MappedStatement.class);
                when(statement.getId()).thenReturn("com.skillforge.core.capability.CapabilityCatalogContextMapper.bind");
                when(statement.getSqlCommandType()).thenReturn(SqlCommandType.SELECT);
                when(statement.getBoundSql(parameters)).thenReturn(new BoundSql(new org.apache.ibatis.session.Configuration(),
                        "SELECT set_config(?, ?, true)",java.util.List.of(),parameters));
                try {invokeUnchecked(new Invocation(executor,Executor.class.getMethod("query",MappedStatement.class,Object.class,RowBounds.class,org.apache.ibatis.session.ResultHandler.class),
                        new Object[]{statement,parameters,RowBounds.DEFAULT,null}));}
                catch(NoSuchMethodException failure){throw new IllegalStateException(failure);}
                return null;
            })).hasMessage("definition_impact_setting_not_allowed");
        }
        verify(executor,never()).query(any(),any(),any(),any());
    }

    @Test void definitionImpactRejectsConnectionThatIsNotReadOnlyAndPreservesOriginalFailureOnCleanupError() throws Exception {
        var reset=platformReadTransaction();when(connection.isReadOnly()).thenReturn(false);
        assertThatThrownBy(()->TenantRlsInterceptor.withDefinitionImpactTargets(31,Set.of(7L),()->{readImpact(7,"SELECT revision FROM nw_agent_deployments");return null;}))
                .hasMessage("definition_impact_connection_not_read_only");
        when(connection.isReadOnly()).thenReturn(true);
        doThrow(new SQLException("restore failed")).when(reset).execute();
        var original=new IllegalStateException("read failed");
        assertThatThrownBy(()->TenantRlsInterceptor.withDefinitionImpactTargets(31,Set.of(7L),()->{
            readImpact(7,"SELECT revision FROM nw_agent_deployments");throw original;
        })).isSameAs(original);
        assertThat(original.getSuppressed()).hasSize(1);assertThat(TenantContext.getTenantId()).isNull();
    }

    private PreparedStatement platformReadTransaction() throws Exception {
        springTransaction();TenantContext.clear();TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        when(connection.isReadOnly()).thenReturn(true);
        var capture=mock(PreparedStatement.class);var result=mock(ResultSet.class);
        when(connection.prepareStatement("SELECT COALESCE(current_setting('app.tenant_id',true),''),COALESCE(current_setting('app.platform_admin',true),'false')")).thenReturn(capture);
        when(capture.executeQuery()).thenReturn(result);when(result.next()).thenReturn(true);
        when(result.getString(1)).thenReturn("");when(result.getString(2)).thenReturn("false");
        var reset=mock(PreparedStatement.class);
        when(connection.prepareStatement("SELECT set_config('app.tenant_id',?,true),set_config('app.platform_admin',?,true)")).thenReturn(reset);
        return reset;
    }

    private void readImpact(long tenant,String sql) {
        TenantContext.setTenantId(Long.toString(tenant));
        var statement=mock(MappedStatement.class);
        var parameters=Map.of("tenantId",tenant,"definitionId",31L);
        when(statement.getId()).thenReturn("com.skillforge.core.agent.mapper.AgentDeploymentMapper.impact");
        when(statement.getSqlCommandType()).thenReturn(SqlCommandType.SELECT);
        when(statement.getBoundSql(parameters)).thenReturn(new BoundSql(new org.apache.ibatis.session.Configuration(),sql,java.util.List.of(),parameters));
        try {invokeUnchecked(new Invocation(executor,Executor.class.getMethod("query",MappedStatement.class,Object.class,RowBounds.class,org.apache.ibatis.session.ResultHandler.class),
                new Object[]{statement,parameters,RowBounds.DEFAULT,null}));}
        catch(NoSuchMethodException failure){throw new IllegalStateException(failure);}
        finally {TenantContext.clear();}
    }

    private Invocation invocationUnchecked() {try{return invocation();}catch(NoSuchMethodException failure){throw new IllegalStateException(failure);}}
    private Object invokeUnchecked(Invocation call) {
        try{return interceptor.intercept(call);}
        catch(RuntimeException|Error failure){throw failure;}
        catch(Throwable failure){throw new IllegalStateException(failure);}
    }

    private void springTransaction() throws Exception {
        var spring=mock(SpringManagedTransaction.class);
        when(spring.getConnection()).thenReturn(connection); when(executor.getTransaction()).thenReturn(spring);
        when(connection.getAutoCommit()).thenReturn(false);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true); synchronizationStarted=true;
    }

    private void completeSpringTransaction() {
        if(!synchronizationStarted)return;
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false); synchronizationStarted=false;
    }

    private Invocation invocation() throws NoSuchMethodException {
        return new Invocation(executor, Executor.class.getMethod("update", MappedStatement.class, Object.class),
                new Object[] { mapped, parameter });
    }
}
