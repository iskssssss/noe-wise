package example.sdk;

import com.skillforge.runtime.adapter.sqlite.SqliteExecutionStore;
import com.skillforge.runtime.adapter.testkit.ExecutionStoreContract;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

/** 外部消费方实际继承正式 test-fixtures JAR；实现使用正式 SQLite JAR 与每例独立文件。 */
final class PublishedExecutionStoreContractTest extends ExecutionStoreContract {
    @TempDir Path directory;
    private SqliteExecutionStore first, rebuilt;

    @BeforeEach void open() {
        var origin = ExecutionStoreContract.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        if (!origin.endsWith("-test-fixtures.jar")) throw new IllegalStateException("contract_not_from_published_fixture_jar");
        var file = directory.resolve("contract.db"); var clock = Clock.systemUTC();
        first = new SqliteExecutionStore(file, clock); rebuilt = new SqliteExecutionStore(file, clock);
    }
    @AfterEach void close() {
        try { if (rebuilt != null) rebuilt.close(); } finally { if (first != null) first.close(); }
    }
    @Override protected ExecutionStore firstStore() { return first; }
    @Override protected ExecutionStore rebuiltStore() { return rebuilt; }
    @Override protected ExecutionStore.Admission newAdmission(String suffix) {
        var tenant = new TenantId("published-contract");
        var owner = SessionOwner.user(new SessionRef(tenant, "session-" + suffix), new AgentId("agent"), new EndUserId(1));
        var input = new InputId("input-" + suffix); var run = new RunRef(tenant, "run-" + suffix);
        var actor = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(tenant, owner.requireUser()), owner,
                Set.of("sessions.write"), 1, new ResultRef("identity-" + suffix));
        var identity = new RunIdentity(owner, owner.target().requireAgent(), input, run,
                new TraceEnvelope("trace", "span", input, Optional.empty()), actor.verificationRef(),
                new RunRequester.EndUser(owner.requireUser()), Optional.empty(), new RunOriginRef(tenant, input.value()));
        var budget = new ExecutionBudgetPort.Registration(run, run, ExecutionBudgetPort.Role.PRIMARY,
                new ExecutionBudgetPort.Policy.ObserveOnly(ExecutionBudgetPort.Scenario.STANDARD, "published-contract-v1"));
        return new ExecutionStore.Admission(actor, identity, "fingerprint-" + suffix, new ResultRef("config-" + suffix),
                material("input-" + suffix), material("context-" + suffix), budget, List.of(), List.of());
    }
    private static ExecutionStore.Material material(String reference) {
        return new ExecutionStore.Material(new ResultRef(reference), new TypedPayload("published-contract.v1", "{}"));
    }
}
