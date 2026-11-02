package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.chat.message.MemoryService;
import com.skillforge.institution.runtime.AgentRuntimeExecutionConfiguration;
import com.skillforge.institution.runtime.AgentRuntimeExecutionWorker;
import com.skillforge.runtime.api.TenantId;
import com.zaxxer.hikari.HikariDataSource;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** 三种负载独立验收，各50预热+500测量，始终50客户端槽位，失败与超时不删除或重试。 */
@Tag("runtime-capacity")
@Tag("runtime-latency")
@Import({ChatRuntimeChannelE2ETest.LoopbackEvents.class, DeterministicModelCatalogConfiguration.class,
        AgentRuntimeExecutionConfiguration.class, ChatExecutionCapacityE2ETest.ProbeConfiguration.class})
class ChatExecutionCapacityE2ETest extends ChatExecutionChainE2ETest {
    private static final int CONCURRENCY = 50;
    private static final int WARMUPS = 50;
    private static final int MEASURED = 500;
    @Autowired RuntimeExecutionDataSourceProbe probe;
    @Autowired MemoryService memory;
    @Autowired DataSource source;
    @Autowired com.skillforge.core.foundation.settings.OperationalSettingsService operationalSettings;

    /** 仅本容量上下文加入透明计数，不启用SQL正文和调用栈Profiler。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean static RuntimeExecutionDataSourceProbe executionDataSourceProbe() { return new RuntimeExecutionDataSourceProbe(); }
    }

    @Test void ordinaryFiftyConcurrentRequests() throws Exception { capacity("ordinary", RuntimeExecutionWorkloadProvider.Workload.ORDINARY, false); }
    @Test void multiStepFiftyConcurrentRequests() throws Exception { capacity("multi-step", RuntimeExecutionWorkloadProvider.Workload.MULTI_STEP, false); }
    @Test void longHistoryFiftyConcurrentRequests() throws Exception { capacity("long-history", RuntimeExecutionWorkloadProvider.Workload.ORDINARY, true); }

    /** 同一租户、独立会话且不在计时中建账号、发布能力或预置历史。 */
    private void capacity(String scenario, RuntimeExecutionWorkloadProvider.Workload workload, boolean longHistory) throws Exception {
        assertFalse("1".equals(System.getenv("SKILLFORGE_PERF_SQL")), "正式容量必须关闭详细SQL profiler");
        assertFalse("1".equals(System.getenv("SKILLFORGE_PERF_SQL_CALLERS")), "正式容量必须关闭SQL调用栈");
        Path evidence = Path.of("build", "reports", "runtime-execution-capacity", scenario + "-" + SnowflakeIds.nextId());
        Files.createDirectories(evidence);
        var report = new LinkedHashMap<String, Object>();
        report.put("scenario", scenario); report.put("outcome", "FAILED");
        report.put("tenant", tenantId); report.put("concurrency", CONCURRENCY);
        report.put("plannedWarmups", WARMUPS); report.put("plannedMeasured", MEASURED);
        report.put("modelCallsPerRequest", workload.modelCalls); report.put("toolCallsPerRequest", workload.toolCalls);
        report.put("historyMessages", longHistory ? 100 : 0); report.put("historyUtf8Bytes", longHistory ? 65536 : 0);
        report.put("timingScope", "client starts HTTP submission through postSse return, includes client/network/HTTP admission/storage/queue/tool/encoding/delivery and owned reader cleanup");
        // 本批三家门诊试点的用户确认参考线；不代表生产模型或hxy首段正文体验验收。
        long latencyReferenceMillis = !longHistory && workload == RuntimeExecutionWorkloadProvider.Workload.ORDINARY ? 5000 : 10000;
        report.put("latencyReferenceMillis", latencyReferenceMillis);
        report.put("latencyReferenceScope", "user-approved three-clinic pilot local platform reference, 2026-10-26; excludes real-model and hxy first-body acceptance");
        report.put("receiptTimingScope", "separate same-JVM terminal frame receipt timestamp before body close and reader shutdown; diagnostic subdivision, not a replacement gate");
        report.put("providerDeduction", "zero simulated Provider execution; no time subtracted");
        report.put("sqlScope", "all application DataSource JDBC execute calls during each batch window; not attributed per request");
        report.put("commitScope", "successful explicit JDBC commit calls, plus separately labelled whole-database cumulative snapshot delta");
        report.put("queueScope", "Gateway.observeReady after configuration/history preparation and before Store.admit, through Activity control.open recording claimed after Store.claim returns; includes Store admission and slot wait, excludes earlier HTTP/configuration/history preparation");
        report.put("connectionScope", "real DataSource.getConnection acquisition, includes pool wait and driver connect overhead");
        report.put("javaVersion", System.getProperty("java.version")); report.put("javaVm", System.getProperty("java.vm.name"));
        report.put("maxHeapBytes", Runtime.getRuntime().maxMemory()); report.put("sqlProfiler", false); report.put("sqlCallers", false);
        report.put("loadedClassSha256", capacityClassHashes());
        report.put("databaseVersion", jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>) connection -> connection.getMetaData().getDatabaseProductVersion()));
        report.put("measuredStatus", "NOT_RUN");
        // 固定负载包含1100次执行/订阅HTTP与550次Session准备，需在隔离fixture中明确发布容量策略。
        // 仍经过真实限流器，不修改平台默认值或删除原失败；策略值和修订随报告保存。
        var originalRate = operationalSettings.read("rate_limits");
        long policyActor = SnowflakeIds.nextId();
        jdbc.update("INSERT INTO nw_platform_members(id,username,password_hash,status) VALUES(?,?,?,'disabled')",
                policyActor, "runtime-capacity-policy-" + policyActor, "unused");
        var capacityRate = operationalSettings.update("rate_limits", originalRate.revision(),
                Map.of("requests_per_minute_default", 10000), policyActor);
        report.put("httpRatePolicy", Map.of("requestsPerMinute", 10000, "revision", capacityRate.revision(),
                "scope", "isolated fixture, actual production limiter remains active"));
        try (var provider = new RuntimeExecutionWorkloadProvider(objectMapper)) {
            var allCases = new ArrayList<Case>();
            try {
            provider.start(); TenantContext.setTenantId(tenantId);
            try {
                var catalog = applicationContext.getBean(com.skillforge.core.model.PlatformModelCatalogService.class);
                if (longHistory) {
                    // 原64KiB历史本身超过32k模型输入额度；本场景显式声明64k能力，不改变普通/多步默认模型。
                    var created = catalog.create(new com.skillforge.core.model.ModelWrite(
                            null, "Runtime long-history 64k fixture", "LLM", true,
                            "custom", "custom-openai-compatible", "custom-v1", "openai-chat-completions", "bearer-api-key",
                            provider.endpoint(), "runtime-fixture", "runtime-fixture-key",
                            4096, 65536, 1024, 0.2, "DISABLED", 30,
                            null, null, null, null, null, "CNY"), "capacity-long-history-fixture");
                    catalog.publish(created.id(), created.revision(), "capacity-long-history-fixture");
                    catalog.select(tenantId, new com.skillforge.core.model.ModelSelections(created.id(), null, null));
                } else configureTenantModel(provider.endpoint());
                var selected = catalog.resolveLlm(tenantId, agentId);
                assertEquals(longHistory ? 65536 : 32768, selected.contextWindowTokens());
                assertEquals(1024, selected.maxOutputTokens());
                long safety = Math.ceilDiv(selected.contextWindowTokens().longValue() * 5, 100);
                report.put("modelContextBudget", Map.of("modelConfigId", selected.id(), "revision", selected.revision(),
                        "windowTokens", selected.contextWindowTokens(), "outputTokens", selected.maxOutputTokens(),
                        "safetyPercent", 5, "safetyTokens", safety,
                        "inputAllowanceTokens", selected.contextWindowTokens() - selected.maxOutputTokens() - safety));
            } finally { TenantContext.clear(); }
            login(); mountGenericTemplateTool();
            var warm = prepare(provider, scenario + "-warm", workload, longHistory, WARMUPS);
            allCases.addAll(warm);
            var warmed = batch("warmup", warm, evidence, report);
            verify(warm, warmed, workload, longHistory, evidence);
            var measured = prepare(provider, scenario + "-measured", workload, longHistory, MEASURED);
            allCases.addAll(measured);
            report.put("measuredStatus", "RUNNING");
            var samples = batch("measured", measured, evidence, report);
            verify(measured, samples, workload, longHistory, evidence);
            assertEquals((WARMUPS + MEASURED) * workload.modelCalls, provider.requests().size(), "保留额外或无法路由的模型请求，不能只核对成功脚本");
            var elapsed = samples.stream().map(Sample::elapsedNanos).sorted().toList();
            report.put("platformLatencyNanos", percentiles(elapsed));
            long start = samples.stream().mapToLong(Sample::startedNanos).min().orElseThrow();
            long end = samples.stream().mapToLong(Sample::finishedNanos).max().orElseThrow();
            report.put("throughputRequestsPerSecond", MEASURED * 1_000_000_000.0 / (end - start));
            report.put("measuredStatus", "COMPLETED");
            assertTrue(elapsed.get((int)Math.ceil(MEASURED * .95) - 1) <= Duration.ofMillis(latencyReferenceMillis).toNanos(),
                    "本组平台P95超过本批试点参考线 " + latencyReferenceMillis + "ms；原始失败与时延证据保留");
            report.put("outcome", "PASS");
            } finally {
                report.put("allProviderRequests", provider.requests());
                report.put("allProviderScripts", allCases.stream().map(value -> value.script().snapshot()).toList());
            }
        } catch (Exception | AssertionError failure) {
            if ("RUNNING".equals(report.get("measuredStatus"))) report.put("measuredStatus", "FAILED");
            report.put("failureType", failure.getClass().getName()); report.put("failure", failure.getMessage()); throw failure;
        } finally {
            operationalSettings.update("rate_limits", capacityRate.revision(), originalRate.values(), policyActor);
            Files.writeString(evidence.resolve("report.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            System.out.println("RUNTIME_EXECUTION_CAPACITY_REPORT " + evidence.resolve("report.json").toAbsolutePath());
        }
    }

    /** 每个输入在计时外准备独立Session；长历史严格100条消息、65536 UTF8字节，并校验实际入模。 */
    private List<Case> prepare(RuntimeExecutionWorkloadProvider provider, String prefix,
            RuntimeExecutionWorkloadProvider.Workload workload, boolean longHistory, int count) throws Exception {
        var cases = new ArrayList<Case>();
        for (int index = 0; index < count; index++) {
            String input = "capacity-" + SnowflakeIds.nextId();
            var created = send("/api/v1/chat/sessions", "POST", Map.of("agent_id", agentId, "end_user_id", endUserId), accessToken);
            assertEquals(200, created.statusCode(), created.body());
            String session = successData(created).path("session_id").asText(); assertFalse(session.isBlank());
            var script = provider.register(prefix + "-" + index, workload);
            if (longHistory) {
                var history = new ArrayList<String>();
                TenantContext.setTenantId(tenantId);
                try {
                    for (int message = 0; message < 100; message++) {
                        String text = longHistoryMessage(message);
                        history.add(text);
                        memory.append(tenantId, session, endUserId, message % 2 == 0 ? "user" : "assistant", text, Map.of(), input + "-history-" + message);
                    }
                    assertEquals(100, memory.list(tenantId, session, 100).size());
                } finally { TenantContext.clear(); }
                assertEquals(65536, history.stream().mapToInt(text -> text.getBytes(StandardCharsets.UTF_8).length).sum());
                script.requireHistory(history);
            }
            var body = Map.<String, Object>of("session_id", session, "agent_id", agentId, "client_message_id", input,
                    "content", List.of(Map.of("type", "text", "text", script.prompt())), "context", Map.of("end_user_id", endUserId));
            cases.add(new Case(index, session, "run:" + input, script, body));
        }
        assertEquals(count, cases.stream().map(Case::session).distinct().count());
        return List.copyOf(cases);
    }

    /** 首50个任务必须全部ready才同时释放；线程数固定50，完成后立即从客户端任务队列补位。 */
    private List<Sample> batch(String phase, List<Case> cases, Path evidence, Map<String, Object> report) throws Exception {
        // 在计时窗外留下本批阶段与进程身份，独立采样无需猜测预热或测量开始时刻。
        Files.writeString(evidence.resolve(phase + "-window-started.json"), objectMapper.writeValueAsString(Map.of(
                "phase", phase, "pid", ProcessHandle.current().pid(), "startedAt", java.time.Instant.now().toString())),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        var samples = new ArrayList<Sample>(); var ready = new CountDownLatch(CONCURRENCY); var release = new CountDownLatch(1);
        var active = new AtomicInteger(); var maximum = new AtomicInteger();
        var clients = Executors.newFixedThreadPool(CONCURRENCY, Thread.ofPlatform().name("runtime-capacity-client-", 0).factory());
        var completed = new ExecutorCompletionService<Sample>(clients);
        var worker = applicationContext.getBean(AgentRuntimeExecutionWorker.class);
        var beforeDb = databaseCounters();
        var window = probe.begin();
        try (var queue = worker.observeQueue(new TenantId(tenantId), cases.size() + 10); var resources = new ResourceSampler(source)) {
            for (var testCase : cases) completed.submit(() -> {
                if (testCase.index() < CONCURRENCY) { ready.countDown(); if (!release.await(30, TimeUnit.SECONDS)) throw new AssertionError("first_fifty_barrier_timeout"); }
                int inFlight = active.incrementAndGet(); maximum.accumulateAndGet(inFlight, Math::max);
                long started = System.nanoTime();
                try {
                    var capture = postSse(testCase.body());
                    long finished = System.nanoTime();
                    return new Sample(testCase.index(), testCase.session(), testCase.run(), started, finished, finished - started, capture, "", "");
                } catch (Exception | AssertionError failed) {
                    long finished = System.nanoTime(); var error = new StringWriter(); failed.printStackTrace(new PrintWriter(error));
                    return new Sample(testCase.index(), testCase.session(), testCase.run(), started, finished, finished - started, null, failed.getClass().getName(), error.toString());
                } finally { active.decrementAndGet(); }
            });
            assertTrue(ready.await(30, TimeUnit.SECONDS), "首50个独立客户端必须同时就绪"); release.countDown();
            for (int index = 0; index < cases.size(); index++) samples.add(completed.take().get());
            report.put(phase + "Queue", queue.samples()); report.put(phase + "QueueOverflowed", queue.overflowed());
            var runs = cases.stream().map(Case::run).collect(java.util.stream.Collectors.toSet());
            var ownQueue = queue.samples().stream().filter(sample -> runs.contains(sample.run().runId())).toList();
            report.put(phase + "QueueClaimed", ownQueue.stream().filter(sample -> sample.claimedNanos().isPresent()).count());
            report.put(phase + "QueueLatencyNanos", percentiles(ownQueue.stream().filter(sample -> sample.queueNanos().isPresent()).map(sample -> sample.queueNanos().getAsLong()).sorted().toList()));
            report.put(phase + "Resources", resources.samples());
            assertFalse(queue.overflowed(), "队列测量不能静默丢弃样本");
            assertEquals(cases.size(), ownQueue.size()); assertTrue(ownQueue.stream().allMatch(sample -> sample.claimedNanos().isPresent()));
        } finally {
            release.countDown(); clients.shutdownNow();
            assertTrue(clients.awaitTermination(5, TimeUnit.SECONDS), "本组客户端必须先静止");
            var dataSource = probe.end(window);
            report.put(phase + "DataSource", dataSource);
            report.put(phase + "ConnectionAcquisitionLatencyNanos", percentiles(dataSource.connectionAcquisitionNanos().stream().sorted().toList()));
            var afterDb = databaseCounters();
            report.put(phase + "DatabaseBefore", beforeDb); report.put(phase + "DatabaseAfter", afterDb);
            if (beforeDb.get("xact_commit") instanceof Number before && afterDb.get("xact_commit") instanceof Number after) {
                report.put(phase + "DatabaseDelta", Map.of("xact_commit", after.longValue() - before.longValue(),
                        "xact_rollback", ((Number)afterDb.get("xact_rollback")).longValue() - ((Number)beforeDb.get("xact_rollback")).longValue(),
                        "statisticsResetUnchanged", java.util.Objects.equals(beforeDb.get("stats_reset"), afterDb.get("stats_reset")),
                        "scope", "whole database cumulative view; asynchronous statistics can lag and other connections are included"));
            } else report.put(phase + "DatabaseDelta", Map.of("status", "UNAVAILABLE"));
            report.put(phase + "ObservedMaxInFlight", maximum.get());
            report.put(phase + "ExecutedRequests", samples.size());
            report.put(phase + "TransportFailures", samples.stream().filter(sample -> !sample.failureType().isEmpty()).count());
            var terminalFailures = new LinkedHashMap<String, Long>();
            for (var sample : samples) if (sample.capture() != null) {
                terminalFailure(objectMapper, sample.capture()).ifPresent(reason -> terminalFailures.merge(reason, 1L, Long::sum));
            }
            report.put(phase + "TerminalErrors", terminalFailures.values().stream().mapToLong(Long::longValue).sum());
            report.put(phase + "TerminalFailureReasons", terminalFailures);
            report.put(phase + "AllAttemptLatencyNanos", percentiles(samples.stream().map(Sample::elapsedNanos).sorted().toList()));
            var received = samples.stream().filter(sample -> sample.capture() != null && sample.capture().terminalReceivedNanos() > 0).toList();
            report.put(phase + "TerminalReceiptLatencyNanos", percentiles(received.stream()
                    .map(sample -> sample.capture().terminalReceivedNanos() - sample.startedNanos()).sorted().toList()));
            report.put(phase + "ResponseCloseLatencyNanos", percentiles(received.stream()
                    .map(sample -> sample.capture().bodyClosedNanos() - sample.capture().terminalReceivedNanos()).sorted().toList()));
            report.put(phase + "ReaderShutdownLatencyNanos", percentiles(received.stream()
                    .map(sample -> sample.capture().readerStoppedNanos() - sample.capture().bodyClosedNanos()).sorted().toList()));
            report.put(phase + "Samples", samples);
            Files.writeString(evidence.resolve(phase + "-samples.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(samples), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            for (var sample : samples) {
                if (sample.capture() != null) Files.writeString(evidence.resolve(phase + "-" + sample.index() + ".sse.txt"), sample.capture().raw(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                else Files.writeString(evidence.resolve(phase + "-" + sample.index() + ".error.txt"), sample.failure(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            }
        }
        assertEquals(cases.size(), samples.size()); assertEquals(CONCURRENCY, maximum.get());
        return List.copyOf(samples);
    }

    /** 所有原始样本先落盘再验收；错误数不是从成功延迟中删除后继续宣称PASS。 */
    private void verify(List<Case> cases, List<Sample> samples, RuntimeExecutionWorkloadProvider.Workload workload,
            boolean longHistory, Path evidence) throws Exception {
        var failures = samples.stream().filter(sample -> !sample.failureType().isEmpty()).toList();
        assertTrue(failures.isEmpty(), "有失败/超时样本，完整error文件已保留: " + failures.size());
        for (var sample : samples) {
            assertEquals(200, sample.capture().statusCode());
            var failure = terminalFailure(objectMapper, sample.capture());
            assertTrue(failure.isEmpty(), "原Run未成功完成: " + sample.run() + " " + failure.orElse(""));
        }
        await("Provider响应收尾", () -> cases.stream().allMatch(value -> value.script().snapshot().requests().size() >= workload.modelCalls));
        for (var testCase : cases) {
            var sample = samples.stream().filter(value -> value.index() == testCase.index()).findFirst().orElseThrow();
            assertEquals(200, sample.capture().statusCode()); assertTrue(sample.capture().events().contains("execution.completed"), sample.capture().raw());
            assertFalse(sample.capture().events().contains("error"), sample.capture().raw()); assertTrue(sample.capture().raw().contains(testCase.script().finalAnswer()), sample.capture().raw());
            assertTrue(sample.startedNanos() <= sample.capture().terminalReceivedNanos()
                    && sample.capture().terminalReceivedNanos() <= sample.capture().bodyClosedNanos()
                    && sample.capture().bodyClosedNanos() <= sample.capture().readerStoppedNanos()
                    && sample.capture().readerStoppedNanos() <= sample.finishedNanos(), "完整终态接收及客户端静止时刻必须按单调时间有序");
            var provider = testCase.script().snapshot();
            assertEquals(workload.modelCalls, provider.requestCount()); assertEquals(workload.modelCalls, provider.requests().size());
            assertEquals(workload.toolCalls, provider.verifiedToolResults());
            for (var request : provider.requests()) { assertEquals(200, request.httpStatus()); assertEquals("", request.failureReason()); assertEquals(0, request.simulatedExecutionNanos()); }
            if (longHistory) { assertEquals(100, provider.verifiedHistoryMessages()); assertEquals(65536, provider.verifiedHistoryUtf8Bytes()); }
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND status='COMPLETED' AND phase='FINISHED' AND lease_claim_id IS NULL", tenantId, testCase.run()));
            assertEquals(workload.modelCalls, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='MODEL' AND status='SUCCEEDED'", tenantId, testCase.run()));
            assertEquals(workload.toolCalls, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='TOOL' AND status='SUCCEEDED'", tenantId, testCase.run()));
            assertEquals(workload.modelCalls + workload.toolCalls, countRuntime("SELECT count(*) FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=? AND adopted AND result_kind='SUCCEEDED' AND result_ref IS NOT NULL", tenantId, testCase.run()));
            assertEquals(workload.modelCalls + workload.toolCalls, countRuntime("SELECT count(*) FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=?", tenantId, testCase.run()));
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_events WHERE tenant_id=? AND run_id=? AND type='execution.completed'", tenantId, testCase.run()));
            assertEquals(0, countRuntime("SELECT count(*) FROM agent_runtime_runs WHERE tenant_id=? AND run_id=?", tenantId, testCase.run()));
        }
        Files.writeString(evidence.resolve(cases.size() == WARMUPS ? "warmup-provider.json" : "measured-provider.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(cases.stream().map(value -> value.script().snapshot()).toList()), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    /** 固定历史工作量不随模型窗口变化；预算不容纳时保持真实拒绝，不改变正文或长度。 */
    static String longHistoryMessage(int message) {
        String marker = String.format("capacity-history-%03d:", message);
        return marker + "h".repeat(655 + (message < 36 ? 1 : 0) - marker.length());
    }

    /** completed只是终态事件名；FAILED/CANCELLED终态不能计为成功或等待并未发生的Provider调用。 */
    static java.util.Optional<String> terminalFailure(com.fasterxml.jackson.databind.ObjectMapper json, SseCapture capture) {
        if (capture.events().contains("error")) return java.util.Optional.of("error_event");
        boolean completed = false;
        for (var frame : capture.raw().replace("\r\n", "\n").split("\n\n")) {
            String event = ""; var data = new StringBuilder();
            for (var line : frame.split("\n")) {
                if (line.startsWith("event:")) event = line.substring(6).strip();
                if (line.startsWith("data:")) {
                    if (!data.isEmpty()) data.append('\n');
                    data.append(line.substring(5).stripLeading());
                }
            }
            if (!"execution.completed".equals(event)) continue;
            completed = true;
            try {
                var value = json.readTree(data.toString());
                if (value == null || !value.isObject()) return java.util.Optional.of("invalid_completed_payload");
                String status = value.path("status").asText();
                if (!"completed".equals(status)) return java.util.Optional.of(status + ":" + value.path("reason").asText());
            } catch (java.io.IOException malformed) {
                return java.util.Optional.of("invalid_completed_payload");
            }
        }
        return completed ? java.util.Optional.empty() : java.util.Optional.of("terminal_missing");
    }

    /** 仅报告实际数据库累计视图；全库包含其他连接，统计刷新也可能滞后，不是本组逐请求事务数。 */
    private Map<String, Object> databaseCounters() {
        try { return jdbc.queryForMap("SELECT datname,xact_commit,xact_rollback,numbackends,stats_reset FROM pg_stat_database WHERE datname=current_database()"); }
        catch (RuntimeException unavailable) { return Map.of("status", "UNAVAILABLE", "failureType", unavailable.getClass().getName()); }
    }

    private Map<String, String> capacityClassHashes() throws Exception {
        var hashes = new LinkedHashMap<String, String>();
        for (String name : List.of("com.skillforge.institution.runtime.AgentRuntimeExecutionGateway", "com.skillforge.institution.runtime.AgentRuntimeExecutionWorker",
                "com.skillforge.institution.runtime.AgentRuntimeExecutionSources", "com.skillforge.institution.runtime.AgentRuntimeExecutionCapabilities",
                "com.skillforge.institution.runtime.AgentRuntimeExecutionProjection", "com.skillforge.runtime.session.AgentExecutionProgram",
                "com.skillforge.runtime.session.ExecutionCoordinator", "com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore",
                "com.skillforge.runtime.adapter.model.OpenAiModelPort", "com.skillforge.institution.runtime.AgentRuntimePlatformToolProvider",
                "com.skillforge.institution.integration.RuntimeExecutionWorkloadProvider")) {
            var type = Class.forName(name, false, getClass().getClassLoader());
            try (var input = type.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
                assertNotNull(input); hashes.put(name, java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())));
            }
        }
        return hashes;
    }

    private static Map<String, Object> percentiles(List<Long> sorted) {
        if (sorted.isEmpty()) return Map.of("count", 0, "status", "UNAVAILABLE");
        return Map.of("count", sorted.size(), "p50", sorted.get((int)Math.ceil(sorted.size() * .5) - 1),
                "p95", sorted.get((int)Math.ceil(sorted.size() * .95) - 1), "p99", sorted.get((int)Math.ceil(sorted.size() * .99) - 1), "max", sorted.getLast());
    }

    private static void await(String reason, BooleanSupplier condition) throws Exception {
        long limit = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < limit) { if (condition.getAsBoolean()) return; Thread.sleep(10); }
        throw new AssertionError(reason);
    }

    private record Case(int index, String session, String run, RuntimeExecutionWorkloadProvider.Script script, Map<String, Object> body) { }
    private record Sample(int index, String session, String run, long startedNanos, long finishedNanos, long elapsedNanos,
            SseCapture capture, String failureType, String failure) { }

    /** 100ms真实采样CPU累计值、堆、非堆及连接池，不把虚拟地址空间当作RSS。 */
    private static final class ResourceSampler implements AutoCloseable {
        private final java.util.concurrent.ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("runtime-capacity-resources").factory());
        private final CopyOnWriteArrayList<Map<String, Object>> points = new CopyOnWriteArrayList<>();
        private final HikariDataSource hikari;
        ResourceSampler(DataSource source) throws Exception {
            hikari = source.isWrapperFor(HikariDataSource.class) ? source.unwrap(HikariDataSource.class) : null;
            sample(); timer.scheduleAtFixedRate(this::sample, 100, 100, TimeUnit.MILLISECONDS);
        }
        private void sample() {
            var memory = ManagementFactory.getMemoryMXBean(); var row = new LinkedHashMap<String, Object>();
            row.put("atNanos", System.nanoTime()); row.put("heapUsedBytes", memory.getHeapMemoryUsage().getUsed());
            row.put("heapCommittedBytes", memory.getHeapMemoryUsage().getCommitted()); row.put("nonHeapUsedBytes", memory.getNonHeapMemoryUsage().getUsed());
            row.put("threads", ManagementFactory.getThreadMXBean().getThreadCount());
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
                row.put("processCpuNanos", os.getProcessCpuTime()); row.put("processCommittedVirtualBytes", os.getCommittedVirtualMemorySize());
                row.put("availableProcessors", os.getAvailableProcessors());
            }
            if (hikari != null && hikari.getHikariPoolMXBean() != null) {
                row.put("poolMaximum", hikari.getMaximumPoolSize()); row.put("poolActive", hikari.getHikariPoolMXBean().getActiveConnections());
                row.put("poolIdle", hikari.getHikariPoolMXBean().getIdleConnections()); row.put("poolWaiting", hikari.getHikariPoolMXBean().getThreadsAwaitingConnection());
            }
            points.add(Map.copyOf(row));
        }
        List<Map<String, Object>> samples() { sample(); return List.copyOf(points); }
        @Override public void close() throws InterruptedException { timer.shutdownNow(); assertTrue(timer.awaitTermination(5, TimeUnit.SECONDS)); }
    }
}
