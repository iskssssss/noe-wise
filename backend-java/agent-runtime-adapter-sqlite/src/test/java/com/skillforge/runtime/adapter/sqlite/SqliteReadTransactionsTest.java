package com.skillforge.runtime.adapter.sqlite;

import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 WAL 文件：查询不取得写权，跨查询快照保留一致性。 */
class SqliteReadTransactionsTest {
    @TempDir Path directory;

    @Test void publicReadsCompleteWhileAnotherConnectionOwnsTheWriter() throws Exception {
        var file = directory.resolve("read.db");
        var tenant = new TenantId("desktop-local");
        var run = new RunRef(tenant, "missing");
        try (var store = new SqliteExecutionStore(file, Clock.systemUTC());
             var navigation = new SqliteDesktopSessions(file, Clock.systemUTC());
             var writer = DriverManager.getConnection("jdbc:sqlite:" + file);
             var statement = writer.createStatement();
             var executor = Executors.newSingleThreadExecutor()) {
            navigation.create("session", "Title", directory.toString());
            statement.execute("BEGIN IMMEDIATE");
            try {
                executor.submit(() -> {
                    try (var reopened = new SqliteExecutionStore(file, Clock.systemUTC())) {
                        assertTrue(reopened.load(run).isEmpty());
                    }
                    assertTrue(store.load(run).isEmpty());
                    assertTrue(store.runsForSession(new SessionRef(tenant, "session")).isEmpty());
                    assertTrue(store.actions(run, 0, 10).isEmpty());
                    assertTrue(store.due(tenant, 10).isEmpty());
                    assertTrue(store.events(new SessionRef(tenant, "session"), 0, 10).isEmpty());
                    assertEquals("Title", navigation.find("session").orElseThrow().title());
                    assertEquals(1, navigation.list().size());
                }).get(2, TimeUnit.SECONDS);
            } finally { statement.execute("ROLLBACK"); }
        }
    }

    @Test void deferredReadKeepsOneSnapshotWithoutBlockingCommit() throws Exception {
        var file = directory.resolve("snapshot.db");
        try (var database = new SqliteDatabase(file, Clock.systemUTC());
             var reader = database.readSource().getConnection();
             var writer = database.source().getConnection()) {
            try (var write = writer.createStatement()) {
                write.execute("CREATE TABLE snapshot_probe (value INTEGER NOT NULL)");
                write.execute("INSERT INTO snapshot_probe VALUES (1)");
                reader.setAutoCommit(false);
                assertEquals(1, value(reader));
                write.execute("UPDATE snapshot_probe SET value = 2");
                assertEquals(1, value(reader));
                reader.rollback();
                assertEquals(2, value(reader));
            }
        }
    }

    @Test void recordsCurrentVersionAndRejectsNewerSchemaWithoutDowngrade() throws Exception {
        var file = directory.resolve("version.db");
        try (var database = new SqliteDatabase(file, Clock.systemUTC());
             var connection = database.source().getConnection();
             var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("PRAGMA user_version")) {
                assertTrue(result.next()); assertEquals(1, result.getInt(1));
            }
            statement.execute("PRAGMA user_version = 2");
        }
        var failure = assertThrows(IllegalStateException.class, () -> new SqliteDatabase(file, Clock.systemUTC()));
        assertEquals("desktop_database_schema_version_unsupported", failure.getMessage());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             var statement = connection.createStatement();
             var result = statement.executeQuery("PRAGMA user_version")) {
            assertTrue(result.next()); assertEquals(2, result.getInt(1));
        }
    }

    @Test void incompleteUnversionedLayoutIsRejectedAndBootstrapRollsBack() throws Exception {
        var file = directory.resolve("partial.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE agent_runtime_budget_roots (tenant_id TEXT NOT NULL, root_id TEXT NOT NULL, policy_mode TEXT NOT NULL, PRIMARY KEY(tenant_id,root_id), UNIQUE(tenant_id,root_id,policy_mode)) STRICT");
            statement.execute("INSERT INTO agent_runtime_budget_roots VALUES ('desktop-local','original','ENFORCED')");
        }
        assertThrows(RuntimeException.class, () -> new SqliteDatabase(file, Clock.systemUTC()));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("PRAGMA user_version")) {
                assertTrue(result.next()); assertEquals(0, result.getInt(1));
            }
            try (var result = statement.executeQuery("SELECT root_id FROM agent_runtime_budget_roots")) {
                assertTrue(result.next()); assertEquals("original", result.getString(1)); assertFalse(result.next());
            }
            try (var result = statement.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table'")) {
                assertTrue(result.next()); assertEquals(1, result.getInt(1));
            }
        }
    }

    private static int value(java.sql.Connection connection) throws Exception {
        try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT value FROM snapshot_probe")) {
            assertTrue(result.next()); return result.getInt(1);
        }
    }
}
