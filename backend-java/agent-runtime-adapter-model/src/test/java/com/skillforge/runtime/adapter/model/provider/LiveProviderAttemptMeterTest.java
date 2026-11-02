package com.skillforge.runtime.adapter.model.provider;

import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.runtime.adapter.model.provider.LiveProviderAttemptMeter.*;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.InvocationResult;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.StreamEvent;
import com.skillforge.runtime.api.port.ModelPort.ModelUsage;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** No network: checks the consumer-visible HttpClient boundary, durable gate ordering, and whole-lane stop. */
class LiveProviderAttemptMeterTest {
    @TempDir Path root;
    final List<Map<String, Object>> journal = new ArrayList<>();
    final AtomicLong elapsed = new AtomicLong();
    private Map<String, String> environment() {
        return new LinkedHashMap<>(Map.of("LIVE_GRADLE_SUITE", "provider-smoke", "LIVE_GRADLE_RUN_ID", "full-project-test-20261012",
            "LIVE_GRADLE_ROUND", "1", "LIVE_GRADLE_RESERVATION_ID", "1b0cc243-311d-47a6-a49c-cbf8d0cd8f30",
            "LIVE_GRADLE_BATCH_ID", "offline-control", "LIVE_GRADLE_EVIDENCE_DIR", root.toString(),
            "LIVE_GRADLE_MAX_ATTEMPTS", "4", "LIVE_GRADLE_MAX_TOKENS", "40000", "LIVE_GRADLE_MAX_SECONDS", "600"));
    }
    private State state() {
        return new State(environment(), Clock.fixed(Instant.parse("2026-10-12T00:00:00Z"), ZoneOffset.UTC), elapsed::get,
            row -> journal.add(new LinkedHashMap<>(row)));
    }
    private Ticket ticket(State state, String scenario) {
        return LiveProviderAttemptMeter.prepare(state, "fixture-provider", "fixture-model", scenario, 8192, 128, 4096);
    }
    private HttpRequest request() {
        return HttpRequest.newBuilder(URI.create("https://fixture.invalid/chat/completions"))
            .POST(HttpRequest.BodyPublishers.ofString("{}")) .build();
    }
    private ModelUsage usage(long input, long output) { return new ModelUsage(OptionalLong.of(input), OptionalLong.of(output)); }

    @Test void durableSentPrecedesSyncAndAsyncDelegateBoundary() throws Exception {
        State state = state();
        FakeHttp sync = new FakeHttp(() -> assertEquals("sent", journal.getLast().get("event")));
        Ticket first = ticket(state, "sync");
        LiveProviderAttemptMeter.client(sync, first).send(request(), HttpResponse.BodyHandlers.discarding());
        LiveProviderAttemptMeter.result(first, new InvocationResult.Completed("OK", List.of(), "fixture-model", usage(10, 1), "stop"));
        FakeHttp async = new FakeHttp(() -> assertEquals("sent", journal.getLast().get("event")));
        Ticket second = ticket(state, "async");
        LiveProviderAttemptMeter.client(async, second).sendAsync(request(), HttpResponse.BodyHandlers.discarding()).join();
        assertEquals(1, sync.calls); assertEquals(1, async.calls);
        assertEquals(2, journal.stream().filter(row -> "sent".equals(row.get("event"))).count());
    }

    @Test void sentFsyncFailureMakesZeroDelegateCalls() {
        State state = new State(environment(), Clock.systemUTC(), elapsed::get, row -> {
            if ("sent".equals(row.get("event"))) throw new IllegalStateException("injected_persistence_failure");
        });
        FakeHttp delegate = new FakeHttp(() -> fail("delegate must not run"));
        HttpClient client = LiveProviderAttemptMeter.client(delegate, ticket(state, "fsync"));
        assertThrows(IllegalStateException.class, () -> client.send(request(), HttpResponse.BodyHandlers.discarding()));
        assertEquals(0, delegate.calls); assertTrue(state.stopped());
        assertThrows(IllegalStateException.class, () -> ticket(state, "next-method"));
    }

    @Test void expiredGateMakesZeroDelegateCalls() {
        State state = state(); Ticket ticket = ticket(state, "expired");
        elapsed.set(Duration.ofSeconds(600).toNanos());
        FakeHttp delegate = new FakeHttp(() -> fail("expired delegate must not run"));
        HttpClient client = LiveProviderAttemptMeter.client(delegate, ticket);
        assertThrows(IllegalStateException.class, () -> client.send(request(), HttpResponse.BodyHandlers.discarding()));
        assertEquals(0, delegate.calls);
    }

    @Test void duplicateWireSendAndSecondBodySubscriptionStopWholeLane() throws Exception {
        State state = state(); Ticket ticket = ticket(state, "duplicate");
        FakeHttp delegate = new FakeHttp(() -> { });
        HttpClient client = LiveProviderAttemptMeter.client(delegate, ticket);
        client.send(request(), HttpResponse.BodyHandlers.discarding());
        assertThrows(IllegalStateException.class, () -> client.send(request(), HttpResponse.BodyHandlers.discarding()));
        assertEquals(1, delegate.calls); assertTrue(state.stopped());
        assertThrows(IllegalStateException.class, () -> ticket(state, "other-method"));

        State second = state(); FakeHttp duplicateBody = new FakeHttp(() -> { });
        duplicateBody.subscribeBodyTwice = true;
        LiveProviderAttemptMeter.client(duplicateBody, ticket(second, "body-repeat"))
            .send(request(), HttpResponse.BodyHandlers.discarding());
        assertEquals(1, duplicateBody.completeBodies); assertEquals(1, duplicateBody.bodyErrors);
        assertTrue(second.stopped());
        assertEquals("unresolved", journal.getLast().get("event"));
    }

    @Test void unknownTerminalStopsOtherMethodsAndPreservesPartialUsage() throws Exception {
        State state = state(); Ticket ticket = ticket(state, "unknown");
        FakeHttp delegate = new FakeHttp(() -> { });
        LiveProviderAttemptMeter.client(delegate, ticket).send(request(), HttpResponse.BodyHandlers.discarding());
        var subscriber = LiveProviderAttemptMeter.observe(ticket, new Discard());
        subscriber.onNext(new StreamEvent.UsageDelta(1, new ModelUsage(OptionalLong.of(17), OptionalLong.empty())));
        subscriber.onNext(new StreamEvent.OutcomeUnknown("fixture_unknown"));
        assertTrue(state.stopped());
        assertThrows(IllegalStateException.class, () -> ticket(state, "next-method"));
        assertEquals("17", state.providerRecords().getLast().get("prompt_tokens"));
        assertNull(state.providerRecords().getLast().get("completion_tokens"));
        assertEquals("UNRESOLVED", state.providerAttempts().getFirst().get("status"));
    }

    @Test void cumulativeUsageRevisionsAndCacheSubsetsAreNeverAdded() throws Exception {
        State state = state(); Ticket ticket = ticket(state, "usage");
        LiveProviderAttemptMeter.client(new FakeHttp(() -> { }), ticket)
            .send(request(), HttpResponse.BodyHandlers.discarding());
        var observer = LiveProviderAttemptMeter.observe(ticket, new Discard());
        observer.onNext(new StreamEvent.UsageDelta(1, usage(100, 2)));
        ModelUsage finalUsage = new ModelUsage(OptionalLong.of(120), OptionalLong.of(3),
            OptionalLong.of(100), OptionalLong.of(10), OptionalLong.of(2));
        observer.onNext(new StreamEvent.Completed(new InvocationResult.Completed("OK", List.of(), "fixture-model", finalUsage, "stop")));
        state.outcome("PASS"); state.snapshot("provider_wire_audit", true, state.providerAttempts(), state.providerRecords());
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(root.resolve("attempt-evidence.json").toFile());
        assertEquals(123, json.path("charged_or_reserved_tokens").asLong());
        assertEquals(2, state.providerRecords().size());
        assertEquals("120", state.providerRecords().getLast().get("prompt_tokens"));
        assertEquals("3", state.providerRecords().getLast().get("completion_tokens"));
        assertFalse(state.stopped());
    }

    @Test void missingUsageAndActualExcessBothStopNextDispatchWithoutZeroSubstitution() throws Exception {
        State missing = state(); Ticket partial = ticket(missing, "partial");
        LiveProviderAttemptMeter.client(new FakeHttp(() -> { }), partial).send(request(), HttpResponse.BodyHandlers.discarding());
        LiveProviderAttemptMeter.result(partial, new InvocationResult.Completed("OK", List.of(), "fixture-model",
            new ModelUsage(OptionalLong.of(9), OptionalLong.empty()), "stop"));
        assertTrue(missing.stopped());
        assertNull(missing.providerRecords().getFirst().get("completion_tokens"));
        State excessive = state(); Ticket over = ticket(excessive, "excess");
        LiveProviderAttemptMeter.client(new FakeHttp(() -> { }), over).send(request(), HttpResponse.BodyHandlers.discarding());
        LiveProviderAttemptMeter.result(over, new InvocationResult.Completed("OK", List.of(), "fixture-model", usage(8193, 129), "stop"));
        assertTrue(excessive.stopped());
        assertEquals("8193", excessive.providerRecords().getLast().get("prompt_tokens"));
        assertThrows(IllegalStateException.class, () -> ticket(excessive, "after-excess"));
    }

    @Test void capDriftAndReusedJournalAreRejectedAndZeroSendSnapshotIsComplete() throws Exception {
        var drifted = environment(); drifted.put("LIVE_GRADLE_MAX_ATTEMPTS", "5");
        assertThrows(IllegalStateException.class, () -> new State(drifted, Clock.systemUTC(), elapsed::get, row -> { }));
        Files.createFile(root.resolve("attempts.jsonl"));
        assertThrows(IllegalStateException.class, () -> new State(environment(), Clock.systemUTC(), elapsed::get));
        State empty = state(); empty.outcome("FAIL"); empty.outcome("PASS");
        empty.snapshot("provider_wire_audit", true, empty.providerAttempts(), empty.providerRecords());
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(root.resolve("attempt-evidence.json").toFile());
        assertEquals("FAIL", json.path("acceptance_outcome").asText());
        assertTrue(json.path("collection_complete").asBoolean()); assertEquals(0, json.path("attempts").size());
    }

    @Test void wireBodyBoundAndRedirectPolicyRejectBeforeSend() {
        State state = state(); FakeHttp delegate = new FakeHttp(() -> fail("oversized body must not send"));
        Ticket small = LiveProviderAttemptMeter.prepare(state, "fixture-provider", "fixture-model", "wire-bound", 8, 1, 1);
        assertThrows(IllegalStateException.class, () -> LiveProviderAttemptMeter.client(delegate, small)
            .send(request(), HttpResponse.BodyHandlers.discarding()));
        assertEquals(0, delegate.calls); assertTrue(state.stopped());
        FakeHttp redirects = new FakeHttp(() -> { }); redirects.redirect = HttpClient.Redirect.ALWAYS;
        assertThrows(IllegalStateException.class, () -> LiveProviderAttemptMeter.client(redirects, ticket(state(), "redirects")));
    }

    private static final class Discard implements Flow.Subscriber<StreamEvent> {
        @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
        @Override public void onNext(StreamEvent value) { }
        @Override public void onError(Throwable failure) { }
        @Override public void onComplete() { }
    }
    private static final class FakeHttp extends HttpClient {
        final Runnable before; int calls, completeBodies, bodyErrors; boolean subscribeBodyTwice;
        Redirect redirect = Redirect.NEVER;
        FakeHttp(Runnable before) { this.before = before; }
        private void dispatch(HttpRequest request) {
            before.run(); calls++;
            var sink = new Flow.Subscriber<ByteBuffer>() {
                @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
                @Override public void onNext(ByteBuffer bytes) { }
                @Override public void onError(Throwable failure) { bodyErrors++; }
                @Override public void onComplete() { completeBodies++; }
            };
            request.bodyPublisher().orElseThrow().subscribe(sink);
            if (subscribeBodyTwice) request.bodyPublisher().orElseThrow().subscribe(sink);
        }
        @Override public <T> HttpResponse<T> send(HttpRequest r, HttpResponse.BodyHandler<T> h) { dispatch(r); return null; }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h) { dispatch(r); return CompletableFuture.completedFuture(null); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h, HttpResponse.PushPromiseHandler<T> p) { return sendAsync(r, h); }
        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return redirect; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { try { return SSLContext.getDefault(); } catch (Exception e) { throw new AssertionError(e); } }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
    }
}
