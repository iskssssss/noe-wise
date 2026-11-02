package com.skillforge.runtime.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.runtime.adapter.model.JTokkitSessionTokenCounter;
import com.skillforge.runtime.adapter.sqlite.SqliteDesktopSessions;
import com.skillforge.runtime.adapter.sqlite.SqliteExecutionStore;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.CapabilityRef;
import com.skillforge.runtime.api.CapabilityType;
import com.skillforge.runtime.api.ToolEffect;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
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
import com.skillforge.runtime.api.port.ModelPort;
import com.skillforge.runtime.api.port.ModelPort.ModelMessage;
import com.skillforge.runtime.api.port.ModelPort.ModelRole;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.ToolPortal;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.core.CoreRuntime;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.AgentExecutionModelMedia;
import com.skillforge.runtime.session.AgentExecutionProgram;
import com.skillforge.runtime.session.AgentExecutionTime;
import com.skillforge.runtime.session.ExecutionCoordinator;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.spi.ExecutionStore.Material;
import com.skillforge.runtime.session.spi.ExecutionStore.Run;
import com.skillforge.runtime.session.spi.SessionTokenCounter;
import com.skillforge.runtime.session.spi.SessionToolDirectory;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** 单机装配根：执行状态完全由 SQLite ExecutionStore 拥有，内存只持活动槽和取消信号。 */
public final class DesktopRuntime implements AutoCloseable {
    static final TenantId TENANT = new TenantId("desktop-local");
    private static final EndUserId USER = new EndUserId(1L);
    private static final AgentId AGENT = new AgentId("desktop-agent");
    static final CodecLimits CODEC_LIMITS = new CodecLimits(8_000_000, 8_000_000, 128);
    private static final int QUERY_PAGE_SIZE = 100;
    private static final Duration STATE_REFRESH = Duration.ofMillis(200);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration FORCE_SHUTDOWN_RESERVE = Duration.ofSeconds(5);
    private final Clock clock;
    private final DesktopPolicy policy;
    private int configuredOutputTokens;
    private final Consumer<ObjectNode> events;
    private final SqliteExecutionStore store;
    private final SqliteDesktopSessions sessions;
    private final DesktopModels models;
    private final AgentExecutionCodec codec = new AgentExecutionCodec(CODEC_LIMITS);
    private final AgentExecutionProgram program;
    private final ExecutionCoordinator<AgentExecutionProgram.Materials> coordinator;
    private final CoreRuntime core;
    private final ConcurrentHashMap<RunRef, Flight> active = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DesktopTools> tools = new ConcurrentHashMap<>();
    private final Map<RunRef, Long> published = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ResultRef, SessionTokenCounter.Profile> tokenizerProfiles = new ConcurrentHashMap<>();
    private final JTokkitSessionTokenCounter tokenCounter = new JTokkitSessionTokenCounter(reference -> Optional.ofNullable(tokenizerProfiles.get(reference)));
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService observer;
    private final AtomicBoolean closing = new AtomicBoolean();
    private final FileChannel lockChannel;
    private final FileLock dataLock;
    private final String workerId = "desktop-host:" + UUID.randomUUID();
    private final Path receiptsDirectory;

    public DesktopRuntime(Path dataDirectory, Clock clock, DesktopPolicy policy, Consumer<ObjectNode> events) throws IOException {
        this.clock = clock; this.policy = policy; this.configuredOutputTokens = policy.outputTokens(); this.events = events;
        Files.createDirectories(dataDirectory);
        receiptsDirectory = dataDirectory.resolve("tool-receipts"); Files.createDirectories(receiptsDirectory);
        lockChannel = FileChannel.open(dataDirectory.resolve("host.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        dataLock = lockChannel.tryLock();
        if (dataLock == null) { lockChannel.close(); throw new IllegalStateException("DESKTOP_DATA_IN_USE"); }
        store = new SqliteExecutionStore(dataDirectory.resolve("runtime.db"), clock);
        sessions = new SqliteDesktopSessions(dataDirectory.resolve("sessions.db"), clock);
        models = new DesktopModels(clock, CODEC_LIMITS);
        var payloads = new com.skillforge.runtime.session.SessionRuntimePayloads();
        core = new CoreRuntime(CODEC_LIMITS, payloads);
        program = new AgentExecutionProgram(new Sources(), core, CODEC_LIMITS);
        coordinator = new ExecutionCoordinator<>(store, program, new DesktopCapabilities(this, store, models, codec, core),
                this::control, new ExecutionCoordinator.LocalPlans() {
                    @Override public <T> ExecutionCoordinator.LocalPlanResult<T> execute(Run run, ExecutionCoordinator.Step.PlanLocal command,
                            java.util.function.BiFunction<Run, Material, ExecutionCoordinator.Adoption<T>> adopter) {
                        throw new IllegalStateException("desktop_plan_not_available");
                    }
                });
        workers = new ThreadPoolExecutor(policy.maxActiveTasks(), policy.maxActiveTasks(), 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(policy.maxActiveTasks()), Thread.ofPlatform().name("desktop-run-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
        observer = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().daemon().name("desktop-state").factory(), new ThreadPoolExecutor.AbortPolicy());
    }

    /** 显式启动本地观察，不在构造器发起模型或工具调用。 */
    public void start() {
        observer.scheduleWithFixedDelay(this::observe, 0, STATE_REFRESH.toMillis(), TimeUnit.MILLISECONDS);
    }

    public synchronized ObjectNode configureModel(String providerId, String baseUrl, String model, String apiKey, OptionalInt outputTokens) {
        var selectedPolicy = policy.withOutputTokens(outputTokens.orElse(policy.outputTokens()));
        // 先校验预算，不允许无效配置更改模型或凭据槽。
        var settings = models.configure(providerId, baseUrl, model, apiKey);
        configuredOutputTokens = selectedPolicy.outputTokens();
        schedule();
        return DesktopJson.object().put("configured", true).put("outputTokens", configuredOutputTokens).set("model", settings.json());
    }

    public ObjectNode testModel() { return models.test(); }

    public JsonNode listSessions() {
        var values = DesktopJson.JSON.createArrayNode(); sessions.list().forEach(session -> values.add(sessionJson(session))); return values;
    }

    public synchronized ObjectNode createSession(String id, String title, String workspace) throws IOException {
        if (closing.get()) throw new DesktopRequestException(DesktopRequestException.Code.DESKTOP_SHUTTING_DOWN);
        var directory = Path.of(workspace).toRealPath();
        if (!Files.isDirectory(directory)) throw new IllegalArgumentException("WORKSPACE_NOT_DIRECTORY");
        return sessionJson(sessions.create(id, title, directory.toString()));
    }

    public synchronized ObjectNode submitTask(String sessionId, String text, String inputId) {
        if (closing.get()) throw new DesktopRequestException(DesktopRequestException.Code.DESKTOP_SHUTTING_DOWN);
        var session = requireSession(sessionId);
        var runRef = new RunRef(TENANT, "desktop-run:" + AgentExecutionCodec.digest(sessionId + "\0" + inputId));
        var existing = store.load(runRef);
        if (existing.isPresent()) {
            var original = codec.frozenInput(store.originalInput(runRef).orElseThrow().content());
            if (!DesktopJson.parse(original.canonicalJson()).path("text").asText().equals(text)) throw new ExecutionStore.Rejection("execution_input_conflict");
            return DesktopJson.object().put("runId", runRef.runId()).put("created", false);
        }
        var model = models.current();
        var modelPolicy = policy.withOutputTokens(configuredOutputTokens);
        long queued = store.queuedTaskCount(TENANT, sessions.list().stream().map(SqliteDesktopSessions.Session::id).toList());
        if (queued >= policy.maxQueuedTasks()) throw new DesktopRequestException(DesktopRequestException.Code.DESKTOP_QUEUE_FULL);
        var prior = store.latestRun(new SessionRef(TENANT, sessionId));
        prior.ifPresent(this::requireSupported);
        var predecessor = prior.map(run -> run.identity().runRef().runId());
        var localTools = tools(session);
        var prompt = DesktopPrompts.prepare(modelPolicy);
        var frozen = DesktopJson.material(DesktopConfiguration.SCHEMA,
                DesktopConfiguration.freeze(model, modelPolicy, session.workspace(), predecessor, localTools.definitions(), prompt));
        var owner = owner(sessionId); var identity = actor(owner); var input = new InputId(inputId);
        var runIdentity = new com.skillforge.runtime.api.port.RunIdentity(owner, AGENT, input, runRef,
                new TraceEnvelope("desktop-trace:" + UUID.randomUUID(), "desktop-span:" + UUID.randomUUID(), input, Optional.empty()),
                identity.verificationRef(), new RunRequester.EndUser(USER), Optional.empty(), new RunOriginRef(TENANT, "desktop:" + sessionId));
        var business = new TypedPayload("noewise.desktop.task.v1", DesktopJson.write(DesktopJson.object().put("text", text)));
        var inputPayload = codec.encodeInput(List.of(new ModelMessage("desktop-system", ModelRole.SYSTEM, prompt.body()),
                new ModelMessage("input:" + inputId, ModelRole.USER, text)), business);
        var original = new Material(AgentExecutionCodec.reference(inputPayload), inputPayload);
        var context = program.initialContext(original.reference());
        var budget = new ExecutionBudgetPort.Policy.ObserveOnly(ExecutionBudgetPort.Scenario.STANDARD, frozen.reference().value());
        var registered = new ExecutionBudgetPort.Registration(runRef, runRef, ExecutionBudgetPort.Role.PRIMARY, budget);
        var result = store.admit(new ExecutionStore.Admission(identity, runIdentity,
                AgentExecutionCodec.digest(sessionId + "\0" + text), frozen.reference(), original, context, registered,
                List.of(AgentExecutionProgram.admissionEvent(runIdentity)), List.of(frozen)));
        publish(sessionId); schedule();
        return DesktopJson.object().put("runId", runRef.runId()).put("created", result.created());
    }

    public synchronized ObjectNode cancelTask(String runId, String inputId) {
        var run = requireRun(runId); var flight = active.get(run.identity().runRef());
        if (flight != null) { flight.cancelled.set(true); cancelTools(flight); }
        var value = DesktopJson.object().put("reason", "user_cancelled");
        var event = new ExecutionStore.Event(new EventId("desktop-cancel:" + AgentExecutionCodec.digest(runId + inputId)),
                "desktop.cancelled", new TypedPayload("noewise.desktop.cancelled.v1", DesktopJson.write(value)));
        var saved = store.cancel(new ExecutionStore.Cancel(actor(run.identity().owner()), run.identity().runRef(), new InputId(inputId), List.of(event)));
        publish(run.identity().owner().sessionRef().sessionId()); schedule();
        return DesktopJson.object().put("runId", runId).put("status", saved.status().name());
    }

    public synchronized ObjectNode confirmTool(String runId, String waitingId, boolean approved, String inputId) {
        var run = requireRun(runId); requireSupported(run); var waiting = requireWaiting(run, waitingId);
        if (waiting.kind() != ExecutionStore.WaitKind.TOOL || !waiting.requested().keySet().equals(Set.of(DesktopCapabilities.COMMAND_CONFIRMATION)))
            throw new IllegalArgumentException("DESKTOP_WAIT_UNSUPPORTED");
        var response = new com.skillforge.runtime.api.port.ToolPortal.ToolInputResponse(
                com.skillforge.runtime.api.port.ToolPortal.ToolInputMethod.ELICITATION_CREATE,
                new TypedPayload("application/json", DesktopJson.write(DesktopJson.object().put("approved", approved))));
        var payload = codec.encodeToolResponses(Map.of(DesktopCapabilities.COMMAND_CONFIRMATION, response));
        var material = new Material(AgentExecutionCodec.reference(payload), payload);
        var accepted = store.resume(new ExecutionStore.Resume(actor(run.identity().owner()), run.identity().runRef(), waiting.reference(),
                run.revision(), new InputId(inputId), material, AgentExecutionCodec.digest(payload.canonicalJson()), waiting.requested(),
                List.of(AgentExecutionProgram.resolved(run, new InputId(inputId), waiting.reference()))));
        publish(run.identity().owner().sessionRef().sessionId()); schedule();
        return DesktopJson.object().put("runId", runId).put("accepted", accepted.accepted());
    }

    public ObjectNode getSession(String sessionId) {
        var session = requireSession(sessionId); var result = DesktopJson.object(); result.set("session", sessionJson(session));
        var messages = result.putArray("messages"); var runs = result.putArray("runs"); var confirmations = result.putArray("pendingConfirmations");
        var snapshot = store.desktopProjection(new SessionRef(TENANT, sessionId), DesktopProjection.RECENT_RUNS,
                DesktopProjection.RECENT_MESSAGES, DesktopProjection.RECENT_PROCESS_EVENTS, DesktopProjection.RECENT_TOOL_EVENTS,
                AgentExecutionProgram::finishedEventId);
        for (var candidate : snapshot.messageCandidates()) {
            var run = candidate.run(); var ref = run.identity().runRef();
            var input = codec.frozenInput(candidate.input().content());
            messages.addObject().put("role", "user").put("text", DesktopJson.parse(input.canonicalJson()).path("text").asText()).put("runId", ref.runId());
            candidate.finished().ifPresent(value -> {
                var body = DesktopJson.parse(value.event().data().canonicalJson());
                if (!body.path("text").asText().isBlank()) messages.addObject().put("role", "assistant").put("text", body.path("text").asText()).put("runId", ref.runId());
            });
        }
        for (var run : snapshot.runs()) {
            var ref = run.identity().runRef();
            boolean supported = DesktopConfiguration.supports(snapshot.materials().get(run.configuration()));
            var projectedRun = runs.addObject().put("id", ref.runId()).put("status", run.status().name()).put("phase", run.phase().name())
                    .put("active", active.containsKey(ref)).put("createdAt", run.createdAt().toString()).put("updatedAt", run.updatedAt().toString());
            if (!supported) projectedRun.put("recoveryBlocked", true)
                    .put("recoveryReason", DesktopRequestException.Code.DESKTOP_RUN_FORMAT_UNSUPPORTED.name());
        }
        // 等待确认独立于展示窗口：旧Run未出现在最近100条仍保留原操作入口。
        for (var candidate : snapshot.waiting()) {
            var run = candidate.run(); var wait = candidate.waiting(); var ref = run.identity().runRef();
            var configuration = snapshot.materials().get(run.configuration());
            if (!DesktopConfiguration.supports(configuration) || wait.acceptedResponse().isPresent() || wait.kind() != ExecutionStore.WaitKind.TOOL) continue;
            var request = codec.decodeTool(snapshot.materials().get(candidate.attempt().request()).content());
            var frozen = DesktopJson.parse(configuration.content().canonicalJson());
            if (DesktopConfiguration.toolName(frozen, request).equals("run_command")) confirmations.addObject().put("runId", ref.runId())
                    .put("waitingId", waitingId(wait.reference())).put("command", DesktopJson.parse(request.arguments().canonicalJson()).path("command").asText())
                    .put("workspace", session.workspace()).put("actionId", candidate.action().reference().actionId());
        }
        var projection = result.putArray("events");
        for (var value : snapshot.events()) {
            var data = DesktopJson.parse(value.event().data().canonicalJson());
            var entry = projection.addObject().put("sequence", value.sequence()).put("type", value.event().type()).put("runId", value.run().runId())
                    .put("text", data.path("summary").asText(data.path("reason").asText("")));
            entry.set("data", data);
        }
        for (var candidate : snapshot.tools()) {
            var run = candidate.run(); var action = candidate.action(); var attempt = candidate.attempt();
            var request = codec.decodeTool(snapshot.materials().get(attempt.request()).content());
            var entry = projection.addObject().put("sequence", action.ordinal()).put("type", "tool").put("runId", run.identity().runRef().runId())
                    .put("text", action.status().name()).put("toolName", DesktopConfiguration.projectedToolName(DesktopJson.parse(snapshot.materials().get(run.configuration()).content().canonicalJson()), request))
                    .put("attemptId", attempt.id()).put("requestRef", attempt.request().value());
            attempt.result().ifPresent(reference -> entry.put("resultRef", reference.value()));
            entry.set("arguments", DesktopJson.parse(request.arguments().canonicalJson()));
            var receipt = attempt.result().isPresent() ? toolReceipt(attempt) : Optional.<JsonNode>empty();
            if (receipt.isPresent()) {
                entry.put("text", receipt.orElseThrow().path("status").asText()); entry.set("result", receipt.orElseThrow().required("result"));
            }
            else if (attempt.feedback().isPresent()) {
                var feedback = DesktopJson.parse(snapshot.materials().get(attempt.feedback().orElseThrow()).content().canonicalJson());
                entry.set("result", feedback.has("structuredContent") ? feedback.get("structuredContent") : feedback);
            }
        }
        return DesktopProjection.bound(result, snapshot.totalMessages(), snapshot.totalRuns(), snapshot.totalEvents());
    }

    private synchronized void schedule() {
        if (closing.get()) return;
        for (var session : sessions.list()) {
            if (active.size() >= policy.maxActiveTasks()) return;
            if (active.values().stream().anyMatch(flight -> flight.sessionId.equals(session.id()))) continue;
            var candidate = store.firstUnfinishedRun(new SessionRef(TENANT, session.id()));
            if (candidate.isEmpty()) continue;
            var run = candidate.orElseThrow(); var ref = run.identity().runRef();
            if (!supports(run)) continue;
            if (active.containsKey(ref) || run.phase() == ExecutionStore.Phase.WAITING || run.phase() == ExecutionStore.Phase.UNCERTAIN
                    || run.lease().isPresent() && clock.instant().isBefore(run.lease().orElseThrow().leaseUntil())) continue;
            var settings = DesktopModels.Settings.read(frozen(run).required("model"));
            if (run.phase() == ExecutionStore.Phase.READY && !models.available(settings)) continue;
            var flight = new Flight(session.id()); active.put(ref, flight);
            workers.execute(() -> drive(ref, flight));
        }
    }

    private void drive(RunRef ref, Flight flight) {
        try {
            var run = store.load(ref).orElseThrow(); var frozen = frozen(run); var limits = DesktopConfiguration.policy(frozen);
            var outcome = coordinator.drive(new ExecutionStore.Claim(ref, workerId, UUID.randomUUID().toString(), limits.leaseDuration()));
            if (outcome.stop() == ExecutionCoordinator.Stop.YIELDED || outcome.stop() == ExecutionCoordinator.Stop.REJECTED) {
                events.accept(DesktopJson.object().put("event", "hostError").put("runId", ref.runId())
                        .set("data", DesktopJson.object().put("code", "EXECUTION_STOPPED").put("reason", outcome.reason())
                                .put("message", "执行因未满足的约束而停止，请检查任务状态；未自动重试。")));
            }
        } catch (RuntimeException failure) {
            // 真实调用异常可能已派发；保留原登记，由下次领取按UNKNOWN规则静止，不自行重发。
            var current = store.load(ref).orElseThrow();
            if (current.phase() == ExecutionStore.Phase.AWAITING_RESULT && current.lease().isPresent()) {
                if (current.currentBatch().isPresent()) store.quiesceBatch(new ExecutionStore.BatchQuiesce(current.currentBatch().orElseThrow(),
                        current.revision(), current.lease().orElseThrow(), List.of()));
                else if (current.currentAction().isPresent()) {
                    var action = store.action(current.currentAction().orElseThrow()).orElseThrow();
                    var attempt = store.attempt(ref, action.currentAttempt()).orElseThrow();
                    if (attempt.result().isEmpty()) store.quiesceUnknown(new ExecutionStore.QuiesceUnknown(ref, attempt.id(),
                            current.revision(), current.lease().orElseThrow(), List.of()));
                }
            }
            String reason = failure instanceof ExecutionStore.Rejection rejected ? rejected.reason()
                    : failure instanceof DesktopRequestException rejected ? rejected.code().name() : "execution_interrupted";
            System.err.println("桌面执行中断 type=" + failure.getClass().getSimpleName() + " reason=" + reason);
            events.accept(DesktopJson.object().put("event", "hostError").put("runId", ref.runId())
                    .set("data", DesktopJson.object().put("code", "EXECUTION_INTERRUPTED").put("reason", reason)
                            .put("message", "执行已中断，请检查任务状态；未确认的操作不会自动重发。")));
        } finally {
            active.remove(ref, flight); published.remove(ref); publish(store.load(ref).orElseThrow().identity().owner().sessionRef().sessionId()); schedule();
        }
    }

    private ExecutionCoordinator.Control control(Run run) {
        var flight = active.get(run.identity().runRef());
        if (flight == null) throw new IllegalStateException("desktop_execution_slot_missing");
        flight.startedNanos = System.nanoTime();
        flight.startedAt = clock.instant(); flight.intervalId = UUID.randomUUID().toString();
        flight.priorActiveMillis = store.budgets().snapshot(run.identity().runRef()).runActiveMillis();
        flight.lastConfirmedAt = flight.startedAt;
        // lease是observer使用该活动帧的发布屏障，所有计时字段必须先完成初始化。
        flight.lease = run.lease().orElseThrow();
        return new ExecutionCoordinator.Control() {
            public boolean cancelled() { return flight.cancelled.get(); }
            public boolean ownershipLost() { return flight.ownershipLost.get(); }
            public void close() {
                try {
                    // 任务终态可能早于驱动资源收敛；此通知完成前仍持有原会话活动槽。
                    events.accept(DesktopJson.object().put("event", "runStopping").put("runId", run.identity().runRef().runId())
                            .put("sessionId", run.identity().owner().sessionRef().sessionId()));
                } finally {
                    store.budgets().recordActivity(run.identity().runRef(), flight.intervalId, flight.startedAt, clock.instant());
                    flight.lease = null;
                }
            }
        };
    }

    private void observe() {
        if (closing.get()) return;
        try {
            for (var entry : active.entrySet()) {
                var flight = entry.getValue(); var lease = flight.lease;
                if (lease != null && Duration.between(flight.lastConfirmedAt, clock.instant()).compareTo(Duration.ofSeconds(5)) >= 0) {
                    store.budgets().recordActivity(entry.getKey(), flight.intervalId, flight.startedAt, clock.instant());
                    flight.lastConfirmedAt = clock.instant();
                }
                if (lease != null && clock.instant().plusSeconds(45).isAfter(lease.leaseUntil())) {
                    try { flight.lease = store.renew(entry.getKey(), lease, policy.leaseDuration()).lease().orElseThrow(); }
                    catch (ExecutionStore.Rejection rejected) { flight.ownershipLost.set(true); }
                }
            }
            for (var entry : active.entrySet()) {
                var run = store.load(entry.getKey()).orElseThrow();
                if (!Long.valueOf(run.revision()).equals(published.put(entry.getKey(), run.revision()))) publish(entry.getValue().sessionId);
            }
            schedule();
        } catch (RuntimeException failure) {
            events.accept(DesktopJson.object().put("event", "hostError")
                    .set("data", DesktopJson.object().put("code", "STATE_READ_FAILED").put("message", "无法读取本地任务状态。")));
        }
    }

    private void publish(String sessionId) {
        events.accept(DesktopJson.object().put("event", "state").put("sessionId", sessionId));
    }

    void modelPreview(Run run, String attemptId, String delta) {
        if (!cancelled(run)) events.accept(DesktopJson.object().put("event", "modelPreview")
                .put("sessionId", run.identity().owner().sessionRef().sessionId()).put("runId", run.identity().runRef().runId())
                .put("attemptId", attemptId).put("delta", delta));
    }

    private boolean supports(Run run) { return DesktopConfiguration.supports(material(run, run.configuration())); }
    private void requireSupported(Run run) {
        if (!supports(run)) throw new DesktopRequestException(DesktopRequestException.Code.DESKTOP_RUN_FORMAT_UNSUPPORTED);
    }
    private JsonNode savedConfiguration(Run run) { return DesktopJson.parse(material(run, run.configuration()).content().canonicalJson()); }
    JsonNode frozen(Run run) { requireSupported(run); return savedConfiguration(run); }
    Instant instant() { return clock.instant(); }
    void confirmActivity(Run run) {
        var flight = active.get(run.identity().runRef());
        if (flight != null && flight.intervalId != null) {
            store.budgets().recordActivity(run.identity().runRef(), flight.intervalId, flight.startedAt, clock.instant());
            flight.lastConfirmedAt = clock.instant();
        }
    }
    Material material(Run run, ResultRef reference) { return store.material(TENANT, reference).orElseThrow(() -> new IllegalStateException("desktop_material_missing")); }
    boolean cancelled(Run run) { var flight = active.get(run.identity().runRef()); return closing.get() || flight == null || flight.cancelled.get() || flight.ownershipLost.get(); }
    void toolStarted(Run run, String operation) {
        var flight = active.get(run.identity().runRef());
        if (flight == null) throw new IllegalStateException("desktop_execution_slot_missing");
        flight.toolOperations.add(operation);
    }
    void toolStopped(Run run, String operation) {
        var flight = active.get(run.identity().runRef()); if (flight != null) flight.toolOperations.remove(operation);
    }
    private void cancelTools(Flight flight) {
        var instance = tools.get(flight.sessionId);
        if (instance != null) flight.toolOperations.forEach(instance::cancel);
    }
    DesktopTools tools(Run run) { return tools(requireSession(run.identity().owner().sessionRef().sessionId())); }
    private DesktopTools tools(SqliteDesktopSessions.Session session) {
        return tools.computeIfAbsent(session.id(), ignored -> {
            try { return new DesktopTools(Path.of(session.workspace())); }
            catch (IOException failure) { throw new DesktopRequestException(DesktopRequestException.Code.WORKSPACE_UNAVAILABLE, failure); }
        });
    }

    /** 原工具输出是独立不可变文件材料；失败/UNKNOWN 的 PortFailure 只引用它，不冒充成功 observation。 */
    ResultRef saveToolReceipt(ExecutionStore.DispatchResult.Registered registered, DesktopTools.Result result) {
        var receipt = DesktopJson.object().put("runId", registered.run().identity().runRef().runId())
                .put("attemptId", registered.attempt().id()).put("status", result.status().name());
        receipt.set("result", DesktopJson.parse(result.contentJson()));
        var id = AgentExecutionCodec.digest(registered.attempt().id()); var file = receiptsDirectory.resolve(id + ".json");
        try {
            Files.writeString(file, DesktopJson.write(receipt), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException failure) { throw new IllegalStateException("desktop_tool_receipt_write_failed", failure); }
        return new ResultRef("desktop-tool-receipt:" + id);
    }

    private Optional<JsonNode> toolReceipt(ExecutionStore.Attempt attempt) {
        var path = receiptsDirectory.resolve(AgentExecutionCodec.digest(attempt.id()) + ".json");
        if (!Files.exists(path)) return Optional.empty();
        try {
            var value = DesktopJson.parse(Files.readString(path));
            if (!value.path("attemptId").asText().equals(attempt.id()) || !value.path("runId").asText().equals(attempt.run().runId()))
                throw new IllegalStateException("desktop_tool_receipt_mismatch");
            return Optional.of(value);
        } catch (IOException failure) { throw new IllegalStateException("desktop_tool_receipt_read_failed", failure); }
    }
    private SqliteDesktopSessions.Session requireSession(String id) { return sessions.find(id).orElseThrow(() -> new IllegalArgumentException("SESSION_NOT_FOUND")); }
    private Run requireRun(String id) { return store.load(new RunRef(TENANT, id)).orElseThrow(() -> new IllegalArgumentException("RUN_NOT_FOUND")); }
    static SessionOwner owner(String sessionId) { return SessionOwner.user(new SessionRef(TENANT, sessionId), AGENT, USER); }
    static VerifiedIdentity actor(SessionOwner owner) { return new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(TENANT, USER), owner,
            Set.of("desktop:execute"), 1, new ResultRef("desktop-identity:" + owner.sessionRef().sessionId())); }
    private static String waitingId(ExecutionStore.WaitRef ref) { return AgentExecutionCodec.digest(ref.action().actionId() + "\0" + ref.generation()); }
    private ExecutionStore.Waiting requireWaiting(Run run, String id) {
        if (run.waiting().isPresent() && waitingId(run.waiting().orElseThrow()).equals(id)) return store.waiting(run.identity().runRef(), run.waiting().orElseThrow()).orElseThrow();
        // 已接受响应的同幂等键重放仍读取原等待，不把它变成新工具批准。
        for (var action : actions(run.identity().runRef())) {
            var source = store.waiting(run.identity().runRef(), action.currentAttempt());
            if (source.isPresent() && waitingId(source.orElseThrow().reference()).equals(id)) return source.orElseThrow();
            if (action.kind() == ExecutionStore.Kind.TOOL) {
                var attempt = store.attempt(run.identity().runRef(), action.currentAttempt()).orElseThrow();
                var request = codec.decodeTool(material(run, attempt.request()).content());
                if (request.continuation().isPresent() && waitingId(request.continuation().orElseThrow().waiting()).equals(id))
                    return store.waiting(run.identity().runRef(), request.continuation().orElseThrow().waiting()).orElseThrow();
            }
        }
        throw new IllegalArgumentException("WAIT_NOT_FOUND");
    }

    private List<ExecutionStore.Action> actions(RunRef run) {
        var values = new ArrayList<ExecutionStore.Action>(); long after = 0;
        while (true) {
            var page = store.actions(run, after, QUERY_PAGE_SIZE); values.addAll(page);
            if (page.size() < QUERY_PAGE_SIZE) return List.copyOf(values);
            after = page.getLast().ordinal();
        }
    }

    private static ObjectNode sessionJson(SqliteDesktopSessions.Session session) { return DesktopJson.object().put("id", session.id())
            .put("title", session.title()).put("workspace", session.workspace()).put("createdAt", session.createdAt().toString()); }

    @Override public void close() {
        if (!closing.compareAndSet(false, true)) return;
        long deadline = System.nanoTime() + SHUTDOWN_TIMEOUT.toNanos();
        observer.shutdown();
        workers.shutdown();
        active.values().forEach(flight -> { flight.cancelled.set(true); cancelTools(flight); });
        RuntimeException failure = null;
        for (var ref : List.copyOf(active.keySet())) {
            try { cancelTask(ref.runId(), "shutdown:" + UUID.randomUUID()); }
            catch (RuntimeException rejected) { failure = accumulate(failure, rejected); }
        }
        try { awaitStopped(observer, deadline); }
        catch (RuntimeException rejected) { failure = accumulate(failure, rejected); }
        try { awaitStopped(workers, deadline); }
        catch (RuntimeException rejected) { failure = accumulate(failure, rejected); }
        if (failure != null) {
            observer.shutdownNow(); workers.shutdownNow(); active.values().forEach(this::cancelTools);
            // 失败时也停止双方受理并取消工具；仍被使用的存储不能被提前关闭。
            if (!observer.isTerminated() || !workers.isTerminated()) throw failure;
        }
        for (var instance : tools.values()) failure = closeResource(instance, failure);
        failure = closeResource(models, failure); failure = closeResource(sessions, failure); failure = closeResource(store, failure);
        failure = closeResource(dataLock, failure); failure = closeResource(lockChannel, failure);
        if (failure != null) throw failure;
    }

    private static RuntimeException accumulate(RuntimeException first, RuntimeException next) {
        if (first == null) return next;
        if (first != next) first.addSuppressed(next);
        return first;
    }

    private static RuntimeException closeResource(AutoCloseable resource, RuntimeException failure) {
        try { resource.close(); return failure; }
        catch (Exception rejected) { return accumulate(failure, new IllegalStateException("desktop_resource_close_failed", rejected)); }
    }

    /** SQLite操作和回调不会因线程中断立即结束；必须先证明观察与执行线程静止才能关闭存储。 */
    private static void awaitStopped(java.util.concurrent.ExecutorService executor, long deadline) {
        try {
            long graceful = Math.max(0, deadline - System.nanoTime() - FORCE_SHUTDOWN_RESERVE.toNanos());
            if (!executor.awaitTermination(graceful, TimeUnit.NANOSECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS))
                    throw new IllegalStateException("desktop_shutdown_timeout");
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow(); Thread.currentThread().interrupt();
            throw new IllegalStateException("desktop_shutdown_interrupted", interrupted);
        }
    }

    /** Sources 只解释原冻结引用；恢复不重新选择当前模型配置。 */
    private final class Sources implements AgentExecutionProgram.Sources {
        @Override public boolean currentRead(SessionToolDirectory.Entry frozen, TypedPayload arguments) {
            var target = frozen.target(); var binding = frozen.binding();
            String handle = target.handle().value();
            String name = switch (handle) {
                case "tool:desktop:read_file" -> "read_file";
                case "tool:desktop:list_files" -> "list_files";
                case "tool:desktop:search_files" -> "search_files";
                default -> null;
            };
            return name != null && target.capability().equals(new CapabilityRef(CapabilityType.TOOL, "desktop:" + name, "1"))
                    && target.deploymentId().equals("desktop-tools") && target.deploymentRevision() == 1
                    && target.effect() == ToolEffect.READ_ONLY && target.callIdentityPolicy() == ToolPortal.ToolCallIdentityPolicy.ARGUMENTS
                    && target.confirmation() == ToolPortal.ToolConfirmationPolicy.NEVER && target.modelInvocable()
                    && binding.providerType() == ToolPortal.ToolBinding.ProviderType.PLATFORM
                    && binding.providerRef().equals("desktop") && binding.toolName().equals("workspace_files");
        }
        /** 原Desktop失败正文只来自绑定同一Run/Attempt的不可变receipt，不查询或重放工具。 */
        @Override public Optional<TypedPayload> toolFailureEvidence(Run run, com.skillforge.runtime.core.AgentExecutionLoop.ToolSource source,
                com.skillforge.runtime.api.port.PortFailure failure) {
            if (!failure.resultRef().value().startsWith("desktop-tool-receipt:")) return Optional.empty();
            if (!failure.resultRef().value().equals("desktop-tool-receipt:" + AgentExecutionCodec.digest(source.attemptId())))
                throw new IllegalStateException("desktop_tool_receipt_mismatch");
            var attempt = store.attempt(run.identity().runRef(), source.attemptId()).orElseThrow();
            if (!attempt.action().equals(source.action()) || !attempt.request().equals(source.request())
                    || attempt.result().filter(result -> !result.equals(source.result())).isPresent())
                throw new IllegalStateException("desktop_tool_receipt_mismatch");
            return toolReceipt(attempt).map(receipt -> {
                if (!"FAILED".equals(receipt.path("status").asText()) || !receipt.has("result"))
                    throw new IllegalStateException("desktop_tool_receipt_mismatch");
                return new TypedPayload("noewise.desktop.tool-failure.v1", DesktopJson.write(receipt.get("result")));
            });
        }
        @Override public Optional<ExecutionBudgetPort.Snapshot> budgetSnapshot(ExecutionStore.Run run) {
            return Optional.of(store.budgets().snapshot(run.identity().runRef()));
        }

        @Override public AgentExecutionProgram.Configuration configuration(Run run, ResultRef input) {
            var frozen = frozen(run); var own = codec.decodeInput(material(run, input).content());
            var messages = new ArrayList<>(own);
            var predecessor = frozen.get("predecessor");
            if (predecessor != null && predecessor.isTextual()) {
                var prior = requireRun(predecessor.textValue());
                if (prior.phase() != ExecutionStore.Phase.FINISHED) throw new IllegalStateException("desktop_predecessor_unfinished");
                var history = history(prior, new HashSet<>());
                // 新Run动作序号从1重新开始；历史协议身份按原Run隔离，避免与新回合的message/call ID碰撞。
                String prefix = "history:" + prior.identity().runRef().runId() + ":";
                messages = new ArrayList<>(own.stream().filter(message -> message.role() == ModelRole.SYSTEM).toList());
                messages.addAll(history.stream().filter(message -> message.role() != ModelRole.SYSTEM)
                        .map(message -> new ModelMessage(prefix + message.messageId(), message.role(), message.contents(),
                        message.toolCalls().stream().map(call -> new ModelPort.ModelToolCall(prefix + call.callId(), call.wireName(), call.arguments())).toList(),
                        message.toolCallId().map(id -> prefix + id), message.reasoningContent())).toList());
                messages.addAll(own.stream().filter(message -> message.role() != ModelRole.SYSTEM).toList());
            }
            return DesktopConfiguration.configuration(run, frozen, models, messages, actor(run.identity().owner()));
        }
        @Override public Material material(Run run, ResultRef reference) { return DesktopRuntime.this.material(run, reference); }
        @Override public List<Optional<Material>> materials(Run run, List<ResultRef> references) {
            return store.materials(run.identity().runRef().tenantId(), references);
        }
        @Override public SessionTokenCounter.Measurement measure(AgentExecutionProgram.Configuration configuration, List<ModelMessage> messages, List<com.skillforge.runtime.api.port.ModelPort.ModelTool> tools) {
            var profile = new SessionTokenCounter.Profile(configuration.profile().tokenizerProfileRef(), "desktop-tokenizer.v1",
                    JTokkitSessionTokenCounter.TOKENIZER_VERSION, "cl100k_base", configuration.target(),
                    new SessionTokenCounter.RoleCosts(1, 1, 1, 1), 3, 3, 2000, 2_000_000);
            var existing = tokenizerProfiles.putIfAbsent(profile.reference(), profile);
            if (existing != null && !existing.equals(profile)) throw new IllegalStateException("desktop_tokenizer_profile_conflict");
            return tokenCounter.measure(profile.reference(), profile.target(), messages, tools);
        }
        @Override public AgentExecutionModelMedia.Prepared prepareModelMessages(Run run, AgentExecutionProgram.Configuration configuration, List<ModelMessage> messages) {
            return new AgentExecutionModelMedia.Prepared(messages, List.of());
        }
        @Override public AgentExecutionTime.Sample sampleModelTime(Run run) { return AgentExecutionTime.sample(clock); }
        @Override public Duration elapsed(Run run) {
            var flight = active.get(run.identity().runRef()); return flight == null ? Duration.ZERO
                    : Duration.ofMillis(flight.priorActiveMillis).plusNanos(System.nanoTime() - flight.startedNanos);
        }
    }

    private List<ModelMessage> history(Run run, Set<RunRef> seen) {
        if (!seen.add(run.identity().runRef()) || seen.size() > policy.maxQueuedTasks()) throw new IllegalStateException("desktop_history_cycle");
        // 完整消息由正式Program的已保存context链恢复；最终回答由已提交完成事件追加。
        var materials = program.open(run, material(run, run.progress().context()));
        var messages = new ArrayList<>(materials.messages());
        var finish = store.event(run.identity().runRef(), AgentExecutionProgram.finishedEventId(run.identity().runRef()));
        finish.ifPresent(value -> {
            var text = DesktopJson.parse(value.event().data().canonicalJson()).path("text").asText();
            if (!text.isBlank()) messages.add(new ModelMessage("answer:" + run.identity().runRef().runId(), ModelRole.ASSISTANT, text));
        });
        return List.copyOf(messages);
    }

    private static final class Flight {
        final String sessionId;
        final Set<String> toolOperations = ConcurrentHashMap.newKeySet();
        final AtomicBoolean cancelled = new AtomicBoolean(); final AtomicBoolean ownershipLost = new AtomicBoolean();
        volatile com.skillforge.runtime.api.port.LeaseFence lease;
        volatile long startedNanos = System.nanoTime();
        volatile Instant startedAt;
        volatile Instant lastConfirmedAt;
        volatile String intervalId;
        volatile long priorActiveMillis;
        Flight(String sessionId) { this.sessionId = sessionId; }
    }
}
