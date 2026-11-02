package com.skillforge.runtime.adapter.model.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.InvocationResult;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.StreamEvent;
import com.skillforge.runtime.api.port.ModelPort.ModelUsage;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Pure test-scope, no JDBC: gate the actual HttpClient send and preserve original Provider oracles. */
final class LiveProviderAttemptMeter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static State active;
    static boolean enabled() { return System.getenv("LIVE_GRADLE_SUITE") != null; }
    private static synchronized State current() {
        if (active == null) active = new State(System.getenv(), Clock.systemUTC(), System::nanoTime);
        return active;
    }

    static final class Outcome implements AfterTestExecutionCallback {
        @Override public void afterTestExecution(ExtensionContext context) {
            if (enabled()) {
                current().outcome(context.getExecutionException().isEmpty() ? "PASS" : "FAIL");
                if (context.getExecutionException().isPresent()) current().stop();
            }
        }
    }

    static Ticket prepare(String provider, String model, String scenario,
            long inputUpperBound, long outputUpperBound, long maximumRequestBytes) {
        if (!enabled()) return null;
        return prepare(current(), provider, model, scenario, inputUpperBound, outputUpperBound, maximumRequestBytes);
    }
    static Ticket prepare(State state, String provider, String model, String scenario,
            long inputUpperBound, long outputUpperBound, long maximumRequestBytes) {
        check(maximumRequestBytes > 0 && scenario != null && !scenario.isBlank(), "wire_fixture_bounds_required");
        String id = UUID.randomUUID().toString();
        check(state.reserve(id, scenario, provider, model, new Bounds(inputUpperBound, outputUpperBound)),
                "whole_provider_lane_gate_rejected");
        return new Ticket(state, id, maximumRequestBytes);
    }
    static HttpClient client(HttpClient delegate, Ticket ticket) {
        return ticket == null ? delegate : new GuardedHttpClient(delegate, ticket);
    }
    static void result(Ticket ticket, InvocationResult result) {
        if (ticket == null) return;
        if (result instanceof InvocationResult.Completed complete)
            ticket.state.complete(ticket.id, "succeeded", complete.usage());
        else {
            ticket.state.stop();
            ModelUsage usage = result instanceof InvocationResult.Rejected rejected ? rejected.usage()
                    : ((InvocationResult.OutcomeUnknown) result).usage();
            if (ticket.sent.get()) {
                ticket.state.usage(ticket.id, ticket.nextRevision(), usage);
                ticket.state.terminalIfOpen(ticket.id, result instanceof InvocationResult.Rejected ? "failed" : "unresolved");
            } else ticket.state.notSent(ticket.id);
        }
    }
    static Flow.Subscriber<StreamEvent> observe(Ticket ticket, Flow.Subscriber<StreamEvent> downstream) {
        if (ticket == null) return downstream;
        return new Flow.Subscriber<>() {
            private boolean terminal;
            @Override public void onSubscribe(Flow.Subscription value) {
                downstream.onSubscribe(new Flow.Subscription() {
                    @Override public void request(long count) { value.request(count); }
                    @Override public void cancel() {
                        if (!terminal) ticket.uncertain();
                        value.cancel();
                    }
                });
            }
            @Override public void onNext(StreamEvent event) {
                if (event instanceof StreamEvent.UsageDelta delta)
                    ticket.state.usage(ticket.id, ticket.nextRevision(), delta.usage());
                else if (event instanceof StreamEvent.Completed complete) {
                    terminal = true; ticket.state.complete(ticket.id, "succeeded", complete.result().usage());
                } else if (event instanceof StreamEvent.Rejected || event instanceof StreamEvent.OutcomeUnknown) {
                    terminal = true; ticket.state.stop();
                    if (ticket.sent.get()) ticket.state.terminalIfOpen(ticket.id,
                            event instanceof StreamEvent.Rejected ? "failed" : "unresolved");
                    else ticket.state.notSent(ticket.id);
                }
                downstream.onNext(event);
            }
            @Override public void onError(Throwable failure) {
                ticket.uncertain(); downstream.onError(failure);
            }
            @Override public void onComplete() {
                if (!terminal) ticket.uncertain();
                downstream.onComplete();
            }
        };
    }
    static void snapshot() {
        if (!enabled()) return;
        State state = current();
        state.snapshot("provider_wire_audit", true, state.providerAttempts(), state.providerRecords());
    }
    static final class Ticket {
        final State state; final String id; final long maxRequestBytes;
        final AtomicBoolean sent = new AtomicBoolean(), bodySubscribed = new AtomicBoolean();
        private long revision;
        Ticket(State state, String id, long maxRequestBytes) { this.state = state; this.id = id; this.maxRequestBytes = maxRequestBytes; }
        synchronized long nextRevision() { return ++revision; }
        void uncertain() {
            state.stop();
            if (sent.get()) state.terminalIfOpen(id, "unresolved");
            else state.notSentIfOpen(id);
        }
    }

    /** Journal fsync completes before the real HTTP send; no redirects or second body subscription. */
    static final class GuardedHttpClient extends HttpClient {
        private final HttpClient delegate;
        private final Ticket ticket;
        GuardedHttpClient(HttpClient delegate, Ticket ticket) {
            check(delegate.followRedirects() == Redirect.NEVER, "live_provider_redirects_forbidden");
            this.delegate = delegate; this.ticket = ticket;
        }
        private HttpRequest guarded(HttpRequest request) {
            HttpRequest.BodyPublisher original = request.bodyPublisher().orElseThrow();
            if (original.contentLength() <= 0 || original.contentLength() > ticket.maxRequestBytes) {
                ticket.uncertain();
                throw new IllegalStateException("provider_request_wire_bound_exceeded_or_unknown");
            }
            HttpRequest.BodyPublisher once = new HttpRequest.BodyPublisher() {
                @Override public long contentLength() { return original.contentLength(); }
                @Override public void subscribe(Flow.Subscriber<? super ByteBuffer> downstream) {
                    if (!ticket.bodySubscribed.compareAndSet(false, true)) {
                        ticket.uncertain();
                        downstream.onSubscribe(new Flow.Subscription() {
                            @Override public void request(long n) { }
                            @Override public void cancel() { }
                        });
                        downstream.onError(new IOException("provider_request_body_resubscription_forbidden"));
                        return;
                    }
                    original.subscribe(downstream);
                }
            };
            HttpRequest guarded = HttpRequest.newBuilder(request, (name, value) -> true)
                    .method(request.method(), once).build();
            if (ticket.sent.get()) {
                ticket.uncertain();
                throw new IllegalStateException("provider_wire_send_repeated");
            }
            // A durable SENT is conservative even if the delegate later throws before body subscription.
            ticket.state.sent(ticket.id);
            check(ticket.sent.compareAndSet(false, true), "provider_wire_send_repeated");
            return guarded;
        }
        @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
                throws IOException, InterruptedException {
            HttpRequest bounded = guarded(request);
            try { return delegate.send(bounded, handler); }
            catch (IOException | InterruptedException | RuntimeException failure) { ticket.uncertain(); throw failure; }
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            HttpRequest bounded = guarded(request);
            try { return delegate.sendAsync(bounded, handler).whenComplete((response, failure) -> {
                if (failure != null) ticket.uncertain();
            }); } catch (RuntimeException failure) { ticket.uncertain(); throw failure; }
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> pushes) {
            HttpRequest bounded = guarded(request);
            try { return delegate.sendAsync(bounded, handler, pushes).whenComplete((response, failure) -> {
                if (failure != null) ticket.uncertain();
            }); } catch (RuntimeException failure) { ticket.uncertain(); throw failure; }
        }
        @Override public Optional<CookieHandler> cookieHandler() { return delegate.cookieHandler(); }
        @Override public Optional<Duration> connectTimeout() { return delegate.connectTimeout(); }
        @Override public Redirect followRedirects() { return delegate.followRedirects(); }
        @Override public Optional<ProxySelector> proxy() { return delegate.proxy(); }
        @Override public SSLContext sslContext() { return delegate.sslContext(); }
        @Override public SSLParameters sslParameters() { return delegate.sslParameters(); }
        @Override public Optional<Authenticator> authenticator() { return delegate.authenticator(); }
        @Override public Version version() { return delegate.version(); }
        @Override public Optional<Executor> executor() { return delegate.executor(); }
        @Override public void shutdown() { delegate.shutdown(); }
        @Override public void shutdownNow() { delegate.shutdownNow(); }
        @Override public boolean awaitTermination(Duration duration) throws InterruptedException { return delegate.awaitTermination(duration); }
        @Override public boolean isTerminated() { return delegate.isTerminated(); }
        @Override public void close() { delegate.close(); }
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
            String suite = LiveProviderAttemptMeter.required(env, "LIVE_GRADLE_SUITE");
            caps = expectedCaps(suite);
            for (var entry : caps.entrySet()) check(Long.parseLong(LiveProviderAttemptMeter.required(env,
                    "LIVE_GRADLE_" + entry.getKey().toUpperCase(java.util.Locale.ROOT))) == entry.getValue(), "live_lane_cap_drift");
            int round = Integer.parseInt(LiveProviderAttemptMeter.required(env, "LIVE_GRADLE_ROUND"));
            check(round >= 1 && round <= 3 && "full-project-test-20261012".equals(LiveProviderAttemptMeter.required(env, "LIVE_GRADLE_RUN_ID")), "live_lane_run_or_round_invalid");
            String reservation = LiveProviderAttemptMeter.required(env, "LIVE_GRADLE_RESERVATION_ID");
            UUID.fromString(reservation);
            owner = Map.of("schema_version", 1, "run_id", "full-project-test-20261012", "suite", suite,
                    "round", round, "reservation_id", reservation, "batch_id", LiveProviderAttemptMeter.required(env, "LIVE_GRADLE_BATCH_ID"));
            root = Path.of(LiveProviderAttemptMeter.required(env, "LIVE_GRADLE_EVIDENCE_DIR")).toAbsolutePath().normalize();
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
                case "provider-smoke" -> caps(4, 40000, 600);
                case "provider-cache" -> caps(24, 800000, 1800);
                default -> throw new IllegalStateException("unselected_provider_live_lane");
            };
        }

        private static Map<String, Long> caps(long attempts, long tokens, long seconds) {
            return Map.of("max_attempts", attempts, "max_tokens", tokens, "max_seconds", seconds);
        }
        public synchronized void registerTenant(String tenant) { check(tenant != null && !tenant.isBlank(), "owned_tenant_required"); tenants.add(tenant); }
        public synchronized Set<String> tenants() { return Set.copyOf(tenants); }
        public synchronized void outcome(String value) { check(Set.of("PASS", "FAIL").contains(value), "test_outcome_invalid"); if (!"FAIL".equals(outcome)) if (!"FAIL".equals(outcome)) outcome = value; }
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
        public synchronized void notSentIfOpen(String id) {
            Attempt attempt = required(id);
            if (!attempt.sent && attempt.terminal == null) notSent(id);
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
