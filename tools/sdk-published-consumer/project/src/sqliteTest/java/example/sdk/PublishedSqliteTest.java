package example.sdk;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.adapter.sqlite.SqliteExecutionStore;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 正式 SQLite JAR 在没有 Spring 和测试夹具的独立类路径中持久化并恢复运行。 */
final class PublishedSqliteTest {
    @TempDir Path directory;

    @Test
    void persistsAdmissionAndLeaseAcrossStoreReconstructionWithoutSpring() throws Exception {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.springframework.context.ApplicationContext"));
        assertTrue(SqliteExecutionStore.class.getProtectionDomain().getCodeSource().getLocation().getPath().endsWith(".jar"));
        assertTrue(ExecutionStore.class.getProtectionDomain().getCodeSource().getLocation().getPath().endsWith(".jar"));
        var file = directory.resolve("published-runtime.db");
        var now = Instant.parse("2026-10-23T01:02:03.123456789Z");
        var clock = Clock.fixed(now, ZoneOffset.UTC);
        var tenant = new TenantId("published-sqlite");
        var owner = SessionOwner.user(new SessionRef(tenant, "session"), new AgentId("agent"), new EndUserId(1));
        var input = new InputId("input");
        var run = new RunRef(tenant, "run");
        var verified = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(tenant, owner.requireUser()), owner,
                Set.of("sessions.write"), 1, new ResultRef("identity"));
        var identity = new RunIdentity(owner, new AgentId("agent"), input, run,
                new TraceEnvelope("trace", "span", input, Optional.empty()), verified.verificationRef(),
                new RunRequester.EndUser(owner.requireUser()), Optional.empty(), new RunOriginRef(tenant, "origin"));
        var budget = new ExecutionBudgetPort.Registration(run, run, ExecutionBudgetPort.Role.PRIMARY,
                new ExecutionBudgetPort.Policy.ObserveOnly(ExecutionBudgetPort.Scenario.STANDARD, "published-v1"));
        var payload = new TypedPayload("published-input-v1", "\"hello\"");
        var original = new ExecutionStore.Material(new ResultRef("input-material"), payload);
        var context = new ExecutionStore.Material(new ResultRef("context-material"), payload);
        var admission = new ExecutionStore.Admission(verified, identity, "fingerprint", new ResultRef("configuration"),
                original, context, budget, List.of(), List.of());
        ExecutionStore.Run claimed;
        try (var store = new SqliteExecutionStore(file, clock)) {
            var created = store.admit(admission);
            assertTrue(created.created());
            assertEquals(now, created.run().createdAt());
            assertFalse(store.admit(admission).created());
            claimed = store.claim(new ExecutionStore.Claim(run, "worker", "claim", Duration.ofMinutes(2))).orElseThrow();
            assertTrue(store.claim(new ExecutionStore.Claim(run, "competitor", "competing-claim", Duration.ofMinutes(2))).isEmpty());
            var changed = new ExecutionStore.Admission(verified, identity, "changed-fingerprint", admission.configuration(),
                    original, context, budget, List.of(), List.of());
            assertThrows(ExecutionStore.Rejection.class, () -> store.admit(changed));
        }
        try (var reopened = new SqliteExecutionStore(file, clock)) {
            assertEquals(claimed, reopened.load(run).orElseThrow());
            assertEquals(List.of(Optional.of(context), Optional.empty(), Optional.of(original), Optional.of(context)),
                    reopened.materials(tenant, List.of(context.reference(), new ResultRef("missing"), original.reference(), context.reference())));
            assertTrue(reopened.materials(new TenantId("other-tenant"), List.of(original.reference())).getFirst().isEmpty());
            assertEquals(original, reopened.originalInput(run).orElseThrow());
            assertEquals(1, reopened.runsForSession(owner.sessionRef()).size());
            assertTrue(reopened.load(new RunRef(new TenantId("other-tenant"), run.runId())).isEmpty());
        }
        System.out.println("SDK_PUBLISHED_SQLITE_CONSUMPTION_COMPLETE restarted=1 spring=0 lease_conflict_rejected=1");
    }
}
