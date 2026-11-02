package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.institution.runtime.AgentRuntimeExecutionWorker;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

/** 真实登录、能力发布、MCP HTTP及typed Store上的工具等待协议；不复用旧Inbox或checkpoint身份。 */
@Tag("runtime-execution-wait")
@Tag("runtime-latency")
@Import({ChatRuntimeChannelE2ETest.LoopbackEvents.class, DeterministicModelCatalogConfiguration.class,
        com.skillforge.institution.runtime.AgentRuntimeExecutionConfiguration.class})
class ChatExecutionWaitingE2ETest extends ChatExecutionChainE2ETest {
    /** 真模型与受控MCP端点的独立链路，不重放公开MCP未知请求。 */
    @Test
    @Tag("live")
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "SDK4_LIVE_MODEL_KEY", matches = ".+")
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "SKILLFORGE_LIVE_CONTROLLED_MCP", matches = "1")
    void liveDeepSeekControlledMcpAndConversationReplay() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            com.skillforge.runtime.api.port.ModelPort.ModelCall call = invocation.getArgument(0);
            if (tenantId.equals(call.target().binding().tenantId().value()) && calls.incrementAndGet() > 3)
                throw new IllegalStateException("live_controlled_mcp_model_call_limit_exceeded");
            return invocation.callRealMethod();
        }).when(liveModelPort).forDispatch(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        try (var server = new RuntimeExecutionMcpWaitServer(objectMapper)) {
            server.start(); login(); mountMcpWaitTool(server, false);
            String ticket = "live-query-" + SnowflakeIds.nextId();
            var script = server.register(ticket, RuntimeExecutionMcpWaitServer.Mode.READ_QUERY);
            String firstRun = "run:" + runId;
            var firstPayload = chatPayload("请调用 runtime_wait 工具一次，ticket 参数必须为 " + ticket
                    + "。根据返回的 rendered 值作答，只输出这个值，不要调用其他工具。");
            var first = send("/api/v1/chat", "POST", firstPayload, accessToken);
            assertEquals(202, first.statusCode(), first.body());
            var firstSse = observeSse(sessionId, null, firstRun);
            assertTrue(firstSse.events().contains("execution.completed"), firstSse.raw());
            assertTrue(liveFinalText(firstSse, firstRun).contains("QUERY-7"), firstSse.raw());
            awaitRuntimeCondition("controlled tool run and usage", () -> executionCompleted(firstRun) && liveUsage(firstRun).size() == 2);
            var facts = conversationInvocationSnapshot(firstRun);
            var usage = liveUsage(firstRun);
            assertEquals(3, facts.size());
            assertEquals(2, facts.stream().filter(row -> "MODEL".equals(row.get("kind"))).count());
            assertEquals(1, facts.stream().filter(row -> "TOOL".equals(row.get("kind"))).count());
            assertTrue(facts.stream().allMatch(row -> "SUCCEEDED".equals(row.get("status"))
                    && "SUCCEEDED".equals(row.get("result_kind")) && Boolean.TRUE.equals(row.get("adopted"))));
            assertTrue(usage.stream().allMatch(row -> "deepseek-flash".equals(row.get("model"))
                    && "PROVIDER_REPORTED".equals(row.get("usage_source"))));
            assertEquals(1, script.calls.get());
            assertEquals(2, calls.get());
            var history = successData(get("/api/v1/chat/history?session_id=" + sessionId));
            String cursor = history.path("event_cursor").asText();
            assertFalse(cursor.isBlank());
            var replay = send("/api/v1/chat", "POST", firstPayload, accessToken);
            assertTrue(successData(replay).path("replayed").asBoolean());
            assertEquals(receiptWithoutReplay(first), receiptWithoutReplay(replay));
            runId = "live-controlled-followup-" + SnowflakeIds.nextId();
            String secondRun = "run:" + runId;
            var followup = chatPayload("请复述上一轮回答中的完整值，不调用工具。");
            var second = send("/api/v1/chat", "POST", followup, accessToken);
            assertEquals(202, second.statusCode(), second.body());
            var secondSse = observeSse(sessionId, cursor, secondRun);
            assertTrue(secondSse.events().contains("execution.completed"), secondSse.raw());
            assertTrue(liveFinalText(secondSse, secondRun).contains("QUERY-7"), secondSse.raw());
            awaitRuntimeCondition("controlled followup and usage", () -> executionCompleted(secondRun) && liveUsage(secondRun).size() == 1);
            assertConversationInvocation(secondRun);
            var nextFacts = conversationInvocationSnapshot(secondRun);
            var nextUsage = liveUsage(secondRun);
            var nextReplay = send("/api/v1/chat", "POST", followup, accessToken);
            assertTrue(successData(nextReplay).path("replayed").asBoolean());
            assertEquals(receiptWithoutReplay(second), receiptWithoutReplay(nextReplay));
            assertEquals(facts, conversationInvocationSnapshot(firstRun));
            assertEquals(usage, liveUsage(firstRun));
            assertEquals(nextFacts, conversationInvocationSnapshot(secondRun));
            assertEquals(nextUsage, liveUsage(secondRun));
            assertEquals(1, script.calls.get());
            assertEquals(3, calls.get());
        }
    }
    /** 确定失败不重放旧Attempt；真实MCP正文保存后以有序事实识别相同周期。 */
    @Test
    void repeatedMcpFailurePersistsOriginalFactsAndStopsAtFive() throws Exception {
        try (var server = new RuntimeExecutionMcpWaitServer(objectMapper)) {
            server.start(); login(); mountMcpWaitTool(server, false);
            String ticket = "repeat-" + SnowflakeIds.nextId();
            var script = server.register(ticket, RuntimeExecutionMcpWaitServer.Mode.REPEATED_READ_FAILURE);
            provider.enableRepeatedMcp(ticket);
            var accepted = send("/api/v1/chat", "POST", chatPayload("调用已挂载的受控只读工具。"), accessToken);
            assertEquals(202, accepted.statusCode(), accepted.body());
            var actualRun = successData(accepted).path("execution_id").asText();
            var capture = observeSseUntil(sessionId, null, actualRun, Set.of("execution.completed", "error"));
            assertTrue(capture.events().contains("execution.completed"), capture.raw());
            assertEquals(5, provider.requestCount()); assertEquals(5, script.calls.get());
            assertEquals(5, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='MODEL'", tenantId, actualRun));
            assertEquals(5, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='TOOL' AND status='FAILED'", tenantId, actualRun));
            var rows = queryRows(jdbc, tenantId, "SELECT a.action_id,a.action_ordinal,t.attempt_id,t.result_kind,t.adopted,req.schema_id AS request_schema,req.canonical_json AS request_json,res.schema_id AS result_schema,res.canonical_json AS result_json FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.action_id=a.action_id JOIN agent_runtime_execution_materials req ON req.tenant_id=t.tenant_id AND req.reference=t.request_ref JOIN agent_runtime_execution_materials res ON res.tenant_id=t.tenant_id AND res.reference=t.result_ref WHERE a.tenant_id=? AND a.run_id=? AND a.kind='TOOL' ORDER BY a.action_ordinal LIMIT 10", tenantId, actualRun);
            assertEquals(5, rows.size());
            var codec = new com.skillforge.runtime.session.AgentExecutionCodec(new com.skillforge.runtime.json.CodecLimits(262144, 20000, 64));
            com.skillforge.runtime.api.TypedPayload expected = null;
            for (var row : rows) {
                var request = codec.decodeTool(new com.skillforge.runtime.api.TypedPayload(row.get("request_schema").toString(), row.get("request_json").toString()));
                assertEquals(com.skillforge.runtime.api.ToolEffect.READ_ONLY, request.target().effect());
                assertEquals(com.skillforge.runtime.api.port.ToolPortal.ToolCallIdentityPolicy.ARGUMENTS, request.target().callIdentityPolicy());
                var outcome = assertInstanceOf(com.skillforge.runtime.api.port.ToolPortal.ToolOutcome.Failed.class,
                        codec.decodeToolOutcome(new com.skillforge.runtime.api.TypedPayload(row.get("result_schema").toString(), row.get("result_json").toString())));
                var fact = outcome.comparisonFact().orElseThrow();
                assertTrue(fact.canonicalJson().contains("same-private-error"));
                if (expected == null) expected = fact; else assertEquals(expected, fact);
                assertEquals("FAILED", row.get("result_kind")); assertEquals(Boolean.TRUE, row.get("adopted"));
            }
            var state = queryRows(jdbc, tenantId, "SELECT r.status,r.phase,m.canonical_json FROM agent_runtime_execution_runs r JOIN agent_runtime_execution_materials m ON m.tenant_id=r.tenant_id AND m.reference=r.context_ref WHERE r.tenant_id=? AND r.run_id=? LIMIT 1", tenantId, actualRun).getFirst();
            assertEquals("TERMINATED", state.get("status")); assertEquals("FINISHED", state.get("phase"));
            var delta = objectMapper.readTree(state.get("canonical_json").toString());
            assertEquals("tool-progress-stop", delta.path("type").asText());
            var progress = objectMapper.readTree(delta.path("progress").path("canonicalJson").asText());
            assertEquals("STOP", progress.path("level").asText()); assertEquals(5, progress.path("sources").size());
            var location = System.getenv("SKILLFORGE_PROGRESS_EVIDENCE_DIR");
            if (location != null && !location.isBlank()) {
                var directory = Path.of(location); Files.createDirectories(directory);
                Files.writeString(directory.resolve("controlled-mcp-state.json"), objectMapper.writeValueAsString(Map.of(
                        "run", actualRun, "session", sessionId, "status", state.get("status"), "modelActions", 5, "toolAttempts", 5,
                        "samePrivateFacts", true, "decision", "STOP")), StandardCharsets.UTF_8);
            }
        }
    }

    /** AskUser是本地等待：原模型结果成功，无额外外调Attempt；准确回复后下一模型收到原问题与原回答。 */
    @Test
    void humanAskUserWaitResumesOriginalRunWithoutInventingProviderAttempt() throws Exception {
        Path evidence = Path.of("build", "reports", "runtime-execution-human", "ask-user-" + SnowflakeIds.nextId());
        Files.createDirectories(evidence);
        var report = new LinkedHashMap<String, Object>(); report.put("outcome", "FAILED");
        report.put("scenario", "ask_user local wait; exact stable human input; no external continuation attempt");
        String actualRun = "run:" + runId;
        try (var provider = new RuntimeExecutionWorkloadProvider(objectMapper)) {
            provider.start(); TenantContext.setTenantId(tenantId);
            try { configureTenantModel(provider.endpoint()); } finally { TenantContext.clear(); }
            login();
            var script = provider.register("human-" + SnowflakeIds.nextId(), RuntimeExecutionWorkloadProvider.Workload.HUMAN_WAIT);
            report.put("run", actualRun); report.put("session", sessionId);
            try {
                var accepted = send("/api/v1/chat", "POST", chatPayload(script.prompt()), accessToken);
                assertEquals(202, accepted.statusCode(), accepted.body());
                assertEquals(actualRun, successData(accepted).path("execution_id").asText(), accepted.body());
                var waitingSse = observeSseUntil(sessionId, null, actualRun, Set.of("hitl.required", "execution.completed", "error"));
                saveSse(evidence, "waiting", waitingSse);
                var waiting = publicWait(waitingSse);
                assertEquals("user_input", waiting.path("interaction_type").asText());
                assertEquals(script.question(), waiting.path("question").asText());
                assertEquals("user_input", waiting.path("card").path("type").asText());
                assertEquals(1, script.snapshot().requestCount());
                assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? "
                        + "AND phase='WAITING' AND status='SUSPENDED' AND lease_claim_id IS NULL", tenantId, actualRun));
                assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=? "
                        + "AND adopted AND result_kind='SUCCEEDED'", tenantId, actualRun));
                assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=?", tenantId, actualRun));
                assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_waits WHERE tenant_id=? AND run_id=? "
                        + "AND kind='HUMAN' AND input_id IS NULL AND continuation_attempt IS NULL AND consumed_revision IS NULL", tenantId, actualRun));
                String responseInput = "human-response-" + runId;
                var reply = chatPayload(script.humanReply()); reply.put("client_message_id", responseInput);
                reply.put("hitl_response", Map.of("execution_id", actualRun, "run_revision", waiting.path("run_revision").asText(),
                        "human_input", Map.of("wait", Map.of("action_id", waiting.path("tool_wait").path("action_id").asText(),
                                "generation", waiting.path("tool_wait").path("generation").asText()), "text", script.humanReply())));
                var response = send("/api/v1/chat", "POST", reply, accessToken);
                assertEquals(202, response.statusCode(), response.body()); assertFalse(successData(response).path("replayed").asBoolean());
                assertEquals(actualRun, successData(response).path("execution_id").asText(), response.body());
                var completed = observeSseUntil(sessionId, null, actualRun, Set.of("execution.completed", "error"));
                saveSse(evidence, "complete", completed);
                assertTrue(completed.events().contains("execution.completed"), completed.raw());
                assertFalse(completed.events().contains("error"), completed.raw()); assertTrue(completed.raw().contains(script.finalAnswer()), completed.raw());
                awaitFact("two actual model responses complete", () -> script.snapshot().requests().size() == 2);
                assertEquals(2, script.snapshot().requestCount()); assertEquals(1, script.snapshot().verifiedHumanReplies());
                assertEquals(0, script.snapshot().verifiedToolResults());
                assertEquals(2, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='MODEL' AND status='SUCCEEDED'", tenantId, actualRun));
                assertEquals(0, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='TOOL'", tenantId, actualRun));
                assertEquals(2, countRuntime("SELECT count(*) FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=? AND adopted AND result_kind='SUCCEEDED'", tenantId, actualRun));
                assertEquals(2, countRuntime("SELECT count(*) FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=?", tenantId, actualRun));
                assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_waits WHERE tenant_id=? AND run_id=? "
                        + "AND kind='HUMAN' AND input_id=? AND actor_ref IS NOT NULL AND continuation_attempt IS NULL "
                        + "AND consumed_revision>accepted_revision", tenantId, actualRun, responseInput));
                var duplicate = send("/api/v1/chat", "POST", reply, accessToken);
                assertEquals(202, duplicate.statusCode(), duplicate.body()); assertTrue(successData(duplicate).path("replayed").asBoolean());
                assertEquals(actualRun, successData(duplicate).path("execution_id").asText(), duplicate.body());
                assertEquals(2, script.snapshot().requestCount());
                assertNoOldGraph(actualRun); report.put("outcome", "PASS");
            } finally { report.put("provider", script.snapshot()); }
        } catch (Exception | AssertionError failed) { report.put("failureType", failed.getClass().getName()); throw failed; }
        finally {
            Files.writeString(evidence.resolve("report.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            System.out.println("RUNTIME_EXECUTION_HUMAN_REPORT " + evidence.resolve("report.json").toAbsolutePath());
        }
    }

    /** 页面断开后等待仍存在；过期身份被拒绝，准确续答与稳定输入重复均只完成原工具一次。 */
    @Test
    void exactMcpWaitResponseResumesOriginalRunAndDuplicateDoesNotReplay() throws Exception {
        withWaitFixture("exact", RuntimeExecutionMcpWaitServer.Mode.WAIT, fixture -> {
            var initial = submitUntilWaiting(fixture);
            var wait = publicWait(initial);
            assertWaiting(fixture, wait);
            String revision = wait.path("run_revision").asText();
            String generation = wait.path("tool_wait").path("generation").asText();
            var staleRevision = reply(wait, "stale-revision-" + runId,
                    Long.toString(Long.parseLong(revision) + 1), generation);
            assertRejected(send("/api/v1/chat", "POST", staleRevision, accessToken));
            var staleGeneration = reply(wait, "stale-generation-" + runId, revision,
                    Long.toString(Long.parseLong(generation) + 1));
            assertRejected(send("/api/v1/chat", "POST", staleGeneration, accessToken));
            var nonCanonical = reply(wait, "noncanonical-" + runId, "0" + revision, generation);
            assertEquals(422, send("/api/v1/chat", "POST", nonCanonical, accessToken).statusCode());
            assertWaiting(fixture, wait);
            // 初次订阅已关闭，重新连接真实持久SSE仍可取得同一等待身份。
            var reconnect = observeSseUntil(sessionId, null, fixture.actualRun(), Set.of("hitl.required", "error"));
            assertEquals(wait, publicWait(reconnect));
            var body = reply(wait, "response-" + runId, revision, generation);
            var accepted = send("/api/v1/chat", "POST", body, accessToken);
            assertEquals(202, accepted.statusCode(), accepted.body());
            assertFalse(successData(accepted).path("replayed").asBoolean(), accepted.body());
            assertEquals(fixture.actualRun(), successData(accepted).path("execution_id").asText(), accepted.body());
            var complete = observeSseUntil(sessionId, null, fixture.actualRun(), Set.of("execution.completed", "error"));
            saveSse(fixture.evidence(), "complete", complete);
            assertTrue(complete.events().contains("execution.completed"), complete.raw());
            assertTrue(complete.raw().contains(fixture.model().finalAnswer()), complete.raw());
            awaitFact("MCP handler returns", () -> fixture.server().requests().stream()
                    .filter(request -> fixture.key().equals(request.ticket())).count() == 2);
            assertEquals(2, fixture.model().snapshot().requestCount());
            assertEquals(1, fixture.model().snapshot().verifiedToolResults());
            assertEquals(2, fixture.script().calls.get());
            assertEquals(1, fixture.script().effects.get());
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_waits "
                    + "WHERE tenant_id=? AND run_id=? AND input_id=? AND actor_ref IS NOT NULL "
                    + "AND continuation_attempt IS NOT NULL", tenantId, fixture.actualRun(), "response-" + runId));
            assertEquals(2, countRuntime("SELECT count(*) FROM agent_runtime_execution_attempts t "
                    + "JOIN agent_runtime_execution_actions a ON a.tenant_id=t.tenant_id AND a.action_id=t.action_id "
                    + "WHERE t.tenant_id=? AND t.run_id=? AND a.kind='TOOL'", tenantId, fixture.actualRun()));
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_attempts "
                    + "WHERE tenant_id=? AND run_id=? AND result_kind='INPUT_REQUIRED' AND adopted", tenantId, fixture.actualRun()));
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs "
                    + "WHERE tenant_id=? AND run_id=? AND status='COMPLETED' AND phase='FINISHED'", tenantId, fixture.actualRun()));
            var replay = send("/api/v1/chat", "POST", body, accessToken);
            assertEquals(202, replay.statusCode(), replay.body());
            assertTrue(successData(replay).path("replayed").asBoolean(), replay.body());
            assertEquals(fixture.actualRun(), successData(replay).path("execution_id").asText(), replay.body());
            assertEquals(2, fixture.script().calls.get());
            assertEquals(1, fixture.script().effects.get());
            assertEquals(2, fixture.model().snapshot().requestCount());
            assertNoOldGraph(fixture.actualRun());
        });
    }

    /** 取消等待是持久控制；后续准确但已失效的回复不能派发continuation或第二轮模型。 */
    @Test
    void cancelledWaitingRunRejectsContinuation() throws Exception {
        withWaitFixture("cancel-wait", RuntimeExecutionMcpWaitServer.Mode.WAIT, fixture -> {
            var waiting = publicWait(submitUntilWaiting(fixture));
            assertWaiting(fixture, waiting);
            var cancel = send("/api/v1/chat/runs/cancel", "POST",
                    Map.of("session_id", sessionId, "run_id", fixture.actualRun()), accessToken);
            assertEquals(200, cancel.statusCode(), cancel.body());
            assertEquals("cancelled", successData(cancel).path("status").asText());
            var cancelled = observeSseUntil(sessionId, null, fixture.actualRun(), Set.of("execution.completed", "error"));
            saveSse(fixture.evidence(), "cancelled", cancelled);
            assertTrue(cancelled.events().contains("execution.completed"), cancelled.raw());
            assertTrue(cancelled.raw().contains("cancelled"), cancelled.raw());
            var response = reply(waiting, "cancelled-response-" + runId, waiting.path("run_revision").asText(),
                    waiting.path("tool_wait").path("generation").asText());
            assertRejected(send("/api/v1/chat", "POST", response, accessToken));
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs "
                    + "WHERE tenant_id=? AND run_id=? AND cancelled AND status='CANCELLED' AND phase='FINISHED'", tenantId, fixture.actualRun()));
            assertEquals(0, countRuntime("SELECT count(*) FROM agent_runtime_execution_waits "
                    + "WHERE tenant_id=? AND run_id=? AND input_id IS NOT NULL", tenantId, fixture.actualRun()));
            assertEquals(1, fixture.script().calls.get());
            assertEquals(0, fixture.script().effects.get());
            assertEquals(1, fixture.model().snapshot().requestCount());
            assertNoOldGraph(fixture.actualRun());
        });
    }

    /** MCP已接收原调用但丢失响应，状态查询及重复唤醒均不能重放非幂等调用。 */
    @Test
    void unknownMcpResultRemainsUncertainAndNeverReplays() throws Exception {
        withWaitFixture("unknown", RuntimeExecutionMcpWaitServer.Mode.DROP_INITIAL_RESULT, fixture -> {
            var accepted = send("/api/v1/chat", "POST", chatPayload(fixture.model().prompt()), accessToken);
            assertEquals(202, accepted.statusCode(), accepted.body());
            assertTrue(fixture.script().awaitInitial(Duration.ofSeconds(30)), "原MCP请求必须真实到达");
            awaitFact("original MCP result is uncertain", () -> countRuntime("SELECT count(*) FROM agent_runtime_execution_runs "
                    + "WHERE tenant_id=? AND run_id=? AND phase='UNCERTAIN'", tenantId, fixture.actualRun()) == 1);
            var worker = applicationContext.getBean(AgentRuntimeExecutionWorker.class);
            for (int index = 0; index < 3; index++) {
                worker.wake(new RunRef(new TenantId(tenantId), fixture.actualRun()));
                var status = get("/api/v1/chat/runs/status?session_id=" + sessionId + "&run_id=" + fixture.actualRun());
                assertEquals(200, status.statusCode(), status.body());
            }
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_attempts t "
                    + "JOIN agent_runtime_execution_actions a ON a.tenant_id=t.tenant_id AND a.action_id=t.action_id "
                    + "WHERE t.tenant_id=? AND t.run_id=? AND a.kind='TOOL' AND t.result_kind='UNKNOWN'", tenantId, fixture.actualRun()));
            assertEquals(1, fixture.script().calls.get());
            assertEquals(0, fixture.script().effects.get());
            assertEquals(1, fixture.model().snapshot().requestCount());
            assertEquals(0, countRuntime("SELECT count(*) FROM agent_runtime_execution_events "
                    + "WHERE tenant_id=? AND run_id=? AND type='execution.completed'", tenantId, fixture.actualRun()));
            assertNoOldGraph(fixture.actualRun());
        });
    }

    /** 写工具的结构化422保留原Attempt身份，安全反馈实际进入下一次模型请求。 */
    @Test
    void writeMcpArgumentRejectionReachesNextModelWithoutRedispatch() throws Exception {
        withWaitFixture("write-rejected", RuntimeExecutionMcpWaitServer.Mode.WRITE_ARGUMENTS_REJECTED, fixture -> {
            var input = chatPayload(fixture.model().prompt());
            var accepted = send("/api/v1/chat", "POST", input, accessToken);
            assertEquals(202, accepted.statusCode(), accepted.body());
            assertEquals(fixture.actualRun(), successData(accepted).path("execution_id").asText());
            awaitFact("write failure and next model complete", () -> countRuntime("SELECT count(*) FROM agent_runtime_execution_runs "
                    + "WHERE tenant_id=? AND run_id=? AND status='COMPLETED' AND phase='FINISHED'", tenantId, fixture.actualRun()) == 1);
            awaitFact("model and MCP handlers finish", () -> fixture.model().snapshot().requests().size() == 2
                    && fixture.server().requests().stream().anyMatch(request -> "tools/call".equals(request.method())));
            var run = new RunRef(new TenantId(tenantId), fixture.actualRun());
            var store = applicationContext.getBean(com.skillforge.runtime.session.spi.ExecutionStore.class);
            var actions = store.actions(run, 0, 100);
            var tools = actions.stream().filter(action -> action.kind() == com.skillforge.runtime.session.spi.ExecutionStore.Kind.TOOL).toList();
            assertEquals(1, tools.size());
            assertEquals(2, actions.stream().filter(action -> action.kind() == com.skillforge.runtime.session.spi.ExecutionStore.Kind.MODEL).count());
            var attempt = store.attempt(run, tools.getFirst().selectedAttempt().orElseThrow()).orElseThrow();
            assertTrue(attempt.adopted());
            assertEquals(com.skillforge.runtime.session.spi.ExecutionStore.ResultKind.FAILED, attempt.resultKind().orElseThrow());
            var properties = applicationContext.getBean(com.skillforge.runtime.starter.AgentRuntimeProperties.class);
            var codec = new com.skillforge.runtime.session.AgentExecutionCodec(new com.skillforge.runtime.json.CodecLimits(
                    properties.getMaxPayloadCodeUnits(), properties.getMaxPayloadNodes(), properties.getMaxPayloadDepth()));
            var request = codec.decodeTool(store.material(run.tenantId(), attempt.request()).orElseThrow().content());
            assertEquals(com.skillforge.runtime.api.ToolEffect.NON_IDEMPOTENT_WRITE, request.target().effect());
            assertFalse(request.target().idempotent());
            assertTrue(store.material(run.tenantId(), attempt.result().orElseThrow()).orElseThrow().content().canonicalJson()
                    .contains("mcp_tool_arguments_invalid_after_dispatch"));
            assertTrue(fixture.model().writeFailureFeedback().contains("结果引用：mcp-result:" + attempt.id()));
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_attempts t "
                    + "JOIN agent_runtime_execution_actions a ON a.tenant_id=t.tenant_id AND a.action_id=t.action_id "
                    + "WHERE t.tenant_id=? AND t.run_id=? AND a.kind='TOOL'", tenantId, fixture.actualRun()));
            assertEquals(1, fixture.script().calls.get());
            assertEquals(1, fixture.server().requests().stream().filter(observed -> "tools/call".equals(observed.method())
                    && observed.httpStatus() == 200 && observed.failure().isEmpty()).count());
            assertEquals(1, fixture.model().snapshot().verifiedToolResults());
            assertTrue(fixture.model().snapshot().requests().stream().allMatch(timing -> timing.failureReason().isEmpty()));
            var replay = send("/api/v1/chat", "POST", input, accessToken);
            assertEquals(202, replay.statusCode(), replay.body());
            assertTrue(successData(replay).path("replayed").asBoolean());
            assertEquals(fixture.actualRun(), successData(replay).path("execution_id").asText());
            assertEquals(actions, store.actions(run, 0, 100));
            assertEquals(1, fixture.script().calls.get());
            assertEquals(2, fixture.model().snapshot().requestCount());
            assertNoOldGraph(fixture.actualRun());
        });
    }

    /** 每个场景独立真实Provider及MCP listener；报告包含所有原RPC观测及最终持久事实。 */
    private void withWaitFixture(String scenario, RuntimeExecutionMcpWaitServer.Mode mode, WaitCase test) throws Exception {
        Path evidence = Path.of("build", "reports", "runtime-execution-wait", scenario + "-" + SnowflakeIds.nextId());
        Files.createDirectories(evidence);
        var report = new LinkedHashMap<String, Object>();
        report.put("scenario", scenario); report.put("outcome", "FAILED");
        report.put("recoveryScope", mode == RuntimeExecutionMcpWaitServer.Mode.WRITE_ARGUMENTS_REJECTED
                ? "local controlled write rejection and duplicate input; no external supplier or restart claim"
                : "same server persisted wait and reconnect; no process restart claim");
        try (var provider = new RuntimeExecutionWorkloadProvider(objectMapper);
                var server = new RuntimeExecutionMcpWaitServer(objectMapper, mode == RuntimeExecutionMcpWaitServer.Mode.WRITE_ARGUMENTS_REJECTED)) {
            provider.start(); server.start();
            TenantContext.setTenantId(tenantId);
            try { configureTenantModel(provider.endpoint()); } finally { TenantContext.clear(); }
            login(); mountMcpWaitTool(server, mode == RuntimeExecutionMcpWaitServer.Mode.WRITE_ARGUMENTS_REJECTED);
            String key = scenario + "-" + SnowflakeIds.nextId();
            var script = server.register(key, mode);
            var model = provider.register(key, mode == RuntimeExecutionMcpWaitServer.Mode.WRITE_ARGUMENTS_REJECTED
                    ? RuntimeExecutionWorkloadProvider.Workload.MCP_WRITE_REJECTED : RuntimeExecutionWorkloadProvider.Workload.MCP_WAIT);
            var fixture = new WaitFixture(key, "run:" + runId, server, script, model, evidence);
            report.put("run", fixture.actualRun()); report.put("session", sessionId);
            try { test.run(fixture); report.put("outcome", "PASS"); }
            catch (Exception | AssertionError failure) { report.put("failureType", failure.getClass().getName()); throw failure; }
            finally {
                report.put("provider", model.snapshot()); report.put("mcpRequests", server.requests());
                report.put("mcpCalls", script.calls.get()); report.put("mcpEffects", script.effects.get());
                report.put("runFacts", queryRows(jdbc, tenantId, "SELECT status,phase,revision,cancelled,waiting_action_id,waiting_generation "
                        + "FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=?", tenantId, fixture.actualRun()));
            }
        } finally {
            Files.writeString(evidence.resolve("report.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            System.out.println("RUNTIME_EXECUTION_WAIT_REPORT " + evidence.resolve("report.json").toAbsolutePath());
        }
    }

    /** 实际公开目录发现、版本校验发布、Deployment挂载及Agent发布均在计时外执行。 */
    protected void mountMcpWaitTool(RuntimeExecutionMcpWaitServer server, boolean write) throws Exception {
        var revise = send("/api/v1/agents/revise", "POST", Map.of("agent_id", agentId), accessToken);
        agentId = successData(revise).path("id").asText();
        var connector = send("/api/v1/connectors/instances", "POST", Map.of("executor_type", "mcp", "name", "Runtime wait fixture",
                "base_url", server.endpoint(), "auth_type", "none", "headers", Map.of("MCP-Protocol-Version", RuntimeExecutionMcpWaitServer.VERSION)), accessToken);
        assertEquals(200, connector.statusCode(), connector.body());
        String providerRef = successData(connector).path("instance_id").asText();
        assertFalse(providerRef.isBlank(), connector.body());
        String key = "runtime-wait-" + SnowflakeIds.nextId();
        var descriptor = Map.of("input_schema", RuntimeExecutionMcpWaitServer.inputSchema(), "output_schema", RuntimeExecutionMcpWaitServer.outputSchema(),
                "effect", write ? "NON_IDEMPOTENT_WRITE" : "READ_ONLY", "idempotent", false, "timeout_ms", 30000, "tool_name", RuntimeExecutionMcpWaitServer.TOOL,
                "model_invocable", true, "user_invocable", false, "confirmation_policy", "NEVER");
        var definitionResponse = send("/api/v1/capabilities/definitions", "POST", Map.of("capability_type", "TOOL", "capability_key", key,
                "name", "Runtime MCP wait", "description", "真实MCP表单等待续答"), accessToken);
        assertEquals(201, definitionResponse.statusCode(), definitionResponse.body());
        long definition = successData(definitionResponse).path("id").asLong();
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(descriptor)));
        var draft = send("/api/v1/capabilities/definitions/versions", "POST", Map.of("definition_id", definition,
                "payload", Map.of("version_key", "v1", "name", "Runtime MCP wait", "description", "用准确ticket等待quantity并返回实际结果",
                        "content_hash", hash, "descriptor", descriptor)), accessToken);
        assertEquals(201, draft.statusCode(), draft.body());
        long version = successData(draft).path("id").asLong();
        var validated = send("/api/v1/capabilities/definitions/versions/validate", "POST", Map.of("definition_id", definition, "version_id", version), accessToken);
        assertTrue(successData(validated).path("valid").asBoolean(), validated.body());
        var publish = httpClient.send(HttpRequest.newBuilder().uri(URI.create(baseUrl() + "/api/v1/capabilities/definitions/versions/publish"))
                .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + accessToken).header("Content-Type", "application/json")
                .header("If-Match", successData(validated).path("definition_revision").asText())
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of("definition_id", definition, "version_id", version))))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, publish.statusCode(), publish.body());
        var deployment = send("/api/v1/capabilities/deployments", "POST", Map.of("deployment_key", key, "tool_version_id", version,
                "provider_type", "MCP", "provider_ref", providerRef, "environment", "TEST", "status", "ACTIVE"), accessToken);
        assertEquals(201, deployment.statusCode(), deployment.body());
        var mount = send("/api/v1/agents/capability-mounts", "POST", Map.of("agent_id", agentId, "payload", Map.of("capability_type", "TOOL",
                "capability_definition_id", Long.toString(definition), "capability_version_id", Long.toString(version), "tool_config", Map.of("tool_deployment_id", key,
                        "tool_deployment_revision", successData(deployment).path("revision").asLong()), "priority", 0)), accessToken);
        assertEquals(201, mount.statusCode(), mount.body());
        assertEquals(200, send("/api/v1/agents/publish", "POST", Map.of("agent_id", agentId), accessToken).statusCode());
        var session = send("/api/v1/chat/sessions", "POST", Map.of("agent_id", agentId, "end_user_id", endUserId), accessToken);
        sessionId = successData(session).path("session_id").asText();
        assertFalse(sessionId.isBlank());
    }

    /** 受理和观测使用原Run；首个hitl.required返回即关闭本订阅。 */
    private SseCapture submitUntilWaiting(WaitFixture fixture) throws Exception {
        var accepted = send("/api/v1/chat", "POST", chatPayload(fixture.model().prompt()), accessToken);
        assertEquals(202, accepted.statusCode(), accepted.body());
        assertEquals(fixture.actualRun(), successData(accepted).path("execution_id").asText(), accepted.body());
        var capture = observeSseUntil(sessionId, null, fixture.actualRun(), Set.of("hitl.required", "execution.completed", "error"));
        saveSse(fixture.evidence(), "waiting", capture);
        assertTrue(capture.events().contains("hitl.required"), capture.raw());
        return capture;
    }

    /** 公共字段不携带旧pending/checkpoint/wake身份；等待版本必须按字符串精确保留。 */
    private JsonNode publicWait(SseCapture capture) throws Exception {
        String event = null;
        for (String line : capture.raw().split("\\R")) {
            if (line.startsWith("event:")) event = line.substring(6).trim();
            else if (line.startsWith("data:") && "hitl.required".equals(event)) {
                var wait = objectMapper.readTree(line.substring(5).trim());
                assertTrue(wait.path("execution_id").isTextual());
                assertTrue(wait.path("run_revision").isTextual());
                assertTrue(wait.path("tool_wait").path("generation").isTextual());
                assertFalse(wait.has("checkpoint_revision")); assertFalse(wait.has("pending_ref")); assertFalse(wait.has("wake_ref"));
                return wait;
            }
        }
        throw new AssertionError("缺少真实hitl.required: " + capture.raw());
    }

    /** 原等待与原已采纳input_required结果构成权威，页面关闭没有触发第二轮模型。 */
    private void assertWaiting(WaitFixture fixture, JsonNode wait) {
        assertEquals(fixture.actualRun(), wait.path("execution_id").asText());
        assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? "
                + "AND phase='WAITING' AND status='SUSPENDED' AND lease_claim_id IS NULL AND revision=? "
                + "AND waiting_action_id=? AND waiting_generation=?", tenantId, fixture.actualRun(), Long.parseLong(wait.path("run_revision").asText()),
                wait.path("tool_wait").path("action_id").asText(), Long.parseLong(wait.path("tool_wait").path("generation").asText())));
        assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_waits WHERE tenant_id=? AND run_id=? "
                + "AND source_attempt IS NOT NULL AND input_id IS NULL AND continuation_attempt IS NULL", tenantId, fixture.actualRun()));
        assertEquals(1, fixture.model().snapshot().requestCount());
        assertEquals(1, fixture.script().calls.get()); assertEquals(0, fixture.script().effects.get());
    }

    /** 新稳定输入身份只承载准确等待与响应，不允许客户端携带业务授权。 */
    private Map<String, Object> reply(JsonNode wait, String input, String revision, String generation) {
        var body = chatPayload("quantity=27"); body.put("client_message_id", input);
        body.put("hitl_response", Map.of("execution_id", wait.path("execution_id").asText(), "run_revision", revision,
                "tool_input", Map.of("wait", Map.of("action_id", wait.path("tool_wait").path("action_id").asText(), "generation", generation),
                        "responses", Map.of("quantity", Map.of("method", "elicitation/create", "result", Map.of("schema_id", "application/json",
                                "canonical_json", "{\"action\":\"accept\",\"content\":{\"quantity\":27}}"))))));
        return body;
    }

    /** 公开拒绝不能成为成功受理或服务端500。 */
    private static void assertRejected(HttpResponse<String> response) { assertTrue(response.statusCode() >= 400 && response.statusCode() < 500, response.body()); }

    /** 只等待本场景的权威事实，不重试HTTP业务调用。 */
    private static void awaitFact(String reason, BooleanSupplier condition) throws Exception {
        long limit = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        while (System.nanoTime() < limit) { if (condition.getAsBoolean()) return; Thread.sleep(20); }
        throw new AssertionError(reason);
    }

    /** 证据使用独立目录CREATE_NEW，不能覆盖失败场景。 */
    private static void saveSse(Path evidence, String name, SseCapture capture) throws Exception {
        Files.writeString(evidence.resolve(name + ".sse.txt"), capture.raw(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    /** 本候选不能通过旧Session图产生事实。 */
    private void assertNoOldGraph(String actualRun) {
        for (String table : List.of("agent_runtime_runs", "agent_runtime_actions", "agent_runtime_session_events",
                "nw_runtime_session_model_operations", "nw_runtime_session_tool_operations"))
            assertEquals(0, countRuntime("SELECT count(*) FROM " + table + " WHERE tenant_id=? AND run_id=?", tenantId, actualRun), table);
    }

    @FunctionalInterface private interface WaitCase { void run(WaitFixture fixture) throws Exception; }
    private record WaitFixture(String key, String actualRun, RuntimeExecutionMcpWaitServer server,
            RuntimeExecutionMcpWaitServer.Script script, RuntimeExecutionWorkloadProvider.Script model, Path evidence) { }
}
