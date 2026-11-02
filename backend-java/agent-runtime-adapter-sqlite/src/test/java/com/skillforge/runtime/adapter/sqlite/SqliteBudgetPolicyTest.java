package com.skillforge.runtime.adapter.sqlite;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.ExecutionBudgetPort.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 真文件测试预算模式、历史有限账本迁移及原事务回滚；不连接用户运行数据库。 */
class SqliteBudgetPolicyTest {
    private static final TenantId TENANT = new TenantId("desktop-local");
    private static final RunRef LEGACY = new RunRef(TENANT, "legacy");
    private static final Resources CAPACITY = new Resources(20, 10, 20, 10, 100_000, 100_000, 200_000);
    private static final Resources CLOSING = new Resources(1, 0, 0, 0, 10, 10, 20);
    private static final Resources CONSUMED = new Resources(2, 0, 0, 0, 3, 4, 7);
    private static final Resources UNKNOWN = new Resources(0, 0, 0, 0, 10, 20, 30);
    @TempDir Path directory;

    @Test void observeOnlyCountsEveryCategoryAndRetainsUnknownAfterReopenWithoutInferringClosing() {
        var file = directory.resolve("observe.db");
        var run = new RunRef(TENANT, "observed");
        var policy = new Policy.ObserveOnly(Scenario.STANDARD, "desktop-config-v4");
        Snapshot expected;
        try (var store = new SqliteExecutionStore(file, Clock.systemUTC())) {
            var budgets = store.budgets();
            budgets.register(new Registration(run, run, Role.PRIMARY, policy));
            var start = Instant.parse("2026-01-01T00:00:00Z");
            budgets.recordActivity(run, "long-task", start, start.plus(Duration.ofDays(2)));
            var closing = new Closing(new Resources(3, 2, 2, 0, 10_000, 10_000, 20_000), 100_000);
            assertFalse(budgets.admitClosingAndChoosePhase(run, new ClosingAdmission(closing, closing),
                    java.util.Set.of("local:finalize"), Resources.call(Category.BUSINESS_MODEL, 20_000, 20_000)));
            for (var category : Category.values()) for (int i = 0; i < 25; i++) {
                var id = category.name() + i;
                var demand = new Demand(run, id, "fp-" + id, category, Resources.call(category, 3_000, 2_000), false);
                assertInstanceOf(Reservation.Granted.class, budgets.reserve(demand));
                assertTrue(budgets.markSent(run, id));
                assertFalse(budgets.markSent(run, id));
                budgets.saveResult(run, id, Usage.reported(OptionalLong.of(2_000), OptionalLong.of(1_000)), "result-" + id);
                budgets.settle(run, id);
            }
            var unknown = new Demand(run, "unknown", "unknown-fp", Category.BUSINESS_MODEL,
                    Resources.call(Category.BUSINESS_MODEL, 40_000, 30_000), false);
            assertInstanceOf(Reservation.Granted.class, budgets.reserve(unknown));
            assertTrue(budgets.markSent(run, unknown.attemptId()));
            assertEquals(AttemptState.SENT, assertInstanceOf(Reservation.Granted.class, budgets.reserve(unknown)).permit().state());
            assertEquals(Refusal.REQUEST_CONFLICT, assertInstanceOf(Reservation.Denied.class, budgets.reserve(new Demand(run,
                    "unknown", "changed-fp", Category.BUSINESS_MODEL, unknown.resources(), false))).reason());
            expected = budgets.snapshot(run);
            assertEquals(new Resources(26, 25, 25, 25, 200_000, 100_000, 300_000), expected.consumed());
            assertEquals(new Resources(0, 0, 0, 0, 40_000, 30_000, 70_000), expected.reserved());
            assertEquals(Duration.ofDays(2).toMillis(), expected.activeMillis());
            assertEquals(Phase.BUSINESS, expected.phase());
            assertEquals(closing, expected.closing());
        }
        try (var store = new SqliteExecutionStore(file, Clock.systemUTC())) {
            assertEquals(policy, store.budgets().find(run).orElseThrow().policy());
            store.budgets().recover(run);
            assertEquals(expected, store.budgets().snapshot(run));
        }
    }

    @Test void observeOnlyStillHonorsExplicitClosingAndRejectsMixedRootModes() {
        try (var store = new SqliteExecutionStore(directory.resolve("phases.db"), Clock.systemUTC())) {
            var budgets = store.budgets(); var run = new RunRef(TENANT, "primary");
            var policy = new Policy.ObserveOnly(Scenario.STANDARD, "observed");
            var registration = new Registration(run, run, Role.PRIMARY, policy);
            budgets.register(registration);
            var member = new RunRef(TENANT, "member");
            assertThrows(IllegalStateException.class, () -> budgets.register(new Registration(member, run, Role.MEMBER,
                    new Policy.Enforced(new Limits(Scenario.STANDARD, CAPACITY, CAPACITY, CAPACITY, 60, 60, "finite")))));
            assertTrue(budgets.find(member).isEmpty());
            budgets.register(new Registration(member, run, Role.MEMBER, policy));
            var reserved = new Demand(run, "before-closing", "fp", Category.TOOL, Resources.call(Category.TOOL, 0, 0), false);
            assertInstanceOf(Reservation.Granted.class, budgets.reserve(reserved));
            budgets.enterClosing(run);
            assertFalse(budgets.markSent(run, reserved.attemptId()));
            assertEquals(Refusal.CLOSING_REQUIRED, assertInstanceOf(Reservation.Denied.class,
                    budgets.reserve(new Demand(run, "business", "fp", Category.BUSINESS_MODEL,
                            Resources.call(Category.BUSINESS_MODEL, 1, 1), false))).reason());
            assertEquals(Refusal.CLOSING_REQUIRED, assertInstanceOf(Reservation.Denied.class,
                    budgets.reserve(new Demand(member, "member-closing", "fp", Category.TOOL,
                            Resources.call(Category.TOOL, 0, 0), true))).reason());
            var closing = new Demand(run, "final", "fp", Category.BUSINESS_MODEL, Resources.call(Category.BUSINESS_MODEL, 1, 1), true);
            assertInstanceOf(Reservation.Granted.class, budgets.reserve(closing));
            assertTrue(budgets.markSent(run, closing.attemptId()));
            assertThrows(IllegalStateException.class, () -> budgets.register(new Registration(run, run, Role.PRIMARY,
                    new Policy.ObserveOnly(Scenario.STANDARD, "changed"))));
        }
    }

    @Test void legacyFiniteLedgerMigratesAtomicallyWithUsageUnknownReceiptsAndForeignKeysIntact() throws Exception {
        var file = directory.resolve("legacy.db"); createLegacy(file);
        Snapshot migrated;
        try (var store = new SqliteExecutionStore(file, Clock.systemUTC())) {
            var budgets = store.budgets();
            var registration = new Registration(LEGACY, LEGACY, Role.PRIMARY,
                    new Policy.Enforced(new Limits(Scenario.STANDARD, CAPACITY, CAPACITY, CAPACITY, 3_600, 1_800, "legacy-v1")));
            assertEquals(registration, budgets.find(LEGACY).orElseThrow());
            assertEquals(registration, budgets.register(registration));
            migrated = budgets.snapshot(LEGACY);
            assertEquals(CONSUMED, migrated.consumed()); assertEquals(UNKNOWN, migrated.reserved());
            assertEquals(new Closing(CLOSING, 31), migrated.closing());
            assertEquals(Phase.CLOSING, migrated.phase()); assertEquals(1_000, migrated.activeMillis());
            budgets.recover(LEGACY);
            assertEquals(migrated, budgets.snapshot(LEGACY));
            assertThrows(IllegalStateException.class, () -> budgets.register(new Registration(LEGACY, LEGACY, Role.PRIMARY,
                    new Policy.ObserveOnly(Scenario.STANDARD, "new-config"))));
        }
        try (var database = new SqliteDatabase(file, Clock.systemUTC()); var connection = database.source().getConnection()) {
            assertEquals(0, scalar(connection, "SELECT count(*) FROM pragma_foreign_key_check"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM agent_runtime_budget_attempts WHERE attempt_id='unknown' AND state='SENT'"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM agent_runtime_budget_result_receipts WHERE result_reference='known-result'"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM agent_runtime_budget_closing_targets WHERE target_handle='local:finalize'"));
            assertThrows(java.sql.SQLException.class, () -> connection.createStatement().executeUpdate(
                    "INSERT INTO agent_runtime_budget_attempts(tenant_id,attempt_id,run_id,fingerprint,category,closing,state) VALUES('desktop-local','orphan','missing','fp','TOOL',0,'RESERVED')"));
        }
        try (var store = new SqliteExecutionStore(file, Clock.systemUTC())) { assertEquals(migrated, store.budgets().snapshot(LEGACY)); }
    }

    @Test void failedLegacyCopyRollsBackSchemaAndDataRatherThanLeavingPartialMigration() throws Exception {
        var file = directory.resolve("broken-legacy.db"); createLegacy(file);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file); var statement = connection.createStatement()) {
            statement.execute("PRAGMA ignore_check_constraints=ON");
            statement.executeUpdate("UPDATE agent_runtime_budget_roots SET active_limit_seconds=0");
        }
        assertThrows(RuntimeException.class, () -> new SqliteDatabase(file, Clock.systemUTC()));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            assertEquals(0, scalar(connection, "PRAGMA user_version"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM pragma_table_info('agent_runtime_budget_roots') WHERE name='policy_mode'"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM sqlite_master WHERE name LIKE '%_policy_v2'"));
            assertEquals(14, scalar(connection, "SELECT count(*) FROM agent_runtime_budget_attempt_amounts"));
            assertEquals(2, scalar(connection, "SELECT count(*) FROM agent_runtime_budget_attempts"));
            assertEquals(0, scalar(connection, "SELECT active_limit_seconds FROM agent_runtime_budget_roots"));
        }
    }

    private static long scalar(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertTrue(result.next()); return result.getLong(1);
        }
    }

    private static void createLegacy(Path file) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file); var statement = connection.createStatement();
             var input = SqliteBudgetPolicyTest.class.getResourceAsStream("/sqlite-budget-schema-v1.sql")) {
            assertNotNull(input);
            for (String sql : new String(input.readAllBytes(), StandardCharsets.UTF_8).split(";")) if (!sql.isBlank()) statement.execute(sql);
            statement.executeUpdate("INSERT INTO agent_runtime_budget_roots VALUES('desktop-local','legacy','STANDARD','legacy-v1',3600,31,'legacy','CLOSING')");
            statement.executeUpdate("INSERT INTO agent_runtime_budget_runs VALUES('desktop-local','legacy','legacy','PRIMARY','legacy-v1',1800)");
            for (String scope : List.of("ROOT", "RUN", "STAGE_BASE", "STAGE", "CLOSING", "CLOSING_RUN")) {
                var maximum = scope.startsWith("CLOSING") ? CLOSING : CAPACITY;
                boolean usage = List.of("ROOT", "RUN", "STAGE").contains(scope);
                for (var dimension : Dimension.values()) try (var insert = connection.prepareStatement("INSERT INTO agent_runtime_budget_balances VALUES(?,?,?,?,?,?,?)")) {
                    insert.setString(1, TENANT.value()); insert.setString(2, scope); insert.setString(3, LEGACY.runId()); insert.setString(4, dimension.name());
                    insert.setLong(5, maximum.value(dimension)); insert.setLong(6, usage ? CONSUMED.value(dimension) : 0);
                    insert.setLong(7, usage ? UNKNOWN.value(dimension) : 0); insert.executeUpdate();
                }
            }
            statement.executeUpdate("INSERT INTO agent_runtime_budget_attempts VALUES('desktop-local','unknown','legacy','unknown-fp','BUSINESS_MODEL',0,'SENT',NULL)");
            statement.executeUpdate("INSERT INTO agent_runtime_budget_attempts VALUES('desktop-local','known','legacy','known-fp','BUSINESS_MODEL',0,'SETTLED','known-result')");
            statement.executeUpdate("INSERT INTO agent_runtime_budget_result_receipts VALUES('desktop-local','known','known-result')");
            statement.executeUpdate("INSERT INTO agent_runtime_budget_closing_targets VALUES('desktop-local','legacy','local:finalize')");
            statement.executeUpdate("INSERT INTO agent_runtime_budget_activity VALUES('desktop-local','legacy','activity','2026-01-01T00:00:00.000000000Z','2026-01-01T00:00:01.000000000Z')");
            for (var id : List.of("unknown", "known")) for (var dimension : Dimension.values()) {
                boolean token = dimension.ordinal() >= Dimension.INPUT_TOKENS.ordinal();
                var resources = id.equals("unknown") ? Resources.call(Category.BUSINESS_MODEL, 10, 20) : Resources.call(Category.BUSINESS_MODEL, 5, 5);
                long reported = id.equals("known") ? CONSUMED.value(dimension) : resources.value(dimension);
                if (dimension == Dimension.BUSINESS_MODELS) reported = 1;
                try (var insert = connection.prepareStatement("INSERT INTO agent_runtime_budget_attempt_amounts VALUES(?,?,?,?,?,?)")) {
                    insert.setString(1, TENANT.value()); insert.setString(2, id); insert.setString(3, dimension.name()); insert.setLong(4, resources.value(dimension));
                    if (id.equals("unknown") && token) insert.setNull(5, java.sql.Types.BIGINT); else insert.setLong(5, reported);
                    insert.setInt(6, id.equals("unknown") && token ? 0 : 1); insert.executeUpdate();
                }
            }
        }
    }
}
