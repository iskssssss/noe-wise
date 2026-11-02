package com.skillforge.institution.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.adapter.model.ModelInvocationAudit;
import com.skillforge.runtime.api.port.ModelPort.ModelCall;
import com.skillforge.runtime.api.port.ModelPort.ModelUsage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** 仅显式实时测试使用的发送前持久预约；生产 audit 和冻结 Runtime Demand 保持权威。 */
public final class LiveGradleAttemptMeter implements ModelInvocationAudit {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static State active;
    private final ModelInvocationAudit delegate;
    private final DataSource source;
    private final Bounds visionBounds;
    private final State state;

    private LiveGradleAttemptMeter(ModelInvocationAudit delegate, DataSource source, Bounds visionBounds, State state) {
        this.delegate = delegate; this.source = source; this.visionBounds = visionBounds; this.state = state;
    }

    public static ModelInvocationAudit wrap(ModelInvocationAudit delegate, DataSource source) {
        if (!enabled()) return delegate;
        return new LiveGradleAttemptMeter(delegate, source, null, current());
    }

    static ModelInvocationAudit wrapForTest(ModelInvocationAudit delegate, DataSource source, State state) {
        return new LiveGradleAttemptMeter(delegate, source, null, state);
    }

    public static ModelInvocationAudit vision(long inputUpperBound, long outputUpperBound) {
        check("vision".equals(System.getenv("LIVE_GRADLE_SUITE")), "vision_lane_identity_required");
        return new LiveGradleAttemptMeter(ModelInvocationAudit.noop(), null,
                new Bounds(inputUpperBound, outputUpperBound), current());
    }

    public static boolean enabled() { return System.getenv("LIVE_GRADLE_SUITE") != null; }
    private static synchronized State current() {
        if (active == null) active = new State(System.getenv(), Clock.systemUTC(), System::nanoTime);
        return active;
    }

    public static void registerOwnedTenant(String tenant) {
        if (enabled()) current().registerTenant(tenant);
    }

    /** JUnit body outcome is recorded before @AfterEach; the supervisor also checks cleanup/JUnit failures. */
    public static final class Outcome implements AfterTestExecutionCallback {
        @Override public void afterTestExecution(ExtensionContext context) {
            if (enabled()) {
                current().outcome(context.getExecutionException().isEmpty() ? "PASS" : "FAIL");
                if (context.getExecutionException().isPresent()) current().stop();
            }
        }
    }

    @Override public boolean start(ModelCall call, Provider provider) {
        synchronized (state) {
            String attempt = attempt(call), run = run(call), tenant = tenant(call);
            state.registerTenant(tenant);
            if (state.contains(attempt)) return false;
            Bounds bounds = visionBounds == null ? demand(source, call) : visionBounds;
            check(call.generation().maxOutputTokens().isPresent()
                    && call.generation().maxOutputTokens().getAsInt() <= bounds.output(), "frozen_output_bound_mismatch");
            if (!state.reserve(attempt, run, provider.providerId(), provider.modelId(), bounds)) return false;
            try {
                if (!delegate.start(call, provider)) { state.notSent(attempt); return false; }
                // Returning true is the Adapter's permission to send; durable journal must precede that return.
                state.sent(attempt);
                return true;
            } catch (RuntimeException failure) {
                state.abortStart(attempt);
                throw failure;
            }
        }
    }

    @Override public void usageReported(ModelCall call, Provider provider, long revision, ModelUsage usage) {
        synchronized (state) {
            state.usage(attempt(call), revision, usage);
            try { delegate.usageReported(call, provider, revision, usage); }
            catch (RuntimeException failure) { state.stop(); throw failure; }
        }
    }

    @Override public void succeeded(ModelCall call, Provider provider, ModelUsage usage) {
        synchronized (state) {
            String attempt = attempt(call);
            try {
                delegate.succeeded(call, provider, usage);
                state.complete(attempt, "succeeded", usage);
            } catch (RuntimeException failure) {
                state.terminalIfOpen(attempt, "unresolved");
                throw failure;
            }
        }
    }

    @Override public void failed(ModelCall call, Provider provider, String reason) {
        synchronized (state) {
            state.stop();
            try { delegate.failed(call, provider, reason); }
            finally { state.terminalIfOpen(attempt(call), "failed"); }
        }
    }

    @Override public void unresolved(ModelCall call, Provider provider, String reason) {
        synchronized (state) {
            state.stop();
            try { delegate.unresolved(call, provider, reason); }
            finally { state.terminalIfOpen(attempt(call), "unresolved"); }
        }
    }

    private static String attempt(ModelCall call) { return call.context().dispatch().attemptId(); }
    private static String run(ModelCall call) { return call.context().callContext().runIdentity().runRef().runId(); }
    private static String tenant(ModelCall call) { return call.context().callContext().runIdentity().runRef().tenantId().value(); }

    /** Read the exact original demand already marked SENT by the real Store; never infer tokens from text length. */
    static Bounds demand(DataSource source, ModelCall call) {
        check(source != null, "owned_budget_source_required");
        String sql = "SELECT a.run_id,a.category,a.state,m.dimension,m.reserved FROM public.agent_runtime_budget_attempts a "
                + "JOIN public.agent_runtime_budget_attempt_amounts m ON m.tenant_id=a.tenant_id AND m.attempt_id=a.attempt_id "
                + "WHERE a.tenant_id=? AND a.attempt_id=?";
        try (Connection connection = source.getConnection()) {
            connection.setReadOnly(true); connection.setAutoCommit(false);
            try (PreparedStatement bind = connection.prepareStatement("SELECT set_config('app.tenant_id',?,true)")) {
                bind.setString(1, tenant(call)); bind.execute();
            }
            Long input = null, output = null;
            int rows = 0;
            try (PreparedStatement query = connection.prepareStatement(sql)) {
                query.setString(1, tenant(call)); query.setString(2, attempt(call));
                try (ResultSet result = query.executeQuery()) {
                    while (result.next()) {
                        rows++;
                        check(run(call).equals(result.getString(1)) && call.budgetCategory().name().equals(result.getString(2))
                                && Set.of("BUSINESS_MODEL", "AUXILIARY_MODEL").contains(result.getString(2))
                                && "SENT".equals(result.getString(3)), "frozen_budget_attempt_owner_or_state_mismatch");
                        String dimension = result.getString(4);
                        long value = result.getLong(5);
                        check(!result.wasNull() && value >= 0, "frozen_budget_amount_invalid");
                        if ("INPUT_TOKENS".equals(dimension)) { check(input == null, "duplicate_input_budget_amount"); input = value; }
                        if ("OUTPUT_TOKENS".equals(dimension)) { check(output == null, "duplicate_output_budget_amount"); output = value; }
                    }
                }
            }
            connection.rollback();
            check(rows > 0 && input != null && output != null, "frozen_budget_token_dimensions_missing");
            return new Bounds(input, output);
        } catch (java.sql.SQLException failure) { throw new IllegalStateException("owned_budget_read_failed"); }
    }

    /** Call in finally/@AfterEach before the owned Testcontainer is destroyed, even after a failed assertion. */
    public static void snapshot(DataSource source, String tenant) {
        if (!enabled()) return;
        State meter = current();
        if (tenant != null) meter.registerTenant(tenant);
        List<Map<String, Object>> attempts = new ArrayList<>(), records = new ArrayList<>();
        boolean complete = true;
        try {
            check(source != null && !meter.tenants().isEmpty(), "owned_collector_tenant_required");
            for (String owned : meter.tenants()) collect(source, owned, attempts, records);
        } catch (Exception failure) { complete = false; meter.stop(); }
        meter.snapshot("owned_testcontainer_jdbc", complete, attempts, records);
    }

    public static void visionSnapshot() {
        State meter = current();
        meter.snapshot("vision_model_audit", true, meter.providerAttempts(), meter.providerRecords());
    }

    private static void collect(DataSource source, String tenant, List<Map<String, Object>> attempts,
            List<Map<String, Object>> records) throws java.sql.SQLException {
        try (Connection connection = source.getConnection()) {
            connection.setReadOnly(true); connection.setAutoCommit(false);
            try (PreparedStatement bind = connection.prepareStatement("SELECT set_config('app.tenant_id',?,true)")) {
                bind.setString(1, tenant); bind.execute();
            }
            read(connection, tenant, "SELECT attempt_id,invocation_id,operation_type,status,provider,metadata->>'model' AS model,root_input_id,source_event_id "
                    + "FROM public.nw_external_invocations WHERE tenant_id=? AND operation_type='model.chat' ORDER BY id", attempts);
            read(connection, tenant, "SELECT id::text,event_id,attempt_id,event_type,usage_event_kind,report_revision::text,provider,model,session_id,run_id,"
                    + "tokens::text,prompt_tokens::text,completion_tokens::text,usage_source,total_amount_cents::text,currency "
                    + "FROM public.nw_usage_events WHERE tenant_id=? AND event_type='llm.chat' ORDER BY id", records);
            connection.rollback();
        }
    }

    private static void read(Connection connection, String tenant, String sql, List<Map<String, Object>> output)
            throws java.sql.SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, Long.parseLong(tenant));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int index = 1; index <= result.getMetaData().getColumnCount(); index++)
                        row.put(result.getMetaData().getColumnLabel(index).toLowerCase(java.util.Locale.ROOT), result.getString(index));
                    output.add(row);
                }
            }
        }
    }

    public record Bounds(long input, long output) {
        public Bounds { check(input >= 0 && output >= 0 && Math.addExact(input, output) > 0, "positive_token_reservation_required"); }
        public long total() { return Math.addExact(input, output); }
    }

    /** Test-only state shared by all attempts in one JVM lane; injectable clock/sink support deterministic controls. */
    public static final class State {
        private final Map<String, Object> owner;
        private final Map<String, Long> caps;
        private final Path root;
        private final Clock clock;
        private final LongSupplier monotonic;
        private final long started;
        private final Map<String, Attempt> attempts = new LinkedHashMap<>();
        private final List<Map<String, Object>> reports = new ArrayList<>();
        private final Set<String> tenants = new LinkedHashSet<>();
        private final Sink sink;
        private long sequence, chargedOrReserved;
        private int sent;
        private boolean stopped;
        private String outcome = "UNKNOWN";

        @FunctionalInterface public interface Sink { void append(Map<String, Object> row); }

        public State(Map<String, String> env, Clock clock, LongSupplier monotonic) {
            this(env, clock, monotonic, null);
        }

        public State(Map<String, String> env, Clock clock, LongSupplier monotonic, Sink supplied) {
            this.clock = clock; this.monotonic = monotonic; this.started = monotonic.getAsLong();
            String suite = LiveGradleAttemptMeter.required(env, "LIVE_GRADLE_SUITE");
            caps = expectedCaps(suite);
            for (var entry : caps.entrySet()) check(Long.parseLong(LiveGradleAttemptMeter.required(env,
                    "LIVE_GRADLE_" + entry.getKey().toUpperCase(java.util.Locale.ROOT))) == entry.getValue(), "live_lane_cap_drift");
            int round = Integer.parseInt(LiveGradleAttemptMeter.required(env, "LIVE_GRADLE_ROUND"));
            check(round >= 1 && round <= 3 && "full-project-test-20261012".equals(LiveGradleAttemptMeter.required(env, "LIVE_GRADLE_RUN_ID")), "live_lane_run_or_round_invalid");
            String reservation = LiveGradleAttemptMeter.required(env, "LIVE_GRADLE_RESERVATION_ID");
            UUID.fromString(reservation);
            owner = Map.of("schema_version", 1, "run_id", "full-project-test-20261012", "suite", suite,
                    "round", round, "reservation_id", reservation, "batch_id", LiveGradleAttemptMeter.required(env, "LIVE_GRADLE_BATCH_ID"));
            root = Path.of(LiveGradleAttemptMeter.required(env, "LIVE_GRADLE_EVIDENCE_DIR")).toAbsolutePath().normalize();
            check(Files.isDirectory(root), "owned_evidence_root_required");
            if (supplied == null) {
                Path journal = root.resolve("attempts.jsonl");
                try { Files.createFile(journal); }
                catch (IOException failure) { throw new IllegalStateException("fresh_attempt_journal_required"); }
                sink = row -> append(journal, row);
            } else sink = supplied;
        }

        private static Map<String, Long> expectedCaps(String suite) {
            return switch (suite) {
                case "conversation" -> caps(2, 20000, 600);
                case "tool-conversation" -> caps(3, 40000, 600);
                case "chat-browser" -> caps(1, 20000, 600);
                case "prompt-cache" -> caps(18, 400000, 600);
                case "operational-config" -> caps(6, 400000, 480);
                case "capability" -> caps(16, 160000, 1800);
                case "public" -> caps(64, 3000000, 7200);
                case "vision" -> caps(6, 60000, 600);
                default -> throw new IllegalStateException("unselected_institution_live_lane");
            };
        }

        private static Map<String, Long> caps(long attempts, long tokens, long seconds) {
            return Map.of("max_attempts", attempts, "max_tokens", tokens, "max_seconds", seconds);
        }
        public synchronized void registerTenant(String tenant) { check(tenant != null && !tenant.isBlank(), "owned_tenant_required"); tenants.add(tenant); }
        public synchronized Set<String> tenants() { return Set.copyOf(tenants); }
        public synchronized void outcome(String value) { check(Set.of("PASS", "FAIL").contains(value), "test_outcome_invalid"); if (!"FAIL".equals(outcome)) outcome = value; }
        public synchronized boolean contains(String id) { return attempts.containsKey(id); }
        public synchronized void stop() { stopped = true; }
        public synchronized boolean stopped() { return stopped; }

        public synchronized boolean reserve(String id, String run, String provider, String model, Bounds bounds) {
            if (stopped || expired() || attempts.containsKey(id) || sent >= caps.get("max_attempts")
                    || bounds.total() > caps.get("max_tokens") - chargedOrReserved) return false;
            check(id != null && !id.isBlank() && run != null && !run.isBlank(), "attempt_and_run_identity_required");
            Attempt attempt = new Attempt(id, run, provider, model, bounds);
            emit(attempt, "reserved", Map.of("reserved_input_tokens", bounds.input(), "reserved_output_tokens", bounds.output()));
            attempts.put(id, attempt); chargedOrReserved = Math.addExact(chargedOrReserved, bounds.total());
            return true;
        }
        public synchronized void sent(String id) {
            Attempt attempt = required(id);
            check(!attempt.sent && attempt.terminal == null && !stopped && !expired(), "attempt_send_gate_rejected");
            check(sent < caps.get("max_attempts"), "attempt_count_cap_exhausted");
            emit(attempt, "sent", Map.of()); attempt.sent = true; sent++;
        }
        public synchronized void notSent(String id) {
            Attempt attempt = required(id);
            check(!attempt.sent && attempt.terminal == null, "not_sent_after_possible_dispatch");
            emit(attempt, "not_sent", Map.of()); attempt.terminal = "not_sent"; chargedOrReserved -= attempt.bounds.total();
        }
        public synchronized void abortStart(String id) {
            stopped = true;
            Attempt attempt = required(id);
            if (!attempt.sent && attempt.terminal == null) notSent(id);
            else if (attempt.sent && attempt.terminal == null) terminalIfOpen(id, "unresolved");
        }
        public synchronized void usage(String id, long revision, ModelUsage usage) {
            Attempt attempt = required(id);
            check(attempt.sent && attempt.terminal == null && revision > attempt.revision, "usage_report_not_active_or_revision_invalid");
            emit(attempt, "usage_report", Map.of("revision", revision));
            attempt.revision = revision;
            if (usage.inputTokens().isPresent()) attempt.input = usage.inputTokens();
            if (usage.outputTokens().isPresent()) attempt.output = usage.outputTokens();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", Long.toString(reports.size() + 1L)); row.put("event_id", id + ":usage:" + revision);
            row.put("attempt_id", id); row.put("usage_event_kind", "usage_report"); row.put("report_revision", Long.toString(revision));
            row.put("provider", attempt.provider); row.put("model", attempt.model); row.put("run_id", attempt.run);
            row.put("usage_source", "PROVIDER_REPORTED");
            row.put("prompt_tokens", usage.inputTokens().isPresent() ? Long.toString(usage.inputTokens().getAsLong()) : null);
            row.put("completion_tokens", usage.outputTokens().isPresent() ? Long.toString(usage.outputTokens().getAsLong()) : null);
            reports.add(row);
        }
        public synchronized void complete(String id, String terminal, ModelUsage usage) {
            Attempt attempt = required(id);
            // Completed.usage is an actual Provider snapshot, independently of earlier partial UsageDelta reports.
            usage(id, Math.addExact(attempt.revision, 1), usage);
            terminalIfOpen(id, terminal);
        }
        public synchronized void terminalIfOpen(String id, String terminal) {
            Attempt attempt = required(id);
            if (attempt.terminal != null) return;
            check(attempt.sent && Set.of("succeeded", "failed", "unresolved").contains(terminal), "attempt_terminal_invalid");
            if (!"succeeded".equals(terminal) || attempt.input.isEmpty() || attempt.output.isEmpty()) stopped = true;
            if (attempt.input.isPresent() && attempt.output.isPresent()) {
                long actual = Math.addExact(attempt.input.getAsLong(), attempt.output.getAsLong());
                chargedOrReserved = Math.addExact(chargedOrReserved - attempt.bounds.total(), actual);
                if (attempt.input.getAsLong() > attempt.bounds.input() || attempt.output.getAsLong() > attempt.bounds.output()
                        || chargedOrReserved > caps.get("max_tokens")) stopped = true;
            }
            emit(attempt, terminal, Map.of()); attempt.terminal = terminal;
        }
        public synchronized List<Map<String, Object>> providerAttempts() {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Attempt attempt : attempts.values()) if (attempt.sent) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("attempt_id", attempt.id); row.put("invocation_id", attempt.id + ":invocation"); row.put("operation_type", "model.chat");
                row.put("provider", attempt.provider); row.put("model", attempt.model);
                row.put("status", attempt.terminal == null ? "STARTED" : attempt.terminal.toUpperCase(java.util.Locale.ROOT));
                rows.add(row);
            }
            return rows;
        }
        public synchronized List<Map<String, Object>> providerRecords() { return List.copyOf(reports); }
        public synchronized void snapshot(String source, boolean collectionComplete, List<Map<String, Object>> supplierAttempts,
                List<Map<String, Object>> supplierRecords) {
            Map<String, Object> document = new LinkedHashMap<>(owner);
            document.put("caps", caps); document.put("source", source); document.put("collection_complete", collectionComplete);
            document.put("captured_at", clock.instant().toString()); document.put("acceptance_outcome", outcome);
            document.put("attempts", supplierAttempts); document.put("provider_records", supplierRecords);
            document.put("stop_new_dispatch", stopped); document.put("charged_or_reserved_tokens", chargedOrReserved);
            writeSnapshot(root.resolve("attempt-evidence.json"), document);
        }
        private boolean expired() { return monotonic.getAsLong() - started >= Math.multiplyExact(caps.get("max_seconds"), 1_000_000_000L); }
        private Attempt required(String id) { Attempt result = attempts.get(id); check(result != null, "attempt_not_reserved"); return result; }
        private void emit(Attempt attempt, String event, Map<String, Object> fields) {
            Map<String, Object> row = new LinkedHashMap<>(owner);
            row.put("sequence", sequence + 1); row.put("time", clock.instant().toString());
            row.put("attempt_id", attempt.id); row.put("run_id_owned", attempt.run); row.put("event", event); row.putAll(fields);
            try { sink.append(row); sequence++; }
            catch (RuntimeException failure) { stopped = true; throw failure; }
        }
        private static final class Attempt {
            final String id, run, provider, model; final Bounds bounds;
            boolean sent; String terminal; long revision;
            OptionalLong input = OptionalLong.empty(), output = OptionalLong.empty();
            Attempt(String id, String run, String provider, String model, Bounds bounds) {
                this.id = id; this.run = run; this.provider = provider; this.model = model; this.bounds = bounds;
            }
        }
    }

    private static void append(Path path, Map<String, Object> row) {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer bytes = ByteBuffer.wrap((JSON.writeValueAsString(row) + "\n").getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        } catch (IOException failure) { throw new IllegalStateException("attempt_journal_persist_failed"); }
    }
    private static void writeSnapshot(Path path, Map<String, Object> document) {
        Path temporary = path.resolveSibling(path.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(document);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException failure) { throw new IllegalStateException("attempt_snapshot_persist_failed"); }
    }
    private static String required(Map<String, String> env, String name) {
        String value = env.get(name); check(value != null && !value.isBlank(), "missing_live_meter_env:" + name); return value;
    }
    private static void check(boolean condition, String code) { if (!condition) throw new IllegalStateException(code); }
}
