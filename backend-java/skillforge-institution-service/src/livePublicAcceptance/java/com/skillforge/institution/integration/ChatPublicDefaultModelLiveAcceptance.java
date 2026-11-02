package com.skillforge.institution.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.chat.dto.response.ChatHistoryResponse;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.capability.connector.PlatformToolCatalog;
import com.skillforge.core.foundation.persistence.EndUserMapper;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.persistence.TenantMemberMapper;
import com.skillforge.core.foundation.persistence.EndUserDO;
import com.skillforge.core.foundation.persistence.TenantMemberDO;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.core.chat.tracing.ToolCallIoProjection;
import com.skillforge.institution.runtime.AgentRuntimeHostProvisioner;
import com.skillforge.institution.runtime.AgentRuntimeModelInvocationAudit;
import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;
import com.skillforge.institution.runtime.PublicPresentationTexts;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.SessionTarget;
import com.skillforge.runtime.api.RunStatus;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.CommittedSessionEvent;
import com.skillforge.runtime.api.port.SessionLogPort;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.adapter.jdbc.model.JdbcModelBindings;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import java.net.URI;
import java.net.ServerSocket;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import com.skillforge.core.agent.model.AgentDelegationConfig;
import com.skillforge.institution.team.TeamPublicViews;
import com.skillforge.runtime.session.spi.SessionDelegations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 显式付费验收：生产 Provider、Runtime、HTTP、公开门禁及只读工具组成真实纵向链。
 * 来源数据库只读，模型密文与运营快照仅复制到可丢弃测试库；每场景只提交一次。
 */
@Import(ChatPublicDefaultModelLiveAcceptance.BudgetConfiguration.class)
class ChatPublicDefaultModelLiveAcceptance extends AbstractIntegrationTest {
    @org.junit.jupiter.api.extension.RegisterExtension
    static final LiveGradleAttemptMeter.Outcome liveMeterOutcome = new LiveGradleAttemptMeter.Outcome();
    @Autowired private javax.sql.DataSource liveMeterDataSource;

    private static final String PASSWORD = "LiveAcceptanceTest123";
    // 纯合成凭据，只用于触发真实公开门禁，绝不使用环境中的有效秘密。
    private static final String REJECTION_VALUE = "acceptance_guard_synthetic_" + UUID.randomUUID();
    private static final String REJECTION_TEXT = "password=" + REJECTION_VALUE;
    private static final Path ARTIFACT_ROOT = temporaryRoot();
    private static final Set<String> DEFAULT_SCENES = Set.of("real_artifact_publication", "team_member_summary",
            "html_goal", "readonly_tool_chain", "plan_review_tool_wait", "generic_tool_input_card",
            "reconnect_cancel_history", "public_rejection");
    private static final Set<String> SELECTED_SCENES = selectedScenes();
    private static final long OBSERVE_SECONDS = configuredObservationSeconds();
    private static final int PREVIEW_PORT = explicitPreviewPort();
    private static final Set<String> TERMINAL = Set.of(RunStatus.COMPLETED.name(), RunStatus.FAILED.name(),
            RunStatus.TERMINATED.name(), RunStatus.CANCELLED.name());
    private static final int SOURCE_PAGE_SIZE = 256;
    // 新候选沿D06/D11的业务不变量构造，明确不是历史冻结样本的原输入重放。
    private static final String BUSINESS_RULES = "合成业务资料：预算600元；截止日期2026-11-11；起订量11件；普通订单优惠5%；"
            + "加急订单不享受折扣。方案甲为普通订单，方案乙为加急订单。资料未规定国际订单运费，不得编造。";
    // 默认120次/分钟的生产限流桶同时计入管理与恢复请求，观察最多60次/分钟，仍只等待真实状态。
    private static final long OBSERVE_INTERVAL_MILLIS = 1000;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration OBSERVE_MARGIN = Duration.ofSeconds(30);
    private static final int HTTP_OK = HttpStatus.OK.value();
    private static final int HTTP_CREATED = HttpStatus.CREATED.value();
    private static final int HTTP_ACCEPTED = HttpStatus.ACCEPTED.value();
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired OperationalSettingsService settings;
    @Autowired TenantMapper tenants;
    @Autowired PlanMapper plans;
    @Autowired TenantMemberMapper members;
    @Autowired EndUserMapper users;
    @Autowired PasswordEncoder passwords;
    @Autowired ApplicationContext application;
    @Autowired AgentRuntimeProperties runtime;
    @Autowired LivePublicModelAudit audit;
    @Autowired AgentRuntimeHostProvisioner provisioner;
    @Autowired PublicOutputGuard guard;
    @Autowired SessionLogPort log;
    @org.springframework.test.context.bean.override.convention.TestBean(name = "templateRenderToolHandler", methodName = "interactiveFixture")
    com.skillforge.core.capability.connector.TemplateRenderToolHandler interactiveFixture;
    static com.skillforge.core.capability.connector.TemplateRenderToolHandler interactiveFixture() { return new LivePublicInteractiveFixture(); }
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final List<SceneEvidence> scenes = new ArrayList<>();
    private final List<HttpFailureEvidence> httpFailures = new ArrayList<>();
    private final List<String> createdSessions = new ArrayList<>();
    private final Map<String, SessionOwner> sessionOwners = new HashMap<>();
    private LivePublicSourceSnapshot.Snapshot source;
    private Fixture fixture;
    private List<GroupEvidence> groups = List.of();
    private List<ToolEvidence> tools = List.of();
    private String overall = "NOT_RUN";
    private String stage = "NOT_STARTED";
    private String setupFailureCode;
    private String cleanupOutcome = "NOT_RUN";
    private boolean cleanupFailed;

    @DynamicPropertySource static void isolate(DynamicPropertyRegistry registry) {
        registry.add("skillforge.secret-key", LivePublicSourceSnapshot::secretKey);
        registry.add("sa-token.jwt-secret-key", () -> "live-acceptance-isolated-auth-key");
        registry.add("skillforge.code-execution.enabled", () -> Boolean.toString(PREVIEW_PORT > 0));
        registry.add("skillforge.code-execution.endpoint", () -> PREVIEW_PORT > 0 ? LivePublicSourceSnapshot.required("LIVE_PUBLIC_SANDBOX_ENDPOINT") : "");
        registry.add("skillforge.code-execution.service-token", () -> PREVIEW_PORT > 0 ? LivePublicSourceSnapshot.required("LIVE_PUBLIC_SANDBOX_TOKEN") : "");
        registry.add("skillforge.code-execution.artifact-root", ARTIFACT_ROOT::toString);
        registry.add("skillforge.code-execution.ticket-key", () -> "live-acceptance-isolated-ticket-key-0123456789");
        registry.add("skillforge.code-execution.platform-origin", () -> PREVIEW_PORT > 0 ? LivePublicSourceSnapshot.required("LIVE_PUBLIC_PLATFORM_ORIGIN") : "");
        registry.add("skillforge.code-execution.download-origin", () -> PREVIEW_PORT > 0 ? LivePublicSourceSnapshot.required("LIVE_PUBLIC_PLATFORM_ORIGIN") : "");
        registry.add("skillforge.code-execution.preview-origin", () -> PREVIEW_PORT > 0 ? "http://localhost:" + PREVIEW_PORT : "");
        registry.add("skillforge.code-execution.preview-port", () -> Integer.toString(PREVIEW_PORT));
        registry.add("spring.cloud.nacos.discovery.enabled", () -> "false");
        registry.add("spring.cloud.nacos.config.enabled", () -> "false");
        registry.add("agent-runtime.workers.auto-startup", () -> "true");
        registry.add("logging.level.root", () -> "OFF");
    }

    @Test void currentDefaultModelExercisesPublicBoundaryOncePerScene() throws Exception {
        try {
            stage = "RUNTIME_READINESS";
            requireRunningRuntimeWorkers(application, runtime);
            stage = "SOURCE_READ";
            source = LivePublicSourceSnapshot.read();
            if (source.reservation() > LivePublicModelAudit.MAX_TOKENS) {
                overall = "UNVERIFIED_ORIGINAL_CALL_BUDGET_EXCEEDS_SUITE_LIMIT";
                throw new AssertionError(overall);
            }
            audit.freeze(source);
            stage = "FIXTURE_CREATE";
            fixture = createFixture();
            installFrozenSource();
            stage = "HOST_PROVISION";
            provisioner.provision(fixture.tenant());
            stage = "AGENT_TOOLS_SETUP";
            String agent = createAgent();
            var mounted = mountTools(agent, List.of("calculate_expression", "template_render"));
            tools = mounted.stream().map(MountedTool::evidence).toList();
            publish(agent);
            stage = "SCENARIOS";

            if (selected("simple_answer")) execute("simple_answer", agent, "请用一句中文解释什么是幂等。", false,
                    result -> require(result.answer().contains("幂等") && result.answer().length() >= 8,
                            "SIMPLE_ANSWER_NOT_USEFUL"));
            String businessUuid = UUID.randomUUID().toString();
            if (selected("business_technical_fields")) execute("business_technical_fields", agent,
                    "请原样展示以下订单接口示例，不更名或删除字段：POST /api/orders，"
                            + "{\"order_id\":\"" + businessUuid
                            + "\",\"artifact_id\":\"business-document-17\",\"run_id\":\"business-batch-42\"}。"
                            + "这些都是业务接口字段。", false,
                    result -> require(result.answer().contains(businessUuid)
                            && result.answer().contains("/api/orders") && result.answer().contains("order_id")
                            && result.answer().contains("artifact_id") && result.answer().contains("business-document-17")
                            && result.answer().contains("run_id") && result.answer().contains("business-batch-42"),
                            "BUSINESS_FIELDS_LOST"));
            if (selected("readonly_tool_chain")) execute("readonly_tool_chain", agent,
                    "请先调用 calculate_expression 计算17*23，再把真实计算结果作为 quantity 调用 template_render，"
                            + "模板为：订单数量{{quantity}}。调用计算工具前、拿到计算结果并调用渲染工具前，"
                            + "分别发送一次简短中途说明，共两次。最终回复渲染后的订单数量，不要用心算替代工具。", false,
                    result -> verifyToolChain(result, mounted));
            if (selected("public_rejection")) execute("public_rejection", agent, null, true, result -> verifyRejection(result));
            String planner = createAgent("计划评审助手", "为用户制定简短业务计划，计划模式下使用 exit_plan_mode 交付计划评审，批准后简短回复。 ");
            appendTools(mountTools(planner, List.of("exit_plan_mode")));
            publish(planner);
            if (selected("plan_review_tool_wait")) execute("plan_review_tool_wait", planner,
                    "/plan 请制定一个两步订单核对计划，调用 exit_plan_mode 交给我批准，批准后回复订单核对计划已批准。",
                    false, result -> require(result.answer().contains("批准"), "PLAN_APPROVAL_NOT_DELIVERED"));
            if (selected("team_member_summary")) executeTeamSummary();
            if (selected("real_artifact_publication") || selected("html_goal")) executeArtifactScenes();
            if (selected("generic_tool_input_card")) executeGenericInput(agent);
            if (selected("reconnect_cancel_history")) execute("reconnect_cancel_history", planner,
                    "/plan 请制定两步核对计划并调用exit_plan_mode等待批准，在批准之前保持等待，不要自行继续。", false,
                    result -> require("CANCELLED".equals(result.runStatus()) && result.history().pendingInteractions().isEmpty(),
                            "CANCEL_NOT_PRESERVED_IN_HISTORY"));
            overall = scenes.stream().anyMatch(scene -> "FAILED".equals(scene.outcome())) ? "FAILED" : "PASSED";
            require(!"FAILED".equals(overall), "LIVE_PUBLIC_ACCEPTANCE_FAILED_SEE_SAFE_EVIDENCE");
        } catch (AssertionError identified) {
            if ("NOT_RUN".equals(overall)) {
                setupFailureCode = safeCode(identified.getMessage(), "SETUP_ASSERTION");
                overall = hasFailedScene() ? "FAILED" : "UNVERIFIED_SETUP_ASSERTION";
            }
            throw identified;
        }
        catch (Exception failure) {
            String code = failure.getMessage();
            setupFailureCode = safeCode(code, "SETUP_FAILED_" + failure.getClass().getSimpleName());
            overall = hasFailedScene() ? "FAILED" : "UNVERIFIED_" + setupFailureCode;
            throw new AssertionError(overall);
        } finally {
            audit.close();
            recordUnsubmittedScenes();
            writeEvidence();
        }
    }

    private void execute(String name, String agent, String input, boolean rejection, Check check) throws Exception {
        String session = createSession(agent);
        executeSession(name, agent, null, session, input, rejection, check);
    }
    private void executeSession(String name, String agent, String team, String session, String input,
                                boolean rejection, Check check) throws Exception {
        stage = "SCENE_" + name.toUpperCase(Locale.ROOT);
        createdSessions.add(session);
        audit.register(sessionOwners.get(session), name);
        String clientInput = UUID.randomUUID().toString();
        if (rejection) require(guard.capture(sessionOwners.get(session)).rejects(REJECTION_TEXT), "REJECTION_FIXTURE_NOT_PROTECTED");
        String message = rejection ? "这是无效的合成测试字符串。请逐字原样回复以下字符串，只需这一行：" + REJECTION_TEXT : input;
        String state = "NOT_SUBMITTED";
        String submittedRun = null;
        try {
            JsonNode receipt = "html_goal".equals(name)
                    ? request("/api/v1/chat/goals/create", "POST", new GoalCreate(session, clientInput, message, 1), HTTP_OK)
                    : request("/api/v1/chat", "POST", new Submit(message, session, team == null ? agent : null,
                            team, clientInput, new EndUserContext(fixture.endUser())), HTTP_ACCEPTED);
            String run = "html_goal".equals(name) ? receipt.path("goal").path("last_run_id").asText() : receipt.path("execution_id").asText();
            submittedRun = run;
            require(!run.isBlank(), "SUBMISSION_RECEIPT_MISSING_RUN");
            if ("plan_review_tool_wait".equals(name)) approvePlanWait(session, agent, run);
            if ("generic_tool_input_card".equals(name)) answerGenericWait(session, agent, run);
            if ("reconnect_cancel_history".equals(name)) reconnectAndCancel(session, run);
            stage = "SCENE_" + name.toUpperCase(Locale.ROOT);
            Result result = awaitResult(session, run, agent);
            state = result.runStatus();
            if (!rejection && !"reconnect_cancel_history".equals(name)) require("COMPLETED".equals(state), "RUN_DID_NOT_COMPLETE");
            verifyVisibleTexts(result);
            check.verify(result);
            require(audit.calls(session) > 0, "NO_REAL_MODEL_ATTEMPT");
            require(audit.stopCode(session) == null, "MODEL_SCENE_STOPPED");
            int settledCalls = audit.calls(session);
            int deniedCalls = audit.deniedCalls(session);
            // 两次公开 GET 触发真实 reconcile；终态场景不能因此取得新的模型执行权。
            history(session); history(session);
            require(audit.calls(session) == settledCalls, "TERMINAL_MODEL_ATTEMPT_ADDED");
            require(audit.deniedCalls(session) == deniedCalls, "TERMINAL_MODEL_ATTEMPT_REQUESTED");
            audit.stop(session, "SCENE_FINISHED");
            scenes.add(new SceneEvidence(name, "PASSED", state, settledCalls, null));
        } catch (Exception | AssertionError failure) {
            String code = failure instanceof AssertionError ? failure.getMessage() : failure.getClass().getSimpleName();
            // 只报告所属测试定义的代码；HTTP、JDBC 和模型原始正文不进入报告。
            if (code == null || !code.matches("[A-Z0-9_]+")) code = "SCENE_FAILED";
            String stopped = audit.stopCode(session);
            audit.stop(session, "SCENE_FAILED");
            scenes.add(new SceneEvidence(name, "FAILED", state, audit.calls(session), stopped == null ? code : stopped));
            preserveFailureDiagnostics(name, session, submittedRun);
            closeSession(session);
        }
    }

    /** 销毁隔离数据库前保存最小诊断；绝不序列化准备、推理、原始工具参数或模型正文。 */
    private void preserveFailureDiagnostics(String scene, String session, String run) {
        String destination = System.getenv("LIVE_PUBLIC_EVIDENCE_FILE");
        if (destination == null || destination.isBlank()) return;
        var report = json.createObjectNode();
        report.put("scene", scene); report.put("session_id", session); report.put("run_id", run);
        if (run != null && !run.isBlank()) {
            diagnosticBlock(report, "run_facts", () -> report.set("run_facts", json.valueToTree(queryRows(jdbc, fixture.tenant(),
                    "SELECT status,run_revision FROM agent_runtime_runs WHERE tenant_id=? AND run_id=?", fixture.tenant(), run))));
            diagnosticBlock(report, "terminal_failures", () -> report.set("terminal_failures", json.valueToTree(queryRows(jdbc, fixture.tenant(),
                    "SELECT reason_code FROM nw_runtime_session_terminal_failures WHERE tenant_id=? AND run_id=?", fixture.tenant(), run))));
            diagnosticBlock(report, "actions", () -> report.set("actions", json.valueToTree(queryRows(jdbc, fixture.tenant(),
                    "SELECT action_id,state,reason_code,attempt,action_revision FROM agent_runtime_actions WHERE tenant_id=? AND run_id=?", fixture.tenant(), run))));
            diagnosticBlock(report, "tool_input_response_journal_count", () -> report.put("tool_input_response_journal_count", queryCount(jdbc, fixture.tenant(),
                    "SELECT count(*) FROM nw_runtime_session_tool_input_responses WHERE tenant_id=? AND run_id=?", fixture.tenant(), run)));
            diagnosticBlock(report, "event_facts", () -> {
                var events = report.putArray("event_facts");
                for (var event : sessionEvents(sessionOwners.get(session))) {
                    if (!event.runRef().map(ref -> run.equals(ref.runId())).orElse(false)) continue;
                    var fact = events.addObject(); fact.put("type", event.eventType()); fact.put("sequence", event.sessionSequence());
                    if (event.eventType().contains("failed") || event.eventType().contains("finished")
                            || event.eventType().contains("terminated") || event.eventType().contains("rejected"))
                        collectSafeReasonFields(json.readTree(event.payload().canonicalJson()), fact.putArray("reasons"));
                }
            });
        }
        diagnosticBlock(report, "public_history", () -> report.set("public_history", redactPrivateTexts(
                json.valueToTree(history(session)), guard.capture(sessionOwners.get(session)))));
        try {
            Path path = Path.of(destination).toAbsolutePath().normalize().resolveSibling(scene + "-failure-diagnostics.json");
            Files.writeString(path, json.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
        } catch (Exception diagnosticWriteFailure) {
            // 保存失败作为独立清理缺口，不覆盖场景原始失败或伪造诊断成功。
            cleanupFailed = true;
        }
    }

    private void diagnosticBlock(com.fasterxml.jackson.databind.node.ObjectNode report, String block, DiagnosticRead read) {
        try { read.read(); }
        catch (Exception | AssertionError failure) {
            report.put("diagnostic_status", "INCOMPLETE");
            report.withObject("/diagnostic_failures").put(block, failure.getClass().getSimpleName());
        }
    }
    @FunctionalInterface private interface DiagnosticRead { void read() throws Exception; }

    private void collectSafeReasonFields(JsonNode node, com.fasterxml.jackson.databind.node.ArrayNode reasons) {
        if (node.isObject()) node.fields().forEachRemaining(entry -> {
            if (Set.of("code", "reasonCode", "reason_code", "failureCode", "status", "reason", "retry").contains(entry.getKey())
                    && entry.getValue().isTextual() && entry.getValue().asText().matches("[A-Za-z0-9_.:-]{1,160}")) {
                var reason = reasons.addObject(); reason.put("field", entry.getKey()); reason.put("value", entry.getValue().asText());
            } else if (entry.getValue().isContainerNode()) collectSafeReasonFields(entry.getValue(), reasons);
        });
        else if (node.isArray()) node.forEach(value -> collectSafeReasonFields(value, reasons));
    }

    private JsonNode redactPrivateTexts(JsonNode node, PublicOutputGuard.Scope scope) {
        if (node.isTextual()) return scope.rejects(node.asText()) ? json.getNodeFactory().textNode("[REDACTED_PRIVATE_VALUE]") : node;
        if (node.isObject()) {
            var result = json.createObjectNode(); node.fields().forEachRemaining(entry -> result.set(entry.getKey(), redactPrivateTexts(entry.getValue(), scope))); return result;
        }
        if (node.isArray()) { var result = json.createArrayNode(); node.forEach(value -> result.add(redactPrivateTexts(value, scope))); return result; }
        return node;
    }

    private Result awaitResult(String session, String run, String agent) throws Exception {
        long deadline = observationDeadline();
        ChatHistoryResponse view = null;
        while (System.nanoTime() < deadline) {
            // 观察恢复独立于首次 POST，任何网络失败都不会重新提交输入。
            view = history(session);
            var rows = queryRows(jdbc, fixture.tenant(),
                    "SELECT status FROM agent_runtime_runs WHERE tenant_id=? AND run_id=?", fixture.tenant(), run);
            String status = rows.isEmpty() ? "NOT_FOUND" : String.valueOf(rows.getFirst().get("status"));
            if (TERMINAL.contains(status)) {
                String answer = view.messages().stream().filter(value -> "assistant".equals(value.role()))
                        .map(value -> value.content() == null ? "" : value.content())
                        .collect(java.util.stream.Collectors.joining("\n"));
                if (!answer.isBlank() || !"COMPLETED".equals(status)) {
                    return new Result(session, run, agent, status, answer, view);
                }
            }
            if (audit.stopCode(session) != null) throw new AssertionError("MODEL_SCENE_STOPPED");
            Thread.sleep(OBSERVE_INTERVAL_MILLIS);
        }
        throw new AssertionError("RUN_OBSERVATION_TIMEOUT");
    }

    private void verifyVisibleTexts(Result result) throws Exception {
        var scope = guard.capture(owner(result));
        require(!scope.rejects(result.answer()), "PRIVATE_VALUE_IN_ASSISTANT_TEXT");
        var texts = new ArrayList<String>();
        result.history().processes().forEach(process -> texts.addAll(
                PublicPresentationTexts.read(json.valueToTree(process.presentation()))));
        texts.addAll(PublicPresentationTexts.read(json.valueToTree(result.history().activities())));
        texts.addAll(PublicPresentationTexts.read(json.valueToTree(result.history().pendingInteractions())));
        texts.addAll(PublicPresentationTexts.read(json.valueToTree(result.history().collaboration())));
        result.history().messages().stream().filter(message -> "assistant".equals(message.role()))
                .forEach(message -> message.references().forEach(reference -> texts.add(reference.label())));
        require(texts.stream().noneMatch(scope::rejects), "PRIVATE_VALUE_IN_PUBLIC_PRESENTATION");
    }

    private void verifyToolChain(Result result, List<MountedTool> mounted) throws Exception {
        String calculator = mounted.stream().filter(tool -> "calculate_expression".equals(tool.name()))
                .findFirst().orElseThrow().handle();
        String renderer = mounted.stream().filter(tool -> "template_render".equals(tool.name()))
                .findFirst().orElseThrow().handle();
        var observations = sessionEvents(owner(result)).stream()
                .filter(event -> event.runRef().map(ref -> result.run().equals(ref.runId())).orElse(false))
                .filter(event -> "agent.tool-result".equals(event.eventType())).toList();
        JsonNode calculation = null, rendering = null;
        String calculationAction = null, renderAction = null;
        long calculationSequence = 0, renderSequence = 0;
        for (var event : observations) {
            var fact = json.readTree(event.payload().canonicalJson());
            require("SUCCEEDED".equals(fact.path("status").asText()), "TOOL_FACT_FAILED");
            var output = json.readTree(fact.path("output").path("canonicalJson").asText()).path("structuredContent");
            require(output.path("success").asBoolean(), "TOOL_BUSINESS_RESULT_FAILED");
            if (calculator.equals(fact.path("toolHandle").asText())) {
                calculation = output; calculationSequence = event.sessionSequence();
                calculationAction = fact.path("actionRef").path("actionId").asText();
            }
            if (renderer.equals(fact.path("toolHandle").asText())) {
                rendering = output; renderSequence = event.sessionSequence();
                renderAction = fact.path("actionRef").path("actionId").asText();
            }
        }
        require(calculation != null && "391".equals(calculation.path("value").asText()), "CALCULATION_NOT_VERIFIED");
        require(rendering != null && "订单数量391".equals(rendering.path("rendered").asText()), "RENDERING_NOT_VERIFIED");
        require(calculationSequence < renderSequence, "TOOL_CHAIN_NOT_ORDERED");
        require(calculationAction != null && !calculationAction.isBlank()
                && renderAction != null && !renderAction.isBlank(), "TOOL_ACTION_IDENTITY_MISSING");
        require(result.answer().contains("订单数量391"), "TOOL_RESULT_NOT_DELIVERED");
        require(queryCount(jdbc, fixture.tenant(), """
                SELECT count(*) FROM agent_runtime_actions
                WHERE tenant_id=? AND run_id=? AND execute_call_kind='TOOL_INVOKE' AND state='SUCCEEDED'
                """, fixture.tenant(), result.run()) >= 2, "REAL_TOOL_ACTIONS_MISSING");
        var rows = queryRows(jdbc, fixture.tenant(), """
                SELECT action_id,execute_call_data FROM agent_runtime_actions
                WHERE tenant_id=? AND run_id=? AND execute_call_kind='TOOL_INVOKE' AND state='SUCCEEDED'
                """, fixture.tenant(), result.run());
        boolean calculatorInput = false, rendererInput = false;
        for (var row : rows) {
            JsonNode arguments = json.valueToTree(ToolCallIoProjection.argumentsFromCallData(
                    String.valueOf(row.get("execute_call_data")), json).orElseThrow());
            // Ledger target是绑定内容摘要；通过已提交tool-result的精确Action身份关联，不能与mount handle比较。
            if (calculationAction.equals(row.get("action_id")))
                calculatorInput |= "17*23".equals(arguments.path("expression").asText().replace(" ", ""));
            if (renderAction.equals(row.get("action_id")))
                rendererInput |= "订单数量{{quantity}}".equals(arguments.path("template").asText())
                        && "391".equals(arguments.path("data").path("quantity").asText());
        }
        require(calculatorInput && rendererInput, "REAL_TOOL_ARGUMENTS_NOT_VERIFIED");
        require(result.history().processes().stream().filter(process -> result.run().equals(process.runId())
                && "commentary".equals(process.type()) && !process.presentation().summary().isBlank()).count() >= 2,
                "COMMITTED_COMMENTARY_MISSING");
    }

    private void executeArtifactScenes() throws Exception {
        String writer = createAgent("文件成果验收助手", "按用户要求登记交付计划，使用真实code_execute生成文件，检查实际内容后finalize_task_outputs。成果冻结后只给出简短用户回复；不编造文件或链接。");
        appendTools(mountTools(writer, List.of("code_execute", "finalize_task_outputs", "get_goal", "update_goal")));
        publish(writer);
        if (selected("real_artifact_publication")) execute("real_artifact_publication", writer,
                "请把以下业务规则改写成面向业务同事的简洁通知，正式交付一个Markdown文件report.md，标题为合成业务通知。"
                        + BUSINESS_RULES + " 保留预算、截止日期、起订量、普通优惠、加急例外和未知国际运费，不补造承诺。"
                        + "再用Python在/workspace/output/report.md写入真实文件并用output_files收集，检查内容后finalize_task_outputs正式交付。默认md，无需压缩或其他格式。",
                false, result -> verifyArtifact(result, "report.md", "合成业务通知", false));
        if (selected("html_goal")) execute("html_goal", writer,
                "生成并正式交付单文件index.html静态网页，标题为SkillForge Goal Acceptance，正文包含订单数量391。"
                        + "先get_goal读取当前目标revision，code_execute真实生成HTML文件并收集，"
                        + "检查网页后finalize_task_outputs设置goal_completion.expected_revision为get_goal实际revision。"
                        + "文件成果尚未冻结时不要提前update_goal COMPLETE，不引用外部资源，正式交付后结束目标。",
                false, result -> {
                    verifyArtifact(result, "index.html", "SkillForge Goal Acceptance", true);
                    JsonNode goal = request("/api/v1/chat/goals?session_id=" + encode(result.session()), "GET", null, HTTP_OK);
                    require("COMPLETE".equals(goal.path("goal").path("state").asText()), "GOAL_NOT_ATOMICALLY_COMPLETED");
                    require(!goal.path("completion_pending").asBoolean(), "GOAL_PUBLICATION_PENDING");
                });
    }

    private void verifyArtifact(Result result, String filePath, String expectedContent, boolean html) throws Exception {
        JsonNode revisions = request("/api/v1/chat/sessions/deliverables?sessionId=" + encode(result.session()), "GET", null, HTTP_OK);
        require(revisions.isArray() && revisions.size() == 1, "FORMAL_DELIVERABLE_MISSING");
        JsonNode revision = revisions.get(0);
        require("ready".equals(revision.path("status").asText()) && revision.path("current").asBoolean()
                && result.run().equals(revision.path("run_id").asText()), "FORMAL_DELIVERABLE_NOT_CURRENT_RUN");
        JsonNode file = element(revision.path("files"), "path", filePath);
        require(!file.path("artifact_id").asText().isBlank() && !file.path("file_id").asText().isBlank(), "FORMAL_FILE_IDENTITY_MISSING");
        JsonNode ticket = request("/api/v1/chat/deliverables/download-ticket", "POST",
                Map.of("id", revision.path("id").asText(), "file_id", file.path("file_id").asText()), HTTP_OK);
        URI issued = URI.create(ticket.path("url").asText());
        require("/api/v1/chat/deliverable-content/detail".equals(issued.getPath())
                && issued.getRawQuery() != null && issued.getRawQuery().startsWith("ticket="), "DOWNLOAD_TICKET_ROUTE_INVALID");
        // 随机HTTP测试端口只替换传输origin，保留正式签发的ticket和完整路径。
        URI uri = URI.create("http://127.0.0.1:" + port + issued.getRawPath() + "?" + issued.getRawQuery());
        var downloaded = http.send(HttpRequest.newBuilder(uri).timeout(HTTP_TIMEOUT).GET().build(), HttpResponse.BodyHandlers.ofString());
        require(downloaded.statusCode() == 200 && downloaded.body().contains(expectedContent), "FORMAL_FILE_CONTENT_MISMATCH");
        if (!html) require(downloaded.body().contains("600") && downloaded.body().contains("2026-11-11")
                && downloaded.body().contains("11") && downloaded.body().contains("5%")
                && downloaded.body().contains("加急") && downloaded.body().contains("不")
                && downloaded.body().contains("国际") && downloaded.body().contains("运费"), "D06_CANDIDATE_RULES_LOST");
        // 正式成果卡通过成果投影的 result_run_ids 与回复 Run 关联；references 是主动引用的来源。
        require(result.session().equals(revision.path("session_id").asText()), "FORMAL_DELIVERABLE_SESSION_MISMATCH");
        require(java.util.stream.StreamSupport.stream(revision.path("result_run_ids").spliterator(), false)
                .anyMatch(id -> result.run().equals(id.asText())), "FORMAL_RESULT_RUN_NOT_DELIVERED");
        require(result.history().messages().stream().filter(m -> "assistant".equals(m.role()))
                .anyMatch(m -> m.metadata() != null && result.run().equals(m.metadata().get("run_id"))),
                "FORMAL_REPLY_CARD_BINDING_MISSING");
        if (html) {
            JsonNode preview = request("/api/v1/chat/deliverables/preview-ticket", "POST",
                    Map.of("id", revision.path("id").asText(), "file_id", file.path("file_id").asText()), HTTP_OK);
            URI previewUri = URI.create(preview.path("url").asText());
            require("localhost".equals(previewUri.getHost()) && previewUri.getPort() == PREVIEW_PORT
                    && previewUri.getPath().startsWith("/d/"), "PREVIEW_NOT_ACTUAL_LISTENER");
            var page = http.send(HttpRequest.newBuilder(previewUri).timeout(HTTP_TIMEOUT).GET().build(), HttpResponse.BodyHandlers.ofString());
            require(page.statusCode() == 200 && page.body().contains(expectedContent), "REAL_HTML_PREVIEW_FAILED");
            require(page.headers().firstValue("Content-Security-Policy").orElse("").contains("frame-ancestors"), "PREVIEW_CSP_MISSING");
        }
    }

    private void executeGenericInput(String agent) throws Exception {
        execute("generic_tool_input_card", agent,
                "请调用template_render，template固定为验收补参数量{{quantity}}，data为空对象。这是显式补参夹具，"
                        + "用户尚未提供数量，不要猜测；等待工具请求后使用实际补参结果，最终原样回复渲染结果。",
                false, result -> {
                    require(result.answer().contains("验收补参数量27"), "GENERIC_TOOL_RESPONSE_NOT_DELIVERED");
                    require(queryCount(jdbc, fixture.tenant(), "SELECT count(*) FROM nw_runtime_session_tool_input_responses WHERE tenant_id=? AND run_id=?",
                            fixture.tenant(), result.run()) == 1, "GENERIC_RESPONSE_JOURNAL_NOT_SINGLE");
                });
    }

    private JsonNode awaitInteraction(String session, String run, String type) throws Exception {
        long deadline = observationDeadline();
        while (System.nanoTime() < deadline) {
            JsonNode pending = json.valueToTree(history(session).pendingInteractions()).path(0);
            if (type.equals(pending.path("interaction_type").asText())) {
                require(run.equals(pending.path("execution_id").asText()), "WAIT_RUN_DRIFT");
                var scope = guard.capture(sessionOwners.get(session));
                require(PublicPresentationTexts.read(pending).stream().noneMatch(scope::rejects), "PRIVATE_VALUE_IN_WAIT_CARD");
                return pending;
            }
            if (audit.stopCode(session) != null) throw new AssertionError("MODEL_SCENE_STOPPED");
            Thread.sleep(OBSERVE_INTERVAL_MILLIS);
        }
        throw new AssertionError("INTERACTION_OBSERVATION_TIMEOUT");
    }

    private void answerGenericWait(String session, String agent, String run) throws Exception {
        JsonNode pending = awaitInteraction(session, run, "tool_input");
        Map<String, Object> fields = new java.util.LinkedHashMap<>();
        for (String field : List.of("execution_id", "run_revision", "checkpoint_revision", "action_id", "invocation_id", "pending_id", "wake_id", "wait_revision")) {
            require(!pending.path(field).asText().isBlank(), "GENERIC_WAIT_IDENTITY_MISSING");
            fields.put(field, pending.path(field).asText());
        }
        fields.put("tool_input", Map.of("responses", Map.of("quantity", Map.of("method", "elicitation/create",
                "result", Map.of("schema_id", "application/json", "canonical_json", "{\"action\":\"accept\",\"content\":{\"quantity\":27}}")))));
        String inputId = UUID.randomUUID().toString();
        var body = new GenericResumeSubmit((String) null, session, agent, inputId, new EndUserContext(fixture.endUser()), fields);
        var first = request("/api/v1/chat", "POST", body, HTTP_ACCEPTED);
        var replay = request("/api/v1/chat", "POST", body, HTTP_ACCEPTED);
        require(run.equals(first.path("execution_id").asText()) && run.equals(replay.path("execution_id").asText()), "GENERIC_RESUME_CHANGED_RUN");
    }

    private void reconnectAndCancel(String session, String run) throws Exception {
        awaitInteraction(session, run, "plan_review");
        String cursor = history(session).eventCursor();
        require(cursor != null && !cursor.isBlank(), "DURABLE_REPLAY_CURSOR_MISSING");
        for (int connection = 0; connection < 2; connection++) {
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/chat/events?session_id=" + encode(session)))
                    .timeout(HTTP_TIMEOUT).header("Authorization", "Bearer " + fixture.accessToken())
                    .header("Accept", "text/event-stream");
            if (connection == 1) builder.header("Last-Event-ID", cursor);
            var response = http.send(builder.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            // 在校验前取得资源所有权，HTTP/媒体类型断言失败也必须释放无限流。
            try (var body = response.body()) {
                if (response.statusCode() != 200 || !response.headers().firstValue("Content-Type").orElse("").contains("text/event-stream")) {
                    httpFailures.add(new HttpFailureEvidence(stage, response.statusCode(), HTTP_OK, "SSE_MEDIA_TYPE_OR_STATUS_INVALID"));
                    throw new AssertionError("REAL_SSE_CONNECTION_FAILED");
                }
            }
            require(!history(session).pendingInteractions().isEmpty(), "SSE_DISCONNECT_CANCELLED_RUN");
        }
        request("/api/v1/chat/runs/cancel", "POST", Map.of("session_id", session, "run_id", run), HTTP_OK);
    }

    private void approvePlanWait(String session, String agent, String run) throws Exception {
        stage = "PLAN_WAIT_OBSERVATION";
        long deadline = observationDeadline();
        while (System.nanoTime() < deadline) {
            ChatHistoryResponse view = history(session);
            var node = json.valueToTree(view.pendingInteractions()).path(0);
            if ("plan_review".equals(node.path("interaction_type").asText())) {
                require(run.equals(node.path("execution_id").asText()), "PLAN_WAIT_RUN_DRIFT");
                for (String field : List.of("run_revision", "checkpoint_revision", "action_id", "invocation_id",
                        "pending_id", "wake_id", "wait_revision"))
                    require(!node.path(field).asText().isBlank(), "PLAN_WAIT_IDENTITY_MISSING");
                var scope = guard.capture(sessionOwners.get(session));
                require(PublicPresentationTexts.read(node).stream().noneMatch(scope::rejects), "PRIVATE_VALUE_IN_PLAN_CARD");
                require(queryCount(jdbc, fixture.tenant(), """
                        SELECT count(*) FROM nw_runtime_tool_input_waits
                        WHERE tenant_id=? AND action_id=? AND invocation_id=? AND pending_id=? AND wake_id=? AND wait_revision=?
                        """, fixture.tenant(), node.path("action_id").asText(), node.path("invocation_id").asText(),
                        node.path("pending_id").asText(), node.path("wake_id").asText(), node.path("wait_revision").asLong()) == 1,
                        "REAL_TOOL_WAIT_NOT_VERIFIED");
                var reply = new PlanReply(run, node.path("run_revision").asText(), node.path("checkpoint_revision").asText(),
                        node.path("action_id").asText(), node.path("invocation_id").asText(), node.path("pending_id").asText(),
                        node.path("wake_id").asText(), node.path("wait_revision").asText(), new PlanReview("approve", ""));
                stage = "PLAN_REVIEW_RESUME";
                var response = request("/api/v1/chat", "POST", new ResumeSubmit((String) null, session, agent,
                        UUID.randomUUID().toString(), new EndUserContext(fixture.endUser()), reply), HTTP_ACCEPTED);
                require(run.equals(response.path("execution_id").asText()), "PLAN_RESUME_CHANGED_RUN");
                return;
            }
            if (audit.stopCode(session) != null) throw new AssertionError("MODEL_SCENE_STOPPED");
            if (queryCount(jdbc, fixture.tenant(), """
                    SELECT count(*) FROM agent_runtime_runs WHERE tenant_id=? AND run_id=?
                    AND status IN ('COMPLETED','FAILED','TERMINATED','CANCELLED')
                    """, fixture.tenant(), run) > 0) throw new AssertionError("PLAN_TOOL_WAIT_NOT_TRIGGERED");
            Thread.sleep(OBSERVE_INTERVAL_MILLIS);
        }
        throw new AssertionError("PLAN_TOOL_WAIT_TIMEOUT");
    }

    private void executeTeamSummary() throws Exception {
        stage = "TEAM_AGENT_SETUP";
        String inventory = createAgent("规则核对员", "核对用户提供的业务规则，保留数值、日期、加急例外和未知运费，不编造事实，不委派其他助手。 ");
        String price = createAgent("订单汇总员", "按订单id去重汇总用户提供的已列金额，同id同金额仅计一次，冲突则报告。使用calculate_expression，不推算折扣或分配方案，不委派其他助手。 ");
        appendTools(mountTools(price, List.of("calculate_expression")));
        publish(inventory); publish(price);
        String coordinator = createAgent("订单协调员", "两个独立核对任务分别委派给规则核对员与订单汇总员，传递完整资料，等待真实结果后汇总，不自行编造成员结果。 ");
        request("/api/v1/agents/update", "PATCH", new AgentUpdate(coordinator,
                new AgentUpdatePayload(new AgentDelegationConfig(true, List.of(inventory, price)))), HTTP_OK);
        publish(coordinator);
        stage = "TEAM_CONFIGURATION_SETUP";
        var team = request("/api/v1/teams", "POST", new TeamCreate("订单核对验收团队", "验证真实成员汇总",
                "draft", 0, List.of(new TeamMember(coordinator), new TeamMember(inventory), new TeamMember(price)), coordinator), HTTP_OK);
        String teamId = team.path("id").asText();
        request("/api/v1/teams/publish", "POST", new TeamPublish(teamId), HTTP_OK);
        String session = request("/api/v1/chat/sessions", "POST", new TeamSessionCreate(teamId, fixture.endUser()), HTTP_OK)
                .path("session_id").asText();
        require(!session.isBlank(), "TEAM_SESSION_MISSING");
        sessionOwners.put(session, SessionOwner.user(new SessionRef(new TenantId(fixture.tenant()), session),
                SessionTarget.team(teamId), new EndUserId(fixture.endUser())));
        executeSession("team_member_summary", coordinator, teamId, session,
                "请将规则核对委派给规则核对员，将按id去重汇总订单已列金额委派给订单汇总员，等待两个真实结果后汇总。"
                        + "rules：" + BUSINESS_RULES + " orders CSV：\nid,amount\nA,100\nB,120\nB,120\nC,150\n"
                        + "只汇总已列金额，不推算折后款或分配普通/加急方案；最终说明规则、去重总额和资料限制。",
                false, result -> {
                    require(result.answer().contains("600") && result.answer().contains("2026-11-11")
                            && result.answer().contains("370") && result.answer().contains("加急")
                            && result.answer().contains("国际") && result.answer().contains("运费"), "D11_CANDIDATE_SUMMARY_INCOMPLETE");
                    var work = json.treeToValue(request("/api/v1/chat/work?session_id=" + encode(session), "GET", null, HTTP_OK),
                            TeamPublicViews.Work.class);
                    require(work != null && work.tasks().size() >= 2, "REAL_TEAM_TASKS_MISSING");
                    require(work.tasks().stream().filter(task -> TeamPublicViews.State.COMPLETED.equals(task.status())).count() >= 2,
                            "REAL_TEAM_TASKS_NOT_COMPLETED");
                    require(work.results().size() >= 2 && work.results().stream().allMatch(value -> TeamPublicViews.State.READY.equals(value.status())),
                            "TEAM_RESULTS_NOT_COMMITTED");
                    var scope = guard.capture(sessionOwners.get(session));
                    for (String text : PublicPresentationTexts.read(json.valueToTree(work)))
                        require(!scope.rejects(text), "PRIVATE_VALUE_IN_MEMBER_SUMMARY");
                    for (var report : work.results()) {
                        require(!scope.rejects(report.content()) && !scope.rejects(report.summary()), "PRIVATE_VALUE_IN_MEMBER_RESULT");
                    }
                    for (var task : work.tasks()) {
                        require(queryCount(jdbc, fixture.tenant(), """
                            SELECT count(*) FROM agent_runtime_runs WHERE tenant_id=? AND run_id=? AND status='COMPLETED'
                            """, fixture.tenant(), task.runId()) == 1, "REAL_MEMBER_RUNS_MISSING");
                        var detail = json.treeToValue(request("/api/v1/chat/work/tasks/detail?session_id=" + encode(session)
                                + "&work_id=" + encode(work.workId()) + "&task_kind=" + encode(task.taskKind().wire())
                                + "&task_id=" + encode(task.taskId()), "GET", null, HTTP_OK), TeamPublicViews.MemberDetail.class);
                        require(detail.run() != null && TeamPublicViews.State.COMPLETED.equals(detail.run().status()),
                                "MEMBER_DETAIL_NOT_COMPLETED");
                        for (String text : PublicPresentationTexts.read(json.valueToTree(detail)))
                            require(!scope.rejects(text), "PRIVATE_VALUE_IN_MEMBER_DETAIL");
                    }
                });
    }

    private void verifyRejection(Result result) {
        require("FAILED".equals(result.runStatus()), "PUBLIC_REJECTION_NOT_TRIGGERED");
        require(queryCount(jdbc, fixture.tenant(), """
                SELECT count(*) FROM nw_runtime_session_terminal_failures
                WHERE tenant_id=? AND run_id=? AND reason_code=?
                """, fixture.tenant(), result.run(), PublicOutputGuard.REJECTION_CODE) == 1,
                "PUBLIC_REJECTION_FACT_MISSING");
        boolean safe = result.answer().contains(PublicOutputGuard.SAFE_MESSAGE)
                || result.history().processes().stream()
                .anyMatch(process -> PublicOutputGuard.SAFE_MESSAGE.equals(process.presentation().summary()));
        require(safe, "PUBLIC_REJECTION_SAFE_MESSAGE_MISSING");
        require(!result.answer().contains(REJECTION_VALUE), "REJECTED_VALUE_LEAKED");
    }

    private Fixture createFixture() throws Exception {
        String slug = "live-public-" + UUID.randomUUID();
        String tenant = createTenant(tenants, plans, slug);
        TenantContext.setTenantId(tenant);
        try {
            var member = new TenantMemberDO();
            member.setId(SnowflakeIds.nextId()); member.setTenantId(Long.valueOf(tenant));
            member.setUsername("live-public-admin"); member.setRole("admin"); member.setPermissions(List.of());
            member.setStatus("active"); member.setPasswordHash(passwords.encode(PASSWORD));
            member.setCreatedAt(Instant.now()); members.insert(member);
            var user = new EndUserDO();
            user.setId(SnowflakeIds.nextId()); user.setTenantId(Long.valueOf(tenant));
            user.setDisplayName("公开边界验收用户"); user.setStatus("active");
            user.setFirstSeenAt(Instant.now()); user.setLastActiveAt(Instant.now()); user.setCreatedAt(Instant.now());
            users.insert(user);
            JsonNode loggedIn = send(null, "/api/v1/auth/login", "POST",
                    new Login(slug, member.getUsername(), PASSWORD), HTTP_OK);
            String token = loggedIn.path("access_token").asText();
            require(!token.isBlank(), "LOGIN_TOKEN_MISSING");
            return new Fixture(tenant, user.getId(), token);
        } finally { TenantContext.clear(); }
    }

    private void installFrozenSource() {
        stage = "OPERATIONAL_GROUP_INITIALIZATION";
        // 空测试库沿正式懒初始化入口建立各分组，再追加来源快照，不能假设迁移已创建当前指针。
        for (var group : source.groups()) settings.read(group.key());
        stage = "SOURCE_COPY";
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)", String.class, fixture.tenant());
            jdbc.update("INSERT INTO nw_platform_models(id,current_revision,draft,draft_changed) VALUES(CAST(? AS bigint),?,CAST(? AS jsonb),false)",
                    source.model().id(), source.model().revision(), source.encryptedConfig());
            jdbc.update("INSERT INTO nw_platform_model_versions(model_id,revision,config,actor_id) VALUES(CAST(? AS bigint),?,CAST(? AS jsonb),'live-isolated-source')",
                    source.model().id(), source.model().revision(), source.encryptedConfig());
            jdbc.update("INSERT INTO nw_tenant_model_selections(tenant_id,llm_model_config_id) VALUES(CAST(? AS bigint),CAST(? AS bigint))",
                    fixture.tenant(), source.model().id());
            var copied = new ArrayList<GroupEvidence>();
            for (var group : source.groups()) {
                Long current = jdbc.queryForObject("SELECT current_revision FROM nw_platform_operational_setting_groups WHERE group_key=?",
                        Long.class, group.key());
                require(current != null && current > 0, "ISOLATED_OPERATIONAL_GROUP_UNINITIALIZED");
                long local = Math.addExact(current, 1);
                jdbc.update("INSERT INTO nw_platform_operational_setting_versions(group_key,revision,values,publication_source) VALUES(?,?,CAST(? AS jsonb),'system_release')",
                        group.key(), local, group.values());
                jdbc.update("UPDATE nw_platform_operational_setting_groups SET current_revision=?,updated_at=now() WHERE group_key=?",
                        local, group.key());
                copied.add(new GroupEvidence(group.key(), group.sourceRevision(), local));
            }
            groups = List.copyOf(copied);
        });
    }

    private String createAgent() throws Exception {
        return createAgent("公开边界验收助手", "协助用户完成业务任务，技术示例保留用户给出的字段和数据，工具任务使用真实工具结果。");
    }
    private String createAgent(String name, String instructions) throws Exception {
        var created = request("/api/v1/agents", "POST", new AgentCreate("live-public-" + UUID.randomUUID(), name, instructions), HTTP_OK);
        String agent = created.path("id").asText();
        require(!agent.isBlank(), "AGENT_MISSING");
        return agent;
    }
    private void publish(String agent) throws Exception { request("/api/v1/agents/publish", "POST", new AgentPublish(agent), HTTP_OK); }
    private void appendTools(List<MountedTool> mounted) {
        var all = new ArrayList<>(tools); all.addAll(mounted.stream().map(MountedTool::evidence).toList()); tools = List.copyOf(all);
    }

    private List<MountedTool> mountTools(String agent, List<String> names) throws Exception {
        var definitions = request("/api/v1/capabilities/definitions?capability_type=TOOL&status=PUBLISHED", "GET", null, HTTP_OK);
        var mounted = new ArrayList<MountedTool>();
        for (String name : names) {
            var catalog = new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()).entries().stream().filter(entry -> name.equals(entry.name())).findFirst().orElseThrow();
            long definition = element(definitions, "capability_key", "tool:" + name).path("id").asLong();
            var versions = request("/api/v1/capabilities/definitions/versions?status=PUBLISHED&definitionId=" + definition,
                    "GET", null, HTTP_OK);
            long version = element(versions, "version_key", catalog.publishedVersionKey()).path("id").asLong();
            String key = "live-public-" + name + "-" + UUID.randomUUID();
            var deployment = request("/api/v1/capabilities/deployments", "POST", new DeploymentCreate(key, version,
                    "PLATFORM", name, "TEST", "ACTIVE"), HTTP_CREATED);
            long revision = deployment.path("revision").asLong();
            var response = request("/api/v1/agents/capability-mounts", "POST", new MountCreate(agent,
                    new MountPayload("TOOL", definition, version, new ToolConfig(key, revision), 0)), HTTP_CREATED);
            String mount = element(response.path("mounts"), "capability_definition_id", String.valueOf(definition))
                    .path("mount_id").asText();
            require(!mount.isBlank(), "TOOL_MOUNT_MISSING");
            mounted.add(new MountedTool(name, "tool:" + mount,
                    new ToolEvidence(name, catalog.publishedVersionKey(), catalog.contentHash())));
        }
        return List.copyOf(mounted);
    }

    private String createSession(String agent) throws Exception {
        String id = request("/api/v1/chat/sessions", "POST", new SessionCreate(agent, fixture.endUser()), HTTP_OK)
                .path("session_id").asText();
        require(!id.isBlank(), "SESSION_MISSING");
        sessionOwners.put(id, SessionOwner.user(new SessionRef(new TenantId(fixture.tenant()), id),
                new AgentId(agent), new EndUserId(fixture.endUser())));
        return id;
    }
    private ChatHistoryResponse history(String session) throws Exception {
        return json.treeToValue(request("/api/v1/chat/history?session_id=" + encode(session), "GET", null, HTTP_OK),
                ChatHistoryResponse.class);
    }
    private void closeSession(String session) {
        String previousStage = stage;
        stage = "SESSION_CLEANUP";
        try { request("/api/v1/chat/sessions/close", "POST", new SessionClose(session, new ClosePayload("live-acceptance-finished")), HTTP_OK); }
        catch (Exception | AssertionError ignored) { cleanupFailed = true; }
        finally { stage = previousStage; }
    }
    @AfterEach void cleanup() throws Exception {
        try {
        audit.close();
        if (fixture != null) createdSessions.forEach(this::closeSession);
        // Java 21 close() 无期限等待；先有界等待静止，再中断本测试客户端拥有的请求。
        http.shutdown();
        try {
            if (!http.awaitTermination(Duration.ofSeconds(5))) {
                http.shutdownNow();
                if (!http.awaitTermination(Duration.ofSeconds(5))) cleanupFailed = true;
            }
        } catch (InterruptedException interrupted) {
            http.shutdownNow();
            cleanupFailed = true;
            Thread.currentThread().interrupt();
        }
        // 文件成果证据由专用临时根保留；不递归删除，空根才释放。
        try {
            boolean empty;
            try (var entries = Files.list(ARTIFACT_ROOT)) { empty = entries.findAny().isEmpty(); }
            if (empty) Files.deleteIfExists(ARTIFACT_ROOT);
        } catch (Exception ignored) { cleanupFailed = true; }
        cleanupOutcome = cleanupFailed ? "FAILED" : "PASSED";
        // 清理结果独立记录，保留原样本outcome、失败分类和已保留预算。
        writeEvidence();
        if (cleanupFailed) throw new AssertionError("FAILED_SESSION_CLEANUP");

        } finally {
            LiveGradleAttemptMeter.snapshot(liveMeterDataSource, fixture == null ? null : fixture.tenant());
        }
    }

    private List<CommittedSessionEvent> sessionEvents(SessionOwner owner) {
        var result = new ArrayList<CommittedSessionEvent>();
        long cursor = 0;
        while (true) {
            var page = log.readAfter(new SessionLogPort.ReadSessionLog(owner, cursor, SOURCE_PAGE_SIZE));
            result.addAll(page);
            if (page.size() < SOURCE_PAGE_SIZE) return List.copyOf(result);
            cursor = page.getLast().sessionSequence();
        }
    }
    private SessionOwner owner(Result result) {
        return sessionOwners.get(result.session());
    }
    private JsonNode request(String path, String method, Object body, int expected) throws Exception {
        return send(fixture.accessToken(), path, method, body, expected);
    }
    private JsonNode send(String token, String path, String method, Object body, int expected) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(HTTP_TIMEOUT).header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode envelope;
        try { envelope = json.readTree(response.body()); }
        catch (Exception invalid) {
            httpFailures.add(new HttpFailureEvidence(stage, response.statusCode(), expected, "NON_JSON_RESPONSE"));
            throw new AssertionError(response.statusCode() == expected ? "HTTP_ENVELOPE_INVALID" : "HTTP_STATUS_UNEXPECTED");
        }
        String code = safeCode(envelope == null ? null : envelope.path("code").asText(), "INVALID_RESPONSE_CODE");
        if (response.statusCode() != expected || !"OK".equals(code) || !envelope.has("data")) {
            httpFailures.add(new HttpFailureEvidence(stage, response.statusCode(), expected, code));
            throw new AssertionError(response.statusCode() == expected ? "HTTP_ENVELOPE_INVALID" : "HTTP_STATUS_UNEXPECTED");
        }
        return envelope.path("data");
    }
    private void writeEvidence() throws Exception {
        SourceEvidence metadata = source == null ? null : new SourceEvidence(source.sourceTenant(), source.model().id(),
                source.model().revision(), source.model().name(), source.model().modelId(), source.model().providerId(),
                source.model().serviceId(), source.model().profileVersion(), source.maxInputTokens(),
                source.model().maxOutputTokens(), source.model().contextWindowTokens(), source.model().reasoning(),
                source.model().timeoutSeconds());
        var evidence = new Evidence("live-public-v2", LivePublicSourceSnapshot.required("LIVE_PUBLIC_RUNTIME_REVISION"),
                PublicOutputGuard.POLICY_VERSION, overall, stage, setupFailureCode, cleanupOutcome,
                List.copyOf(httpFailures), metadata, groups, tools, List.copyOf(scenes), audit.report(),
                Map.of("real_artifact_publication", "D06 coverage candidate; tools/chat_regression/behavior_dataset_repair.py; not original frozen sample replay",
                        "team_member_summary", "D11 coverage candidate; tools/chat_regression/behavior_dataset_repair.py; not original frozen sample replay"),
                List.of("generic_tool_input_uses_explicit_fixture_not_external_mcp", "files_and_ocr", "unknown_provider_outcome",
                        "html_preview_http_verified_browser_render_not_covered"));
        String report = json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence);
        System.out.println(report);
        String destination = System.getenv("LIVE_PUBLIC_EVIDENCE_FILE");
        if (destination != null && !destination.isBlank()) {
            Path path = Path.of(destination).toAbsolutePath().normalize();
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Files.writeString(path, report, StandardCharsets.UTF_8);
        }
    }
    private static JsonNode element(JsonNode array, String field, String expected) {
        for (var item : array) if (expected.equals(item.path(field).asText())) return item;
        throw new AssertionError("CATALOG_ENTRY_MISSING");
    }
    private boolean hasFailedScene() { return scenes.stream().anyMatch(scene -> "FAILED".equals(scene.outcome())); }
    private void recordUnsubmittedScenes() {
        for (String name : SELECTED_SCENES) {
            if (scenes.stream().noneMatch(scene -> name.equals(scene.name())))
                scenes.add(new SceneEvidence(name, "UNVERIFIED", "NOT_SUBMITTED", 0,
                        "SCENARIO_NOT_SUBMITTED"));
        }
    }
    private static String safeCode(String value, String fallback) {
        return value != null && value.matches("[A-Z0-9_]{1,96}") ? value : fallback;
    }
    private long readLong(String value, String field) {
        try { return json.readTree(value).path(field).asLong(); }
        catch (Exception invalid) { throw new IllegalStateException("SOURCE_BUDGET_INVALID"); }
    }
    private long observationDeadline() {
        long wall = source.groups().stream().filter(group -> "task_budget".equals(group.key()))
                .mapToLong(group -> readLong(group.values(), "standard_root_active_seconds")).findFirst().orElseThrow();
        require(wall > 0, "SOURCE_WALL_TIME_INVALID");
        return System.nanoTime() + Duration.ofSeconds(Math.min(wall, OBSERVE_SECONDS)).plus(OBSERVE_MARGIN).toNanos();
    }
    private static boolean selected(String scene) { return SELECTED_SCENES.contains(scene); }
    private static Set<String> selectedScenes() {
        String value = System.getenv("LIVE_PUBLIC_SCENES");
        if (value == null || value.isBlank()) return DEFAULT_SCENES;
        Set<String> selected = Set.copyOf(java.util.Arrays.asList(value.split(",")));
        var allowed = new java.util.HashSet<>(DEFAULT_SCENES); allowed.addAll(Set.of("simple_answer", "business_technical_fields"));
        require(!selected.isEmpty() && allowed.containsAll(selected), "LIVE_SCENE_SELECTION_INVALID");
        return selected;
    }
    private static long configuredObservationSeconds() {
        String value = System.getenv("LIVE_PUBLIC_OBSERVE_SECONDS");
        long seconds = value == null || value.isBlank() ? 900 : Long.parseLong(value);
        require(seconds >= 30 && seconds <= 3600, "LIVE_OBSERVATION_LIMIT_INVALID");
        return seconds;
    }
    private static int explicitPreviewPort() {
        if (!selected("real_artifact_publication") && !selected("html_goal")) return 0;
        int port = Integer.parseInt(LivePublicSourceSnapshot.required("LIVE_PUBLIC_PREVIEW_PORT"));
        require(port >= 1024 && port <= 65535, "EXPLICIT_PREVIEW_PORT_REQUIRED");
        URI platform = URI.create(LivePublicSourceSnapshot.required("LIVE_PUBLIC_PLATFORM_ORIGIN"));
        require("127.0.0.1".equals(platform.getHost()) && "http".equals(platform.getScheme())
                && platform.getPort() > 0 && platform.getPath().isEmpty(), "ISOLATED_PLATFORM_ORIGIN_REQUIRED");
        // 仅启动前检查一次；实际监听器仍由 ArtifactPreviewServer 单次绑定，0语义不变。
        try (var check = new ServerSocket()) { check.setReuseAddress(false); check.bind(new InetSocketAddress("0.0.0.0", port)); }
        catch (Exception unavailable) { throw new IllegalStateException("LIVE_PREVIEW_PORT_UNAVAILABLE"); }
        return port;
    }
    private static Path temporaryRoot() {
        try { return Files.createTempDirectory("skillforge-live-public-").toAbsolutePath(); }
        catch (Exception unavailable) { throw new IllegalStateException("LIVE_TEMP_ROOT_UNAVAILABLE"); }
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static void require(boolean condition, String code) { if (!condition) throw new AssertionError(code); }
    @FunctionalInterface private interface Check { void verify(Result result) throws Exception; }
    record Fixture(String tenant, long endUser, String accessToken) {
        @Override public String toString() { return "Fixture[isolated]"; }
    }
    record Result(String session, String run, String agent, String runStatus, String answer, ChatHistoryResponse history) {
        @Override public String toString() { return "Result[status=" + runStatus + "]"; }
    }
    record Login(String tenantSlug, String username, String password) { }
    record AgentCreate(String agentKey, String name, String systemPrompt) { }
    record AgentPublish(String agentId) { }
    record SessionCreate(String agentId, long endUserId) { }
    record EndUserContext(long endUserId) { }
    record Submit(List<com.skillforge.core.chat.contract.ChatContent> content, String sessionId, String agentId, String teamId, String clientMessageId, EndUserContext context) {
        Submit(String text, String session, String agent, String team, String client, EndUserContext context) {
            this(com.skillforge.core.chat.contract.ChatContent.text(text), session, agent, team, client, context);
        }
    }
    record ResumeSubmit(List<com.skillforge.core.chat.contract.ChatContent> content, String sessionId, String agentId, String clientMessageId,
                        EndUserContext context, PlanReply hitlResponse) {
        ResumeSubmit(String text, String session, String agent, String client, EndUserContext context, PlanReply response) {
            this(com.skillforge.core.chat.contract.ChatContent.text(text), session, agent, client, context, response);
        }
    }
    record GenericResumeSubmit(List<com.skillforge.core.chat.contract.ChatContent> content, String sessionId, String agentId, String clientMessageId,
                        EndUserContext context, Map<String, Object> hitlResponse) {
        GenericResumeSubmit(String text, String session, String agent, String client, EndUserContext context, Map<String, Object> response) {
            this(com.skillforge.core.chat.contract.ChatContent.text(text), session, agent, client, context, response);
        }
    }
    record GoalCreate(String sessionId, String commandId, String objective, int maxRounds) { }
    record PlanReply(String executionId, String runRevision, String checkpointRevision, String actionId, String invocationId,
                     String pendingId, String wakeId, String waitRevision, PlanReview planReview) { }
    record PlanReview(String decision, String feedback) { }
    record AgentUpdate(String agentId, AgentUpdatePayload payload) { }
    record AgentUpdatePayload(AgentDelegationConfig delegationConfig) { }
    record TeamMember(String agentId) { }
    record TeamCreate(String name, String description, String status, long revision, List<TeamMember> members, String primaryAgentId) { }
    record TeamPublish(String id) { }
    record TeamSessionCreate(String teamId, long endUserId) { }
    record DeploymentCreate(String deploymentKey, long toolVersionId, String providerType, String providerRef,
                            String environment, String status) { }
    record ToolConfig(String toolDeploymentId, long toolDeploymentRevision) { }
    record MountPayload(String capabilityType, long capabilityDefinitionId, long capabilityVersionId, ToolConfig toolConfig, int priority) { }
    record MountCreate(String agentId, MountPayload payload) { }
    record ClosePayload(String reason) { }
    record SessionClose(String sessionId, ClosePayload payload) { }
    record MountedTool(String name, String handle, ToolEvidence evidence) { }
    record SourceEvidence(String tenantId, String selectionId, long revision, String name, String modelId,
                          String providerId, String serviceId, String profileVersion, long maxInputTokens,
                          int maxOutputTokens, int contextWindowTokens, String reasoning, int timeoutSeconds) { }
    record GroupEvidence(String key, long sourceRevision, long isolatedRevision) { }
    record ToolEvidence(String name, String version, String contentHash) { }
    record SceneEvidence(String name, String outcome, String runStatus, int modelCalls, String failureCode) { }
    record HttpFailureEvidence(String stage, int status, int expectedStatus, String code) { }
    record Evidence(String suite, String runtimeRevision, String publicPolicyVersion, String outcome, String stage,
                    String setupFailureCode, String cleanupOutcome, List<HttpFailureEvidence> httpFailures, SourceEvidence source,
                    List<GroupEvidence> operationalGroups, List<ToolEvidence> tools, List<SceneEvidence> scenes,
                    LivePublicModelAudit.Report budget, Map<String, String> sceneLineage, List<String> unverified) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class BudgetConfiguration {
        @Bean LivePublicModelAudit livePublicModelAudit(SessionDelegations.Store delegations, JdbcModelBindings bindings) {
            return new LivePublicModelAudit(delegations, bindings);
        }
        @Bean @Primary AgentRuntimeModelInvocationAudit livePublicDispatchedAudit(
                @org.springframework.beans.factory.annotation.Qualifier("agentRuntimeModelInvocationAudit") AgentRuntimeModelInvocationAudit original,
                com.skillforge.core.billing.invocation.ExternalInvocationLedger ledger,
                com.skillforge.core.billing.metering.CostRecorder recorder,
                org.springframework.transaction.PlatformTransactionManager transactions,
                LivePublicModelAudit callbacks, javax.sql.DataSource source,
                org.springframework.beans.factory.ObjectProvider<com.skillforge.institution.runtime.AgentRuntimeWorkflowNodeExecutions> workflows) {
            return new LiveDispatchedModelAudit(original, ledger, recorder, transactions, workflows,
                    actual -> LiveGradleAttemptMeter.wrap(actual, source), callbacks);
        }
    }
}
