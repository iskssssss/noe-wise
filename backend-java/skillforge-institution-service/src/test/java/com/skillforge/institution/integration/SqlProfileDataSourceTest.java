package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class SqlProfileDataSourceTest {
    @Test void threadRolesSeparateDrivePollingDeliveryAndControlWithoutRetainingThreadIdentity() {
        assertEquals(SqlProfileDataSource.ThreadRole.EXECUTION, SqlProfileDataSource.threadRole("runtime-execution-0"));
        assertEquals(SqlProfileDataSource.ThreadRole.EXECUTION, SqlProfileDataSource.threadRole("runtime-execution-49"));
        assertEquals(SqlProfileDataSource.ThreadRole.CONTROL, SqlProfileDataSource.threadRole("runtime-execution-control-0"));
        assertEquals(SqlProfileDataSource.ThreadRole.OTHER, SqlProfileDataSource.threadRole("runtime-executions-0"));
        assertEquals(SqlProfileDataSource.ThreadRole.OTHER, SqlProfileDataSource.threadRole("private-runtime-execution-0"));
        assertEquals(SqlProfileDataSource.ThreadRole.SESSION, SqlProfileDataSource.threadRole("agent-runtime-session-42"));
        assertEquals(SqlProfileDataSource.ThreadRole.TOOL, SqlProfileDataSource.threadRole("agent-runtime-tool-batch-0"));
        assertEquals(SqlProfileDataSource.ThreadRole.MODEL, SqlProfileDataSource.threadRole("model-provider-stream"));
        assertEquals(SqlProfileDataSource.ThreadRole.WORKER, SqlProfileDataSource.threadRole("agent-runtime-workers"));
        assertEquals(SqlProfileDataSource.ThreadRole.EVENTS, SqlProfileDataSource.threadRole("agent-runtime-events"));
        assertEquals(SqlProfileDataSource.ThreadRole.EVENTS, SqlProfileDataSource.threadRole("runtime-event-delivery"));
        assertEquals(SqlProfileDataSource.ThreadRole.CONTROL, SqlProfileDataSource.threadRole("agent-runtime-liveness"));
        assertEquals(SqlProfileDataSource.ThreadRole.CONTROL, SqlProfileDataSource.threadRole("agent-runtime-control-check"));
        assertEquals(SqlProfileDataSource.ThreadRole.SSE, SqlProfileDataSource.threadRole("chat-runtime-follow"));
        assertEquals(SqlProfileDataSource.ThreadRole.HTTP, SqlProfileDataSource.threadRole("http-nio-auto-1-exec-1"));
        assertEquals(SqlProfileDataSource.ThreadRole.OTHER, SqlProfileDataSource.threadRole("private-user-identity"));
    }

    @Test void fixedStatementsHaveBoundedCategoriesWithoutSqlOrParameters() {
        assertEquals("SELECT tenant_bind", SqlProfileDataSource.category(
                "/* mapper */ SELECT set_config('app.tenant_id', ?, true)"));
        assertEquals("SELECT tenant_and_timeout", SqlProfileDataSource.category(
                "SELECT set_config('app.tenant_id',?,true),set_config('statement_timeout',?,true)"));
        assertEquals("SELECT statement_timeout", SqlProfileDataSource.category(
                "SELECT set_config('statement_timeout',?,true)"));
        assertEquals("SELECT clock", SqlProfileDataSource.category("SELECT clock_timestamp()"));
        assertEquals("SELECT identity_tenant", SqlProfileDataSource.category(
                "SELECT public.resolve_runtime_authentication_tenant(?)"));
        assertEquals("SELECT advisory_lock", SqlProfileDataSource.category(
                "SELECT pg_advisory_xact_lock(hashtextextended(?,0))::text"));
        assertEquals("SELECT authorization_current", SqlProfileDataSource.category(
                "/* agent_runtime_authorization_current */ WITH runtime_tenant AS (SELECT 1) SELECT 1"));
        assertEquals("SELECT public.agent_runtime_runs", SqlProfileDataSource.category(
                "SELECT run_id, clock_timestamp() FROM public.agent_runtime_runs WHERE tenant_id=?"));
        assertEquals("SELECT", SqlProfileDataSource.category("SELECT 'private-literal'"));
    }

    @Test void connectionCallerUsesOuterConcreteAdapterMethodInsteadOfHelpersOrBusinessStack() {
        assertEquals("com.skillforge.runtime.adapter.jdbc.session.JdbcSessionPreparations#readFrozen",
                SqlProfileDataSource.adapterCaller(List.of(
                        frame("org.apache.ibatis.session.DefaultSqlSession", "selectOne"),
                        frame("com.skillforge.runtime.adapter.jdbc.session.JdbcSessionPreparations", "mapper"),
                        frame("com.skillforge.runtime.adapter.jdbc.session.JdbcSessionPreparations", "lambda$readFrozen$0"),
                        frame("com.skillforge.runtime.adapter.jdbc.session.JdbcSessionPreparations", "readFrozen"),
                        frame("com.skillforge.runtime.session.SessionToolRouting", "route"))));
        assertEquals("<no-jdbc-adapter>", SqlProfileDataSource.adapterCaller(List.of(
                frame("com.skillforge.institution.integration.SqlProfileDataSource", "timedConnection"))));
    }

    private static StackTraceElement frame(String type, String method) {
        return new StackTraceElement(type, method, "Source.java", 1);
    }
}
