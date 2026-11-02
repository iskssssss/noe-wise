package com.skillforge.runtime.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.runtime.adapter.sqlite.SqliteDesktopSessions;
import com.skillforge.runtime.adapter.sqlite.SqliteExecutionStore;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.runtime.api.RunStatus;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.ToolEffect;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.port.ModelPort.ModelMessage;
import com.skillforge.runtime.api.port.ModelPort.ModelRole;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.AgentExecutionProgram;
import com.skillforge.runtime.session.AgentExecutionTime;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 临时真实SQLite + 正式Runtime/Provider HTTP装配；不访问付费模型，不借用存储验收fixture。 */
class DesktopRuntimeTest {
    private static final String API_KEY = "desktop-controlled-secret-not-a-real-key";
    @TempDir Path temporary;

    @Test void queueCapacityAndPredecessorIgnoreFinishedHistory() throws Exception {
        var workspace = Files.createDirectory(temporary.resolve("narrow-scheduling"));
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger();
        var defaults = DesktopPolicy.defaults();
        var policy = new DesktopPolicy(1, 2, defaults.outputTokens(), defaults.inputTokens(), defaults.modelTimeout(), defaults.leaseDuration());
        try (var provider = new ControlledProvider(request -> {
            if (calls.getAndIncrement() == 1) {
                entered.countDown();
                try { assertTrue(release.await(15, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            }
            return assistantText("按顺序完成。");
        })) {
            var notifications = new LinkedBlockingQueue<ObjectNode>();
            try (var host = host(temporary.resolve("narrow-data"), provider, notifications, policy)) {
                host.createSession("ordered", "队列顺序", workspace.toString());
                var history = host.submitTask("ordered", "已完成历史", "history").path("runId").asText();
                await(host, notifications, history, "FINISHED", provider);
                var first = host.submitTask("ordered", "当前任务", "first").path("runId").asText();
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                var following = host.submitTask("ordered", "后继任务", "following").path("runId").asText();
                try {
                    assertEquals(2, calls.get());
                    assertFalse(host.getSession("ordered").path("runs").get(2).path("active").asBoolean());
                    var full = assertThrows(DesktopRequestException.class, () -> host.submitTask("ordered", "超出队列", "full"));
                    assertEquals(DesktopRequestException.Code.DESKTOP_QUEUE_FULL, full.code());
                    assertFalse(host.submitTask("ordered", "当前任务", "first").path("created").asBoolean());
                } finally { release.countDown(); }
                await(host, notifications, following, "FINISHED", provider);
                assertEquals(3, calls.get());
                assertEquals("COMPLETED", host.getSession("ordered").path("runs").get(1).path("status").asText());
                assertTrue(host.getSession("ordered").path("messages").toString().contains("按顺序完成"));
            } finally { release.countDown(); }
        }
    }

    @Test void blockedModelTestDoesNotBlockControlReadsOrWriteAfterShutdown() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var provider = new ControlledProvider(request -> {
            entered.countDown();
            try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            return assistantText("OK");
        }); var host = host(temporary.resolve("async-test"), provider, new LinkedBlockingQueue<>())) {
            var input = new java.io.PipedWriter(); var reader = new java.io.PipedReader(input);
            var output = new StringWriter(); var wire = new DesktopProtocol(new PrintWriter(output, true));
            var failure = new AtomicReference<Throwable>();
            var thread = Thread.ofPlatform().start(() -> {
                try { wire.serve(reader, host); } catch (Throwable problem) { failure.set(problem); }
            });
            try {
                input.write("{\"id\":\"test\",\"method\":\"testModel\",\"params\":{}}\n"); input.flush();
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                input.write("{\"id\":\"read\",\"method\":\"listSessions\",\"params\":{}}\n");
                input.write("{\"id\":\"shutdown\",\"method\":\"shutdown\",\"params\":{}}\n"); input.flush();
                thread.join(2000); assertFalse(thread.isAlive(), "Control reader must not wait for model test");
                assertEquals(null, failure.get());
                var replies = output.toString().lines().map(DesktopJson::parse).toList();
                assertEquals(List.of("read", "shutdown"), replies.stream().map(reply -> reply.path("id").asText()).toList());
                for (var testing : Thread.getAllStackTraces().keySet()) if (testing.getName().equals("desktop-model-test")) {
                    testing.join(1500); assertFalse(testing.isAlive(), "Shutdown must stop the real test before provider release");
                }
                release.countDown();
                assertEquals(1, provider.requests.get());
                wire.event(DesktopJson.object().put("event", "late"));
                assertEquals(2, output.toString().lines().count());
            } finally { release.countDown(); input.close(); reader.close(); thread.join(5000); }
        }
    }

    /** 本地受控 SSE + 正式 Runtime/SQLite；fixture 的能力事实不表示自定义供应商已通过目录或 live 验收。 */
    @Test void streamingPreviewCommitsOnlyTheExactFinalBodyToSqlite() throws Exception {
        exerciseStreamingRuntime("complete");
    }

    @Test void streamingCancellationAndShutdownReleaseTheRuntimeBeforeProviderCompletion() throws Exception {
        exerciseStreamingRuntime("cancel");
        exerciseStreamingRuntime("close");
    }

    private void exerciseStreamingRuntime(String operation) throws Exception {
        var data = temporary.resolve("stream-" + operation); var workspace = Files.createDirectory(temporary.resolve("work-" + operation));
        try (var provider = new ControlledStreamProvider()) {
            var run = seedHistory(data, workspace, provider.url(), DesktopPrompts.prepare(DesktopPolicy.defaults()).body(), Optional.empty(), false, true);
            var notifications = new LinkedBlockingQueue<ObjectNode>();
            var host = new DesktopRuntime(data, Clock.systemUTC(), DesktopPolicy.defaults(), notifications::add);
            try {
                host.configureModel("custom", provider.url(), "controlled-model", API_KEY, OptionalInt.empty()); host.start();
                ObjectNode preview = null;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (preview == null && System.nanoTime() < deadline) {
                    var event = notifications.poll(100, TimeUnit.MILLISECONDS);
                    if (event != null && event.path("event").asText().equals("modelPreview")) preview = event;
                    if (event != null && event.path("event").asText().equals("hostError")) throw new AssertionError(event);
                }
                assertTrue(preview != null, "Actual SSE body must reach the public preview channel");
                assertEquals(run.runId(), preview.path("runId").asText()); assertEquals("公开", preview.path("delta").asText());
                assertFalse(preview.path("attemptId").asText().isBlank());
                assertEquals(1, host.getSession("upgrade").path("messages").size(), "Preview cannot create a persisted assistant message");
                if (operation.equals("complete")) {
                    provider.release.countDown();
                    await(host, notifications, run.runId(), "FINISHED");
                    var state = host.getSession("upgrade");
                    assertEquals("COMPLETED", state.path("runs").get(0).path("status").asText());
                    assertEquals("公开正文", state.path("messages").get(1).path("text").asText());
                } else {
                    if (operation.equals("cancel")) {
                        host.cancelTask(run.runId(), "stream-cancel");
                        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                        while (host.getSession("upgrade").path("runs").get(0).path("active").asBoolean()
                                && System.nanoTime() < deadline) Thread.sleep(10);
                        var state = host.getSession("upgrade");
                        assertFalse(state.path("runs").get(0).path("active").asBoolean(), "Cancelled stream worker must stop before provider release");
                        assertFalse(state.path("runs").get(0).path("status").asText().equals("COMPLETED"));
                        assertEquals(1, state.path("messages").size());
                    }
                    try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                        workers.submit(host::close).get(2, TimeUnit.SECONDS);
                    }
                }
            } finally { host.close(); provider.release.countDown(); }
            try (var store = new SqliteExecutionStore(data.resolve("runtime.db"), Clock.systemUTC())) {
                var saved = store.load(run).orElseThrow();
                if (operation.equals("complete")) {
                    assertEquals(RunStatus.COMPLETED, saved.status());
                    var finished = store.event(run, AgentExecutionProgram.finishedEventId(run)).orElseThrow();
                    assertEquals("公开正文", DesktopJson.parse(finished.event().data().canonicalJson()).path("text").asText());
                } else assertFalse(saved.status() == RunStatus.COMPLETED);
            }
            assertEquals(1, provider.requests.get(), "Completion/cancel/close must not re-dispatch the original model request");
            provider.assertHealthy();
        }
    }

    @Test void windowProjectionMatchesTheLegacyReadsOnTheSameSqliteHistory() throws Exception {
        var data = temporary.resolve("window-data"); var workspace = Files.createDirectory(temporary.resolve("window-work"));
        Files.createDirectories(data); var clock = Clock.systemUTC(); var codec = new AgentExecutionCodec(DesktopRuntime.CODEC_LIMITS);
        final ObjectNode expected;
        try (var models = new DesktopModels(clock, DesktopRuntime.CODEC_LIMITS);
             var store = new SqliteExecutionStore(data.resolve("runtime.db"), clock);
             var sessions = new SqliteDesktopSessions(data.resolve("sessions.db"), clock)) {
            var session = sessions.create("window", "窗口", workspace.toString());
            var settings = models.configure("custom", "http://127.0.0.1:1", "controlled-model", API_KEY);
            var frozen = DesktopJson.material(DesktopConfiguration.SCHEMA, DesktopConfiguration.freeze(settings, DesktopPolicy.defaults(),
                    workspace.toString(), Optional.empty(), List.of(), DesktopPrompts.prepare(DesktopPolicy.defaults())));
            var owner = DesktopRuntime.owner("window"); var actor = DesktopRuntime.actor(owner);
            for (int index = 0; index < 200; index++) {
                var runRef = new RunRef(DesktopRuntime.TENANT, "window-run-" + String.format("%04d", index)); var inputId = new InputId("window-input-" + index);
                var identity = new RunIdentity(owner, new AgentId("desktop-agent"), inputId, runRef,
                        new TraceEnvelope("window-trace", "window-span", inputId, Optional.empty()), actor.verificationRef(),
                        new RunRequester.EndUser(new EndUserId(1L)), Optional.empty(), new RunOriginRef(DesktopRuntime.TENANT, "desktop:window"));
                var task = DesktopJson.object().put("text", "user-" + index);
                var inputPayload = codec.encodeInput(List.of(new ModelMessage("input:" + index, ModelRole.USER, "user-" + index)),
                        new TypedPayload("noewise.desktop.task.v1", DesktopJson.write(task)));
                var input = new ExecutionStore.Material(AgentExecutionCodec.reference(inputPayload), inputPayload);
                var initialPayload = new TypedPayload(AgentExecutionProgram.CONTEXT_SCHEMA,
                        DesktopJson.write(DesktopJson.object().put("type", "base").put("input", input.reference().value())));
                var initial = new ExecutionStore.Material(AgentExecutionCodec.reference(initialPayload), initialPayload);
                var policy = new ExecutionBudgetPort.Policy.ObserveOnly(ExecutionBudgetPort.Scenario.STANDARD, frozen.reference().value());
                store.admit(new ExecutionStore.Admission(actor, identity, "window-" + index, frozen.reference(), input, initial,
                        new ExecutionBudgetPort.Registration(runRef, runRef, ExecutionBudgetPort.Role.PRIMARY, policy),
                        List.of(AgentExecutionProgram.admissionEvent(identity)), List.of(frozen)));
                var claimed = store.claim(new ExecutionStore.Claim(runRef, "fixture", "claim-" + index, Duration.ofMinutes(1))).orElseThrow();
                var body = DesktopJson.object().put("reason", "fixture").put("status", "COMPLETED");
                switch (index % 7) {
                    case 0 -> body.put("text", "answer-" + index);
                    case 1 -> body.put("text", "\u2003");
                    case 2 -> body.put("text", "\u00a0");
                    case 3 -> body.putNull("text");
                    case 4 -> body.put("text", index);
                    case 5 -> body.putArray("text");
                    default -> { }
                }
                var finish = new ExecutionStore.Event(AgentExecutionProgram.finishedEventId(runRef), AgentExecutionProgram.COMPLETED_EVENT,
                        new TypedPayload(AgentExecutionProgram.COMPLETED_SCHEMA, DesktopJson.write(body)));
                var unrelated = new ExecutionStore.Event(new EventId("unrelated-finished-" + index), AgentExecutionProgram.COMPLETED_EVENT,
                        new TypedPayload(AgentExecutionProgram.COMPLETED_SCHEMA, "{\"text\":\"not-the-final-event\"}"));
                store.finish(new ExecutionStore.Finish(runRef, claimed.revision(), claimed.lease().orElseThrow(), RunStatus.COMPLETED, List.of(finish, unrelated)));
            }
            var full = DesktopJson.object(); full.putObject("session").put("id", session.id()).put("title", session.title())
                    .put("workspace", session.workspace()).put("createdAt", session.createdAt().toString());
            var messages = full.putArray("messages"); var runs = full.putArray("runs"); full.putArray("pendingConfirmations"); var events = full.putArray("events");
            int inputReads = 0; int terminalReads = 0; int configurationReads = 0;
            for (var run : store.runsForSession(new SessionRef(DesktopRuntime.TENANT, "window"))) {
                var ref = run.identity().runRef(); var input = store.originalInput(ref).orElseThrow(); inputReads++;
                messages.addObject().put("role", "user").put("text", DesktopJson.parse(codec.frozenInput(input.content()).canonicalJson()).path("text").asText()).put("runId", ref.runId());
                var finish = store.event(ref, AgentExecutionProgram.finishedEventId(ref)); terminalReads++;
                finish.ifPresent(value -> {
                    var text = DesktopJson.parse(value.event().data().canonicalJson()).path("text").asText();
                    if (!text.isBlank()) messages.addObject().put("role", "assistant").put("text", text).put("runId", ref.runId());
                });
                assertTrue(DesktopConfiguration.supports(store.material(DesktopRuntime.TENANT, run.configuration()).orElseThrow())); configurationReads++;
                runs.addObject().put("id", ref.runId()).put("status", run.status().name()).put("phase", run.phase().name()).put("active", false)
                        .put("createdAt", run.createdAt().toString()).put("updatedAt", run.updatedAt().toString());
            }
            long after = 0; int eventReads = 0;
            while (true) {
                var page = store.events(new SessionRef(DesktopRuntime.TENANT, "window"), after, 100);
                for (var value : page) {
                    var body = DesktopJson.parse(value.event().data().canonicalJson());
                    var entry = events.addObject().put("sequence", value.sequence()).put("type", value.event().type()).put("runId", value.run().runId())
                            .put("text", body.path("summary").asText(body.path("reason").asText(""))); entry.set("data", body); eventReads++;
                }
                if (page.size() < 100) break; after = page.getLast().sequence();
            }
            expected = DesktopProjection.bound(full);
            var snapshot = store.desktopProjection(new SessionRef(DesktopRuntime.TENANT, "window"), 100, 40, 80, 40, AgentExecutionProgram::finishedEventId);
            assertEquals(40, snapshot.messageCandidates().size()); assertEquals(100, snapshot.runs().size()); assertEquals(80, snapshot.events().size());
            assertTrue(snapshot.materials().size() <= 41, "Visible inputs and one shared configuration are the only immutable materials needed");
            System.out.println("desktop projection same-SQLite reads: before inputs=" + inputReads + " exact-terminals=" + terminalReads
                    + " configurations=" + configurationReads + " process=" + eventReads + "; after candidates=" + snapshot.messageCandidates().size()
                    + " materials=" + snapshot.materials().size() + " process=" + snapshot.events().size());
        }
        try (var host = new DesktopRuntime(data, clock, DesktopPolicy.defaults(), event -> { })) {
            assertEquals(expected, host.getSession("window"));
        }
    }

    @Test void oldCommandConfirmationRemainsActionableOutsideTheDisplayedRunWindow() throws Exception {
        var data = temporary.resolve("old-wait-data"); var workspace = Files.createDirectory(temporary.resolve("old-wait-work"));
        var defaults = DesktopPolicy.defaults(); var policy = new DesktopPolicy(defaults.maxActiveTasks(), 200, defaults.outputTokens(),
                defaults.inputTokens(), defaults.modelTimeout(), defaults.leaseDuration());
        final String runId; final String waitingId;
        try (var provider = new ControlledProvider(request -> tool(request, "run_command", "{\"command\":\"echo must-not-execute\"}"))) {
            var notifications = new LinkedBlockingQueue<ObjectNode>();
            try (var host = host(data, provider, notifications, policy)) {
                host.createSession("old-wait", "旧确认", workspace.toString());
                runId = host.submitTask("old-wait", "等待确认", "old-wait-input").path("runId").asText();
                await(host, notifications, runId, "WAITING", provider);
                waitingId = host.getSession("old-wait").path("pendingConfirmations").get(0).path("waitingId").asText();
                for (int index = 0; index < 100; index++) host.submitTask("old-wait", "排队-" + index, "queued-" + index);
                var projected = host.getSession("old-wait");
                assertEquals(100, projected.path("runs").size());
                for (var run : projected.path("runs")) assertFalse(run.path("id").asText().equals(runId));
                assertEquals(runId, projected.path("pendingConfirmations").get(0).path("runId").asText());
                assertEquals(waitingId, projected.path("pendingConfirmations").get(0).path("waitingId").asText());
                assertEquals(1, provider.requests.get());
            }
            // 未装配凭据且未start；只提交原确认回执，不派发后继模型请求。
            try (var host = new DesktopRuntime(data, Clock.systemUTC(), policy, event -> { })) {
                assertTrue(host.confirmTool(runId, waitingId, false, "reject-original-wait").path("accepted").asBoolean());
                assertEquals(0, host.getSession("old-wait").path("pendingConfirmations").size());
                assertEquals(1, provider.requests.get());
            }
        }
    }

    @Test void validatesOptionalOutputBudgetBeforeMutationAndOnlyChangesNewRuns() throws Exception {
        var defaults = DesktopPolicy.defaults(); var selected = defaults.outputTokens() / 2; var calls = new AtomicInteger();
        var expected = new AtomicInteger(selected); var data = temporary.resolve("data");
        var workspace = Files.createDirectory(temporary.resolve("budget")); var runIds = new ArrayList<String>();
        try (var provider = new ControlledProvider(request -> {
            assertEquals("controlled-model", request.path("model").asText());
            assertEquals(expected.get(), request.path("max_tokens").asInt());
            assertHostSystem(request, DesktopPrompts.prepare(defaults.withOutputTokens(expected.get())).body());
            calls.incrementAndGet(); return assistantText("预算已核对。");
        })) {
            var notifications = new LinkedBlockingQueue<ObjectNode>();
            try (var host = host(data, provider, notifications)) {
                var params = DesktopJson.object().put("providerId", "custom").put("baseUrl", provider.url()).put("model", "controlled-model").put("apiKey", API_KEY);
                assertEquals(selected, configureThroughProtocol(host, params.deepCopy().put("outputTokens", selected)).path("result").path("outputTokens").asInt());
                var invalid = DesktopJson.parse("[null,0,-1,1.5,\"8192\",true,2147483648]");
                for (var value : invalid) {
                    var poisoned = params.deepCopy().put("model", "invalid-poison"); poisoned.set("outputTokens", value);
                    assertEquals("INVALID_REQUEST", configureThroughProtocol(host, poisoned).path("error").path("code").asText());
                }
                assertEquals("INVALID_REQUEST", configureThroughProtocol(host, params.deepCopy().put("unknown", true)).path("error").path("code").asText());
                host.createSession("budget", "冻结输出预算", workspace.toString());
                for (int turn = 0; turn < 3; turn++) {
                    if (turn == 1) {
                        expected.set(defaults.outputTokens() * 2);
                        configureThroughProtocol(host, params.deepCopy().put("outputTokens", expected.get()));
                    } else if (turn == 2) {
                        expected.set(defaults.outputTokens());
                        assertEquals(defaults.outputTokens(), configureThroughProtocol(host, params).path("result").path("outputTokens").asInt());
                    }
                    var run = host.submitTask("budget", "检查预算", "budget-" + turn).path("runId").asText(); runIds.add(run);
                    await(host, notifications, run, "FINISHED", provider);
                    assertEquals("COMPLETED", host.getSession("budget").path("runs").get(turn).path("status").asText());
                }
                assertEquals(3, calls.get()); provider.assertHealthy();
            }
            try (var store = new SqliteExecutionStore(data.resolve("runtime.db"), Clock.systemUTC())) {
                var run = store.load(new RunRef(DesktopRuntime.TENANT, runIds.getFirst())).orElseThrow();
                var frozen = DesktopJson.parse(store.material(DesktopRuntime.TENANT, run.configuration()).orElseThrow().content().canonicalJson());
                assertEquals(selected, frozen.path("policy").path("outputTokens").asInt());
                assertEquals("observe-only", frozen.path("budgetPolicy").asText());
                assertFalse(frozen.path("policy").has("maxBusinessModelCalls"));
                assertFalse(frozen.path("policy").has("maxModelSteps"));
                var unsupportedPolicy = frozen.deepCopy();
                ((ObjectNode) unsupportedPolicy.path("policy")).put("maxModelSteps", 20);
                assertThrows(IllegalArgumentException.class, () -> DesktopConfiguration.policy(unsupportedPolicy));
                for (var entry : DesktopConfiguration.tools(frozen)) {
                    var localName = entry.discovery().title().orElseThrow();
                    boolean command = localName.equals("run_command");
                    assertEquals(command ? "workspace_exec" : localName.equals("read_context_reference") ? "read_context_reference" : "workspace_files", entry.binding().toolName());
                    assertEquals(entry.binding().toolName(), entry.discovery().name());
                    if (command || Set.of("write_file", "edit_file").contains(localName))
                        assertEquals(ToolEffect.NON_IDEMPOTENT_WRITE, entry.target().effect());
                }
                var input = new AgentExecutionCodec(DesktopRuntime.CODEC_LIMITS).decodeInput(store.originalInput(run.identity().runRef()).orElseThrow().content());
                try (var reconstruction = new DesktopModels(Clock.systemUTC(), DesktopRuntime.CODEC_LIMITS)) {
                    var assembled = DesktopConfiguration.configuration(run, frozen, reconstruction, input, DesktopRuntime.actor(run.identity().owner()));
                    var budget = store.budgets().snapshot(run.identity().runRef()).registration().policy();
                    assertTrue(budget instanceof ExecutionBudgetPort.Policy.ObserveOnly);
                    assertTrue(assembled.budget() instanceof com.skillforge.runtime.core.LoopBudget.ObserveOnly);
                    assertEquals(run.configuration().value(), budget.sourceVersion());
                }
                assertEquals(DesktopPrompts.prepare(defaults.withOutputTokens(selected)).body(), input.getFirst().textContent());
                assertEquals(DesktopPrompts.RESOURCE, frozen.path("prompt").path("resource").asText());
                assertEquals(DesktopPrompts.prepare(defaults.withOutputTokens(selected)).fixedBodySha256(),
                        frozen.path("prompt").path("fixedBodySha256").asText());
                assertEquals(AgentExecutionCodec.digest(input.getFirst().textContent()), frozen.path("prompt").path("effectiveBodySha256").asText());
            }
        }
    }

    @Test void observeOnlySurpassesFormerCumulativeCapsAndKeepsActualSqliteUsageAfterRestart() throws Exception {
        var workspace = Files.createDirectory(temporary.resolve("observe-only"));
        for (int index = 0; index < 42; index++) Files.writeString(workspace.resolve("file-" + index + ".txt"), "observed-" + index);
        var calls = new AtomicInteger(); var data = temporary.resolve("observe-only-data"); String runId;
        try (var provider = new ControlledProvider(request -> {
            int step = calls.getAndIncrement(); assertTrue(step <= 21, "只计量仍不能无故重试");
            JsonNode result;
            if (step == 21) result = assistantText("42个文件已读取，任务完成。");
            else {
                result = tool(request, "read_file", "{\"path\":\"file-" + step * 2 + ".txt\"}");
                var second = tool(request, "read_file", "{\"path\":\"file-" + (step * 2 + 1) + ".txt\"}");
                ((com.fasterxml.jackson.databind.node.ArrayNode) result.path("choices").get(0).path("message").path("tool_calls"))
                        .add(second.path("choices").get(0).path("message").path("tool_calls").get(0));
            }
            // 受控供应商返回合法单次用量，累计超过旧1M上限，不能丢掉计量或提前截停。
            ((ObjectNode) result.path("usage")).put("prompt_tokens", 50_000).put("completion_tokens", 20).put("total_tokens", 50_020);
            return result;
        })) {
            var notifications = new LinkedBlockingQueue<ObjectNode>();
            try (var host = host(data, provider, notifications)) {
                host.createSession("observe-only", "只计量任务", workspace.toString());
                runId = host.submitTask("observe-only", "读取所有文件后说明", "observe-only-input").path("runId").asText();
                await(host, notifications, runId, "FINISHED", provider); var state = host.getSession("observe-only");
                assertEquals("COMPLETED", state.path("runs").get(0).path("status").asText());
                assertEquals(2, state.path("messages").size()); assertEquals(22, calls.get()); provider.assertHealthy();
            }
            try (var host = host(data, provider, notifications)) {
                assertEquals("COMPLETED", host.getSession("observe-only").path("runs").get(0).path("status").asText());
                assertEquals(22, calls.get());
            }
            try (var store = new SqliteExecutionStore(data.resolve("runtime.db"), Clock.systemUTC());
                    var reconstruction = new DesktopModels(Clock.systemUTC(), DesktopRuntime.CODEC_LIMITS)) {
                var run = store.load(new RunRef(DesktopRuntime.TENANT, runId)).orElseThrow();
                var material = store.material(DesktopRuntime.TENANT, run.configuration()).orElseThrow();
                assertEquals(DesktopConfiguration.SCHEMA, material.content().schemaId());
                var frozen = DesktopJson.parse(material.content().canonicalJson());
                for (var name : List.of("maxBusinessModelCalls", "maxToolCalls", "runTokens", "activeTimeoutMillis")) assertFalse(frozen.path("policy").has(name));
                var input = new AgentExecutionCodec(DesktopRuntime.CODEC_LIMITS).decodeInput(store.originalInput(run.identity().runRef()).orElseThrow().content());
                var assembled = DesktopConfiguration.configuration(run, frozen, reconstruction, input, DesktopRuntime.actor(run.identity().owner()));
                var budget = store.budgets().snapshot(run.identity().runRef());
                assertTrue(assembled.budget() instanceof com.skillforge.runtime.core.LoopBudget.ObserveOnly);
                assertTrue(budget.registration().policy() instanceof ExecutionBudgetPort.Policy.ObserveOnly);
                assertEquals(run.configuration().value(), budget.registration().policy().sourceVersion());
                assertEquals(22, budget.consumed().businessModels()); assertEquals(42, budget.consumed().tools());
                assertEquals(22L * 50_020, budget.consumed().totalTokens()); assertEquals(0, budget.consumed().auxiliaryModels());
                assertEquals(0, budget.reserved().businessModels());
                assertEquals(64, store.actions(run.identity().runRef(), 0, 100).size());
            }
        }
    }

    @Test void returnsShortFileObservationsWhileKeepingFullReceiptsAndUiDiff() throws Exception {
        var workspace = Files.createDirectory(temporary.resolve("short-observation")); var data = temporary.resolve("data"); var calls = new AtomicInteger();
        String content = "file-body-".repeat(60) + "TARGET"; String edited = content.replace("TARGET", "DONE");
        try (var provider = new ControlledProvider(request -> {
            int step = calls.getAndIncrement();
            if (step == 0) return tool(request, "write_file", DesktopJson.write(DesktopJson.object().put("path", "page.txt").put("content", content)));
            JsonNode last = null;
            for (var message : request.path("messages")) if (message.path("role").asText().equals("tool")) last = message;
            assertTrue(last != null); var feedback = DesktopJson.parse(last.path("content").asText());
            assertTrue(last.path("content").asText().length() < 512);
            // 正式toolContents把安全structuredContent投影为TOOL文本；HTTP正文根就是该对象。
            var observation = feedback;
            assertEquals("page.txt", observation.path("path").asText(), () -> "Actual HTTP tool content: " + feedback);
            assertFalse(observation.path("version").asText().isBlank());
            assertEquals((step == 1 ? content : edited).getBytes(StandardCharsets.UTF_8).length, observation.path("bytes").asInt());
            assertFalse(observation.has("before")); assertFalse(observation.has("after")); assertFalse(observation.has("diff"));
            return step == 1 ? tool(request, "edit_file", "{\"path\":\"page.txt\",\"oldText\":\"TARGET\",\"newText\":\"DONE\"}") : assistantText("page.txt 已创建并修改。");
        })) {
            var notifications = new LinkedBlockingQueue<ObjectNode>();
            try (var host = host(data, provider, notifications)) {
                host.createSession("short-observation", "短观察完整回执", workspace.toString());
                var run = host.submitTask("short-observation", "创建并修改文件", "short-input").path("runId").asText();
                await(host, notifications, run, "FINISHED", provider); assertEquals(edited, Files.readString(workspace.resolve("page.txt")));
                int edits = 0;
                for (var event : host.getSession("short-observation").path("events")) if (event.path("toolName").asText().equals("edit_file")) {
                    assertEquals(content, event.path("result").path("before").asText()); assertEquals(edited, event.path("result").path("after").asText());
                    assertTrue(event.path("result").path("diff").asText().contains("+++ b/page.txt")); edits++;
                }
                assertEquals(1, edits); assertEquals(3, calls.get()); provider.assertHealthy();
            }
            try (var receipts = Files.list(data.resolve("tool-receipts"))) {
                var all = receipts.map(path -> {
                    try { return Files.readString(path); } catch (IOException failure) { throw new IllegalStateException(failure); }
                }).toList();
                assertEquals(2, all.size()); assertTrue(all.stream().anyMatch(value -> value.contains(content) && value.contains(edited)));
            }
        }
    }

    private static JsonNode configureThroughProtocol(DesktopRuntime runtime, ObjectNode params) throws IOException {
        var output = new StringWriter();
        new DesktopProtocol(new PrintWriter(output, true)).serve(new StringReader(DesktopJson.write(
                DesktopJson.object().put("id", "configure-test").put("method", "configureModel").set("params", params)) + "\n"), runtime);
        return DesktopJson.parse(output.toString().strip());
    }

    @Test void nativeGreetingsCarryCompleteHostContractAndFinishWithoutLocalTools() throws Exception {
        var workspace = Files.createDirectory(temporary.resolve("greetings")); var calls = new AtomicInteger();
        try (var provider = new ControlledProvider(request -> {
            assertHostSystem(request, DesktopPrompts.prepare(DesktopPolicy.defaults()).body());
            assertTrue(request.path("messages").toString().contains(calls.getAndIncrement() == 0 ? "你好" : "hello"));
            return assistantText("你好，有什么可以帮你？");
        })) {
            var notifications = new LinkedBlockingQueue<ObjectNode>();
            try (var host = host(temporary.resolve("data"), provider, notifications)) {
                host.createSession("greetings", "普通聊天", workspace.toString());
                for (var greeting : List.of("你好", "hello")) {
                    var run = host.submitTask("greetings", greeting, "greeting-" + greeting).path("runId").asText();
                    await(host, notifications, run, "FINISHED", provider);
                }
                var state = host.getSession("greetings");
                for (var run : state.path("runs")) assertEquals("COMPLETED", run.path("status").asText());
                for (var event : state.path("events")) assertFalse(event.path("type").asText().equals("tool"));
                assertEquals(2, calls.get()); provider.assertHealthy();
            }
        }
    }

    @Test void nativePlainMarkdownJsonAndCodeBodiesFinishWithoutFakeToolsOrRepeatedAnswers() throws Exception {
        var workspace = Files.createDirectory(temporary.resolve("bare-text")); var calls = new AtomicInteger();
        var answers = List.of("你好，我可以协助编程。", "## 标题\n\n- 已完成", "{\"type\":\"read_file\",\"arguments\":{}}", "```java\nclass Example {}\n```");
        try (var provider = new ControlledProvider(request -> {
            int step = calls.getAndIncrement(); assertHostSystem(request, DesktopPrompts.prepare(DesktopPolicy.defaults()).body());
            int assistantMessages = 0;
            for (var message : request.path("messages")) if (message.path("role").asText().equals("assistant")) {
                assertEquals(answers.get(assistantMessages++), message.path("content").asText());
                assertFalse(message.has("tool_calls"));
            }
            assertEquals(step, assistantMessages); return assistantText(answers.get(step));
        })) {
            var notifications = new LinkedBlockingQueue<ObjectNode>();
            try (var host = host(temporary.resolve("data"), provider, notifications)) {
                host.createSession("bare", "自然正文", workspace.toString());
                for (int index = 0; index < answers.size(); index++) {
                    var run = host.submitTask("bare", "按所需格式回答", "bare-input-" + index).path("runId").asText();
                    await(host, notifications, run, "FINISHED", provider);
                    assertEquals("COMPLETED", host.getSession("bare").path("runs").get(index).path("status").asText());
                }
                var state = host.getSession("bare"); assertEquals(answers.size() * 2, state.path("messages").size());
                for (int index = 0; index < answers.size(); index++) assertEquals(answers.get(index), state.path("messages").get(index * 2 + 1).path("text").asText());
                for (var event : state.path("events")) assertFalse(event.path("type").asText().equals("tool"));
                assertEquals(answers.size(), calls.get()); provider.assertHealthy();
            }
        }
    }

    @Test void textAlongsideToolCallIsRecordedButDoesNotPublishOrCompleteBeforeToolResult() throws Exception {
        var workspace = Files.createDirectory(temporary.resolve("mixed-response")); Files.writeString(workspace.resolve("answer.txt"), "observed");
        var calls = new AtomicInteger(); var data = temporary.resolve("data");
        try (var provider = new ControlledProvider(request -> {
            if (calls.getAndIncrement() == 0) {
                var mixed = tool(request, "read_file", "{\"path\":\"answer.txt\"}");
                ((ObjectNode) mixed.path("choices").get(0).path("message")).put("content", "我会先读取文件。"); return mixed;
            }
            boolean mixedFound = false; boolean toolFound = false;
            for (var message : request.path("messages")) {
                if (message.path("content").asText().equals("我会先读取文件。")) { mixedFound = true; assertEquals(1, message.path("tool_calls").size()); }
                if (message.path("role").asText().equals("tool")) { toolFound = true; assertTrue(message.path("content").asText().contains("observed")); }
            }
            assertTrue(mixedFound); assertTrue(toolFound); return assistantText("文件内容是 observed。");
        })) {
            var notifications = new LinkedBlockingQueue<ObjectNode>();
            try (var host = host(data, provider, notifications)) {
                host.createSession("mixed", "正文与工具共存", workspace.toString());
                var run = host.submitTask("mixed", "读取文件并说明", "mixed-input").path("runId").asText();
                await(host, notifications, run, "FINISHED", provider); var state = host.getSession("mixed");
                assertEquals("COMPLETED", state.path("runs").get(0).path("status").asText());
                assertEquals(2, state.path("messages").size()); assertEquals("文件内容是 observed。", state.path("messages").get(1).path("text").asText());
                assertEquals(2, calls.get()); provider.assertHealthy();
            }
            try (var host = host(data, provider, notifications)) {
                assertEquals(2, host.getSession("mixed").path("messages").size()); assertEquals(2, calls.get());
            }
        }
    }

    @Test void unsupportedRunFormatsRemainReadableButCannotResumeOrBecomeNewRunHistory() throws Exception {
        var workspace = Files.createDirectory(temporary.resolve("old-protocol")); var calls = new AtomicInteger();
        try (var provider = new ControlledProvider(request -> { calls.incrementAndGet(); return assistantText("不应调用。"); })) {
            for (var oldSchema : List.of("noewise.desktop.configuration.v1", "noewise.desktop.configuration.v2", "noewise.desktop.configuration.v3", "noewise.desktop.configuration.v4"))
                for (boolean finished : List.of(false, true)) {
                var data = temporary.resolve(oldSchema + (finished ? "-finished-data" : "-ready-data"));
                var run = seedHistory(data, workspace, provider.url(), "旧版系统正文", Optional.of(oldSchema), finished);
                var notifications = new LinkedBlockingQueue<ObjectNode>();
                try (var host = host(data, provider, notifications)) {
                    var state = host.getSession("upgrade"); assertTrue(state.path("runs").get(0).path("recoveryBlocked").asBoolean());
                    assertEquals("DESKTOP_RUN_FORMAT_UNSUPPORTED", state.path("runs").get(0).path("recoveryReason").asText());
                    assertEquals("历史用户问题", state.path("messages").get(0).path("text").asText());
                    if (finished) assertEquals("历史助手回答", state.path("messages").get(1).path("text").asText());
                    assertEquals(0, state.path("pendingConfirmations").size());
                    assertEquals(DesktopRequestException.Code.DESKTOP_RUN_FORMAT_UNSUPPORTED, assertThrows(DesktopRequestException.class,
                            () -> host.submitTask("upgrade", "新问题", "blocked-new-input")).code());
                    assertEquals(DesktopRequestException.Code.DESKTOP_RUN_FORMAT_UNSUPPORTED, assertThrows(DesktopRequestException.class,
                            () -> host.confirmTool(run.runId(), "unsupported-wait", true, "blocked-confirmation")).code());
                    assertEquals(0, calls.get());
                }
                try (var store = new SqliteExecutionStore(data.resolve("runtime.db"), Clock.systemUTC())) {
                    var saved = store.load(run).orElseThrow(); assertEquals(finished ? ExecutionStore.Phase.FINISHED : ExecutionStore.Phase.READY, saved.phase());
                    assertEquals(oldSchema, store.material(DesktopRuntime.TENANT, saved.configuration()).orElseThrow().content().schemaId());
                    assertTrue(store.actions(run, 0, 100).isEmpty());
                    assertTrue(store.originalInput(run).orElseThrow().content().canonicalJson().contains("旧版系统正文"));
                }
            }
            provider.assertHealthy();
        }
    }

    @Test void independentReadOnlyCallsRetainTheirIdsAndResultsInOneNativeTurn() throws Exception {
        var workspace = Files.createDirectory(temporary.resolve("read-batch"));
        Files.writeString(workspace.resolve("one.txt"), "first-observed"); Files.writeString(workspace.resolve("two.txt"), "second-observed");
        var calls = new AtomicInteger();
        try (var provider = new ControlledProvider(request -> {
            if (calls.getAndIncrement() == 0) {
                var result = tool(request, "read_file", "{\"path\":\"one.txt\"}");
                var wireCalls = (com.fasterxml.jackson.databind.node.ArrayNode) result.path("choices").get(0).path("message").path("tool_calls");
                var second = wireCalls.get(0).deepCopy(); ((ObjectNode) second).put("id", "second-read-call");
                ((ObjectNode) second.path("function")).put("arguments", "{\"path\":\"two.txt\"}"); wireCalls.add(second); return result;
            }
            var results = new ArrayList<JsonNode>();
            for (var message : request.path("messages")) if (message.path("role").asText().equals("tool")) results.add(message);
            assertEquals(2, results.size()); assertFalse(results.get(0).path("tool_call_id").asText().isBlank());
            assertFalse(results.get(0).path("tool_call_id").asText().equals(results.get(1).path("tool_call_id").asText()));
            assertTrue(results.get(0).path("content").asText().contains("first-observed"));
            assertTrue(results.get(1).path("content").asText().contains("second-observed")); return assistantText("两个文件均已读取。");
        })) {
            var notifications = new LinkedBlockingQueue<ObjectNode>();
            try (var host = host(temporary.resolve("data"), provider, notifications)) {
                host.createSession("batch", "独立只读批次", workspace.toString());
                var run = host.submitTask("batch", "读取两个文件", "read-batch-input").path("runId").asText();
                await(host, notifications, run, "FINISHED", provider); var state = host.getSession("batch");
                assertEquals("COMPLETED", state.path("runs").get(0).path("status").asText());
                int reads = 0; for (var event : state.path("events")) if (event.path("toolName").asText().equals("read_file")) reads++;
                assertEquals(2, reads); assertEquals(2, calls.get()); provider.assertHealthy();
            }
        }
    }

    @Test void reusedProviderToolCallIdIsRejectedBeforeSecondWriteDispatch() throws Exception {
        var workspace = Files.createDirectory(temporary.resolve("reused-call-id")); Files.writeString(workspace.resolve("source.txt"), "observed");
        var calls = new AtomicInteger();
        try (var provider = new ControlledProvider(request -> {
            int step = calls.getAndIncrement(); assertTrue(step < 2, "重复调用身份不能触发自动重试");
            var result = step == 0 ? tool(request, "read_file", "{\"path\":\"source.txt\"}")
                    : tool(request, "write_file", "{\"path\":\"must-not-exist.txt\",\"content\":\"forbidden\"}");
            ((ObjectNode) result.path("choices").get(0).path("message").path("tool_calls").get(0)).put("id", "deliberately-reused-call");
            return result;
        })) {
            var notifications = new LinkedBlockingQueue<ObjectNode>();
            try (var host = host(temporary.resolve("data"), provider, notifications)) {
                host.createSession("reused-id", "拒绝重复工具身份", workspace.toString());
                var run = host.submitTask("reused-id", "读取后创建文件", "reused-input").path("runId").asText();
                await(host, notifications, run, "FINISHED", provider); var state = host.getSession("reused-id");
                assertEquals("FAILED", state.path("runs").get(0).path("status").asText());
                assertTrue(state.toString().contains("model_tool_call_id_reused")); assertFalse(Files.exists(workspace.resolve("must-not-exist.txt")));
                int toolCalls = 0;
                for (var event : state.path("events")) if (event.path("type").asText().equals("tool")) {
                    assertEquals("read_file", event.path("toolName").asText()); toolCalls++;
                }
                assertEquals(1, toolCalls); assertEquals(2, calls.get()); provider.assertHealthy();
            }
        }
    }

    @Test void newRunUsesOwnFrozenSystemWhileKeepingOldBusinessHistory() throws Exception {
        var workspace = Files.createDirectory(temporary.resolve("prompt-upgrade")); var data = temporary.resolve("data");
        String oldSystem = "历史宿主系统规则，只属于原受理Run。"; var calls = new AtomicInteger();
        try (var provider = new ControlledProvider(request -> {
            calls.incrementAndGet(); assertHostSystem(request, DesktopPrompts.prepare(DesktopPolicy.defaults()).body());
            var messages = request.path("messages").toString();
            assertFalse(messages.contains(oldSystem)); assertTrue(messages.contains("历史用户问题"));
            assertTrue(messages.contains("历史助手回答")); assertTrue(messages.contains("新用户问题"));
            return assistantText("已保留业务历史。");
        })) {
            var prior = seedFinishedHistory(data, workspace, provider.url(), oldSystem);
            var notifications = new LinkedBlockingQueue<ObjectNode>();
            try (var host = host(data, provider, notifications)) {
                var run = host.submitTask("upgrade", "新用户问题", "new-input").path("runId").asText();
                await(host, notifications, run, "FINISHED", provider);
                assertEquals("COMPLETED", host.getSession("upgrade").path("runs").get(1).path("status").asText());
                assertEquals(1, calls.get()); provider.assertHealthy();
            }
            try (var store = new SqliteExecutionStore(data.resolve("runtime.db"), Clock.systemUTC())) {
                var original = new AgentExecutionCodec(DesktopRuntime.CODEC_LIMITS).decodeInput(store.originalInput(prior).orElseThrow().content());
                assertEquals(oldSystem, original.getFirst().textContent());
            }
        }
    }

    @Test void truncatedWriteArgumentsFailWithoutWritesOrReplayAfterRestart() throws Exception {
        var workspace = Files.createDirectory(temporary.resolve("truncated-write"));
        var target = workspace.resolve("index.html"); Files.writeString(target, "original-page");
        var policy = DesktopPolicy.defaults(); var data = temporary.resolve("data"); var calls = new AtomicInteger();
        try (var provider = new ControlledProvider(request -> {
            calls.incrementAndGet(); assertHostSystem(request, DesktopPrompts.prepare(policy).body());
            assertEquals(policy.outputTokens(), request.path("max_tokens").asInt());
            var result = (ObjectNode) tool(request, "write_file", "{\"path\":\"index.html\",\"content\":\"<html>unfinished");
            ((ObjectNode) result.path("choices").get(0)).put("finish_reason", "length");
            ((ObjectNode) result.path("usage")).put("completion_tokens", policy.outputTokens()).put("total_tokens", 100 + policy.outputTokens());
            return result;
        })) {
            var notifications = new LinkedBlockingQueue<ObjectNode>(); String run;
            try (var host = host(data, provider, notifications)) {
                host.createSession("truncated-write", "截断不写文件", workspace.toString());
                run = host.submitTask("truncated-write", "生成网站", "truncated-input").path("runId").asText();
                await(host, notifications, run, "FINISHED", provider);
                assertLengthFailureWithoutTools(host.getSession("truncated-write"));
                assertEquals("original-page", Files.readString(target)); assertEquals(1, calls.get());
            }
            notifications.clear();
            try (var host = host(data, provider, notifications)) {
                assertLengthFailureWithoutTools(host.getSession("truncated-write"));
                assertFalse(host.submitTask("truncated-write", "生成网站", "truncated-input").path("created").asBoolean());
                assertEquals("original-page", Files.readString(target)); assertEquals(1, calls.get()); provider.assertHealthy();
            }
            try (var store = new SqliteExecutionStore(data.resolve("runtime.db"), Clock.systemUTC())) {
                var actions = store.actions(new RunRef(DesktopRuntime.TENANT, run), 0, 100);
                assertEquals(1, actions.size()); assertEquals(ExecutionStore.Kind.MODEL, actions.getFirst().kind());
            }
            try (var files = Files.list(workspace)) { assertEquals(List.of("index.html"), files.map(path -> path.getFileName().toString()).toList()); }
        }
    }

    @Test void buildsLongFileWithSmallEditsAndSeparateWebsiteAssets() throws Exception {
        var workspace = Files.createDirectory(temporary.resolve("chunked-site")); var calls = new AtomicInteger();
        var defaults = DesktopPolicy.defaults(); var policy = defaults.withOutputTokens(defaults.outputTokens() / 4); String marker = "<!--NEXT-SECTION-->";
        String skeleton = "<!doctype html><html><head><link rel=\"stylesheet\" href=\"styles.css\"></head><body>"
                + marker + "<script src=\"app.js\"></script></body></html>";
        var chunks = new ArrayList<String>();
        for (int index = 0; index < 5; index++) chunks.add("<section id=\"part-" + index + "\"><p>" + "学习".repeat(600) + "</p></section>\n");
        String css = "body{margin:0;font-family:sans-serif}section{padding:2rem}";
        String script = "document.documentElement.dataset.ready='true';";
        try (var provider = new ControlledProvider(request -> {
            assertHostSystem(request, DesktopPrompts.prepare(policy).body()); assertEquals(policy.outputTokens(), request.path("max_tokens").asInt());
            int step = calls.getAndIncrement(); JsonNode result;
            if (step == 0) result = tool(request, "write_file",
                    DesktopJson.write(DesktopJson.object().put("path", "index.html").put("content", skeleton)));
            else if (step <= chunks.size()) {
                assertTrue(Files.exists(workspace.resolve("index.html")));
                result = tool(request, "edit_file", DesktopJson.write(DesktopJson.object().put("path", "index.html")
                        .put("oldText", marker).put("newText", chunks.get(step - 1) + marker)));
            } else if (step == 6) result = tool(request, "edit_file",
                    DesktopJson.write(DesktopJson.object().put("path", "index.html").put("oldText", marker).put("newText", "")));
            else if (step == 7) result = tool(request, "write_file",
                    DesktopJson.write(DesktopJson.object().put("path", "styles.css").put("content", css)));
            else if (step == 8) result = tool(request, "write_file",
                    DesktopJson.write(DesktopJson.object().put("path", "app.js").put("content", script)));
            else return assistantText("已生成 index.html、styles.css、app.js；文件内容已检查，未运行浏览器验证。");
            var arguments = DesktopJson.parse(result.path("choices").get(0).path("message").path("tool_calls").get(0).path("function").path("arguments").asText());
            assertTrue(arguments.path("content").asText(arguments.path("newText").asText()).length() <= 2000);
            return result;
        })) {
            var notifications = new LinkedBlockingQueue<ObjectNode>();
            try (var host = host(temporary.resolve("data"), provider, notifications, policy)) {
                host.createSession("chunked-site", "分块网站", workspace.toString());
                var run = host.submitTask("chunked-site", "分块创建网站，不要一次输出完整源码", "chunked-input").path("runId").asText();
                try { await(host, notifications, run, "FINISHED", provider); }
                catch (AssertionError failure) {
                    provider.assertHealthy();
                    throw new AssertionError("Chunked run diagnostics: " + runDiagnostics(host.getSession("chunked-site"))
                            + "; provider=" + provider.diagnostics(), failure);
                }
                String expected = skeleton.replace(marker, String.join("", chunks));
                assertTrue(expected.length() > policy.outputTokens()); assertEquals(expected, Files.readString(workspace.resolve("index.html")));
                assertEquals(css, Files.readString(workspace.resolve("styles.css"))); assertEquals(script, Files.readString(workspace.resolve("app.js")));
                var state = host.getSession("chunked-site"); assertEquals("COMPLETED", state.path("runs").get(0).path("status").asText());
                int tools = 0;
                for (var event : state.path("events")) if (event.path("type").asText().equals("tool")) {
                    assertEquals("SUCCEEDED", event.path("text").asText()); tools++;
                }
                assertEquals(9, tools); assertEquals(10, calls.get()); provider.assertHealthy();
            }
        }
    }

    private static void assertLengthFailureWithoutTools(JsonNode state) {
        assertEquals("FAILED", state.path("runs").get(0).path("status").asText());
        assertTrue(state.toString().contains("model_output_length"));
        for (var event : state.path("events")) assertFalse(event.path("type").asText().equals("tool"));
    }

    private static String runDiagnostics(JsonNode state) {
        var value = DesktopJson.object(); value.set("runs", state.path("runs"));
        var events = value.putArray("recentEvents"); var source = state.path("events");
        for (int index = Math.max(0, source.size() - 8); index < source.size(); index++) {
            var event = source.get(index);
            events.addObject().put("type", event.path("type").asText()).put("text", event.path("text").asText())
                    .put("toolName", event.path("toolName").asText()).put("reason", event.path("data").path("reason").asText());
        }
        value.put("pendingConfirmations", state.path("pendingConfirmations").size()); return DesktopJson.write(value);
    }

    @Test void listsLargeDirectoryThroughFrozenToolsAndReturnsBothPagesToModel() throws Exception {
        var workspace = Files.createDirectory(temporary.resolve("list-pages"));
        for (int i = 219; i >= 0; i--) Files.createFile(workspace.resolve("item-%03d".formatted(i)));
        var calls = new AtomicInteger(); var names = new ArrayList<String>();
        var cursor = new AtomicReference<String>();
        try (var provider = new ControlledProvider(request -> {
            int step = calls.getAndIncrement();
            if (step == 0) {
                var definition = new ArrayList<JsonNode>();
                for (var entry : request.path("tools")) {
                    var function = entry.path("function");
                    if (function.path("description").asText().contains("title=\"list_files\"")) definition.add(function);
                }
                assertEquals(1, definition.size());
                assertTrue(definition.getFirst().path("description").asText().contains("nextAfter"));
                assertTrue(definition.getFirst().path("parameters").path("properties").has("after"));
                assertTrue(definition.getFirst().path("parameters").path("properties").has("nameContains"));
                return tool(request, "list_files", "{\"path\":\".\",\"nameContains\":\"item-\"}");
            }
            JsonNode last = null;
            for (var message : request.path("messages")) if (message.path("role").asText().equals("tool")) last = message;
            assertTrue(last != null);
            var page = DesktopJson.parse(last.path("content").asText());
            page.path("entries").forEach(entry -> names.add(entry.path("name").asText()));
            if (step == 1) {
                assertTrue(page.path("truncated").asBoolean());
                cursor.set(page.path("nextAfter").asText());
                assertEquals("item-199", cursor.get());
                var args = DesktopJson.object().put("path", ".").put("nameContains", "item-").put("after", cursor.get());
                return tool(request, "list_files", DesktopJson.write(args));
            }
            assertEquals(2, step);
            assertFalse(page.path("truncated").asBoolean());
            assertFalse(page.has("nextAfter"));
            assertEquals(220, names.size());
            for (int i = 0; i < names.size(); i++) assertEquals("item-%03d".formatted(i), names.get(i));
            return assistantText("目录读取完成。");
        })) {
            var notifications = new LinkedBlockingQueue<ObjectNode>();
            try (var host = host(temporary.resolve("list-data"), provider, notifications)) {
                host.createSession("list", "分页读取目录", workspace.toString());
                var run = host.submitTask("list", "列出所有文件", "list-input").path("runId").asText();
                await(host, notifications, run, "FINISHED", provider);
                assertEquals("COMPLETED", host.getSession("list").path("runs").get(0).path("status").asText());
                assertEquals(3, calls.get());
                provider.assertHealthy();
            }
        }
    }

    @Test void highEntropySearchPagesCompleteWithOriginalContextReadback() throws Exception {
        var workspace = Files.createDirectory(temporary.resolve("search-entropy"));
        var random = new java.util.Random(9173); var source = new StringBuilder();
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        for (int line = 0; line < 120; line++) {
            source.append("needle ");
            for (int i = 0; i < 1500; i++) source.append(alphabet.charAt(random.nextInt(alphabet.length())));
            source.append('\n');
        }
        Files.writeString(workspace.resolve("entropy.txt"), source);
        var calls = new AtomicInteger(); var seen = new AtomicInteger(); var readbacks = new AtomicInteger();
        var readSource = new AtomicReference<String>(); var retrieved = new StringBuilder();
        try (var provider = new ControlledProvider(request -> {
            int step = calls.getAndIncrement();
            if (step == 0) return tool(request, "search_files", "{\"path\":\"entropy.txt\",\"text\":\"needle\"}");
            if (seen.get() == 120) return assistantText("恢复后继续。");
            JsonNode last = null;
            for (var message : request.path("messages")) if (message.path("role").asText().equals("tool")) last = message;
            assertTrue(last != null); var value = DesktopJson.parse(last.path("content").asText());
            if (value.path("schemaId").asText().equals("skillforge.runtime.execution.tool-preview.v2")) {
                readSource.set(value.path("source_event_id").asText()); retrieved.setLength(0); readbacks.incrementAndGet();
                return tool(request, "read_context_reference", DesktopJson.write(DesktopJson.object().put("source_event_id", readSource.get()).put("offset", 0)));
            }
            if (value.has("source_event_id")) {
                assertEquals(readSource.get(), value.path("source_event_id").asText());
                retrieved.append(value.path("content").asText());
                if (!value.path("end").asBoolean())
                    return tool(request, "read_context_reference", DesktopJson.write(DesktopJson.object().put("source_event_id", readSource.get()).put("offset", value.path("next_offset").asInt())));
                var original = DesktopJson.parse(retrieved.toString());
                JsonNode originalTool = null;
                for (var message : original.path("messages")) if (message.path("role").asText().equals("TOOL")) originalTool = message;
                assertTrue(originalTool != null);
                value = DesktopJson.parse(originalTool.path("contents").get(0).path("text").asText());
            }
            seen.addAndGet(value.path("matches").size());
            if (!value.path("hasMore").asBoolean()) return assistantText("搜索完成。");
            var args = DesktopJson.object().put("path", "entropy.txt").put("text", "needle").put("cursor", value.path("nextCursor").asText());
            return tool(request, "search_files", DesktopJson.write(args));
        })) {
            var notifications = new LinkedBlockingQueue<ObjectNode>(); String originalRun;
            try (var host = host(temporary.resolve("search-entropy-data"), provider, notifications)) {
                host.createSession("entropy", "高熵分页搜索", workspace.toString());
                var run = host.submitTask("entropy", "查找全部匹配", "entropy-input").path("runId").asText(); originalRun = run;
                await(host, notifications, run, "FINISHED", provider);
                var state = host.getSession("entropy");
                System.out.println("SEARCH_CONTEXT_CONTROL modelCalls=" + calls.get() + " seenMatches=" + seen.get() + " readbacks=" + readbacks.get());
                assertEquals("COMPLETED", state.path("runs").get(0).path("status").asText(), runDiagnostics(state));
                assertEquals(120, seen.get()); assertTrue(readbacks.get() > 0); provider.assertHealthy();
            }
            int beforeRestart = calls.get(); String sameOwnerRun; String otherOwnerRun;
            var reopened = new LinkedBlockingQueue<ObjectNode>();
            try (var host = host(temporary.resolve("search-entropy-data"), provider, reopened)) {
                sameOwnerRun = host.submitTask("entropy", "继续", "followup").path("runId").asText();
                await(host, reopened, sameOwnerRun, "FINISHED", provider);
                host.createSession("other", "另一个会话", workspace.toString());
                otherOwnerRun = host.submitTask("other", "继续", "other-input").path("runId").asText();
                await(host, reopened, otherOwnerRun, "FINISHED", provider);
                assertEquals(beforeRestart + 2, calls.get(), "Recovery must not repeat prior model or tool calls");
                provider.assertHealthy();
            }
            try (var store = new SqliteExecutionStore(temporary.resolve("search-entropy-data/runtime.db"), Clock.systemUTC())) {
                var original = store.load(new RunRef(DesktopRuntime.TENANT, originalRun)).orElseThrow();
                var sameOwner = store.load(new RunRef(DesktopRuntime.TENANT, sameOwnerRun)).orElseThrow();
                var otherOwner = store.load(new RunRef(DesktopRuntime.TENANT, otherOwnerRun)).orElseThrow();
                var codec = new AgentExecutionCodec(DesktopRuntime.CODEC_LIMITS);
                var args = new TypedPayload("application/json", DesktopJson.write(DesktopJson.object().put("source_event_id", readSource.get()).put("limit", 7)));
                var accepted = DesktopContextReferences.read(store, codec, original, args, () -> false);
                assertEquals(DesktopTools.Status.SUCCEEDED, accepted.status());
                assertEquals("CONTEXT_REFERENCE_UNAVAILABLE", DesktopJson.parse(DesktopContextReferences.read(store, codec, sameOwner, args, () -> false).contentJson()).path("error").path("code").asText());
                assertEquals("CONTEXT_REFERENCE_UNAVAILABLE", DesktopJson.parse(DesktopContextReferences.read(store, codec, otherOwner, args, () -> false).contentJson()).path("error").path("code").asText());
                var forged = new TypedPayload("application/json", DesktopJson.write(DesktopJson.object().put("source_event_id", "execution-context-material:" + "0".repeat(64))));
                assertEquals("CONTEXT_REFERENCE_UNAVAILABLE", DesktopJson.parse(DesktopContextReferences.read(store, codec, original, forged, () -> false).contentJson()).path("error").path("code").asText());
                assertEquals("CANCELLED_BEFORE_DISPATCH", DesktopJson.parse(DesktopContextReferences.read(store, codec, original, args, () -> true).contentJson()).path("error").path("code").asText());
                var saved = store.event(original.identity().runRef(), new EventId(readSource.get())).orElseThrow();
                var modifiedBody = (ObjectNode) DesktopJson.parse(saved.event().data().canonicalJson()).deepCopy();
                modifiedBody.put("sourceContext", "sha256:" + "0".repeat(64));
                var modified = new TypedPayload(saved.event().data().schemaId(), DesktopJson.write(modifiedBody));
                var badSourceId = new EventId("execution-context-material:" + AgentExecutionCodec.digest(modified.canonicalJson()));
                var fake = new AtomicReference<ExecutionStore.SavedEvent>();
                var proxy = (ExecutionStore) java.lang.reflect.Proxy.newProxyInstance(ExecutionStore.class.getClassLoader(), new Class<?>[] {ExecutionStore.class},
                        (ignored, method, arguments) -> method.getName().equals("event") ? Optional.of(fake.get()) : method.invoke(store, arguments));
                var invalidEvents = List.of(
                        new ExecutionStore.Event(saved.event().id(), "wrong", saved.event().data()),
                        new ExecutionStore.Event(saved.event().id(), saved.event().type(), new TypedPayload("wrong", saved.event().data().canonicalJson())),
                        new ExecutionStore.Event(badSourceId, saved.event().type(), modified));
                for (var event : invalidEvents) {
                    fake.set(new ExecutionStore.SavedEvent(original.identity().runRef(), 1, 1, java.time.Instant.EPOCH, event));
                    var invalid = new TypedPayload("application/json", DesktopJson.write(DesktopJson.object().put("source_event_id", event.id().value())));
                    assertEquals("CONTEXT_REFERENCE_UNAVAILABLE", DesktopJson.parse(DesktopContextReferences.read(proxy, codec, original, invalid, () -> false).contentJson()).path("error").path("code").asText());
                }
            }
        }
    }

    @Test void modelUsesSearchColumnToReadLongLineMatch() throws Exception {
        var workspace = Files.createDirectory(temporary.resolve("search-read-column"));
        var random = new java.util.Random(4921); var prefix = new StringBuilder();
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        for (int i = 0; i < 3000; i++) prefix.append(alphabet.charAt(random.nextInt(alphabet.length())));
        prefix.append("界😀");
        Files.writeString(workspace.resolve("long.txt"), prefix + "needle suffix\n");
        var calls = new AtomicInteger();
        try (var provider = new ControlledProvider(request -> {
            int step = calls.getAndIncrement();
            if (step == 0) return tool(request, "search_files", "{\"path\":\"long.txt\",\"text\":\"needle\"}");
            JsonNode last = null;
            for (var message : request.path("messages")) if (message.path("role").asText().equals("tool")) last = message;
            assertTrue(last != null); var result = DesktopJson.parse(last.path("content").asText());
            if (step == 1) {
                var match = result.path("matches").get(0);
                assertTrue(match.path("lineTruncated").asBoolean());
                assertFalse(match.path("text").asText().contains("needle"));
                assertEquals(prefix.length() + 1, match.path("readColumn").asInt());
                var args = DesktopJson.object().put("path", match.path("path").asText())
                        .put("offset", match.path("line").asInt()).put("column", match.path("readColumn").asInt());
                return tool(request, "read_file", DesktopJson.write(args));
            }
            assertEquals(2, step); assertTrue(result.path("content").asText().startsWith("needle suffix"));
            return assistantText("已找到命中内容。");
        })) {
            var notifications = new LinkedBlockingQueue<ObjectNode>();
            try (var host = host(temporary.resolve("search-read-column-data"), provider, notifications)) {
                host.createSession("column", "长行搜索", workspace.toString());
                var run = host.submitTask("column", "读取命中内容", "column-input").path("runId").asText();
                await(host, notifications, run, "FINISHED", provider);
                assertEquals("COMPLETED", host.getSession("column").path("runs").get(0).path("status").asText());
                assertEquals(3, calls.get()); provider.assertHealthy();
            }
        }
    }

    @Test void modelReceivesLightweightSearchPagesWithoutContextReadback() throws Exception {
        var workspace = Files.createDirectory(temporary.resolve("search-lightweight"));
        var random = new java.util.Random(9173); var source = new StringBuilder();
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        for (int line = 0; line < 300; line++) {
            source.append("needle ");
            for (int i = 0; i < 600; i++) source.append(alphabet.charAt(random.nextInt(alphabet.length())));
            source.append('\n');
        }
        Files.writeString(workspace.resolve("many.txt"), source);
        var calls = new AtomicInteger(); var lines = new ArrayList<Integer>();
        try (var provider = new ControlledProvider(request -> {
            int step = calls.getAndIncrement();
            if (step == 0) {
                var definitions = new ArrayList<JsonNode>();
                for (var entry : request.path("tools")) {
                    var function = entry.path("function");
                    if (function.path("description").asText().contains("title=\"search_files\"")) definitions.add(function);
                }
                assertEquals(1, definitions.size());
                assertTrue(definitions.getFirst().path("parameters").path("properties").path("output").path("enum").toString().contains("matches"));
                return tool(request, "search_files", "{\"path\":\"many.txt\",\"text\":\"needle\",\"output\":\"matches\"}");
            }
            JsonNode last = null;
            for (var message : request.path("messages")) if (message.path("role").asText().equals("tool")) last = message;
            assertTrue(last != null); var page = DesktopJson.parse(last.path("content").asText());
            assertFalse(page.has("source_event_id"));
            assertEquals(step == 1 ? 250 : 50, page.path("pageMatchCount").asInt());
            page.path("matches").forEach(match -> { assertEquals(2, match.size()); lines.add(match.path("line").asInt()); });
            if (step == 1) {
                assertTrue(page.path("hasMore").asBoolean());
                var args = DesktopJson.object().put("path", "many.txt").put("text", "needle").put("output", "matches").put("cursor", page.path("nextCursor").asText());
                return tool(request, "search_files", DesktopJson.write(args));
            }
            assertEquals(2, step); assertFalse(page.path("hasMore").asBoolean()); assertEquals(300, lines.size());
            for (int i = 0; i < lines.size(); i++) assertEquals(i + 1, lines.get(i));
            return assistantText("搜索完成。");
        })) {
            var notifications = new LinkedBlockingQueue<ObjectNode>();
            try (var host = host(temporary.resolve("search-lightweight-data"), provider, notifications)) {
                host.createSession("light", "轻量分页", workspace.toString());
                var run = host.submitTask("light", "查找匹配行", "light-input").path("runId").asText();
                await(host, notifications, run, "FINISHED", provider);
                assertEquals("COMPLETED", host.getSession("light").path("runs").get(0).path("status").asText());
                assertEquals(3, calls.get()); provider.assertHealthy();
            }
            List<String> saved;
            try (var store = new SqliteExecutionStore(temporary.resolve("search-lightweight-data/runtime.db"), Clock.systemUTC())) {
                saved = store.events(new SessionRef(DesktopRuntime.TENANT, "light"), 0, 100).stream()
                        .filter(event -> event.event().type().equals(AgentExecutionProgram.TOOL_RESULT_EVENT))
                        .map(event -> event.event().id().value() + ":" + event.event().data().canonicalJson()).toList();
                assertEquals(2, saved.size());
            }
            try (var host = host(temporary.resolve("search-lightweight-data"), provider, new LinkedBlockingQueue<ObjectNode>())) {
                assertEquals("COMPLETED", host.getSession("light").path("runs").get(0).path("status").asText());
                assertEquals(3, calls.get());
            }
            try (var store = new SqliteExecutionStore(temporary.resolve("search-lightweight-data/runtime.db"), Clock.systemUTC())) {
                var after = store.events(new SessionRef(DesktopRuntime.TENANT, "light"), 0, 100).stream()
                        .filter(event -> event.event().type().equals(AgentExecutionProgram.TOOL_RESULT_EVENT))
                        .map(event -> event.event().id().value() + ":" + event.event().data().canonicalJson()).toList();
                assertEquals(saved, after); assertEquals(3, calls.get()); provider.assertHealthy();
            }
        }
    }

    @Test void modelReceivesSearchCursorAndContinuesSameFile() throws Exception {
        var workspace = Files.createDirectory(temporary.resolve("search-pages"));
        Files.writeString(workspace.resolve("many.txt"), "needle\n".repeat(300));
        var calls = new AtomicInteger(); var lines = new ArrayList<Integer>();
        try (var provider = new ControlledProvider(request -> {
            int step = calls.getAndIncrement();
            if (step == 0) return tool(request, "search_files", "{\"path\":\"many.txt\",\"text\":\"needle\"}");
            JsonNode last = null;
            for (var message : request.path("messages")) if (message.path("role").asText().equals("tool")) last = message;
            assertTrue(last != null); var page = DesktopJson.parse(last.path("content").asText());
            page.path("matches").forEach(match -> lines.add(match.path("line").asInt()));
            if (step == 1) {
                assertTrue(page.path("hasMore").asBoolean());
                var args = DesktopJson.object().put("path", "many.txt").put("text", "needle").put("cursor", page.path("nextCursor").asText());
                return tool(request, "search_files", DesktopJson.write(args));
            }
            assertEquals(2, step); assertFalse(page.path("hasMore").asBoolean()); assertEquals(300, lines.size());
            for (int i = 0; i < lines.size(); i++) assertEquals(i + 1, lines.get(i));
            return assistantText("搜索完成。");
        })) {
            var notifications = new LinkedBlockingQueue<ObjectNode>();
            try (var host = host(temporary.resolve("search-pages-data"), provider, notifications)) {
                host.createSession("search", "分页搜索", workspace.toString());
                var run = host.submitTask("search", "查找所有匹配", "search-input").path("runId").asText();
                await(host, notifications, run, "FINISHED", provider);
                assertEquals("COMPLETED", host.getSession("search").path("runs").get(0).path("status").asText());
                provider.assertHealthy();
            }
        }
    }

    @Test void repeatedDeterminateReadFailuresUseOriginalReceiptContentAndStop() throws Exception {
        var workspace = Files.createDirectory(temporary.resolve("repeat-missing")); var calls = new AtomicInteger();
        try (var provider = new ControlledProvider(request -> {
            assertTrue(calls.incrementAndGet() <= 5, "重复失败应在第5次确定结果后停止");
            return tool(request, "read_file", "{\"path\":\"missing.txt\"}");
        })) {
            var notifications = new LinkedBlockingQueue<ObjectNode>();
            try (var host = host(temporary.resolve("repeat-missing-data"), provider, notifications)) {
                host.createSession("repeat-missing", "重复只读失败", workspace.toString());
                var run = host.submitTask("repeat-missing", "读取缺失文件", "repeat-input").path("runId").asText();
                await(host, notifications, run, "FINISHED", provider);
                assertEquals("TERMINATED", host.getSession("repeat-missing").path("runs").get(0).path("status").asText());
                assertEquals(5, calls.get()); provider.assertHealthy();
            }
        }
    }

    @Test void newReadAfterEditingReturnsCurrentFileContent() throws Exception {
        var workspace = Files.createDirectory(temporary.resolve("fresh-read"));
        Files.writeString(workspace.resolve("answer.txt"), "before");
        var calls = new AtomicInteger(); var firstVersion = new AtomicReference<String>();
        try (var provider = new ControlledProvider(request -> {
            int step = calls.getAndIncrement();
            if (step == 0 || step == 2) return tool(request, "read_file", "{\"path\":\"answer.txt\"}");
            if (step == 1) {
                JsonNode last = null; for (var message : request.path("messages")) if (message.path("role").asText().equals("tool")) last = message;
                assertTrue(last != null); firstVersion.set(DesktopJson.parse(last.path("content").asText()).path("version").asText());
                return tool(request, "edit_file", "{\"path\":\"answer.txt\",\"oldText\":\"before\",\"newText\":\"after\"}");
            }
            JsonNode last = null;
            for (var message : request.path("messages")) if (message.path("role").asText().equals("tool")) last = message;
            assertTrue(last != null); var actual = DesktopJson.parse(last.path("content").asText());
            assertEquals("after", actual.path("content").asText());
            assertFalse(firstVersion.get().isBlank()); assertFalse(firstVersion.get().equals(actual.path("version").asText()));
            assertTrue(step == 3 || step == 4);
            return assistantText("完成。");
        })) {
            var notifications = new LinkedBlockingQueue<ObjectNode>();
            try (var host = host(temporary.resolve("fresh-read-data"), provider, notifications)) {
                host.createSession("read", "读取变更", workspace.toString());
                var run = host.submitTask("read", "读取、修改并再读取", "read-input").path("runId").asText();
                await(host, notifications, run, "FINISHED", provider);
                assertEquals("COMPLETED", host.getSession("read").path("runs").get(0).path("status").asText());
                provider.assertHealthy();
            }
            var afterRestart = new LinkedBlockingQueue<ObjectNode>();
            try (var host = host(temporary.resolve("fresh-read-data"), provider, afterRestart)) {
                var run = host.submitTask("read", "基于已保存结果继续", "read-followup").path("runId").asText();
                await(host, afterRestart, run, "FINISHED", provider);
                assertEquals(5, calls.get(), "Recovery must not resend old model or tool operations");
                assertEquals("after", Files.readString(workspace.resolve("answer.txt")));
                assertEquals("COMPLETED", host.getSession("read").path("runs").get(1).path("status").asText());
                provider.assertHealthy();
            }
        }
    }

    @Test void conflictCanBeResolvedByFreshReadWithinSameRun() throws Exception {
        var workspace = Files.createDirectory(temporary.resolve("fresh-conflict"));
        Files.writeString(workspace.resolve("answer.txt"), "before");
        var calls = new AtomicInteger();
        try (var provider = new ControlledProvider(request -> {
            int step = calls.getAndIncrement();
            if (step == 0) return tool(request, "read_file", "{\"path\":\"answer.txt\"}");
            if (step == 2) {
                JsonNode last = null; for (var message : request.path("messages")) if (message.path("role").asText().equals("tool")) last = message;
                assertTrue(last != null && last.path("content").asText().contains("FILE_VERSION_CONFLICT"));
                return tool(request, "read_file", "{\"path\":\"answer.txt\"}");
            }
            if (step == 1) {
                try { Files.writeString(workspace.resolve("answer.txt"), "external"); }
                catch (IOException failure) { throw new IllegalStateException(failure); }
                return tool(request, "edit_file", "{\"path\":\"answer.txt\",\"oldText\":\"before\",\"newText\":\"changed\"}");
            }
            JsonNode last = null;
            for (var message : request.path("messages")) if (message.path("role").asText().equals("tool")) last = message;
            assertTrue(last != null);
            if (step == 3) {
                assertEquals("external", DesktopJson.parse(last.path("content").asText()).path("content").asText());
                return tool(request, "edit_file", "{\"path\":\"answer.txt\",\"oldText\":\"external\",\"newText\":\"fixed\"}");
            }
            assertEquals(4, step); var feedback = DesktopJson.parse(last.path("content").asText());
            assertEquals("answer.txt", feedback.path("path").asText()); assertFalse(feedback.path("version").asText().isBlank());
            return assistantText("完成。");
        })) {
            var notifications = new LinkedBlockingQueue<ObjectNode>();
            try (var host = host(temporary.resolve("fresh-conflict-data"), provider, notifications)) {
                host.createSession("conflict", "冲突后重读", workspace.toString());
                var run = host.submitTask("conflict", "读取并修改", "conflict-input").path("runId").asText();
                await(host, notifications, run, "FINISHED", provider);
                assertEquals("fixed", Files.readString(workspace.resolve("answer.txt")));
                assertEquals("COMPLETED", host.getSession("conflict").path("runs").get(0).path("status").asText());
                provider.assertHealthy();
            }
        }
    }

    @Test void listAndSearchObserveNewFileAfterWriteWithinSameRun() throws Exception {
        var workspace = Files.createDirectory(temporary.resolve("fresh-list-search")); var calls = new AtomicInteger();
        try (var provider = new ControlledProvider(request -> {
            int step = calls.getAndIncrement();
            if (step == 0 || step == 3) return tool(request, "list_files", "{\"path\":\".\"}");
            if (step == 1) return tool(request, "search_files", "{\"path\":\".\",\"text\":\"needle\"}");
            if (step == 4) {
                JsonNode listing = null;
                for (var message : request.path("messages")) if (message.path("role").asText().equals("tool")) listing = message;
                assertTrue(listing != null && listing.path("content").asText().contains("new.txt"));
                return tool(request, "search_files", "{\"path\":\".\",\"text\":\"needle\"}");
            }
            if (step == 2) return tool(request, "write_file", "{\"path\":\"new.txt\",\"content\":\"needle\"}");
            JsonNode last = null;
            for (var message : request.path("messages")) if (message.path("role").asText().equals("tool")) last = message;
            assertTrue(last != null); assertTrue(last.path("content").asText().contains("new.txt"));
            assertEquals(5, step); return assistantText("完成。");
        })) {
            var notifications = new LinkedBlockingQueue<ObjectNode>();
            try (var host = host(temporary.resolve("fresh-list-search-data"), provider, notifications)) {
                host.createSession("list", "目录和搜索", workspace.toString());
                var run = host.submitTask("list", "查询并创建文件", "list-input").path("runId").asText();
                await(host, notifications, run, "FINISHED", provider);
                assertEquals("COMPLETED", host.getSession("list").path("runs").get(0).path("status").asText());
                assertEquals(6, calls.get()); provider.assertHealthy();
            }
        }
    }

    @Test void runsModelReadEditFinalThenCarriesCommittedHistoryIntoQueuedTurn() throws Exception {
        var workspace = Files.createDirectory(temporary.resolve("workspace"));
        Files.writeString(workspace.resolve("answer.txt"), "before");
        var calls = new AtomicInteger(); var requests = new ArrayList<JsonNode>();
        try (var provider = new ControlledProvider(request -> {
            for (var definition : request.path("tools")) assertFalse(definition.path("function").path("strict").asBoolean());
            synchronized (requests) { requests.add(request.deepCopy()); }
            return switch (calls.getAndIncrement()) {
                case 0 -> tool(request, "read_file", "{\"path\":\"answer.txt\"}");
                case 1 -> tool(request, "edit_file", "{\"path\":\"answer.txt\",\"oldText\":\"before\",\"newText\":\"after\"}");
                default -> assistantText("已修复文件，实际结果为 after。");
            };
        })) {
            var notifications = new LinkedBlockingQueue<ObjectNode>();
            try (var host = host(temporary.resolve("data"), provider, notifications)) {
                host.createSession("session-one", "真实工具回合", workspace.toString());
                var first = host.submitTask("session-one", "读取并修复文件", "input-one").path("runId").asText();
                var repeated = host.submitTask("session-one", "读取并修复文件", "input-one");
                assertFalse(repeated.path("created").asBoolean()); assertEquals(first, repeated.path("runId").asText());
                var second = host.submitTask("session-one", "说明上一轮实际结果", "input-two").path("runId").asText();
                await(host, notifications, second, "FINISHED");
                var state = host.getSession("session-one");
                assertEquals("after", Files.readString(workspace.resolve("answer.txt")));
                assertEquals("COMPLETED", state.path("runs").get(0).path("status").asText());
                assertEquals("COMPLETED", state.path("runs").get(1).path("status").asText());
                assertEquals(4, calls.get());
                assertEquals(4, state.path("messages").size());
                assertTrue(state.path("events").toString().contains("diff"));
                synchronized (requests) {
                    assertTrue(requests.get(1).path("messages").toString().contains("before"));
                    assertTrue(requests.get(2).path("messages").toString().contains("after"));
                    assertTrue(requests.get(3).path("messages").toString().contains("已修复文件"));
                }
                provider.assertHealthy();
            }
            assertNoCredentialOnDisk(temporary.resolve("data"));
        }
    }

    @Test void savesCommandWaitAcrossHostRestartAndOnlyRunsAfterExactApproval() throws Exception {
        var workspace = Files.createDirectory(temporary.resolve("commands"));
        var calls = new AtomicInteger(); var defaults = DesktopPolicy.defaults();
        var frozenPolicy = defaults.withOutputTokens(defaults.outputTokens() / 2);
        try (var provider = new ControlledProvider(request -> {
            assertHostSystem(request, DesktopPrompts.prepare(frozenPolicy).body());
            assertEquals(frozenPolicy.outputTokens(), request.path("max_tokens").asInt());
            return calls.getAndIncrement() == 0 ? tool(request, "run_command", "{\"command\":\"echo desktop-proof\"}")
                    : assistantText("命令已完成。");
        })) {
            var notifications = new LinkedBlockingQueue<ObjectNode>();
            String runId; String waitingId; String following;
            try (var host = host(temporary.resolve("data"), provider, notifications, frozenPolicy)) {
                host.createSession("command-session", "命令确认", workspace.toString());
                runId = host.submitTask("command-session", "执行一个确认命令", "command-input").path("runId").asText();
                await(host, notifications, runId, "WAITING", provider);
                var state = host.getSession("command-session");
                waitingId = state.path("pendingConfirmations").get(0).path("waitingId").asText();
                following = host.submitTask("command-session", "等待前一任务确认后再执行", "command-following").path("runId").asText();
                assertFalse(host.getSession("command-session").path("runs").get(1).path("active").asBoolean());
                assertEquals(1, calls.get());
                assertFalse(state.path("events").toString().contains("stdout"));
            }
            notifications.clear();
            try (var host = host(temporary.resolve("data"), provider, notifications)) {
                assertEquals(waitingId, host.getSession("command-session").path("pendingConfirmations").get(0).path("waitingId").asText());
                assertFalse(host.getSession("command-session").path("runs").get(1).path("active").asBoolean());
                assertEquals(1, calls.get());
                host.confirmTool(runId, waitingId, true, "approval-one");
                await(host, notifications, following, "FINISHED", provider);
                var state = host.getSession("command-session");
                assertEquals("COMPLETED", state.path("runs").get(0).path("status").asText());
                assertTrue(state.path("events").toString().contains("desktop-proof"));
                assertTrue(state.path("events").toString().contains("stdout"));
                assertEquals(3, calls.get());
                assertEquals(0, state.path("pendingConfirmations").size());
                provider.assertHealthy();
            }
        }
    }

    @Test void rejectionReturnsToolFailureWithoutExecutingCommand() throws Exception {
        var workspace = Files.createDirectory(temporary.resolve("reject")); var calls = new AtomicInteger();
        try (var provider = new ControlledProvider(request -> calls.getAndIncrement() == 0
                ? tool(request, "run_command", "{\"command\":\"echo forbidden\"}") : assistantText("用户拒绝了命令，未执行。"))) {
            var notifications = new LinkedBlockingQueue<ObjectNode>();
            try (var host = host(temporary.resolve("data"), provider, notifications)) {
                host.createSession("reject-session", "拒绝执行", workspace.toString());
                var run = host.submitTask("reject-session", "请执行命令", "reject-input").path("runId").asText();
                await(host, notifications, run, "WAITING");
                var wait = host.getSession("reject-session").path("pendingConfirmations").get(0).path("waitingId").asText();
                host.confirmTool(run, wait, false, "reject-response");
                await(host, notifications, run, "FINISHED");
                var state = host.getSession("reject-session");
                assertTrue(state.path("events").toString().contains("COMMAND_REJECTED"));
                assertFalse(state.path("events").toString().contains("stdout"));
                assertEquals(2, calls.get()); provider.assertHealthy();
            }
        }
    }

    @Test void runsTwoDifferentCommandsInOneRunWithSeparateExactConfirmations() throws Exception {
        var workspace = Files.createDirectory(temporary.resolve("two-commands")); var calls = new AtomicInteger();
        try (var provider = new ControlledProvider(request -> switch (calls.getAndIncrement()) {
            case 0 -> tool(request, "run_command", "{\"command\":\"echo desktop-first\"}");
            case 1 -> tool(request, "run_command", "{\"command\":\"echo desktop-second\"}");
            default -> assistantText("两条命令都已实际执行。");
        })) {
            var notifications = new LinkedBlockingQueue<ObjectNode>();
            try (var host = host(temporary.resolve("data"), provider, notifications)) {
                host.createSession("two-commands", "连续命令确认", workspace.toString());
                var run = host.submitTask("two-commands", "逐条确认执行两条不同命令", "two-command-input").path("runId").asText();
                var first = awaitCommandConfirmation(host, "two-commands", notifications, provider, "");
                assertEquals("echo desktop-first", first.path("command").asText()); assertEquals(1, calls.get());
                host.confirmTool(run, first.path("waitingId").asText(), true, "two-command-first");
                var second = awaitCommandConfirmation(host, "two-commands", notifications, provider, first.path("waitingId").asText());
                assertEquals("echo desktop-second", second.path("command").asText()); assertEquals(2, calls.get());
                host.confirmTool(run, second.path("waitingId").asText(), true, "two-command-second");
                await(host, notifications, run, "FINISHED", provider);
                var state = host.getSession("two-commands");
                assertEquals("COMPLETED", state.path("runs").get(0).path("status").asText());
                int commands = 0;
                for (var event : state.path("events")) if (event.path("toolName").asText().equals("run_command")) {
                    assertEquals("SUCCEEDED", event.path("text").asText()); assertTrue(event.path("result").path("stdout").asText().contains("desktop-")); commands++;
                }
                assertEquals(2, commands); assertEquals(3, calls.get()); provider.assertHealthy();
            }
        }
    }

    private static JsonNode awaitCommandConfirmation(DesktopRuntime host, String session, BlockingQueue<ObjectNode> notifications,
            ControlledProvider provider, String previous) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            provider.assertHealthy();
            for (var confirmation : host.getSession(session).path("pendingConfirmations"))
                if (!confirmation.path("waitingId").asText().equals(previous)) return confirmation;
            var event = notifications.poll(200, TimeUnit.MILLISECONDS);
            if (event != null && event.path("event").asText().equals("hostError")) throw new AssertionError(event);
        }
        throw new AssertionError("No new command confirmation: " + runDiagnostics(host.getSession(session)));
    }

    @Test void limitsDifferentSessionsToTwoActualConcurrentCalls() throws Exception {
        var workspace = Files.createDirectory(temporary.resolve("parallel"));
        var entered = new CountDownLatch(2); var release = new CountDownLatch(1); var calls = new AtomicInteger();
        try (var provider = new ControlledProvider(request -> {
            calls.incrementAndGet(); entered.countDown();
            try { assertTrue(release.await(15, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            return assistantText("完成。");
        })) {
            var notifications = new LinkedBlockingQueue<ObjectNode>();
            try (var host = host(temporary.resolve("data"), provider, notifications)) {
                for (int number = 0; number < 3; number++) host.createSession("parallel-" + number, "并行任务" + number, workspace.toString());
                var one = host.submitTask("parallel-0", "任务一", "parallel-input-1").path("runId").asText();
                var two = host.submitTask("parallel-1", "任务二", "parallel-input-2").path("runId").asText();
                var three = host.submitTask("parallel-2", "任务三", "parallel-input-3").path("runId").asText();
                try {
                    assertTrue(entered.await(15, TimeUnit.SECONDS));
                    assertEquals(2, calls.get());
                    assertFalse(host.getSession("parallel-2").path("runs").get(0).path("active").asBoolean());
                } finally { release.countDown(); }
                awaitAll(host, notifications, Set.of(one, two, three));
                assertEquals(3, calls.get()); provider.assertHealthy();
            } finally { release.countDown(); }
        }
    }

    @Test void unknownModelOutcomeStaysUnknownAfterRestartWithoutResubmission() throws Exception {
        var workspace = Files.createDirectory(temporary.resolve("unknown")); var calls = new AtomicInteger();
        try (var provider = new ControlledProvider(request -> { calls.incrementAndGet(); return DesktopJson.object(); })) {
            var notifications = new LinkedBlockingQueue<ObjectNode>();
            String run; String following;
            try (var host = host(temporary.resolve("data"), provider, notifications)) {
                host.createSession("unknown-session", "未知回执", workspace.toString());
                run = host.submitTask("unknown-session", "返回任务结果", "unknown-input").path("runId").asText();
                await(host, notifications, run, "UNCERTAIN"); assertEquals(1, calls.get());
                following = host.submitTask("unknown-session", "不能越过未知结果", "following-input").path("runId").asText();
                assertEquals("READY", host.getSession("unknown-session").path("runs").get(1).path("phase").asText());
                assertFalse(host.getSession("unknown-session").path("runs").get(1).path("active").asBoolean());
            }
            notifications.clear();
            try (var host = host(temporary.resolve("data"), provider, notifications)) {
                assertEquals("UNCERTAIN", host.getSession("unknown-session").path("runs").get(0).path("phase").asText());
                assertFalse(host.submitTask("unknown-session", "返回任务结果", "unknown-input").path("created").asBoolean());
                host.configureModel("custom", provider.url(), "controlled-model", API_KEY, OptionalInt.empty());
                var state = host.getSession("unknown-session");
                assertEquals(following, state.path("runs").get(1).path("id").asText());
                assertEquals("READY", state.path("runs").get(1).path("phase").asText());
                assertFalse(state.path("runs").get(1).path("active").asBoolean());
                assertEquals(1, calls.get()); provider.assertHealthy();
            }
        }
    }

    @Test void cancelledRunKeepsItsSessionSlotUntilDriverCleanupFinishes() throws Exception {
        var workspace = Files.createDirectory(temporary.resolve("cancel-order"));
        var requestEntered = new CountDownLatch(1); var releaseResponse = new CountDownLatch(1);
        var cleanupEntered = new CountDownLatch(1); var releaseCleanup = new CountDownLatch(1);
        var calls = new AtomicInteger(); var firstRun = new AtomicReference<String>();
        try (var provider = new ControlledProvider(request -> {
            if (calls.getAndIncrement() == 0) {
                requestEntered.countDown();
                try { releaseResponse.await(20, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            }
            return assistantText("已完成。");
        })) {
            var notifications = new LinkedBlockingQueue<ObjectNode>();
            try (var host = new DesktopRuntime(temporary.resolve("data"), Clock.systemUTC(), DesktopPolicy.defaults(), event -> {
                notifications.add(event);
                if (event.path("event").asText().equals("runStopping") && event.path("runId").asText().equals(firstRun.get())) {
                    cleanupEntered.countDown();
                    try { assertTrue(releaseCleanup.await(15, TimeUnit.SECONDS)); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                }
            })) {
                host.configureModel("custom", provider.url(), "controlled-model", API_KEY, OptionalInt.empty()); host.start();
                host.createSession("cancel-order", "取消后顺序", workspace.toString());
                var one = host.submitTask("cancel-order", "第一个任务", "cancel-order-1").path("runId").asText(); firstRun.set(one);
                assertTrue(requestEntered.await(15, TimeUnit.SECONDS));
                var two = host.submitTask("cancel-order", "第二个任务", "cancel-order-2").path("runId").asText();
                try {
                    host.cancelTask(one, "cancel-order-request");
                    assertTrue(cleanupEntered.await(15, TimeUnit.SECONDS));
                    // 明确触发一次调度；旧会话Flight尚未释放时，新Run不得占槽或进入Provider。
                    host.configureModel("custom", provider.url(), "controlled-model", API_KEY, OptionalInt.empty());
                    assertFalse(host.getSession("cancel-order").path("runs").get(1).path("active").asBoolean());
                    assertEquals(1, calls.get());
                } finally { releaseCleanup.countDown(); releaseResponse.countDown(); }
                await(host, notifications, two, "FINISHED");
                assertEquals(2, calls.get());
                assertEquals("CANCELLED", host.getSession("cancel-order").path("runs").get(0).path("status").asText());
            } finally { releaseCleanup.countDown(); releaseResponse.countDown(); }
        }
    }

    @Test void completedNonzeroCommandFeedsRealDiagnosticBackToModelAndShowsFailure() throws Exception {
        var workspace = Files.createDirectory(temporary.resolve("diagnostic")); var calls = new AtomicInteger();
        String command = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? "[Console]::Error.WriteLine('controlled-build-error'); exit 7" : "printf 'controlled-build-error\\n' >&2; exit 7";
        try (var provider = new ControlledProvider(request -> {
            if (calls.getAndIncrement() == 0) return tool(request, "run_command",
                    DesktopJson.write(DesktopJson.object().put("command", command)));
            assertTrue(request.path("messages").toString().contains("controlled-build-error"));
            assertTrue(request.path("messages").toString().contains("exitCode"));
            return assistantText("命令执行失败，退出码为7，已保留真实错误。");
        })) {
            var notifications = new LinkedBlockingQueue<ObjectNode>();
            try (var host = host(temporary.resolve("data"), provider, notifications)) {
                host.createSession("diagnostic", "错误诊断", workspace.toString());
                var run = host.submitTask("diagnostic", "执行并报告错误", "diagnostic-input").path("runId").asText();
                await(host, notifications, run, "WAITING");
                var wait = host.getSession("diagnostic").path("pendingConfirmations").get(0).path("waitingId").asText();
                host.confirmTool(run, wait, true, "diagnostic-approval"); await(host, notifications, run, "FINISHED");
                var events = host.getSession("diagnostic").path("events");
                boolean foundFailure = false;
                for (var event : events) if (event.path("type").asText().equals("tool") && event.path("toolName").asText().equals("run_command")) {
                    assertEquals("FAILED", event.path("text").asText());
                    assertEquals(7, event.path("result").path("exitCode").asInt());
                    assertTrue(event.path("result").path("stderr").asText().contains("controlled-build-error"));
                    foundFailure = true;
                }
                assertTrue(foundFailure); provider.assertHealthy();
            }
        }
    }

    @Test void boundsLargeToolAndAccumulatedHistoryProjectionWithoutChangingSavedMaterials() throws Exception {
        var workspace = Files.createDirectory(temporary.resolve("large-projection"));
        String fileContent = "a".repeat(250_000); Files.writeString(workspace.resolve("large.txt"), fileContent);
        String answer = "最终回答仍可见。" + " ".repeat(180_000); var calls = new AtomicInteger();
        try (var provider = new ControlledProvider(request -> calls.getAndIncrement() == 0
                ? tool(request, "read_file", "{\"path\":\"large.txt\"}") : assistantText(answer))) {
            var notifications = new LinkedBlockingQueue<ObjectNode>(); var data = temporary.resolve("data");
            try (var host = host(data, provider, notifications)) {
                host.createSession("large-projection", "大结果展示", workspace.toString());
                for (int turn = 0; turn < 6; turn++) {
                    var run = host.submitTask("large-projection", "完成第" + turn + "轮", "large-input-" + turn).path("runId").asText();
                    await(host, notifications, run, "FINISHED");
                }
                var state = host.getSession("large-projection");
                assertTrue(DesktopJson.write(state).getBytes(StandardCharsets.UTF_8).length <= 1024 * 1024);
                assertTrue(state.path("projectionTruncated").asBoolean());
                assertTrue(state.path("messages").get(state.path("messages").size() - 1).path("text").asText().contains("最终回答仍可见"));
                try (var files = Files.list(data.resolve("tool-receipts"))) {
                    var receipt = DesktopJson.parse(Files.readString(files.findFirst().orElseThrow()));
                assertEquals(fileContent.substring(0, 2000), receipt.path("result").path("content").asText());
                assertTrue(receipt.path("result").path("truncated").asBoolean());
                assertEquals(1, receipt.path("result").path("nextLine").asInt());
                assertEquals(2001, receipt.path("result").path("nextColumn").asInt());
                }
                provider.assertHealthy();
            }
        }
    }

    /** 正式Store受理的旧版本输入fixture；没有伪造模型回执，也不触发云端请求。 */
    private static RunRef seedFinishedHistory(Path data, Path workspace, String url, String oldSystem) throws IOException {
        return seedHistory(data, workspace, url, oldSystem, Optional.empty(), true);
    }

    private static RunRef seedHistory(Path data, Path workspace, String url, String oldSystem, Optional<String> oldSchema, boolean finished) throws IOException {
        return seedHistory(data, workspace, url, oldSystem, oldSchema, finished, false);
    }

    private static RunRef seedHistory(Path data, Path workspace, String url, String oldSystem, Optional<String> oldSchema, boolean finished, boolean streaming) throws IOException {
        Files.createDirectories(data); var clock = Clock.systemUTC(); var policy = DesktopPolicy.defaults();
        var codec = new AgentExecutionCodec(DesktopRuntime.CODEC_LIMITS);
        var runRef = new RunRef(DesktopRuntime.TENANT, "desktop-run:old-prompt-fixture");
        var owner = DesktopRuntime.owner("upgrade"); var actor = DesktopRuntime.actor(owner); var inputId = new InputId("old-input");
        var identity = new RunIdentity(owner, new AgentId("desktop-agent"), inputId, runRef,
                new TraceEnvelope("fixture-trace", "fixture-span", inputId, Optional.empty()), actor.verificationRef(),
                new RunRequester.EndUser(new EndUserId(1L)), Optional.empty(), new RunOriginRef(DesktopRuntime.TENANT, "desktop:upgrade"));
        try (var models = new DesktopModels(clock, DesktopRuntime.CODEC_LIMITS);
                var store = new SqliteExecutionStore(data.resolve("runtime.db"), clock);
                var sessions = new SqliteDesktopSessions(data.resolve("sessions.db"), clock)) {
            sessions.create("upgrade", "宿主规则升级", workspace.toString());
            var settings = models.configure("custom", url, "controlled-model", API_KEY);
            if (streaming) settings = new DesktopModels.Settings(settings.providerId(), settings.baseUrl(), settings.model(),
                    settings.serviceId(), settings.profileVersion(), true);
            var frozenJson = DesktopConfiguration.freeze(settings, policy, workspace.toString(), Optional.empty(), List.of(), DesktopPrompts.prepare(policy));
            frozenJson.withObject("prompt").put("effectiveBodySha256", AgentExecutionCodec.digest(oldSystem));
            if (oldSchema.isPresent()) {
                if (oldSchema.orElseThrow().endsWith(".v1")) frozenJson.remove("responseProtocol");
                frozenJson.remove("budgetPolicy");
                var historical = frozenJson.withObject("policy");
                historical.put(oldSchema.orElseThrow().endsWith(".v3") ? "maxBusinessModelCalls" : "maxModelSteps", 20)
                        .put("maxToolCalls", 40).put("runTokens", 1_000_000).put("activeTimeoutMillis", 1_800_000);
            }
            var frozen = DesktopJson.material(oldSchema.orElse(DesktopConfiguration.SCHEMA), frozenJson);
            var payload = codec.encodeInput(List.of(new ModelMessage("desktop-system", ModelRole.SYSTEM, oldSystem),
                    new ModelMessage("input:old-input", ModelRole.USER, "历史用户问题")),
                    new TypedPayload("noewise.desktop.task.v1", "{\"text\":\"历史用户问题\"}"));
            var original = new ExecutionStore.Material(AgentExecutionCodec.reference(payload), payload);
            // 与正式Program.initialContext相同的封套，保证恢复走真实不可变ref链。
            var contextPayload = new TypedPayload(AgentExecutionProgram.CONTEXT_SCHEMA,
                    DesktopJson.write(DesktopJson.object().put("type", "base").put("input", original.reference().value())));
            var context = new ExecutionStore.Material(AgentExecutionCodec.reference(contextPayload), contextPayload);
            ExecutionBudgetPort.Policy budget = new ExecutionBudgetPort.Policy.ObserveOnly(ExecutionBudgetPort.Scenario.STANDARD, frozen.reference().value());
            if (oldSchema.isPresent()) {
                var resources = new ExecutionBudgetPort.Resources(20, 0, 40, 0, 1_000_000, 1_000_000, 1_000_000);
                budget = new ExecutionBudgetPort.Policy.Enforced(new ExecutionBudgetPort.Limits(ExecutionBudgetPort.Scenario.STANDARD,
                        resources, resources, resources, 1800, 1800, "desktop-policy.v1"));
            }
            store.admit(new ExecutionStore.Admission(actor, identity, "old-fixture", frozen.reference(), original, context,
                    new ExecutionBudgetPort.Registration(runRef, runRef, ExecutionBudgetPort.Role.PRIMARY, budget),
                    List.of(AgentExecutionProgram.admissionEvent(identity)), List.of(frozen)));
            if (!finished) return runRef;
            var claimed = store.claim(new ExecutionStore.Claim(runRef, "fixture-worker", "fixture-claim", Duration.ofMinutes(1))).orElseThrow();
            var completed = DesktopJson.object().put("text", "历史助手回答").put("reason", "fixture_completed").put("status", "COMPLETED");
            completed.putNull("resultRef").put("inputId", inputId.value()).put("rootInputId", inputId.value())
                    .put("traceId", "fixture-trace").put("spanId", "fixture-span").put("agentId", "desktop-agent");
            completed.putArray("evidence"); completed.putNull("taskResultRef");
            store.finish(new ExecutionStore.Finish(runRef, claimed.revision(), claimed.lease().orElseThrow(), RunStatus.COMPLETED,
                    List.of(new ExecutionStore.Event(AgentExecutionProgram.finishedEventId(runRef), AgentExecutionProgram.COMPLETED_EVENT,
                            new TypedPayload(AgentExecutionProgram.COMPLETED_SCHEMA, DesktopJson.write(completed))))));
        }
        return runRef;
    }

    private static void assertHostSystem(JsonNode request, String expected) {
        int hosts = 0; int times = 0;
        for (var message : request.path("messages")) {
            var content = message.path("content").asText();
            if (expected.equals(content)) {
                assertEquals("system",message.path("role").asText()); hosts++;
            }
            else if (content.startsWith("本次当前可信时间（UTC）：")) {
                assertEquals("user",message.path("role").asText());
                // HTTP 不携带内部消息身份；只按完整时间正文识别USER锚点，不过滤其他USER。
                var sample=AgentExecutionTime.frozen(List.of(new ModelMessage("time-context:1",ModelRole.USER,content)),1);
                assertTrue(content.startsWith("本次当前可信时间（UTC）："+sample.sampledAt()));
                assertFalse(content.contains("冻结格式"));
                assertTrue(content.contains("先按已知业务时区"));
                assertTrue(content.contains("来源为宿主时钟"));times++;
            }
        }
        assertEquals(1, hosts); assertEquals(1, times);
        assertTrue(expected.contains("能够回答时直接输出正文"));
        assertTrue(expected.contains("普通问候")); assertFalse(expected.contains("final_answer"));
        assertTrue(expected.contains("每轮模型输出上限为 " + request.path("max_tokens").asInt() + " tokens"));
        assertTrue(expected.contains("每次工具调用提交完整合法的参数"));
        assertFalse(expected.contains("唯一锚点"));
        assertFalse(expected.contains("创建网站时"));
    }

    private DesktopRuntime host(Path data, ControlledProvider provider, BlockingQueue<ObjectNode> notifications) throws IOException {
        return host(data, provider, notifications, DesktopPolicy.defaults());
    }

    private DesktopRuntime host(Path data, ControlledProvider provider, BlockingQueue<ObjectNode> notifications, DesktopPolicy policy) throws IOException {
        var runtime = new DesktopRuntime(data, Clock.systemUTC(), policy, notifications::add);
        try {
            runtime.configureModel("custom", provider.url(), "controlled-model", API_KEY, OptionalInt.empty()); runtime.start(); return runtime;
        } catch (RuntimeException failure) { runtime.close(); throw failure; }
    }

    private static void await(DesktopRuntime host, BlockingQueue<ObjectNode> events, String runId, String phase) throws InterruptedException {
        await(host, events, runId, phase, null);
    }

    private static void await(DesktopRuntime host, BlockingQueue<ObjectNode> events, String runId, String phase, ControlledProvider provider) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (provider != null) provider.assertHealthy();
            var event = events.poll(Math.max(1, Math.min(TimeUnit.MILLISECONDS.toNanos(200), deadline - System.nanoTime())), TimeUnit.NANOSECONDS);
            if (event == null) continue;
            if (event.path("event").asText().equals("hostError")) throw new AssertionError(event);
            if (!event.path("event").asText().equals("state")) continue;
            assertFalse(event.has("data"), "State is an invalidation notice; SQLite supplies the projection");
            for (var run : host.getSession(event.required("sessionId").asText()).path("runs")) if (run.path("id").asText().equals(runId) && run.path("phase").asText().equals(phase)) return;
        }
        if (provider != null) provider.assertHealthy();
        throw new AssertionError("Run did not reach " + phase + ": " + runId);
    }

    private static void awaitAll(DesktopRuntime host, BlockingQueue<ObjectNode> events, Set<String> expected) throws InterruptedException {
        var remaining = new java.util.HashSet<>(expected); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!remaining.isEmpty() && System.nanoTime() < deadline) {
            var event = events.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (event == null) break;
            if (event.path("event").asText().equals("hostError")) throw new AssertionError(event);
            if (!event.path("event").asText().equals("state")) continue;
            assertFalse(event.has("data"), "State is an invalidation notice; SQLite supplies the projection");
            for (var run : host.getSession(event.required("sessionId").asText()).path("runs")) if (run.path("phase").asText().equals("FINISHED")) remaining.remove(run.path("id").asText());
        }
        assertTrue(remaining.isEmpty(), "Unfinished actual runs: " + remaining);
    }

    private static JsonNode tool(JsonNode request, String logicalName, String arguments) {
        var matches = new ArrayList<String>();
        for (var tool : request.path("tools")) {
            var function = tool.path("function");
            var metadata = function.path("description").asText().lines().filter(line -> line.startsWith("title=")).toList();
            if (metadata.isEmpty()) continue;
            assertEquals(1, metadata.size());
            var declared = DesktopJson.parse(metadata.getFirst().substring("title=".length()));
            assertTrue(declared.isTextual());
            if (logicalName.equals(declared.textValue())) matches.add(function.path("name").asText());
        }
        assertEquals(1, matches.size(), "Actual model request must declare one logical tool: " + logicalName);
        return response(matches.getFirst(), arguments);
    }

    private static JsonNode assistantText(String text) {
        var result = DesktopJson.object().put("id", "controlled-response:" + UUID.randomUUID()).put("model", "controlled-model");
        result.putArray("choices").addObject().put("index", 0).put("finish_reason", "stop")
                .putObject("message").put("role", "assistant").put("content", text);
        result.putObject("usage").put("prompt_tokens", 100).put("completion_tokens", 20).put("total_tokens", 120);
        return result;
    }

    private static JsonNode response(String name, String arguments) {
        var result = DesktopJson.object().put("id", "controlled-response:" + UUID.randomUUID()).put("model", "controlled-model");
        var choice = result.putArray("choices").addObject().put("index", 0).put("finish_reason", "tool_calls");
        var message = choice.putObject("message").put("role", "assistant"); message.putNull("content");
        message.putArray("tool_calls").addObject().put("id", "controlled-call:" + UUID.randomUUID()).put("type", "function")
                .putObject("function").put("name", name).put("arguments", arguments);
        result.putObject("usage").put("prompt_tokens", 100).put("completion_tokens", 20).put("total_tokens", 120);
        return result;
    }

    private static void assertNoCredentialOnDisk(Path root) throws IOException {
        try (var files = Files.walk(root)) {
            for (var file : files.filter(Files::isRegularFile).toList()) assertFalse(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).contains(API_KEY), file.toString());
        }
    }

    private static final class ControlledStreamProvider implements AutoCloseable {
        private final HttpServer server;
        private final java.util.concurrent.ExecutorService workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        ControlledStreamProvider() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(workers);
            server.createContext("/chat/completions", exchange -> {
                try {
                    requests.incrementAndGet();
                    assertEquals("Bearer " + API_KEY, exchange.getRequestHeaders().getFirst("Authorization"));
                    var request = DesktopJson.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    assertTrue(request.path("stream").asBoolean(), "Formal dispatch must use ModelPort.stream");
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream"); exchange.sendResponseHeaders(200, 0);
                    var output = exchange.getResponseBody();
                    output.write(frame("公开", false).getBytes(StandardCharsets.UTF_8)); output.flush();
                    release.await(10, TimeUnit.SECONDS);
                    output.write(frame("正文", true).getBytes(StandardCharsets.UTF_8));
                    output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8)); output.flush();
                } catch (IOException cancelled) { /* 取消测试主动关闭原连接；不伪造完成帧。 */ }
                catch (Throwable problem) { failure.compareAndSet(null, problem); }
                finally { exchange.close(); }
            }); server.start();
        }
        private static String frame(String text, boolean finished) {
            var frame = DesktopJson.object().put("id", "controlled-stream").put("object", "chat.completion.chunk")
                    .put("created", 1).put("model", "controlled-model");
            var choice = frame.putArray("choices").addObject().put("index", 0);
            choice.putObject("delta").put("role", "assistant").put("content", text);
            if (finished) {
                choice.put("finish_reason", "stop");
                frame.putObject("usage").put("prompt_tokens", 10).put("completion_tokens", 2).put("total_tokens", 12);
            } else choice.putNull("finish_reason");
            return "data: " + DesktopJson.write(frame) + "\n\n";
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        void assertHealthy() { if (failure.get() != null) throw new AssertionError("Controlled SSE failed", failure.get()); }
        public void close() {
            release.countDown(); server.stop(0); workers.shutdownNow();
            try { assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
        }
    }

    private static final class ControlledProvider implements AutoCloseable {
        private final HttpServer server;
        private final ThreadPoolExecutor executor;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicReference<JsonNode> lastRequest = new AtomicReference<>();
        private final AtomicInteger requests = new AtomicInteger();
        ControlledProvider(Function<JsonNode, JsonNode> reply) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            executor = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8),
                    Thread.ofPlatform().name("controlled-provider-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
            server.setExecutor(executor);
            server.createContext("/chat/completions", exchange -> {
                try {
                    assertEquals("Bearer " + API_KEY, exchange.getRequestHeaders().getFirst("Authorization"));
                    var request = DesktopJson.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    lastRequest.set(request); requests.incrementAndGet();
                    if (request.path("tools").isEmpty()) assertFalse(request.has("tool_choice"));
                    else assertEquals("auto", request.path("tool_choice").asText());
                    for (var tool : request.path("tools")) assertFalse(tool.path("function").path("name").asText().equals("final_answer"));
                    var bytes = DesktopJson.write(reply.apply(request)).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (Throwable problem) { failure.compareAndSet(null, problem); exchange.sendResponseHeaders(500, -1); }
                finally { exchange.close(); }
            });
            server.start();
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        String diagnostics() {
            var value = DesktopJson.object().put("requests", requests.get()); var request = lastRequest.get();
            if (request == null) return DesktopJson.write(value);
            value.put("model", request.path("model").asText()).put("maxTokens", request.path("max_tokens").asInt());
            var messages = request.path("messages"); value.put("messageCount", messages.size()); var recent = value.putArray("recentMessages");
            for (int index = Math.max(0, messages.size() - 4); index < messages.size(); index++) {
                var message = messages.get(index); var content = message.path("content").asText();
                var entry = recent.addObject().put("role", message.path("role").asText()).put("contentCharacters", content.length());
                if (message.path("role").asText().equals("tool")) entry.put("content", content.substring(0, Math.min(512, content.length())));
                var calls = entry.putArray("toolCalls");
                for (var call : message.path("tool_calls")) calls.addObject().put("name", call.path("function").path("name").asText())
                        .put("argumentsCharacters", call.path("function").path("arguments").asText().length());
            }
            return DesktopJson.write(value);
        }
        void assertHealthy() { if (failure.get() != null) throw new AssertionError("Controlled provider failed", failure.get()); }
        @Override public void close() {
            server.stop(0); executor.shutdownNow();
            try { assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
        }
    }
}
