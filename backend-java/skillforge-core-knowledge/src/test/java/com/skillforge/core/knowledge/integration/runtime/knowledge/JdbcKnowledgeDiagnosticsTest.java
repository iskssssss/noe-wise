package com.skillforge.core.knowledge.integration.runtime.knowledge;


import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import org.junit.jupiter.api.Test;

/** 诊断不泄露异常正文，且不将诊断原因变成可重试的执行结论。 */
class JdbcKnowledgeDiagnosticsTest {
    @Test
    void recognizesWrappedDatabaseFailuresWithoutLeakingMessages() {
        assertReason("sql_timeout", new SQLTimeoutException("secret query"));
        assertReason("sql_statement_cancelled", new SQLException("secret query", "57014"));
        assertReason("sql_deadlock", new SQLException("private row", "40P01"));
        assertReason("sql_lock_unavailable", new SQLException("private row", "55P03"));
        assertReason("sql_connection_failure", new SQLException("password=value", "08006"));
        assertReason("sql_failure", new SQLException("private row", "unknown"));
        assertReason("operation_timeout", new java.util.concurrent.TimeoutException("private query"));
        assertReason("operation_interrupted", new InterruptedException("private query"));
        assertReason("operation_failure", new IllegalStateException("secret query 57014"));
    }

    @Test
    void cyclicCausesAreBounded() {
        var first = new IllegalStateException("private one");
        var second = new IllegalStateException("private two", first);
        first.initCause(second);
        assertEquals("operation_failure", JdbcKnowledgePortal.diagnosticReason(first));
    }

    private static void assertReason(String expected, Exception failure) {
        assertEquals(expected, JdbcKnowledgePortal.diagnosticReason(new IllegalStateException("private wrapper", failure)));
    }
}
