package com.skillforge.institution.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.model.AgentDelegationConfig;
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
import com.skillforge.institution.artifact.DeliverableView;
import com.skillforge.institution.controller.ChatGoalController;
import com.skillforge.institution.runtime.AgentRuntimeHostProvisioner;
import com.skillforge.institution.runtime.AgentRuntimeModelInvocationAudit;
import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;
import com.skillforge.institution.runtime.PublicPresentationTexts;
import com.skillforge.institution.team.TeamPublicViews;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.SessionLogPort;
import com.skillforge.runtime.adapter.jdbc.model.JdbcModelBindings;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 两个不可重跑样本：真实 Provider、隔离 PG/Redis、真实本地沙箱、正式发布与委派汇总。
 * 一个场景仅一次 POST；结果通过 GET 观察。秘密、输入正文、模型输出和原始错误不写入证据。
 */
@Import(ChatCapacityLiveAcceptance.BudgetConfiguration.class)
class ChatCapacityLiveAcceptance extends AbstractIntegrationTest {
    @org.junit.jupiter.api.extension.RegisterExtension
    static final LiveGradleAttemptMeter.Outcome liveMeterOutcome = new LiveGradleAttemptMeter.Outcome();
    @Autowired private javax.sql.DataSource liveMeterDataSource;

    private static final String PASSWORD = "LiveCapacityIsolated123";
    private static final String LOG_MARKER = "CAPACITY_INTERNAL_DIAGNOSTIC_ONLY";
    private static final String WEBSITE_TITLE = "测试订单计算器";
    private static final String BUSINESS_UUID = "725013a4-8708-4b62-a752-1e9e2255e857";
    private static final String BUSINESS_DOCUMENT = "business-document-17";
    private static final String BUSINESS_BATCH = "business-batch-42";
    private static final String SYSTEM_DRAFT = "draft";
    private static final long SYSTEM_SEED_REVISION = 1;
    private static final int HTTP_OK = 200, HTTP_CREATED = 201, HTTP_ACCEPTED = 202;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration OBSERVATION_TIMEOUT = Duration.ofMinutes(20);
    private static final long OBSERVATION_INTERVAL = 1500;
    private static final int SOURCE_PAGE_SIZE = 200;
    private static final Set<String> TERMINAL = Set.of("COMPLETED", "FAILED", "TERMINATED", "CANCELLED");
    private static final Ports PORTS = ports();
    private static final Path ARTIFACT_ROOT = artifactRoot();
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
    @Autowired AgentRuntimeHostProvisioner provisioner;
    @Autowired PublicOutputGuard guard;
    @Autowired LiveCapacityBudgets budgets;
    @Autowired LiveCapacityModelAudit audit;
    @Autowired SessionLogPort log;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final List<SceneEvidence> scenes = new ArrayList<>();
    private final List<ToolEvidence> tools = new ArrayList<>();
    private final List<String> sessions = new ArrayList<>();
    private final List<SafeDiagnostic> diagnostics = new ArrayList<>();
    private final List<HttpDiagnostic> httpDiagnostics = new ArrayList<>();
    private LiveCapacitySourceSnapshot.Snapshot source;
    private Fixture fixture;
    private SceneSelection selection = SceneSelection.ALL;
    private SelectedOutcome selectedOutcome = SelectedOutcome.NOT_RUN;
    private String outcome = "NOT_RUN", stage = "NOT_STARTED", setupFailure;

    @DynamicPropertySource static void isolate(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> Integer.toString(PORTS.application()));
        registry.add("skillforge.secret-key", LiveCapacitySourceSnapshot::secretKey);
        registry.add("sa-token.jwt-secret-key", () -> "live-capacity-isolated-auth-key");
        registry.add("skillforge.code-execution.enabled", () -> "true");
        registry.add("skillforge.code-execution.endpoint", () -> LiveCapacitySourceSnapshot.required("SANDBOX_ENDPOINT"));
        registry.add("skillforge.code-execution.service-token", () -> LiveCapacitySourceSnapshot.required("SANDBOX_SERVICE_TOKEN"));
        registry.add("skillforge.code-execution.artifact-root", ARTIFACT_ROOT::toString);
        registry.add("skillforge.code-execution.ticket-key", () -> "live-capacity-isolated-ticket-key-0123456789");
        registry.add("skillforge.code-execution.platform-origin", () -> "http://localhost:" + PORTS.application());
        registry.add("skillforge.code-execution.preview-origin", () -> "http://127.0.0.1:" + PORTS.preview());
        registry.add("skillforge.code-execution.preview-port", () -> Integer.toString(PORTS.preview()));
        registry.add("spring.cloud.nacos.discovery.enabled", () -> "false");
        registry.add("spring.cloud.nacos.config.enabled", () -> "false");
        registry.add("agent-runtime.workers.auto-startup", () -> "true");
        registry.add("logging.level.root", () -> "OFF");
    }

    @Test void currentPublishedModelDeliversWebsiteAndDelegatedBusinessResult() throws Exception {
        try {
            stage = "SCENE_SELECTION";
            selection = SceneSelection.fromEnvironment();
            stage = "SOURCE_READ";
            source = LiveCapacitySourceSnapshot.read();
            audit.freeze(source);
            stage = "RUNTIME_READINESS";
            requireRunningRuntimeWorkers(application, runtime);
            stage = "FIXTURE_CREATE";
            fixture = fixture();
            stage = "SOURCE_INSTALL";
            installSource();
            stage = "HOST_PROVISION";
            provisioner.provision(fixture.tenant());
            stage = "WEBSITE_AGENT_CREATE";
            String delivery = agent("网站交付验收助手", "根据用户明确要求生成可交互网页，完成真实文件检查和正式成果交付。只有正式交付事实成立后才报告成功。 ");
            stage = "WEBSITE_AGENT_MOUNT";
            mount(delivery, List.of("code_execute", "list_artifacts", "finalize_task_outputs", "get_goal", "update_goal"));
            stage = "WEBSITE_AGENT_PUBLISH";
            publish(delivery);
            stage = "CALCULATOR_AGENT_CREATE";
            String calculator = agent("计算核对验收成员", "根据委派任务使用真实计算能力核算，不心算替代工具；返回数值、口径与用户要求的业务字段。 ");
            stage = "CALCULATOR_AGENT_MOUNT";
            mount(calculator, List.of("calculate_expression"));
            stage = "CALCULATOR_AGENT_PUBLISH";
            publish(calculator);
            stage = "COORDINATOR_AGENT_CREATE";
            String coordinator = agent("协作汇总验收助手", "把需要核算的任务委派给计算核对验收成员，持久等待真实结果后汇总，并保留用户明确要求的业务字段。 ");
            stage = "COORDINATOR_BIND_TARGET";
            request("/api/v1/agents/update", "PATCH", new AgentUpdate(coordinator,
                    new AgentUpdatePayload(new AgentDelegationConfig(true, List.of(calculator)))), HTTP_OK);
            stage = "COORDINATOR_AGENT_PUBLISH";
            publish(coordinator);
            if ("1".equals(System.getenv("LIVE_CAPACITY_SETUP_ONLY"))) {
                stage = "SETUP_READY";
                require(budgets.report().actualAttempts() == 0 && scenes.isEmpty(), "SETUP_EXECUTED_MODEL");
                outcome = "SETUP_READY";
                return;
            }
            if (selection == SceneSelection.ALL) {
                run(LiveCapacityBudgets.Scene.WEBSITE, delivery, websiteInput(), this::verifyWebsite);
            } else {
                scenes.add(new SceneEvidence(LiveCapacityBudgets.Scene.WEBSITE, "UNVERIFIED", "NOT_RE_RUN",
                        "PREVIOUS_SUBMISSION_NOT_REPLAYED", null));
            }
            var first = budgets.report();
            if (first.chargedOrReservedTokens() >= LiveCapacityBudgets.MAX_TOKENS
                    || first.reservedSlots() >= LiveCapacityBudgets.MAX_ATTEMPTS) {
                scenes.add(new SceneEvidence(LiveCapacityBudgets.Scene.DELEGATION, "UNVERIFIED", "NOT_SUBMITTED", "LIVE_BUDGET_EXHAUSTED", null));
            } else run(LiveCapacityBudgets.Scene.DELEGATION, coordinator, delegationInput(), this::verifyDelegation);
            boolean passed = selection.scenes().stream().allMatch(selected -> scenes.stream()
                    .anyMatch(scene -> scene.scene() == selected && "PASSED".equals(scene.outcome())));
            selectedOutcome = passed ? SelectedOutcome.PASSED : SelectedOutcome.FAILED;
            outcome = passed ? selection == SceneSelection.ALL ? "PASSED" : "PARTIAL" : "FAILED_OR_UNVERIFIED";
            require(passed, "LIVE_CAPACITY_SELECTED_SCENES_NOT_COMPLETE");
        } catch (AssertionError identified) {
            diagnose(identified);
            if ("NOT_RUN".equals(outcome)) { setupFailure = safeCode(identified.getMessage()); outcome = "FAILED_OR_UNVERIFIED"; }
            throw new AssertionError("LIVE_CAPACITY_ACCEPTANCE_SEE_SAFE_EVIDENCE");
        } catch (Exception unavailable) {
            diagnose(unavailable);
            setupFailure = safeCode(unavailable.getMessage()); outcome = "UNVERIFIED_SETUP";
            throw new AssertionError("LIVE_CAPACITY_ACCEPTANCE_SEE_SAFE_EVIDENCE");
        } finally { budgets.close(); evidence(); }
    }

    private void run(LiveCapacityBudgets.Scene scene, String agent, String input, Check check) throws Exception {
        stage = "SCENE_" + scene.name();
        String session = session(agent);
        budgets.begin(scene, fixture.tenant());
        String state = "NOT_SUBMITTED";
        try {
            var receipt = request("/api/v1/chat", "POST", new Submit(input, session, agent,
                    UUID.randomUUID().toString(), new EndUserContext(fixture.endUser())), HTTP_ACCEPTED);
            String run = receipt.path("execution_id").asText();
            require(!run.isBlank(), "RUN_RECEIPT_MISSING");
            var result = await(session, run, agent, scene);
            state = result.state();
            require("COMPLETED".equals(state), "RUN_DID_NOT_COMPLETE");
            verifyPublic(result);
            var facts = check.verify(result);
            var report = budgets.report().scenes().stream().filter(value -> value.scene() == scene).findFirst().orElseThrow();
            require(report.actualAttempts() > 0 && report.unknownUsageAttempts() == 0, "REAL_USAGE_NOT_VERIFIED");
            require(report.stopCode() == null, "MODEL_SCENE_STOPPED");
            int before = budgets.report().actualAttempts();
            history(session); history(session);
            require(budgets.report().actualAttempts() == before, "GET_ADDED_MODEL_ATTEMPT");
            scenes.add(new SceneEvidence(scene, "PASSED", state, null, facts));
        } catch (Exception | AssertionError failure) {
            diagnose(failure);
            String stop = budgets.stopCode(scene);
            scenes.add(new SceneEvidence(scene, "FAILED", state, stop == null ? safeCode(failure.getMessage()) : stop, null));
            closeSession(session);
        } finally { budgets.end(scene); }
    }

    private Result await(String session, String run, String agent, LiveCapacityBudgets.Scene scene) throws Exception {
        long deadline = System.nanoTime() + OBSERVATION_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            // GET history 允许正式 reconcile；不自动重发任何 POST 或创建新输入。
            var view = history(session);
            String state = scalar("SELECT status FROM agent_runtime_runs WHERE tenant_id=? AND run_id=?", fixture.tenant(), run);
            if (TERMINAL.contains(state)) {
                String answer = view.messages().stream().filter(message -> "assistant".equals(message.role())
                        && message.metadata() != null && "final".equals(message.metadata().get("phase")))
                        .map(message -> message.content() == null ? "" : message.content())
                        .collect(java.util.stream.Collectors.joining("\n"));
                if (!answer.isBlank() || !"COMPLETED".equals(state)) return new Result(session, run, agent, state, answer, view);
            }
            require(budgets.stopCode(scene) == null, "MODEL_SCENE_STOPPED");
            Thread.sleep(OBSERVATION_INTERVAL);
        }
        throw new AssertionError("OBSERVATION_TIMEOUT");
    }
    private void verifyPublic(Result result) {
        var scope = guard.capture(SessionOwner.user(new SessionRef(new TenantId(fixture.tenant()), result.session()),
                new AgentId(result.agent()), new EndUserId(fixture.endUser())));
        require(!scope.rejects(result.answer()), "PRIVATE_VALUE_IN_FINAL");
        var texts = new ArrayList<String>();
        result.history().processes().forEach(process -> texts.addAll(PublicPresentationTexts.read(json.valueToTree(process.presentation()))));
        texts.addAll(PublicPresentationTexts.read(json.valueToTree(result.history().activities())));
        texts.addAll(PublicPresentationTexts.read(json.valueToTree(result.history().pendingInteractions())));
        texts.addAll(PublicPresentationTexts.read(json.valueToTree(result.history().collaboration())));
        result.history().messages().forEach(message -> message.references().forEach(reference -> texts.add(reference.label())));
        require(texts.stream().noneMatch(scope::rejects), "PRIVATE_VALUE_IN_PUBLIC_PRESENTATION");
        require(texts.stream().noneMatch(text -> text.contains(LOG_MARKER)), "RAW_EXECUTION_LOG_PUBLIC");
    }
    private Verification verifyWebsite(Result result) throws Exception {
        var listing = request("/api/v1/chat/sessions/deliverables?sessionId=" + encode(result.session()), "GET", null, HTTP_OK);
        require(listing.isArray() && listing.size() == 1, "FORMAL_WEBSITE_MISSING");
        var deliverable = json.treeToValue(listing.get(0), DeliverableView.class);
        require("ready".equals(deliverable.status()) && WEBSITE_TITLE.equals(deliverable.name()), "FORMAL_WEBSITE_INVALID");
        var file = deliverable.files().stream().filter(value -> "html".equals(value.format()) && value.primaryPreview() && value.entry()).findFirst().orElseThrow();
        var ticket = request("/api/v1/chat/deliverables/preview-ticket", "POST", new FileCommand(deliverable.id(), file.fileId()), HTTP_OK);
        URI uri = URI.create(ticket.path("url").asText());
        require("127.0.0.1".equals(uri.getHost()) && uri.getPort() == PORTS.preview() && uri.getPath().startsWith("/d/"), "FORMAL_PREVIEW_ORIGIN_INVALID");
        var response = http.send(HttpRequest.newBuilder(uri).timeout(HTTP_TIMEOUT).GET().build(), HttpResponse.BodyHandlers.ofString());
        require(response.statusCode() == HTTP_OK, "FORMAL_PREVIEW_FAILED");
        String html = response.body();
        require(html.contains("function") && html.contains("addEventListener") && html.contains("order_id"), "LEGITIMATE_FUNCTION_WEBSITE_INCOMPLETE");
        var scope = guard.capture(SessionOwner.user(new SessionRef(new TenantId(fixture.tenant()), result.session()),
                new AgentId(result.agent()), new EndUserId(fixture.endUser())));
        require(!scope.rejects(html), "PRIVATE_VALUE_IN_FORMAL_WEBSITE");
        String csp = response.headers().firstValue("Content-Security-Policy").orElse("");
        require(csp.contains("script-src") && csp.contains("sha256-") && !csp.contains("unsafe-eval"), "PREVIEW_CSP_INVALID");
        var goal = json.treeToValue(request("/api/v1/chat/goals?session_id=" + encode(result.session()), "GET", null, HTTP_OK), ChatGoalController.GoalStatus.class);
        require(goal.goal() != null && goal.goal().state() == com.skillforge.runtime.session.SessionGoal.State.COMPLETE
                && !goal.completionPending() && goal.publication() != null && "published".equals(goal.publication().status()), "GOAL_NOT_COMPLETED_AFTER_PUBLICATION");
        require(queryCount(jdbc, fixture.tenant(), "SELECT count(*) FROM nw_task_output_plans WHERE tenant_id=? AND run_id=? AND goal_completion_state='settled'",
                fixture.tenant(), result.run()) == 1, "GOAL_COMPLETION_INTENT_NOT_SETTLED");
        require(result.history().processes().stream().filter(process -> "commentary".equals(process.type())).count() >= 2, "INTERLEAVED_COMMENTARY_MISSING");
        return new Verification(true, true, true, false, LiveCapacitySourceSnapshot.hash(html));
    }
    private Verification verifyDelegation(Result result) throws Exception {
        for (String expected : List.of("391", "/api/orders", "order_id", "artifact_id", "run_id", BUSINESS_UUID, BUSINESS_DOCUMENT, BUSINESS_BATCH))
            require(result.answer().contains(expected), "BUSINESS_TECHNICAL_CONTENT_LOST");
        var work = json.treeToValue(request("/api/v1/chat/work?session_id=" + encode(result.session()), "GET", null, HTTP_OK), TeamPublicViews.Work.class);
        require(work.tasks().size() == 1 && !work.results().isEmpty(), "REAL_DELEGATION_MISSING");
        var task = work.tasks().getFirst();
        require(TeamPublicViews.State.COMPLETED.equals(task.status()) && work.results().stream().allMatch(value -> TeamPublicViews.State.READY.equals(value.status())), "MEMBER_RESULT_NOT_READY");
        require(queryCount(jdbc, fixture.tenant(), "SELECT count(*) FROM agent_runtime_actions WHERE tenant_id=? AND run_id=? AND execute_call_kind='TOOL_INVOKE' AND state='SUCCEEDED'",
                fixture.tenant(), task.runId()) >= 1, "REAL_MEMBER_CALCULATION_MISSING");
        String childSession = scalar("SELECT session_id FROM agent_runtime_runs WHERE tenant_id=? AND run_id=?", fixture.tenant(), task.runId());
        require(!"NOT_FOUND".equals(childSession), "REAL_MEMBER_SESSION_MISSING");
        var child = SessionOwner.user(new SessionRef(new TenantId(fixture.tenant()), childSession),
                new AgentId(task.agentId()), new EndUserId(fixture.endUser()));
        boolean verified = false;
        long cursor = 0;
        while (true) {
            var page = log.readAfter(new SessionLogPort.ReadSessionLog(child, cursor, SOURCE_PAGE_SIZE));
            for (var event : page) {
                if (!"agent.tool-result".equals(event.eventType()) || !event.runRef().map(value -> value.runId().equals(task.runId())).orElse(false)) continue;
                var fact = json.readTree(event.payload().canonicalJson());
                if (!"SUCCEEDED".equals(fact.path("status").asText())) continue;
                var output = json.readTree(fact.path("output").path("canonicalJson").asText()).path("structuredContent");
                verified |= output.path("success").asBoolean() && "391".equals(output.path("value").asText());
            }
            if (page.size() < SOURCE_PAGE_SIZE) break;
            cursor = page.getLast().sessionSequence();
        }
        require(verified, "REAL_MEMBER_NUMERIC_RESULT_NOT_VERIFIED");
        var detail = json.treeToValue(request("/api/v1/chat/work/tasks/detail?session_id=" + encode(result.session()) + "&work_id=" + encode(work.workId())
                + "&task_kind=" + encode(task.taskKind().wire()) + "&task_id=" + encode(task.taskId()), "GET", null, HTTP_OK), TeamPublicViews.MemberDetail.class);
        require(detail.run() != null && TeamPublicViews.State.COMPLETED.equals(detail.run().status()), "MEMBER_DETAIL_INVALID");
        var scope = guard.capture(SessionOwner.user(new SessionRef(new TenantId(fixture.tenant()), result.session()),
                new AgentId(result.agent()), new EndUserId(fixture.endUser())));
        require(PublicPresentationTexts.read(json.valueToTree(work)).stream().noneMatch(scope::rejects)
                && PublicPresentationTexts.read(json.valueToTree(detail)).stream().noneMatch(scope::rejects), "PRIVATE_VALUE_IN_MEMBER_VIEW");
        return new Verification(false, false, false, true, LiveCapacitySourceSnapshot.hash(result.answer()));
    }

    private Fixture fixture() throws Exception {
        stage = "FIXTURE_TENANT";
        String slug = "live-capacity-" + UUID.randomUUID();
        String tenant = createTenant(tenants, plans, slug);
        TenantContext.setTenantId(tenant);
        try {
            stage = "FIXTURE_MEMBER";
            var member = new TenantMemberDO(); member.setId(SnowflakeIds.nextId()); member.setTenantId(Long.valueOf(tenant));
            member.setUsername("capacity-admin"); member.setRole("admin"); member.setPermissions(List.of());
            member.setStatus("active"); member.setPasswordHash(passwords.encode(PASSWORD)); member.setCreatedAt(Instant.now()); members.insert(member);
            stage = "FIXTURE_END_USER";
            var user = new EndUserDO(); user.setId(SnowflakeIds.nextId()); user.setTenantId(Long.valueOf(tenant));
            user.setDisplayName("隔离验收用户"); user.setStatus("active"); user.setFirstSeenAt(Instant.now());
            user.setLastActiveAt(Instant.now()); user.setCreatedAt(Instant.now()); users.insert(user);
            stage = "FIXTURE_LOGIN";
            String token = send(null, "/api/v1/auth/login", "POST", new Login(slug, member.getUsername(), PASSWORD), HTTP_OK).path("access_token").asText();
            require(!token.isBlank(), "LOGIN_TOKEN_MISSING"); return new Fixture(tenant, user.getId(), token);
        } finally { TenantContext.clear(); }
    }
    private void installSource() {
        for (var group : source.groups()) { stage = "INITIALIZE_GROUP_" + group.key(); settings.read(group.key()); }
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            stage = "INSTALL_MODEL";
            jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)", String.class, fixture.tenant());
            jdbc.update("INSERT INTO nw_platform_models(id,current_revision,draft,draft_changed) VALUES(CAST(? AS bigint),?,CAST(? AS jsonb),false)",
                    source.model().id(), source.model().revision(), source.encryptedConfig());
            jdbc.update("INSERT INTO nw_platform_model_versions(model_id,revision,config,actor_id) VALUES(CAST(? AS bigint),?,CAST(? AS jsonb),'live-isolated-source')",
                    source.model().id(), source.model().revision(), source.encryptedConfig());
            jdbc.update("INSERT INTO nw_tenant_model_selections(tenant_id,llm_model_config_id) VALUES(CAST(? AS bigint),CAST(? AS bigint))", fixture.tenant(), source.model().id());
            for (var group : source.groups()) {
                stage = "INSTALL_GROUP_" + group.key();
                Long current = jdbc.queryForObject("SELECT current_revision FROM nw_platform_operational_setting_groups WHERE group_key=?", Long.class, group.key());
                require(current != null && current > 0, "ISOLATED_GROUP_MISSING");
                long revision = Math.addExact(current, 1);
                jdbc.update("INSERT INTO nw_platform_operational_setting_versions(group_key,revision,values,publication_source) VALUES(?,?,CAST(? AS jsonb),'system_release')", group.key(), revision, group.values());
                jdbc.update("UPDATE nw_platform_operational_setting_groups SET current_revision=?,updated_at=now() WHERE group_key=?", revision, group.key());
            }
            for (var purpose : source.systems()) {
                stage = "INSTALL_PURPOSE_" + purpose.purpose();
                installPurpose(purpose);
            }
        });
    }
    /** 完整迁移已创建用途草稿；仅在身份一致且尚未维护或发布时装配冻结来源版本。 */
    private void installPurpose(LiveCapacitySourceSnapshot.SystemPurpose purpose) {
        var seeds = jdbc.query("""
                SELECT id::text,agent_key,purpose,execution_mode,status,revision,published_version
                FROM nw_platform_system_agents WHERE purpose=? FOR UPDATE
                """, (rows, index) -> new SeededPurpose(rows.getString(1), rows.getString(2), rows.getString(3),
                rows.getString(4), rows.getString(5), rows.getLong(6), rows.getObject(7, Long.class)), purpose.purpose());
        require(seeds.size() == 1, "ISOLATED_SYSTEM_SEED_MISSING");
        var seed = seeds.getFirst();
        require(purpose.id().equals(seed.id()) && purpose.key().equals(seed.key())
                && purpose.purpose().equals(seed.purpose()) && purpose.mode().equals(seed.mode()),
                "ISOLATED_SYSTEM_SEED_IDENTITY_MISMATCH");
        Long versions = jdbc.queryForObject("SELECT count(*) FROM nw_platform_system_agent_versions WHERE agent_id=CAST(? AS bigint)",
                Long.class, seed.id());
        require(SYSTEM_DRAFT.equals(seed.status()) && seed.revision() == SYSTEM_SEED_REVISION
                && seed.publishedVersion() == null && versions != null && versions == 0,
                "ISOLATED_SYSTEM_SEED_ALREADY_MAINTAINED");
        jdbc.update("INSERT INTO nw_platform_system_agent_versions(agent_id,version,config,actor_id) VALUES(CAST(? AS bigint),?,CAST(? AS jsonb),'live-isolated-source')",
                seed.id(), purpose.version(), purpose.config());
        int changed = jdbc.update("""
                UPDATE nw_platform_system_agents SET status='published',revision=?,published_version=?,draft=CAST(? AS jsonb),updated_at=now()
                WHERE id=CAST(? AS bigint) AND agent_key=? AND purpose=? AND execution_mode=?
                AND status=? AND revision=? AND published_version IS NULL
                """, purpose.revision(), purpose.version(), purpose.config(), seed.id(), purpose.key(), purpose.purpose(),
                purpose.mode(), SYSTEM_DRAFT, SYSTEM_SEED_REVISION);
        require(changed == 1, "ISOLATED_SYSTEM_SEED_CHANGED");
    }
    private String agent(String name, String prompt) throws Exception {
        String id = request("/api/v1/agents", "POST", new AgentCreate("live-capacity-" + UUID.randomUUID(), name, prompt), HTTP_OK).path("id").asText();
        require(!id.isBlank(), "AGENT_MISSING"); return id;
    }
    private void publish(String id) throws Exception { request("/api/v1/agents/publish", "POST", new AgentPublish(id), HTTP_OK); }
    private void mount(String agent, List<String> names) throws Exception {
        var definitions = request("/api/v1/capabilities/definitions?capability_type=TOOL&status=PUBLISHED", "GET", null, HTTP_OK);
        for (String name : names) {
            var entry = new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()).entries().stream().filter(value -> name.equals(value.name())).findFirst().orElseThrow();
            long definition = element(definitions, "capability_key", "tool:" + name).path("id").asLong();
            var versions = request("/api/v1/capabilities/definitions/versions?status=PUBLISHED&definitionId=" + definition, "GET", null, HTTP_OK);
            long version = element(versions, "version_key", entry.publishedVersionKey()).path("id").asLong();
            String key = "live-capacity-" + name + "-" + UUID.randomUUID();
            long revision = request("/api/v1/capabilities/deployments", "POST", new DeploymentCreate(key, version, "PLATFORM", name, "TEST", "ACTIVE"), HTTP_CREATED).path("revision").asLong();
            request("/api/v1/agents/capability-mounts", "POST", new MountCreate(agent, new MountPayload("TOOL", definition, version, new ToolConfig(key, revision), 0)), HTTP_CREATED);
            tools.add(new ToolEvidence(name, entry.publishedVersionKey(), entry.contentHash()));
        }
    }
    private String session(String agent) throws Exception {
        String id = request("/api/v1/chat/sessions", "POST", new SessionCreate(agent, fixture.endUser()), HTTP_OK).path("session_id").asText();
        require(!id.isBlank(), "SESSION_MISSING"); sessions.add(id); return id;
    }
    private ChatHistoryResponse history(String session) throws Exception {
        return json.treeToValue(request("/api/v1/chat/history?session_id=" + encode(session), "GET", null, HTTP_OK), ChatHistoryResponse.class);
    }
    private String scalar(String sql, Object... values) {
        return jdbc.execute((ConnectionCallback<String>) connection -> {
            try (var bind = connection.prepareStatement("SELECT set_config('app.tenant_id',?,true)")) { bind.setString(1, fixture.tenant()); bind.execute(); }
            try (var query = connection.prepareStatement(sql)) {
                for (int index = 0; index < values.length; index++) query.setObject(index + 1, values[index]);
                try (var rows = query.executeQuery()) { return rows.next() ? rows.getString(1) : "NOT_FOUND"; }
            }
        });
    }
    private JsonNode request(String path, String method, Object body, int status) throws Exception { return send(fixture.accessToken(), path, method, body, status); }
    private JsonNode send(String token, String path, String method, Object body, int expected) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(HTTP_TIMEOUT).header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode envelope;
        try { envelope = json.readTree(response.body()); }
        catch (Exception invalid) {
            httpDiagnostics.add(new HttpDiagnostic(stage, response.statusCode(), expected, "NON_JSON_RESPONSE"));
            throw new AssertionError("HTTP_ENVELOPE_INVALID");
        }
        if (response.statusCode() != expected || envelope == null || !"OK".equals(envelope.path("code").asText())) {
            httpDiagnostics.add(new HttpDiagnostic(stage, response.statusCode(), expected,
                    envelope == null ? "INVALID_RESPONSE_CODE" : safeCode(envelope.path("code").asText())));
        }
        require(response.statusCode() == expected, "HTTP_STATUS_UNEXPECTED");
        require(envelope != null && "OK".equals(envelope.path("code").asText()) && envelope.has("data"), "HTTP_ENVELOPE_INVALID");
        return envelope.path("data");
    }
    private void closeSession(String session) {
        try { request("/api/v1/chat/sessions/close", "POST", new SessionClose(session, new ClosePayload("live-capacity-finished")), HTTP_OK); }
        catch (Exception | AssertionError ignored) { /* 清理只停止本次隔离会话；证据和文件保留。 */ }
    }
    @AfterEach void cleanup() throws Exception {
        try { budgets.close(); sessions.forEach(this::closeSession); http.close(); evidence();
        } finally {
            LiveGradleAttemptMeter.snapshot(liveMeterDataSource, fixture == null ? null : fixture.tenant());
        }
    }
    private void evidence() throws Exception {
        var model = source == null ? null : new ModelEvidence(source.model().revision(), source.model().modelId(), source.model().profileVersion(),
                source.model().contextWindowTokens(), source.model().maxOutputTokens(), source.model().timeoutSeconds(),
                LiveCapacitySourceSnapshot.hash(source.model().id() + ":" + source.model().revision()));
        var groups = source == null ? List.<ConfigurationEvidence>of() : source.groups().stream().map(value -> new ConfigurationEvidence(value.key(), value.revision(), LiveCapacitySourceSnapshot.hash(value.values()))).toList();
        var systems = source == null ? List.<ConfigurationEvidence>of() : source.systems().stream().map(value -> new ConfigurationEvidence(value.purpose(), value.version(), LiveCapacitySourceSnapshot.hash(value.config()))).toList();
        var report = new Evidence("live-chat-capacity-v1", LiveCapacitySourceSnapshot.required("LIVE_CAPACITY_RUNTIME_REVISION"), outcome, stage, setupFailure,
                selection, selection.scenes(), selectedOutcome,
                List.copyOf(diagnostics), List.copyOf(httpDiagnostics),
                model, groups, systems, List.copyOf(tools), List.copyOf(scenes), budgets.report(),
                List.of("browser_interaction_theme_keyboard", "model_window_overflow_recovery", "tool_wait", "unknown_provider_outcome", "ocr"));
        Path path = Path.of(LiveCapacitySourceSnapshot.required("LIVE_CAPACITY_EVIDENCE_FILE")).toAbsolutePath().normalize();
        Files.createDirectories(path.getParent()); Files.writeString(path, json.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8);
        System.out.println("LIVE_CHAT_CAPACITY_OUTCOME=" + outcome);
    }
    /** 只记录异常类型、SQLState、约束名及有限源码帧，永不记录 message、SQL 或参数。 */
    private void diagnose(Throwable failure) {
        final int maximumCauses = 8, maximumFrames = 4;
        for (int depth = 0; failure != null && depth < maximumCauses; depth++, failure = failure.getCause()) {
            String sqlState = failure instanceof SQLException sql && sql.getSQLState() != null
                    && sql.getSQLState().matches("[A-Z0-9]{5}") ? sql.getSQLState() : null;
            String constraint = null;
            if ("org.postgresql.util.PSQLException".equals(failure.getClass().getName())) {
                try {
                    Object server = failure.getClass().getMethod("getServerErrorMessage").invoke(failure);
                    Object value = server == null ? null : server.getClass().getMethod("getConstraint").invoke(server);
                    if (value instanceof String text && text.matches("[a-z][a-z0-9_]{0,160}")) constraint = text;
                } catch (ReflectiveOperationException ignored) { /* 不添加异常正文。 */ }
            }
            var frames = java.util.Arrays.stream(failure.getStackTrace())
                    .filter(frame -> frame.getClassName().startsWith("com.skillforge."))
                    .limit(maximumFrames).map(frame -> new SourceFrame(frame.getClassName(), frame.getMethodName(),
                            frame.getFileName(), frame.getLineNumber())).toList();
            diagnostics.add(new SafeDiagnostic(stage, failure.getClass().getSimpleName(), sqlState, constraint, frames));
        }
    }
    private static String websiteInput() {
        return "/goal --rounds 1 请制作并正式交付一个单文件HTML交互网站，成果标题必须为“" + WEBSITE_TITLE + "”。"
                + "这是明确的HTML格式要求，不需要再问。页面展示order_id、数量17、单价23和总金额391，点击按钮可重新计算。"
                + "原生JavaScript必须含普通命名function、匿名function和addEventListener，不使用事件属性、eval或Function构造器，不访问外部网络。"
                + "使用真实代码执行生成文件，保存为order-calculator.html；脚本stdout打印" + LOG_MARKER + "作为内部调试日志，该日志不要成为公开业务结果或最终答复。"
                + "先用简短助手说明介绍准备工作，文件生成并核对后再发一次说明，随后准备正式成果清单。"
                + "其他验收完成后读取当前目标版本，在最后一次成果冻结提交目标完成请求；最终回复说明清单已经冻结，正式发布及目标完成由系统确认，不提前声称成功。";
    }
    private static String delegationInput() {
        return "请把17*23的计算核对委派给计算核对验收成员，必须由该成员使用真实计算能力，等待真实结果后再汇总。"
                + "最终回复计算值和以下原样业务接口示例：POST /api/orders，{\"order_id\":\"" + BUSINESS_UUID
                + "\",\"artifact_id\":\"" + BUSINESS_DOCUMENT + "\",\"run_id\":\"" + BUSINESS_BATCH + "\"}。"
                + "这些字段和值属于订单业务示例，请保留。成员只做这一个核对任务，不新增成员、子任务或成果文件。";
    }
    private static JsonNode element(JsonNode array, String field, String expected) {
        for (var item : array) if (expected.equals(item.path(field).asText())) return item;
        throw new AssertionError("CATALOG_ENTRY_MISSING");
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String safeCode(String code) { return code != null && code.matches("[A-Z0-9_]{1,96}") ? code : "SCENE_OR_SETUP_FAILED"; }
    private static void require(boolean condition, String code) { if (!condition) throw new AssertionError(code); }
    private static Ports ports() {
        try (var application = new ServerSocket(); var preview = new ServerSocket()) {
            application.setReuseAddress(false); preview.setReuseAddress(false);
            application.bind(new InetSocketAddress("127.0.0.1", 0)); preview.bind(new InetSocketAddress("127.0.0.1", 0));
            return new Ports(application.getLocalPort(), preview.getLocalPort());
        } catch (Exception unavailable) { throw new IllegalStateException("ISOLATED_PORT_UNAVAILABLE"); }
    }
    private static Path artifactRoot() {
        try { return Files.createTempDirectory("skillforge-live-capacity-").toAbsolutePath(); }
        catch (Exception unavailable) { throw new IllegalStateException("ISOLATED_ARTIFACT_ROOT_UNAVAILABLE"); }
    }
    @FunctionalInterface private interface Check { Verification verify(Result result) throws Exception; }
    /** 显式受限续接只允许尚未提交的协作场景；不提供重跑网站样本的选择。 */
    enum SceneSelection {
        ALL(List.of(LiveCapacityBudgets.Scene.WEBSITE, LiveCapacityBudgets.Scene.DELEGATION)),
        DELEGATION(List.of(LiveCapacityBudgets.Scene.DELEGATION));
        private final List<LiveCapacityBudgets.Scene> scenes;
        SceneSelection(List<LiveCapacityBudgets.Scene> scenes) { this.scenes = List.copyOf(scenes); }
        List<LiveCapacityBudgets.Scene> scenes() { return scenes; }
        static SceneSelection fromEnvironment() {
            String value = System.getenv("LIVE_CAPACITY_SCENE");
            if (value == null || value.isBlank()) return ALL;
            try { return valueOf(value.trim()); }
            catch (IllegalArgumentException invalid) { throw new IllegalStateException("LIVE_CAPACITY_SCENE_INVALID"); }
        }
    }
    enum SelectedOutcome { NOT_RUN, PASSED, FAILED }
    record Ports(int application, int preview) { }
    record Fixture(String tenant, long endUser, String accessToken) { @Override public String toString() { return "Fixture[isolated]"; } }
    record SeededPurpose(String id, String key, String purpose, String mode, String status, long revision, Long publishedVersion) { }
    record Result(String session, String run, String agent, String state, String answer, ChatHistoryResponse history) { @Override public String toString() { return "Result[" + state + "]"; } }
    record Login(String tenantSlug, String username, String password) { }
    record AgentCreate(String agentKey, String name, String systemPrompt) { }
    record AgentPublish(String agentId) { }
    record AgentUpdate(String agentId, AgentUpdatePayload payload) { }
    record AgentUpdatePayload(AgentDelegationConfig delegationConfig) { }
    record EndUserContext(long endUserId) { }
    record SessionCreate(String agentId, long endUserId) { }
    record Submit(List<com.skillforge.core.chat.contract.ChatContent> content, String sessionId, String agentId, String clientMessageId, EndUserContext context) {
        Submit(String message, String sessionId, String agentId, String clientMessageId, EndUserContext context) {
            this(com.skillforge.core.chat.contract.ChatContent.text(message), sessionId, agentId, clientMessageId, context);
        }
    }
    record DeploymentCreate(String deploymentKey, long toolVersionId, String providerType, String providerRef, String environment, String status) { }
    record ToolConfig(String toolDeploymentId, long toolDeploymentRevision) { }
    record MountPayload(String capabilityType, long capabilityDefinitionId, long capabilityVersionId, ToolConfig toolConfig, int priority) { }
    record MountCreate(String agentId, MountPayload payload) { }
    record FileCommand(String id, String fileId) { }
    record ClosePayload(String reason) { }
    record SessionClose(String sessionId, ClosePayload payload) { }
    record Verification(boolean formalPublication, boolean cspVerified, boolean goalCompleted, boolean realMemberCalculation, String contentHash) { }
    record SceneEvidence(LiveCapacityBudgets.Scene scene, String outcome, String runStatus, String failureCode, Verification verification) { }
    record ToolEvidence(String name, String version, String contentHash) { }
    record ModelEvidence(long revision, String modelId, String profileVersion, int contextWindow, int outputLimit, int timeoutSeconds, String bindingHash) { }
    record ConfigurationEvidence(String name, long sourceRevision, String contentHash) { }
    record SourceFrame(String className, String method, String file, int line) { }
    record SafeDiagnostic(String stage, String exceptionType, String sqlState, String constraint, List<SourceFrame> frames) { }
    record HttpDiagnostic(String stage, int actualStatus, int expectedStatus, String code) { }
    record Evidence(String suite, String runtimeRevision, String outcome, String stage, String setupFailure,
                    SceneSelection sceneSelection, List<LiveCapacityBudgets.Scene> selectedScenes, SelectedOutcome selectedOutcome,
                    List<SafeDiagnostic> diagnostics, List<HttpDiagnostic> httpDiagnostics,
                    ModelEvidence model, List<ConfigurationEvidence> configuration, List<ConfigurationEvidence> systemPurposes,
                    List<ToolEvidence> tools, List<SceneEvidence> scenes, LiveCapacityBudgets.Report budget, List<String> unverified) { }
    @TestConfiguration(proxyBeanMethods = false)
    static class BudgetConfiguration {
        @Bean @Primary LiveCapacityBudgets liveCapacityBudgets(DataSource source) { return new LiveCapacityBudgets(source); }
        @Bean LiveCapacityModelAudit liveCapacityAudit(JdbcModelBindings bindings, LiveCapacityBudgets budgets) {
            return new LiveCapacityModelAudit(bindings, budgets);
        }
        @Bean @Primary AgentRuntimeModelInvocationAudit liveCapacityDispatchedAudit(
                @org.springframework.beans.factory.annotation.Qualifier("agentRuntimeModelInvocationAudit") AgentRuntimeModelInvocationAudit original,
                com.skillforge.core.billing.invocation.ExternalInvocationLedger ledger,
                com.skillforge.core.billing.metering.CostRecorder recorder,
                org.springframework.transaction.PlatformTransactionManager transactions,
                LiveCapacityModelAudit callbacks, DataSource source,
                org.springframework.beans.factory.ObjectProvider<com.skillforge.institution.runtime.AgentRuntimeWorkflowNodeExecutions> workflows) {
            return new LiveDispatchedModelAudit(original, ledger, recorder, transactions, workflows,
                    actual -> LiveGradleAttemptMeter.wrap(actual, source), callbacks);
        }
    }
}
