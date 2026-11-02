package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 独立 SQL 调用归因诊断；同租户九个真实 Session，预热两次后逐请求观测七次。
 *
 * <p>复用真实登录、工具挂载、模型 HTTP、产品 HTTP/SSE 与 typed 持久化断言；
 * Profiler 只包围七个测量请求，验证查询和重复受理均在采样停止后执行。
 * 调用栈采集和后台轮询属于诊断成本，本例不产生正式时延或容量验收结论。</p>
 */
@Tag("runtime-latency")
@Tag("runtime-sql-diagnostic")
@EnabledIfEnvironmentVariable(named = "SKILLFORGE_PERF_SQL", matches = "1")
@EnabledIfEnvironmentVariable(named = "SKILLFORGE_PERF_SQL_CALLERS", matches = "1")
@EnabledIfEnvironmentVariable(named = "SKILLFORGE_PERF_SQL_ROLES", matches = "1")
class ChatExecutionSqlDiagnosticE2ETest extends ChatExecutionChainE2ETest {
    /** 只为定位每请求 SQL、事务方法及连接调用者，不放宽正式普通链的 profiler 禁用门控。 */
    @Test
    void profilesSevenSequentialRealTemplateRequestsAfterTwoWarmups() throws Exception {
        Path evidence = Files.createTempDirectory(
                Files.createDirectories(Path.of("build", "reports").toAbsolutePath()), "runtime-execution-sql-diagnostic-");
        var report = new LinkedHashMap<String, Object>();
        var samples = new ArrayList<Map<String, Object>>();
        report.put("classification", "DIAGNOSTIC");
        report.put("outcome", "FAILED");
        report.put("formalLatencyAcceptance", "NOT_ASSERTED");
        report.put("fiftyConcurrentAcceptance", "NOT_RUN");
        report.put("warmupRequests", 2);
        report.put("measuredRequests", 7);
        report.put("sqlProfiler", true);
        report.put("sqlCallers", true);
        report.put("sqlRoles", true);
        report.put("workload", "2 actual HTTP model calls + 1 real PLATFORM template_render per independent Session");
        report.put("samplingScope", "Seven separate process-wide windows: chat HTTP submission through persisted terminal SSE; includes real background work overlapping each window");
        report.put("excludedFromSampling", "Login, mount/publication, Session creation, two warmups, persistence verification and duplicate-input checks");
        report.put("sqlEvidence", "JUnit stdout between SQL_DIAGNOSTIC_WINDOW_BEGIN/END markers: SQL_PROFILE, CONNECTION_PROFILE, CALL_PROFILE and SQL_ROLE");
        report.put("transactionMeaning", "Profiler reports JDBC lifecycle method calls including commit, rollback, setAutoCommit and close; total_transaction_count is not a commit count or database transaction total");
        report.put("timingComparable", false);
        report.put("loadedClassSha256", ordinaryExecutionClassHashes());
        report.put("javaVersion", System.getProperty("java.version"));
        report.put("samples", samples);
        try (var workload = new RuntimeExecutionWorkloadProvider(objectMapper)) {
            workload.start();
            TenantContext.setTenantId(tenantId);
            try { configureTenantModel(workload.endpoint()); }
            finally { TenantContext.clear(); }
            login();
            mountGenericTemplateTool();
            // 全部会话在预热前建立，测量不混入资源创建和首次租户准备。
            var sessions = new ArrayList<String>();
            for (int index = 0; index < 9; index++) {
                var created = send("/api/v1/chat/sessions", "POST", Map.of("agent_id", agentId, "end_user_id", endUserId), accessToken);
                assertEquals(200, created.statusCode(), created.body());
                String session = successData(created).path("session_id").asText();
                assertFalse(session.isBlank(), created.body());
                assertFalse(sessions.contains(session), "每个样本必须使用独立真实 Session");
                sessions.add(session);
            }
            for (int index = 0; index < 9; index++) {
                boolean warmup = index < 2;
                runId = "execution-sql-diagnostic-" + SnowflakeIds.nextId();
                sessionId = sessions.get(index);
                var script = workload.register("ordinary-sql-diagnostic-" + index, RuntimeExecutionWorkloadProvider.Workload.ORDINARY);
                var payload = chatPayload(script.prompt());
                String actualRun = "run:" + runId;
                var sample = new LinkedHashMap<String, Object>();
                sample.put("index", index);
                sample.put("warmup", warmup);
                sample.put("profiled", !warmup);
                sample.put("session", sessionId);
                sample.put("run", actualRun);
                sample.put("outcome", "FAILED");
                samples.add(sample);
                try {
                    SseCapture response;
                    long elapsed;
                    if (warmup) {
                        long started = System.nanoTime();
                        response = postSse(payload);
                        elapsed = System.nanoTime() - started;
                    } else {
                        // 采样在实际请求前开启、终态SSE返回后立即停止；异常也必须关闭全局采样。
                        System.out.printf("SQL_DIAGNOSTIC_WINDOW_BEGIN index=%d run=%s classification=DIAGNOSTIC%n", index, actualRun);
                        SqlProfileDataSource.reset();
                        long started = System.nanoTime();
                        try { response = postSse(payload); }
                        finally {
                            elapsed = System.nanoTime() - started;
                            SqlProfileDataSource.printSummary();
                            System.out.printf("SQL_DIAGNOSTIC_WINDOW_END index=%d run=%s classification=DIAGNOSTIC%n", index, actualRun);
                        }
                    }
                    sample.put("clientElapsedNanosDiagnosticOnly", elapsed);
                    Files.writeString(evidence.resolve(String.format("%02d.sse.txt", index)), response.raw(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                    assertEquals(200, response.statusCode(), response.raw());
                    assertTrue(response.events().contains("execution.completed"), response.raw());
                    assertFalse(response.events().contains("error"), response.raw());
                    assertTrue(response.raw().contains(script.finalAnswer()), response.raw());
                    awaitProvider(() -> script.snapshot().requests().size() >= 2);
                    var snapshot = script.snapshot();
                    sample.put("provider", snapshot);
                    assertEquals(2, snapshot.requestCount(), snapshot.toString());
                    assertEquals(2, snapshot.requests().size(), snapshot.toString());
                    assertEquals(1, snapshot.verifiedToolResults(), snapshot.toString());
                    for (var timing : snapshot.requests()) {
                        assertEquals(200, timing.httpStatus(), timing.toString());
                        assertEquals("", timing.failureReason(), timing.toString());
                        assertEquals(0, timing.simulatedExecutionNanos(), "诊断不得模拟或扣除模型工作量");
                    }
                    assertEquals((index + 1) * 2, workload.requests().size(), "保留所有真实 Provider 请求，不能丢弃失败或额外调用");
                    assertTypedExecutionPersistence(actualRun, script.expectedToolResult(1));
                    var replay = send("/api/v1/chat", "POST", payload, accessToken);
                    assertEquals(202, replay.statusCode(), replay.body());
                    assertTrue(successData(replay).path("replayed").asBoolean(), replay.body());
                    assertEquals(2, script.snapshot().requestCount(), "重复受理不能新增模型调用");
                    assertTypedExecutionPersistence(actualRun, script.expectedToolResult(1));
                    sample.put("duplicateSubmissionReplayed", true);
                    sample.put("outcome", "PASS");
                } catch (Exception | AssertionError failed) {
                    sample.put("failureType", failed.getClass().getName());
                    throw failed;
                }
            }
            assertEquals(18, workload.requests().size());
            report.put("outcome", "PASS");
        } catch (Exception | AssertionError failed) {
            report.put("failureType", failed.getClass().getName());
            throw failed;
        } finally {
            Files.writeString(evidence.resolve("report.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            System.out.println("RUNTIME_EXECUTION_SQL_DIAGNOSTIC_REPORT " + evidence.resolve("report.json"));
        }
    }

    /** 只等待本地 Provider 已关闭原HTTP响应的内存事实，不在 SQL 采样窗口插入诊断查询。 */
    private static void awaitProvider(BooleanSupplier completed) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!completed.getAsBoolean()) {
            if (System.nanoTime() >= deadline) throw new AssertionError("sql_diagnostic_provider_response_not_closed");
            Thread.sleep(25);
        }
    }
}
