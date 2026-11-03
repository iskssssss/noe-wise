package com.skillforge.institution.integration;

import com.skillforge.runtime.api.MemoryOwner;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.agent.memory.MemoryContent;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.AgentPublicationService;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.model.invocation.LlmResult;
import com.skillforge.core.model.invocation.LlmService;
import com.skillforge.core.billing.metering.LlmUsage;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.billing.persistence.PlanDO;
import com.skillforge.core.foundation.application.EndUserService;
import com.skillforge.institution.memory.MemoryDecayWorker;
import com.skillforge.institution.memory.MemoryDecayTransitionGuard;
import com.skillforge.institution.memory.MemoryExtractionWorker;
import com.skillforge.institution.memory.MemoryGovernanceService;
import com.skillforge.institution.runtime.AgentRuntimeHostProvisioner;
import com.skillforge.runtime.adapter.jdbc.session.JdbcSessionLogPort;
import com.skillforge.runtime.adapter.jdbc.memory.JdbcMemoryPort;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.FactStatus;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.MemoryFact;
import com.skillforge.runtime.api.MemoryQuery;
import com.skillforge.runtime.api.MemoryQueryProfile;
import com.skillforge.runtime.api.MemoryRetrievalPolicy;
import com.skillforge.runtime.adapter.jdbc.memory.search.MemorySearchDocument;
import com.skillforge.runtime.api.MemoryScope;
import com.skillforge.runtime.api.MemoryTypeRef;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RetentionClassRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.IdempotencyKey;
import com.skillforge.runtime.api.port.InvocationControl;
import com.skillforge.runtime.api.port.MemoryPort.MemoryFactDraft;
import com.skillforge.runtime.api.port.MemoryPort.MemoryCommand;
import com.skillforge.runtime.api.port.MemoryPort.MemoryMutationResult;
import com.skillforge.runtime.api.port.PortCallContext;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionEventDraft;
import com.skillforge.runtime.api.port.SessionLogPort;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.SessionProjectionTarget;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.skillforge.runtime.memory.MemoryRuntime;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** V144 的原子提取、晋升、审核和事实演进生产链。 */
class MemoryProductizationIntegrationTest extends AbstractIntegrationTest {
    @Autowired TenantMapper tenantMapper;
    @Autowired PlanMapper planMapper;
    @Autowired EndUserService endUsers;
    @Autowired MemoryExtractionWorker extraction;
    @Autowired MemoryDecayWorker decay;
    @Autowired MemoryGovernanceService governance;
    @Autowired com.skillforge.institution.group.GroupBindingService groups;
    @Autowired MemoryRuntime runtime;
    @Autowired JdbcMemoryPort memoryStore;
    @MockitoSpyBean MemoryDecayTransitionGuard transitionGuard;
    @Autowired com.skillforge.core.agent.AgentService agents;
    @Autowired AgentPublicationService publications;
    @Autowired ObjectMapper json;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @LocalServerPort private int port;
    @MockitoBean LlmService llm;
    @MockitoBean com.skillforge.core.model.PlatformModelCatalogService models;
    @Autowired com.skillforge.core.agent.systemagent.SystemAgentService systemAgents;
    @MockitoBean AgentRuntimeHostProvisioner hostProvisioner;
    // 本套测试显式poll，避免后台定时任务抢走READY样本造成时序偶现。
    @MockitoBean org.springframework.scheduling.TaskScheduler taskScheduler;

    private String tenant;
    private long endUser;
    private SessionOwner owner;
    private static final String GOVERNANCE_USERNAME = "memory-governance-admin";
    private static final String GOVERNANCE_PASSWORD = "ControlledMemory1234";

    @BeforeEach
    void setUp() {
        var model = mock(com.skillforge.core.model.PlatformModelConfig.class);
        when(model.revision()).thenReturn(1L);
        when(models.requireEnabled("1", "LLM")).thenReturn(model);
        var current = systemAgents.detail("900000000000000003");
        var draft = current.draft();
        var changed = systemAgents.update(current.id(), current.revision(),
                new com.skillforge.core.agent.systemagent.SystemAgentTypes.Config(draft.name(), draft.description(),
                        draft.systemPrompt(), "1", 30, 1, List.of()), "test");
        systemAgents.transition(changed.id(), changed.revision(), "publish", "test");
        PlanDO plan = ensureFreePlan(planMapper);
        tenant = createTenant(tenantMapper, planMapper, "memory-product-" + SnowflakeIds.nextId());
        TenantContext.setTenantId(tenant);
        endUser = endUsers.create(tenant, "Memory 测试用户").getId();
        owner = memoryOwner(Map.of("memory",Map.of("automatic_extraction",true)),"published");
        lenient().when(llm.generatePinned(anyString(), anyString(), anyLong(), anyString(), anyString(), any(), any(), anyInt(), any()))
                .thenReturn(new LlmResult("{\"schemaVersion\":2,\"candidates\":[]}", "stub", LlmUsage.empty()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void ordinaryInboundRequiresExactBooleanOptInAndPublishedAgentWhileExplicitCandidatesRemain() {
        for (var configuration : List.<Map<String,Object>>of(Map.of(),Map.of("memory",Map.of()),
                Map.of("memory",Map.of("automatic_extraction",false)),
                Map.of("memory",Map.of("automatic_extraction","true")))) {
            var disabled=memoryOwner(configuration,"published");
            String event="disabled-inbound-"+SnowflakeIds.nextId();
            appendInbound(disabled,event,"你好");
            assertTrue(queryRows(jdbc,tenant,"select status from agent_runtime_memory_extraction_jobs where tenant_id=? and source_event_id=?",tenant,event).isEmpty());
        }
        for(String status:List.of("draft","disabled","archived")) {
            var unavailable=memoryOwner(Map.of("memory",Map.of("automatic_extraction",true)),status);
            String event="unpublished-inbound-"+SnowflakeIds.nextId();
            appendInbound(unavailable,event,"你好");
            assertTrue(queryRows(jdbc,tenant,"select status from agent_runtime_memory_extraction_jobs where tenant_id=? and source_event_id=?",tenant,event).isEmpty());
        }
        var explicit=memoryOwner(Map.of(),"published");
        String event="explicit-without-auto-"+SnowflakeIds.nextId();
        append(explicit,event,false,"explicit_save","compact");
        drainExtraction();
        assertEquals(1,governance.list(tenant,null,20).size());
        org.mockito.Mockito.verifyNoInteractions(llm);
    }

    @Test
    void queuedAutomaticExtractionIsSkippedWithoutModelWhenAgentWasDisabledAfterEnqueue() {
        String event="queued-before-disable-"+SnowflakeIds.nextId();
        appendInbound(event,"请记住我的偏好");
        assertEquals("READY",jobStatus(event));
        agents.update(new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<com.skillforge.core.agent.model.AgentDO>()
                .eq(com.skillforge.core.agent.model.AgentDO::getTenantId,Long.valueOf(tenant))
                .eq(com.skillforge.core.agent.model.AgentDO::getId,Long.valueOf(owner.target().requireAgent().value()))
                .set(com.skillforge.core.agent.model.AgentDO::getStatus,"disabled"));
        drainExtraction();
        assertEquals("SKIPPED",jobStatus(event));
        assertTrue(governance.list(tenant,null,20).isEmpty());
        org.mockito.Mockito.verifyNoInteractions(llm);
    }

    @Test
    void committedCandidatesSurviveWorkerReplayAndSupportFullGovernanceLifecycle() {
        append("memory-observed", false, "observed", "light");
        extraction.poll();
        extraction.poll();
        assertNoFailedExtraction();

        List<MemoryFact> initial = governance.list(tenant, null, 20);
        assertEquals(1, initial.size());
        assertEquals("USER_AGENT", initial.getFirst().scope().name());
        assertEquals("episode", initial.getFirst().type().value());
        assertEquals(initial.getFirst().validFrom().plusSeconds(24*60*60),initial.getFirst().expiresAt().orElseThrow());
        assertEquals("ACTIVE", initial.getFirst().status().name());

        append("memory-sensitive", true, "explicit_save", "dark");
        extraction.poll();
        MemoryFact pending = governance.list(tenant, "PENDING_CONFIRM", 20).getFirst();
        assertEquals("USER_AGENT", pending.scope().name());
        assertTrue(pending.reviewRequired());

        MemoryFact approved = governance.transition(tenant, pending.ref().factId(), pending.revision(),
                com.skillforge.runtime.api.FactStatus.ACTIVE, "approved", "review-sensitive");
        MemoryFact corrected = governance.correct(tenant, approved.ref().factId(), approved.revision(),
                approved.subject(), approved.predicate(), explicitContent("value","blue"), 0.95, 0.8,
                "correct-sensitive");
        assertNotEquals(approved.ref(), corrected.ref());
        assertEquals(approved.ref(), corrected.derivedFrom().orElseThrow());

        MemoryFact forgotten = governance.transition(tenant, corrected.ref().factId(), corrected.revision(),
                com.skillforge.runtime.api.FactStatus.FORGOTTEN, "forgotten", "forget-sensitive");
        MemoryFact restored = governance.transition(tenant, forgotten.ref().factId(), forgotten.revision(),
                com.skillforge.runtime.api.FactStatus.ACTIVE, "restored", "restore-sensitive");
        assertEquals("ACTIVE", restored.status().name());
    }

    @Test
    void oldUsageProjectionDecaysFactAndRestoreResetsDecay() {
        append("memory-decay", false, "observed", "compact");
        extraction.poll();
        MemoryFact active = governance.list(tenant, null, 20).getFirst();

        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            jdbc.queryForObject("select set_config('app.tenant_id', ?, true)", String.class, tenant);
            jdbc.update("insert into agent_runtime_memory_usage_projection(tenant_id,fact_id,"
                            + "candidate_count,last_referenced_at,projected_decay_score,updated_at) "
                            + "values(?,?,1,now()-interval '100 days',0,now()-interval '100 days')",
                    tenant, active.ref().factId());
        });

        decay.poll();

        MemoryFact decayed = governance.list(tenant, "DECAYED", 20).getFirst();
        assertEquals(active.ref(), decayed.ref());
        assertEquals(1.0, decayed.decayScore());

        MemoryFact restored = governance.transition(tenant, decayed.ref().factId(), decayed.revision(),
                com.skillforge.runtime.api.FactStatus.ACTIVE, "restored", "restore-decayed");
        assertEquals("ACTIVE", restored.status().name());
        assertEquals(0.0, governance.list(tenant, "ACTIVE", 20).getFirst().decayScore());
    }

    @Test
    void unreferencedFactUsesCreationAgeAcrossProjectionRefreshes() throws InterruptedException {
        append("memory-null-reference", false, "observed", "compact");
        drainExtraction();
        MemoryFact fact = governance.list(tenant, "ACTIVE", 20).getFirst();
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            jdbc.queryForObject("select set_config('app.tenant_id', ?, true)", String.class, tenant);
            jdbc.update("insert into agent_runtime_memory_usage_projection(tenant_id,fact_id,"
                    + "candidate_count,updated_at) values(?,?,1,now()-interval '100 days')",
                    tenant, fact.ref().factId());
        });
        // 等待数据库权威创建时间产生可表示的衰减，不修改不可变事实或只等待固定时长。
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (queryCount(jdbc, tenant, "select count(*) from agent_runtime_memory_fact_revisions "
                + "where tenant_id=? and fact_id=? and revision=1 and "
                + "extract(epoch from now())-created_epoch_second-created_nano/1000000000.0>=2",
                tenant, fact.ref().factId()) == 0) {
            assertTrue(System.nanoTime() < deadline, "database fact age did not reach two seconds");
            Thread.sleep(20);
        }
        decay.poll();
        double first = governance.list(tenant, "ACTIVE", 20).getFirst().decayScore();
        assertTrue(first > 0 && first < 0.001, "projection updated_at must not age a new fact by 100 days");
        decay.poll();
        double second = governance.list(tenant, "ACTIVE", 20).getFirst().decayScore();
        assertTrue(second >= first, "refresh must not reset an unreferenced fact's decay age");
        assertTrue(queryRows(jdbc, tenant, "select last_referenced_at from agent_runtime_memory_usage_projection "
                + "where tenant_id=? and fact_id=?", tenant, fact.ref().factId()).getFirst()
                .get("last_referenced_at") == null);
    }

    @Test
    void extractionAndDecayReachTenantBeyondFirstTwoHundred() {
        var fillers = new java.util.ArrayList<String>();
        try {
            TenantContext.clear();
            for (int index = 0; index < 201; index++)
                fillers.add(createTenant(tenantMapper, planMapper, "memory-rotation-" + SnowflakeIds.nextId()));
            tenant = createTenant(tenantMapper, planMapper, "memory-after-page-" + SnowflakeIds.nextId());
            TenantContext.setTenantId(tenant);
            endUser = endUsers.create(tenant, "轮转测试用户").getId();
            owner = memoryOwner(Map.of(), "published");
            assertTrue(jdbc.queryForObject("select count(*) from nw_tenants where status='active' and id<?",
                    Long.class, Long.valueOf(tenant)) >= 201);
            append("memory-rotation-target", false, "observed", "compact");
            extraction.poll();
            assertTrue(governance.list(tenant, null, 20).isEmpty());
            int rounds = jdbc.queryForObject("select count(*) from nw_tenants where status='active'", Integer.class) / 200 + 2;
            for (int round = 0; round < rounds; round++) extraction.poll();
            assertNoFailedExtraction();
            MemoryFact fact = governance.list(tenant, "ACTIVE", 20).getFirst();
            new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
                jdbc.queryForObject("select set_config('app.tenant_id', ?, true)", String.class, tenant);
                jdbc.update("insert into agent_runtime_memory_usage_projection(tenant_id,fact_id,last_referenced_at) "
                        + "values(?,?,now()-interval '100 days')", tenant, fact.ref().factId());
            });
            decay.poll();
            assertTrue(governance.list(tenant, "DECAYED", 20).isEmpty());
            for (int round = 0; round < rounds; round++) decay.poll();
            assertEquals(fact.ref(), governance.list(tenant, "DECAYED", 20).getFirst().ref());
        } finally {
            // 仅收束本测试创建的租户，避免影响同一套件其他单页场景。
            for (String filler : fillers) jdbc.update("update nw_tenants set status='suspended' where id=?", Long.valueOf(filler));
        }
    }

    @Test
    void conflictingPromotedCandidateRequiresReview() {
        append("memory-conflict-first", false, "explicit_save", "light");
        extraction.poll();
        MemoryFact first = governance.list(tenant, "ACTIVE", 20).getFirst();
        assertEquals("USER_AGENT", first.scope().name());

        append("memory-conflict-second", false, "explicit_save", "dark");
        extraction.poll();

        List<MemoryFact> pending = governance.list(tenant, "PENDING_CONFIRM", 20);
        assertEquals(1, pending.size());
        assertEquals(first.subject(), pending.getFirst().subject());
        assertEquals(first.predicate(), pending.getFirst().predicate());
        assertTrue(pending.getFirst().reviewRequired());
    }

    @Test
    void exactConflictCheckIsNotLimitedBySemanticCandidateBudget() {
        append("memory-conflict-beyond-budget", false, "explicit_save", "old-conflict");
        extraction.poll();
        MemoryFact oldConflict = governance.list(tenant, "ACTIVE", 20).getFirst();
        // 本例只验证冲突查询预算，临时衰减不引入另有专项覆盖的历史遗忘确认门禁。
        MemoryFact decayed = governance.transition(tenant, oldConflict.ref().factId(),
                oldConflict.revision(), com.skillforge.runtime.api.FactStatus.DECAYED,
                "prepare-budget-fixture", "decay-old-conflict");
        for (int index = 0; index < 100; index++) {
            append("memory-same-" + index, false, "explicit_save", "incoming");
            extraction.poll();
        }
        governance.transition(tenant, decayed.ref().factId(), decayed.revision(),
                com.skillforge.runtime.api.FactStatus.ACTIVE,
                "prepare-budget-fixture", "restore-old-conflict");

        append("memory-conflict-after-budget", false, "explicit_save", "incoming");
        extraction.poll();

        List<MemoryFact> pending = governance.list(tenant, "PENDING_CONFIRM", 20);
        assertEquals(1, pending.size());
        assertTrue(pending.getFirst().reviewRequired());
    }

    @Test
    void inboundHydrateGreetingYieldsNoFacts() {
        String eventId = "hydrate-hello-" + SnowflakeIds.nextId();
        appendInbound(eventId, "你好");
        drainExtraction();

        assertJobOutcome(eventId, "SKIPPED", "memory_extraction_empty");
        assertTrue(governance.list(tenant, null, 20).isEmpty());
    }

    @Test
    void inboundHydrateEpisodeBecomesUserAgentFact() {
        String eventId = "hydrate-episode-" + SnowflakeIds.nextId();
        freezeExtractionOutput("episode", "列出最近的几次重置记录", "observed", "user_agent", "recent-reset", "unknown");
        appendInbound(eventId, "列出最近的几次重置记录");
        drainExtraction();
        assertJobOutcome(eventId, "COMPLETED", null);

        List<MemoryFact> facts = governance.list(tenant, "ACTIVE", 20);
        assertEquals(1, facts.size());
        assertEquals("USER_AGENT", facts.getFirst().scope().name());
        assertEquals("episode", facts.getFirst().type().value());
    }

    @Test
    void inboundHydrateExplicitSaveBecomesUserAgentPreference() {
        String eventId = "hydrate-pref-" + SnowflakeIds.nextId();
        freezePreferenceOutput("深色", "user_agent");
        appendInbound(eventId, "请记住我喜欢深色主题");
        drainExtraction();
        assertJobOutcome(eventId, "COMPLETED", null);

        List<MemoryFact> facts = governance.list(tenant, "ACTIVE", 20);
        assertEquals(1, facts.size());
        assertEquals("USER_AGENT", facts.getFirst().scope().name());
        assertEquals("preference", facts.getFirst().type().value());
    }

    /** 提取、实际检索、工具回执和下一次MODEL共用真实事实；重启不会重新搜索。 */
    @Test
    void extractedMemoryReachesNextModelAfterToolCommitAndRestartWithoutRepeatingSearch() throws Exception {
        String relevant="经营周报每周一上午发送，使用表格展示关键指标";
        freezePreferenceOutput(relevant,"user_agent","weekly-business-report");
        appendInbound("injection-relevant","请记住"+relevant);
        drainExtraction();
        MemoryFact expected=governance.list(tenant,"ACTIVE",20).getFirst();

        String irrelevant="饮品只选不加糖的热咖啡";
        freezePreferenceOutput(irrelevant,"user_agent","drink-choice");
        appendInbound("injection-irrelevant","请记住"+irrelevant);
        drainExtraction();
        SessionOwner anotherAgent=memoryOwner(Map.of("memory",Map.of("automatic_extraction",true)),"published");
        String privateText="经营周报包含另一助手私有的绝密报价标记";
        freezePreferenceOutput(privateText,"user_agent","private-report");
        appendInbound(anotherAgent,"injection-another-agent","请记住"+privateText);
        drainExtraction();
        assertEquals(3,governance.list(tenant,"ACTIVE",20).size());

        var execution=new MemoryInjectionProgramFixture(dataSource,runtime,memoryStore,groups,owner,"经营周报");
        execution.drive(2);
        assertEquals(1,execution.toolCalls());
        assertEquals(1,execution.modelCalls());
        var originalRun=execution.identity.runRef();
        execution.restart();
        execution.drive(20);
        assertEquals(originalRun,execution.identity.runRef());
        assertEquals(com.skillforge.runtime.api.RunStatus.COMPLETED,execution.store().load(originalRun).orElseThrow().status());
        assertEquals(1,execution.toolCalls(),"已提交的工具结果必须恢复，不能重新执行检索");
        assertEquals(2,execution.modelCalls());
        String modelInput=execution.nextModelRequest().request().messages().stream()
                .filter(message->message.role()==com.skillforge.runtime.api.port.ModelPort.ModelRole.TOOL)
                .map(com.skillforge.runtime.api.port.ModelPort.ModelMessage::textContent)
                .collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(modelInput.contains(expected.ref().factId()));
        assertTrue(modelInput.contains(relevant));
        assertFalse(modelInput.contains(irrelevant));
        assertFalse(modelInput.contains(privateText));
        assertFalse(modelInput.contains("source_session_ref"));
        assertFalse(modelInput.contains("source_event_ids"));
    }

    /** 当前遗忘阻止新注入和旧检索重放；不把原冻结选择改成重新搜索。 */
    @Test
    void forgottenExtractedMemoryStopsFrozenReplayAndIsAbsentFromNewSearch() {
        String content="经营周报每周二下午复核";
        freezePreferenceOutput(content,"user_agent","weekly-report-time");
        appendInbound("injection-before-forget","请记住"+content);
        drainExtraction();
        MemoryFact fact=governance.list(tenant,"ACTIVE",20).getFirst();
        var handler=new com.skillforge.institution.runtime.MemorySearchToolHandler(runtime,java.time.Clock.systemUTC(),
                new com.skillforge.institution.runtime.MemorySearchProperties(),groups);
        var original=new com.skillforge.core.capability.connector.PlatformToolExecutionContext(chatContext(owner),"injection-before-forget","memory-attempt:before");
        var first=handler.execute(Map.of("query","经营周报"),original);
        assertEquals(true,first.get("success"));
        assertEquals(1,((List<?>)first.get("facts")).size());
        governance.transition(tenant,fact.ref().factId(),fact.revision(),FactStatus.FORGOTTEN,"forgotten","injection-forget");
        var rejected=handler.execute(Map.of("query","经营周报"),original);
        assertEquals(false,rejected.get("success"));
        assertEquals("memory_frozen_fact_no_longer_usable",rejected.get("error"));
        var next=new com.skillforge.core.capability.connector.PlatformToolExecutionContext(chatContext(owner),"injection-after-forget","memory-attempt:after");
        var hidden=handler.execute(Map.of("query","经营周报"),next);
        assertEquals(true,hidden.get("success"));
        assertTrue(((List<?>)hidden.get("facts")).isEmpty());
    }

    /** 正式Worker冻结修订关联，HTTP批准激活原提案；原键重投不新增事实、修订或事件。 */
    @Test
    void correctionProposalHttpApprovalAtomicallyAcceptsExistingFactAndReplaysOriginalReceipt() throws Exception {
        RevisionPair pair = extractCorrectionPair("http-accept-revision");
        String token = governanceAdministratorToken();
        Map<String, Object> body = reviewBody(pair.proposal(), true);
        String key = "http-accept-existing:" + pair.proposal().ref().factId();
        HttpResponse<String> response = governancePost("/api/v1/memories/review", token, body, key);
        assertEquals(200, response.statusCode(), "正式审核HTTP应接受Worker准备的修订提案");
        var acceptedData = json.readTree(response.body()).path("data");

        List<MemoryFact> heads = governance.list(tenant, null, 20);
        assertEquals(2, heads.size(), "接受已保存提案不分配第三个事实");
        MemoryFact archived = heads.stream().filter(fact -> fact.ref().equals(pair.original().ref())).findFirst().orElseThrow();
        MemoryFact active = heads.stream().filter(fact -> fact.ref().equals(pair.proposal().ref())).findFirst().orElseThrow();
        assertEquals(FactStatus.ARCHIVED, archived.status());
        assertEquals(pair.original().revision() + 1, archived.revision());
        assertEquals(Optional.of(active.ref()), archived.supersededBy());
        assertTrue(archived.validTo().isPresent());
        assertEquals(FactStatus.ACTIVE, active.status());
        assertEquals(pair.proposal().revision() + 1, active.revision());
        assertEquals(pair.proposal().object(), active.object());
        assertEquals(pair.proposal().createdAt(), active.createdAt());
        assertEquals(pair.proposal().sourceEventIds(), active.sourceEventIds());
        assertEquals(Optional.of(pair.original().ref()), active.derivedFrom());
        assertEquals(active.ref().factId(), acceptedData.path("ref").path("fact_id").asText());
        assertEquals("ACTIVE", acceptedData.path("status").asText());
        Map<String, Long> acceptedCounts = memoryWriteCounts();
        assertEquals(2L, acceptedCounts.get("heads"));
        assertEquals(4L, acceptedCounts.get("revisions"));
        assertEquals(3L, acceptedCounts.get("operations"));
        assertEquals(3L, acceptedCounts.get("writtenEvents"));

        HttpResponse<String> replay = governancePost("/api/v1/memories/review", token, body, key);
        assertEquals(200, replay.statusCode(), () -> "两端head推进后同body/key应返回原审核结果：" + publicHttpError(replay));
        assertEquals(acceptedData, json.readTree(replay.body()).path("data"));
        assertEquals(acceptedCounts, memoryWriteCounts());
        assertEquals(Set.copyOf(heads), Set.copyOf(governance.list(tenant, null, 20)));

        HttpResponse<String> changed = governancePost("/api/v1/memories/review", token,
                reviewBody(pair.proposal(), false), key);
        assertEquals(409, changed.statusCode(), "同key不同审核结论必须拒绝");
        assertEquals(acceptedCounts, memoryWriteCounts());
        assertEquals(Set.copyOf(heads), Set.copyOf(governance.list(tenant, null, 20)));
    }

    /** 审核保持准备时的目标版本；旧目标变化后双CAS失败不部分归档或激活任一head。 */
    @Test
    void correctionProposalHttpApprovalConflictsWhenFrozenTargetRevisionHasAdvanced() throws Exception {
        RevisionPair pair = extractCorrectionPair("http-stale-revision");
        MemoryFact advanced = governance.transition(tenant, pair.original().ref().factId(), pair.original().revision(),
                FactStatus.DECAYED, "manual-review-target-change", "advance-review-target:" + pair.original().ref().factId());
        assertEquals(pair.original().revision() + 1, advanced.revision());
        List<MemoryFact> before = governance.list(tenant, null, 20);
        Map<String, Long> counts = memoryWriteCounts();
        String token = governanceAdministratorToken();

        HttpResponse<String> response = governancePost("/api/v1/memories/review", token,
                reviewBody(pair.proposal(), true), "stale-target-review:" + pair.proposal().ref().factId());

        assertEquals(409, response.statusCode(), "审核必须使用Worker准备的原目标revision");
        assertEquals(counts, memoryWriteCounts());
        assertEquals(Set.copyOf(before), Set.copyOf(governance.list(tenant, null, 20)));
        assertEquals(List.of(pair.proposal()), governance.list(tenant, "PENDING_CONFIRM", 20));
        assertEquals(List.of(advanced), governance.list(tenant, "DECAYED", 20));
        assertTrue(governance.list(tenant, "ARCHIVED", 20).isEmpty());
        assertTrue(governance.list(tenant, "ACTIVE", 20).isEmpty());
    }

    /** 两条原始来源分别创建旧偏好与明确更正，所有typed关联都由正式冻结/准备链生成。 */
    private RevisionPair extractCorrectionPair(String marker) {
        freezePreferenceOutput("电话", "user_agent", "contact_channel");
        String originalSource = marker + "-original-" + SnowflakeIds.nextId();
        appendInbound(originalSource, "请记住我通常希望通过电话联系");
        drainExtraction();
        assertJobOutcome(originalSource, "COMPLETED", null);
        MemoryFact original = governance.list(tenant, "ACTIVE", 20).getFirst();
        freezeCorrectionOutput(original, "电子邮件");
        String correctionSource = marker + "-correction-" + SnowflakeIds.nextId();
        appendInbound(correctionSource, "更正一下：以后不要电话联系，请通过电子邮件联系我");
        drainExtraction();
        assertJobOutcome(correctionSource, "COMPLETED", null);
        List<MemoryFact> pending = governance.list(tenant, "PENDING_CONFIRM", 20);
        assertEquals(1, pending.size());
        MemoryFact proposal = pending.getFirst();
        assertEquals(Optional.of(original.ref()), proposal.derivedFrom());
        var target = MemoryContent.revisionProposal(MemoryContent.decode(proposal.object().canonicalJson())).orElseThrow();
        assertEquals(original.ref().factId(), target.factId());
        assertEquals(original.revision(), target.expectedRevision());
        assertEquals(List.of(new EventId(correctionSource)), proposal.sourceEventIds());
        assertEquals(List.of(original), governance.list(tenant, "ACTIVE", 20));
        assertEquals(2L, memoryWriteCounts().get("heads"));
        return new RevisionPair(original, proposal);
    }

    /** 只使用冻结输入的目标别名和新来源原文；同控LLM不代替宿主准备与授权。 */
    private void freezeCorrectionOutput(MemoryFact original, String value) {
        doAnswer(invocation -> {
            var input = json.readTree(invocation.getArgument(4, String.class));
            var targets = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
            input.path("existingFacts").forEach(fact -> {
                if (original.subject().equals(fact.path("subject").asText())
                        && original.predicate().equals(fact.path("predicate").asText())
                        && original.revision() == fact.path("revision").asLong()) targets.add(fact);
            });
            assertEquals(1, targets.size(), "正式冻结输入必须包含唯一旧事实别名");
            var source = input.path("sources").get(0);
            var output = json.createObjectNode().put("schemaVersion", 2);
            var candidate = output.putArray("candidates").addObject();
            candidate.put("type", "preference").put("subject", original.subject()).put("predicate", original.predicate())
                    .put("signal", "explicit_save").put("sensitive", false).put("scope", "user_agent");
            candidate.putObject("object").put("text", value);
            completeCandidate(candidate, source.path("id").asText(), source.path("text").asText(), "stable");
            candidate.putObject("relation").put("kind", "correction").put("target", targets.getFirst().path("id").asText());
            return new LlmResult(output.toString(), "controlled-correction-output", LlmUsage.empty());
        }).when(llm).generatePinned(anyString(), anyString(), anyLong(), anyString(), anyString(), any(), any(), anyInt(), any());
    }

    /** 复用正式Foundation管理员建制和机构登录；仅HTTP线程使用取得的机构JWT。 */
    private String governanceAdministratorToken() throws Exception {
        createTestTenantAdministrator(tenant, GOVERNANCE_USERNAME, GOVERNANCE_PASSWORD);
        String slug = tenantMapper.selectById(Long.valueOf(tenant)).getSlug();
        HttpResponse<String> response = governancePost("/api/v1/auth/login", null,
                Map.of("tenant_slug", slug, "username", GOVERNANCE_USERNAME, "password", GOVERNANCE_PASSWORD), null);
        assertEquals(200, response.statusCode(), "机构管理员应通过正式登录取得审核权限");
        String token = json.readTree(response.body()).at("/data/access_token").asText();
        assertFalse(token.isBlank());
        return token;
    }

    private Map<String, Object> reviewBody(MemoryFact proposal, boolean approved) {
        return Map.of("fact_id", proposal.ref().factId(), "payload", Map.of("expected_revision", proposal.revision(),
                "approved", approved, "confirmed_scope", proposal.scope().name()));
    }

    /** 请求只传机构JWT和原业务键，响应断言不打印账号凭据。 */
    private HttpResponse<String> governancePost(String path, String token, Map<String, ?> body, String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json").header("Accept", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (key != null) request.header("Idempotency-Key", key);
        try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            return http.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    private record RevisionPair(MemoryFact original, MemoryFact proposal) { }

    @Test
    void automaticPreferenceReuseKeepsOneBodyAndRestoresOriginalSessionReceiptAcrossStateChanges() throws Exception {
        String body = "界".repeat(2_500);
        freezePreferenceOutput(body, "user_agent");
        String firstSource = "storage-auto-first-" + SnowflakeIds.nextId();
        appendInbound(firstSource, "请记住我的偏好");
        drainExtraction();
        MemoryFact original = governance.list(tenant, "ACTIVE", 20).getFirst();
        var receiptRows = queryRows(jdbc, tenant, """
                select event_id,payload_canonical_json from agent_runtime_session_events
                where tenant_id=? and session_id=? and event_type='memory.written'
                """, tenant, owner.sessionRef().sessionId());
        assertEquals(1, receiptRows.size());
        EventId receiptId = new EventId((String) receiptRows.getFirst().get("event_id"));
        var originalReceipt = new JdbcSessionLogPort(dataSource).findByEvent(owner, receiptId).orElseThrow();
        assertTrue(originalReceipt.payload().canonicalJson().contains(body));
        assertTrue(((String) receiptRows.getFirst().get("payload_canonical_json")).contains("@runtime-session-content:"),
                "真实大正文样本必须进入正式SessionLog body/ref恢复路径");

        var laterOwner = SessionOwner.user(new SessionRef(new TenantId(tenant), "storage-auto-later-" + SnowflakeIds.nextId()),
                owner.target().requireAgent(), owner.requireUser());
        String secondSource = "storage-auto-second-" + SnowflakeIds.nextId();
        appendInbound(laterOwner, secondSource, "再次确认同一偏好");
        drainExtraction();
        assertJobOutcome(secondSource, "COMPLETED", null);
        assertEquals(1, queryCount(jdbc, tenant, "select count(*) from agent_runtime_memory_fact_heads where tenant_id=?", tenant));
        assertEquals(1, queryCount(jdbc, tenant, "select count(*) from agent_runtime_memory_fact_revisions where tenant_id=?", tenant));
        assertEquals(1, queryCount(jdbc, tenant, "select count(*) from agent_runtime_memory_fact_sources where tenant_id=?", tenant));
        assertEquals(1, queryCount(jdbc, tenant, "select count(*) from agent_runtime_memory_extraction_evidence where tenant_id=?", tenant));
        assertEquals(1, queryCount(jdbc, tenant, "select count(*) from agent_runtime_session_events where tenant_id=? and event_type='memory.written'", tenant));
        assertEquals(original.ref(), governance.list(tenant, "ACTIVE", 20).getFirst().ref());

        long contentBytes = queryCount(jdbc, tenant,
                "select sum(coalesce(octet_length(fact_data),0)) from agent_runtime_memory_fact_revisions where tenant_id=?", tenant);
        MemoryFact decayed = governance.transition(tenant, original.ref().factId(), 1, FactStatus.DECAYED,
                "storage-manual-decay", "storage-manual-decay");
        MemoryFact restored = governance.transition(tenant, original.ref().factId(), decayed.revision(), FactStatus.ACTIVE,
                "storage-manual-restore", "storage-manual-restore");
        assertEquals(3, restored.revision());
        assertEquals(original.object(), restored.object());
        assertEquals(original.sourceEventIds(), restored.sourceEventIds());
        assertEquals(contentBytes, queryCount(jdbc, tenant,
                "select sum(coalesce(octet_length(fact_data),0)) from agent_runtime_memory_fact_revisions where tenant_id=?", tenant));
        assertEquals(originalReceipt, new JdbcSessionLogPort(dataSource).findByEvent(owner, receiptId).orElseThrow());
        var canonical = queryRows(jdbc, tenant, """
                select public.agent_runtime_session_event_payload(tenant_id,session_id,target_kind,target_id,
                    end_user_id,run_id,event_id,event_type,payload_schema_id,payload_canonical_json) as restored
                from agent_runtime_session_events where tenant_id=? and event_id=?
                """, tenant, receiptId.value()).getFirst().get("restored");
        assertEquals(originalReceipt.payload().canonicalJson(), canonical);
        // 终态释放claim；原来源和原写回执仍由上面的正式SessionLog恢复断言保护。
        assertEquals(0, queryCount(jdbc, tenant,
                "select count(*) from agent_runtime_memory_extraction_jobs where tenant_id=? and status in ('COMPLETED','SKIPPED') and claimed_at is not null", tenant));
    }

    @Test
    void automaticSameContentInUserAndUserAgentScopesHasDistinctAuthorizedFacts() {
        String firstSource = "storage-scope-agent-" + SnowflakeIds.nextId();
        freezePreferenceOutput("深色主题", "user_agent");
        appendInbound(firstSource, "请当前助手记住偏好");
        drainExtraction();
        String secondSource = "storage-scope-user-" + SnowflakeIds.nextId();
        freezePreferenceOutput("深色主题", "user");
        appendInbound(secondSource, "请所有助手记住偏好");
        drainExtraction();
        assertJobOutcome(firstSource, "COMPLETED", null);
        assertJobOutcome(secondSource, "COMPLETED", null);
        var pending = governance.list(tenant, "PENDING_CONFIRM", 20);
        assertEquals(1, pending.size());
        assertEquals(MemoryScope.USER, pending.getFirst().scope());
        assertTrue(pending.getFirst().reviewRequired());
        governance.review(tenant, pending.getFirst().ref().factId(), pending.getFirst().revision(),
                true, MemoryScope.USER, "confirm-storage-user-scope");
        var facts = governance.list(tenant, "ACTIVE", 20);
        assertEquals(2, facts.size());
        assertEquals(Set.of(MemoryScope.USER, MemoryScope.USER_AGENT), Set.copyOf(facts.stream().map(MemoryFact::scope).toList()));
        assertNotEquals(facts.get(0).ref(), facts.get(1).ref());
        assertEquals(0, queryCount(jdbc, tenant, "select count(*) from agent_runtime_memory_extraction_evidence where tenant_id=?", tenant));
    }

    /** 冻结错误 USER 提议回放到正式保存消费者；这是 SessionLog/Worker 集成，不代表 HTTP 或自然模型验收。 */
    @Test
    void automaticUserScopeProposalRequiresExplicitReviewAndCannotBypassPendingThroughOtherWrites() {
        String eventId = "user-scope-proposal-" + SnowflakeIds.nextId();
        // 保留已捕获供应商候选的内容、关系、信号与范围，仅 subject 绑定本次隔离用户。
        freezePreferenceOutput("整理业务说明时，默认先列三点结论，再解释细节", "user", "业务说明整理格式");
        appendInbound(eventId, "请长期记住：以后为我整理业务说明，默认先列三点结论，再解释细节。这是我的跨任务习惯。");
        drainExtraction();
        assertJobOutcome(eventId, "COMPLETED", null);
        MemoryFact pending = governance.list(tenant, "PENDING_CONFIRM", 20).getFirst();
        assertEquals(MemoryScope.USER, pending.scope());
        assertEquals(1, pending.revision());
        assertTrue(pending.reviewRequired());
        assertEquals(new MemoryOwner.User(new TenantId(tenant), new EndUserId(endUser)), pending.owner());
        assertEquals(List.of(new EventId(eventId)), pending.sourceEventIds());
        assertTrue(governance.list(tenant, "ACTIVE", 20).isEmpty());
        assertTrue(recorded(runtime.retrieve(new MemoryRuntime.RetrievalCall(
                chatContext(owner), query(owner, Set.of(MemoryScope.USER))))).candidateFacts().isEmpty());

        Map<String, Long> before = memoryWriteCounts();
        for (MemoryScope confirmed : new MemoryScope[] {null, MemoryScope.USER_AGENT}) {
            ApiException rejected = assertThrows(ApiException.class, () -> governance.review(
                    tenant, pending.ref().factId(), pending.revision(), true, confirmed, "bad-user-scope-review"));
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, rejected.getStatus());
            assertEquals(confirmed == null ? "memory_user_scope_confirmation_required"
                    : "memory_review_scope_mismatch", rejected.getDetail());
            assertEquals(before, memoryWriteCounts());
        }
        ApiException corrected = assertThrows(ApiException.class, () -> governance.correct(
                tenant, pending.ref().factId(), pending.revision(), null, null,
                "{\"text\":\"未经确认的纠正\"}", .9, .7, "bypass-user-correct"));
        assertEquals("memory_user_scope_confirmation_required", corrected.getDetail());
        ApiException restored = assertThrows(ApiException.class, () -> governance.transition(
                tenant, pending.ref().factId(), pending.revision(), FactStatus.ACTIVE,
                "memory_user_scope_review_approved", "bypass-user-restore"));
        assertEquals("memory_user_scope_confirmation_required", restored.getDetail());
        assertEquals(before, memoryWriteCounts());

        PortCallContext ordinary = chatContext(owner);
        PortCallContext extractor = new PortCallContext(ordinary.runIdentity(), new VerifiedIdentity(
                new PrincipalRef.SystemPrincipal(new TenantId(tenant), "memory-extractor"), owner,
                Set.of("memory:extract"), 1, new ResultRef("scope-extractor-verification")), ordinary.control());
        MemoryRuntime.MutationResult.Stopped directCreate = assertInstanceOf(
                MemoryRuntime.MutationResult.Stopped.class, runtime.mutate(new MemoryRuntime.MutationCall(
                        extractor, new MemoryRuntime.Mutation.Create(new IdempotencyKey("bypass-user-create"),
                                userDraft(owner, eventId)), Instant.now())));
        assertEquals("memory_user_scope_review_required", directCreate.failure().reasonCode());
        assertEquals(before, memoryWriteCounts());
        extraction.poll();
        extraction.poll();
        assertEquals(before, memoryWriteCounts());
        assertEquals(List.of(pending), governance.list(tenant, "PENDING_CONFIRM", 20));

        ApiException cas = assertThrows(ApiException.class, () -> governance.review(
                tenant, pending.ref().factId(), pending.revision() + 1, true, MemoryScope.USER, "stale-user-review"));
        assertEquals(HttpStatus.CONFLICT, cas.getStatus());
        assertEquals(List.of(pending), governance.list(tenant, "PENDING_CONFIRM", 20));
        MemoryFact approved = governance.review(tenant, pending.ref().factId(), pending.revision(),
                true, MemoryScope.USER, "explicit-user-scope-review");
        assertEquals(FactStatus.ACTIVE, approved.status());
        assertEquals(MemoryScope.USER, approved.scope());
        assertEquals(pending.revision() + 1, approved.revision());
        assertEquals(pending.object(), approved.object());
        assertEquals(pending.sourceEventIds(), approved.sourceEventIds());
    }

    /** USER 候选可以不确认共享范围直接拒绝，原 USER_AGENT 自动提取正例仍不要求审核。 */
    @Test
    void userProposalMayBeRejectedWithoutScopeAndUserAgentStillActivatesNormally() {
        freezePreferenceOutput("共享范围仍待确认", "user");
        String userEvent = "reject-user-scope-" + SnowflakeIds.nextId();
        appendInbound(userEvent, "请长期记住我的整理习惯");
        drainExtraction();
        MemoryFact pending = governance.list(tenant, "PENDING_CONFIRM", 20).getFirst();
        MemoryFact rejected = governance.review(tenant, pending.ref().factId(), pending.revision(),
                false, null, "reject-user-without-confirming-scope");
        assertEquals(FactStatus.REJECTED, rejected.status());
        assertTrue(governance.list(tenant, "ACTIVE", 20).isEmpty());

        freezePreferenceOutput("当前助手先列三点结论", "user_agent");
        String agentEvent = "ordinary-agent-scope-" + SnowflakeIds.nextId();
        appendInbound(agentEvent, "请当前助手记住先列三点结论");
        drainExtraction();
        assertJobOutcome(agentEvent, "COMPLETED", null);
        var active = governance.list(tenant, "ACTIVE", 20);
        assertEquals(1, active.size());
        assertEquals(MemoryScope.USER_AGENT, active.getFirst().scope());
        assertFalse(active.getFirst().reviewRequired());
    }

    @Test
    void productionGuardLocksHeadBeforeProjectionOnSameTransactionAndRejectsCommittedReset() throws Exception {
        append("storage-guard-source", false, "explicit_save", "compact");
        drainExtraction();
        MemoryFact current = governance.list(tenant, "ACTIVE", 20).getFirst();
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            jdbc.queryForObject("select set_config('app.tenant_id', ?, true)", String.class, tenant);
            jdbc.update("insert into agent_runtime_memory_usage_projection(tenant_id,fact_id,projected_decay_score) values(?,?,1)",
                    tenant, current.ref().factId());
        });
        var reachedGuard = new CountDownLatch(1);
        var guardPid = new AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            Connection connection = invocation.<org.apache.ibatis.session.SqlSession>getArgument(0).getConnection();
            assertFalse(connection.getAutoCommit());
            try (var statement = connection.createStatement(); var row = statement.executeQuery(
                    "select pg_backend_pid(),current_setting('app.tenant_id'),txid_current()")) {
                assertTrue(row.next());
                guardPid.set(row.getInt(1));
                assertEquals(tenant, row.getString(2));
                assertTrue(row.getLong(3) > 0);
            }
            reachedGuard.countDown();
            return invocation.callRealMethod();
        }).when(transitionGuard).allows(any(), any(), any());
        var command = new MemoryCommand.Transition(chatContext(owner), new IdempotencyKey("storage-guard-reset"),
                current.ref(), current.revision(), FactStatus.DECAYED, "memory_time_decay_threshold");
        var executor = Executors.newSingleThreadExecutor();
        try (var reset = dataSource.getConnection()) {
            reset.setAutoCommit(false);
            bindTestTenant(reset, tenant);
            int resetPid;
            try (var statement = reset.createStatement(); var row = statement.executeQuery("select pg_backend_pid()")) {
                assertTrue(row.next()); resetPid = row.getInt(1);
            }
            try (var update = reset.prepareStatement("update agent_runtime_memory_usage_projection set projected_decay_score=0,last_referenced_at=now() where tenant_id=? and fact_id=?")) {
                update.setString(1, tenant); update.setString(2, current.ref().factId()); assertEquals(1, update.executeUpdate());
            }
            var future = executor.submit(() -> memoryStore.submit(command));
            assertTrue(reachedGuard.await(10, TimeUnit.SECONDS), "生产Starter装配的JdbcMemoryPort必须调用宿主Guard");
            awaitProjectionLock(guardPid.get(), resetPid);
            assertFalse(future.isDone());
            try (var competitor = dataSource.getConnection()) {
                competitor.setAutoCommit(false);
                bindTestTenant(competitor, tenant);
                try (var lock = competitor.prepareStatement("select current_revision from agent_runtime_memory_fact_heads where tenant_id=? and fact_id=? for update nowait")) {
                    lock.setString(1, tenant); lock.setString(2, current.ref().factId());
                    SQLException locked = assertThrows(SQLException.class, lock::executeQuery);
                    assertEquals("55P03", locked.getSQLState(), "等待projection时必须已持有head行锁");
                } finally { competitor.rollback(); }
            }
            reset.commit();
            var rejected = assertInstanceOf(MemoryMutationResult.Rejected.class, future.get(10, TimeUnit.SECONDS));
            assertEquals("memory_transition_precondition_failed", rejected.failure().reasonCode());
            assertEquals(Optional.of(current), memoryStore.findCurrent(command.context(), current.ref()));
            assertEquals(1, queryCount(jdbc, tenant,
                    "select count(*) from agent_runtime_memory_fact_revisions where tenant_id=? and fact_id=?", tenant, current.ref().factId()));
            assertEquals(0, queryCount(jdbc, tenant,
                    "select count(*) from agent_runtime_memory_operations where tenant_id=? and idempotency_key='storage-guard-reset'", tenant));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "Guard竞争线程必须静止后结束用例");
        }
    }

    /** 固定同控模型输出；真实抽取Worker仍执行生产schema、来源、权限及写入路径。 */
    private void freezePreferenceOutput(String value, String scope) {
        freezePreferenceOutput(value, scope, "theme");
    }

    /** 来源为已捕获候选时保留原关系，避免用旧 theme 夹具冒充该回包。 */
    private void freezePreferenceOutput(String value, String scope, String predicate) {
        freezeExtractionOutput("preference",value,"explicit_save",scope,predicate,"stable");
    }

    /** 同控模型仅复用本次真实冻结来源作为证据，不从候选value补造来源。 */
    private void freezeExtractionOutput(String type,String value,String signal,String scope,String predicate,String temporal) {
        doAnswer(invocation -> {
            var input=json.readTree(invocation.getArgument(4,String.class));
            var source=input.path("sources").get(0);
            var output=json.createObjectNode().put("schemaVersion",2);
            var candidate=output.putArray("candidates").addObject();
            candidate.put("type",type).put("subject","user:"+endUser).put("predicate",predicate)
                    .put("signal",signal).put("sensitive",false).put("scope",scope);
            candidate.putObject("object").put("text",value);
            completeCandidate(candidate,source.path("id").asText(),source.path("text").asText(),temporal);
            return new LlmResult(output.toString(),"frozen-storage-output",LlmUsage.empty());
        }).when(llm).generatePinned(anyString(), anyString(), anyLong(), anyString(), anyString(), any(), any(), anyInt(), any());
    }

    private void completeCandidate(com.fasterxml.jackson.databind.node.ObjectNode candidate,
            String sourceId,String quote,String temporal) {
        candidate.putObject("temporal").put("kind",temporal).putNull("from").putNull("to");
        candidate.putArray("conditions");
        candidate.putArray("evidence").addObject().put("sourceId",sourceId).put("quote",quote);
        candidate.putObject("relation").put("kind","new").putNull("target");
        candidate.putArray("uncertainties");
    }

    /** 将可信测试租户绑定至当前测试事务，不通过DTO或全局连接获得写权。 */
    private static void bindTestTenant(Connection connection, String tenant) throws SQLException {
        try (var statement = connection.prepareStatement("select set_config('app.tenant_id',?,true)")) {
            statement.setString(1, tenant); statement.execute();
        }
    }

    /** 等待PG报告实际行锁竞争；轮询间隔不承担竞争成立的断言。 */
    private void awaitProjectionLock(int guardPid, int resetPid) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Boolean.TRUE.equals(jdbc.queryForObject(
                "select ?=any(pg_blocking_pids(?))", Boolean.class, resetPid, guardPid))) {
            assertTrue(System.nanoTime() < deadline, "宿主Guard没有在同一backend等待projection重置行锁");
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
    }

    @Test
    void inboundHydrateUserPreferenceIsSavedAndAdoptedByAuthorizedOtherAgent() {
        String eventId = "hydrate-user-pref-" + SnowflakeIds.nextId();
        freezePreferenceOutput("深色", "user");
        appendInbound(eventId, "请所有助手都记住我喜欢深色主题");
        drainExtraction();
        assertJobOutcome(eventId, "COMPLETED", null);

        List<MemoryFact> saved = governance.list(tenant,
                new MemoryGovernanceService.ListQuery(null, "USER", "preference", null, null, 20));
        assertEquals(1, saved.size());
        MemoryFact userFact = saved.getFirst();
        assertEquals("USER", userFact.scope().name());
        assertEquals(new MemoryOwner.User(new TenantId(tenant), new EndUserId(endUser)), userFact.owner());
        assertEquals(owner.sessionRef(), userFact.sourceSessionRef());
        assertEquals(List.of(new EventId(eventId)), userFact.sourceEventIds());
        assertEquals(FactStatus.PENDING_CONFIRM, userFact.status());
        assertTrue(userFact.reviewRequired());
        userFact = governance.review(tenant, userFact.ref().factId(), userFact.revision(),
                true, MemoryScope.USER, "confirm-cross-agent-user-scope");

        append(owner, "memory-agent-private-" + SnowflakeIds.nextId(), false, "explicit_save", "agent-only");
        extraction.poll();
        SessionOwner reader = SessionOwner.user(new SessionRef(new TenantId(tenant), "memory-session-b"),
                new AgentId("memory-agent-b"), new EndUserId(endUser));
        doReturn(new LlmResult("{\"schemaVersion\":2,\"candidates\":[]}", "stub", LlmUsage.empty())).when(llm)
                .generatePinned(anyString(), anyString(), anyLong(), anyString(), anyString(), any(), any(), anyInt(), any());
        String readerEventId = "hydrate-b-" + SnowflakeIds.nextId();
        appendInbound(reader, readerEventId, "你好");
        drainExtraction();

        MemoryRuntime.RetrievalReceipt adopted = recorded(runtime.retrieve(new MemoryRuntime.RetrievalCall(
                chatContext(reader), query(reader, Set.of(MemoryScope.USER, MemoryScope.USER_AGENT)))));
        assertEquals(List.of(userFact.ref()), adopted.candidateFacts().stream().map(MemoryFact::ref).toList());
        assertEquals(MemoryScope.USER, adopted.candidateFacts().getFirst().scope());
        assertEquals(owner.sessionRef(), adopted.candidateFacts().getFirst().sourceSessionRef());

        MemoryRuntime.RetrievalReceipt hidden = recorded(runtime.retrieve(new MemoryRuntime.RetrievalCall(
                chatContext(reader), query(reader, Set.of(MemoryScope.USER_AGENT)))));
        assertTrue(hidden.candidateFacts().isEmpty());

        long otherUser = endUsers.create(tenant, "Memory 其他用户").getId();
        SessionOwner stranger = SessionOwner.user(new SessionRef(new TenantId(tenant), "memory-session-other"),
                new AgentId("memory-agent-other"), new EndUserId(otherUser));
        appendInbound(stranger, "hydrate-other-" + SnowflakeIds.nextId(), "你好");
        drainExtraction();
        MemoryRuntime.RetrievalReceipt isolated = recorded(runtime.retrieve(new MemoryRuntime.RetrievalCall(
                chatContext(stranger), query(stranger, Set.of(MemoryScope.USER)))));
        assertTrue(isolated.candidateFacts().isEmpty());

        MemoryRuntime.MutationResult.Stopped createDenied = assertInstanceOf(
                MemoryRuntime.MutationResult.Stopped.class,
                runtime.mutate(new MemoryRuntime.MutationCall(
                        chatContext(reader),
                        new MemoryRuntime.Mutation.Create(new IdempotencyKey("user-write-denied"),
                                userDraft(reader, readerEventId)),
                        Instant.now())));
        assertEquals("memory_access_user_write_denied", createDenied.failure().reasonCode());
        MemoryRuntime.MutationResult.Stopped forgetDenied = assertInstanceOf(
                MemoryRuntime.MutationResult.Stopped.class,
                runtime.mutate(new MemoryRuntime.MutationCall(
                        chatContext(reader),
                        new MemoryRuntime.Mutation.Transition(new IdempotencyKey("user-forget-denied"),
                                userFact.ref(), userFact.revision(), MemoryScope.USER, userFact.type(),
                                FactStatus.FORGOTTEN, "forgotten"),
                        Instant.now())));
        assertEquals("memory_access_user_write_denied", forgetDenied.failure().reasonCode());

        append(reader, "memory-b-overlay-" + SnowflakeIds.nextId(), false, "explicit_save", "light");
        extraction.poll();
        MemoryRuntime.RetrievalReceipt overlaid = recorded(runtime.retrieve(new MemoryRuntime.RetrievalCall(
                chatContext(reader), query(reader, Set.of(MemoryScope.USER, MemoryScope.USER_AGENT)))));
        assertEquals(1, overlaid.candidateFacts().size());
        assertEquals(MemoryScope.USER_AGENT, overlaid.candidateFacts().getFirst().scope());
        assertEquals(new MemoryOwner.UserAgent(reader.sessionRef().tenantId(), reader.requireUser(), reader.target().requireAgent()), overlaid.candidateFacts().getFirst().owner());
        MemoryFact stillShared = governance.list(tenant,
                new MemoryGovernanceService.ListQuery(null, "USER", "preference", null, null, 20)).getFirst();
        assertEquals(userFact.ref(), stillShared.ref());
        assertEquals(userFact.revision(), stillShared.revision());
        assertEquals("USER", stillShared.scope().name());
    }

    @Test
    void governanceBatchPreservesDifferentOwnersCreationOrderAndPerFactDecay() {
        append(owner, "batch-governance-first-" + SnowflakeIds.nextId(), false, "observed", "first");
        drainExtraction();
        MemoryFact first = governance.list(tenant, null, 20).getFirst();
        SessionOwner secondOwner = memoryOwner(Map.of(), "published");
        append(secondOwner, "batch-governance-second-" + SnowflakeIds.nextId(), false, "observed", "second");
        drainExtraction();
        MemoryFact second = governance.list(tenant, null, 20).getFirst();
        long otherUser = endUsers.create(tenant, "Memory 批次其他用户").getId();
        SessionOwner thirdOwner = SessionOwner.user(new SessionRef(new TenantId(tenant), "memory-batch-other-user"),
                secondOwner.target().requireAgent(), new EndUserId(otherUser));
        append(thirdOwner, "batch-governance-third-" + SnowflakeIds.nextId(), false, "observed", "third");
        drainExtraction();
        MemoryFact third = governance.list(tenant, null, 20).getFirst();
        assertTrue(first.createdAt().isBefore(second.createdAt()));
        assertTrue(second.createdAt().isBefore(third.createdAt()));

        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            jdbc.queryForObject("select set_config('app.tenant_id', ?, true)", String.class, tenant);
            for (var entry : Map.of(first.ref().factId(), 0.2, second.ref().factId(), 0.5, third.ref().factId(), 0.9).entrySet()) {
                jdbc.update("insert into agent_runtime_memory_usage_projection(tenant_id,fact_id,projected_decay_score) "
                                + "values(?,?,?) on conflict(tenant_id,fact_id) do update set projected_decay_score=excluded.projected_decay_score",
                        tenant, entry.getKey(), entry.getValue());
            }
        });
        List<MemoryFact> facts = governance.list(tenant, null, 200);
        assertEquals(List.of(third.ref(), second.ref(), first.ref()), facts.stream().map(MemoryFact::ref).toList());
        assertEquals(List.of(thirdOwner.sessionRef(), secondOwner.sessionRef(), owner.sessionRef()),
                facts.stream().map(MemoryFact::sourceSessionRef).toList());
        assertEquals(List.of(new MemoryOwner.UserAgent(thirdOwner.sessionRef().tenantId(), thirdOwner.requireUser(), thirdOwner.target().requireAgent()),
                        new MemoryOwner.UserAgent(secondOwner.sessionRef().tenantId(), secondOwner.requireUser(), secondOwner.target().requireAgent()),
                        new MemoryOwner.UserAgent(owner.sessionRef().tenantId(), owner.requireUser(), owner.target().requireAgent())),
                facts.stream().map(MemoryFact::owner).toList());
        assertEquals(List.of(0.9, 0.5, 0.2), facts.stream().map(MemoryFact::decayScore).toList());
        assertEquals(List.of(third.ref(), second.ref()), governance.list(tenant, null, 2).stream().map(MemoryFact::ref).toList());
    }

    @Test
    void governancePageSearchesAuthoritativeContentAndRequiresReadPermission() throws Exception {
        append(owner, "page-first-" + SnowflakeIds.nextId(), false, "observed", "first");
        drainExtraction();
        append(owner, "page-second-" + SnowflakeIds.nextId(), false, "observed", "second");
        drainExtraction();
        String token = governanceAdministratorToken();
        String root = "http://127.0.0.1:" + port + "/api/v1/memories/page";
        try (var http = HttpClient.newHttpClient()) {
            var unauthorized = http.send(HttpRequest.newBuilder(URI.create(root)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(unauthorized.statusCode() == 401 || unauthorized.statusCode() == 403);
            var first = http.send(HttpRequest.newBuilder(URI.create(root + "?limit=1"))
                    .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, first.statusCode());
            var page1 = json.readTree(first.body()).path("data");
            assertEquals(1, page1.path("items").size());
            String cursor = page1.path("next_cursor").asText();
            assertFalse(cursor.isBlank());
            var second = http.send(HttpRequest.newBuilder(URI.create(root + "?limit=1&cursor=" + cursor))
                    .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, second.statusCode());
            var page2 = json.readTree(second.body()).path("data");
            assertEquals(1, page2.path("items").size());
            assertNotEquals(page1.path("items").get(0).at("/ref/fact_id").asText(),
                    page2.path("items").get(0).at("/ref/fact_id").asText());
            var search = http.send(HttpRequest.newBuilder(URI.create(root + "?q=first&limit=1"))
                    .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, search.statusCode());
            assertEquals(1, json.readTree(search.body()).at("/data/items").size());
            var wrongOwner = http.send(HttpRequest.newBuilder(URI.create(root + "?q=first&end_user_id=" + (endUser + 1)))
                    .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(0, json.readTree(wrongOwner.body()).at("/data/items").size());
        }
    }

    @Test
    void listFiltersByScopeTypeAndOwner() {
        append("memory-session-pref-" + SnowflakeIds.nextId(), false, "observed", "light");
        extraction.poll();
        append("memory-user-pref-" + SnowflakeIds.nextId(), false, "explicit_save", "dark");
        extraction.poll();

        List<MemoryFact> unfiltered = governance.list(tenant, null, 20);
        assertEquals(2, unfiltered.size());

        List<MemoryFact> userAgent = governance.list(tenant,
                new MemoryGovernanceService.ListQuery(null, "USER_AGENT", "preference", null, null, 20));
        assertEquals(1, userAgent.size());
        assertEquals("USER_AGENT", userAgent.getFirst().scope().name());
        assertEquals("preference", userAgent.getFirst().type().value());

        List<MemoryFact> session = governance.list(tenant,
                new MemoryGovernanceService.ListQuery(null, "SESSION", "preference", null, null, 20));
        assertTrue(session.isEmpty());
        var episodes=governance.list(tenant,
                new MemoryGovernanceService.ListQuery(null,"USER_AGENT","episode",null,null,20));
        assertEquals(1,episodes.size());
        assertEquals("task",readContent(episodes.getFirst()).path("temporal").path("kind").asText());
        assertTrue(governance.list(tenant,
                new MemoryGovernanceService.ListQuery(null, "USER_AGENT", "preference", "other-agent", null, 20))
                .isEmpty());
        assertTrue(governance.list(tenant,
                new MemoryGovernanceService.ListQuery(null, null, null, null, endUser + 1, 20)).isEmpty());

        ApiException type = assertThrows(ApiException.class, () -> governance.list(tenant,
                new MemoryGovernanceService.ListQuery(null, "USER_AGENT", "summary", null, null, 20)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, type.getStatus());
        ApiException scope = assertThrows(ApiException.class, () -> governance.list(tenant,
                new MemoryGovernanceService.ListQuery(null, "GLOBAL", "preference", null, null, 20)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, scope.getStatus());
        List<MemoryFact> userLevel = governance.list(tenant,
                new MemoryGovernanceService.ListQuery(null, "USER", "preference", null, null, 20));
        assertTrue(userLevel.isEmpty());
    }

    /** 三作用域各二十条，谓词清单独立定义，重复 Worker 回放不能改变六十个事实。 */
    private List<MemoryFact> createComplexSixtyFacts() {
        for (int group = 0; group < 3; group++) {
            for (int index = 0; index < 20; index++) {
                String eventId = "complex-sixty-" + group + "-" + index + "-" + SnowflakeIds.nextId();
                var candidate = json.createObjectNode();
                candidate.put("type", group==2?"episode":"preference").put("subject", "user:" + endUser)
                        .put("predicate", group == 2 ? "complex-episode-" + index : "complex-preference-" + index)
                        .put("signal", group == 2 ? "observed" : "explicit_save")
                        .put("scope", group == 0 ? "user" : "user_agent").put("sensitive", false);
                candidate.putObject("object").put("text", "complex 机构通知规则" + index + "，scope=" + group + "；预算与责任人均待确认。");
                String sourceText="用户明确提供的测试正文：complex 机构通知规则"+index+"，预算与责任人均待确认。";
                completeCandidate(candidate,"s0",sourceText,group==2?"task":"stable");
                var payload = json.createObjectNode();
                payload.set("memoryCandidate", candidate);
                payload.putArray("content").addObject().put("type","text").put("text",sourceText);
                InputId input = new InputId("input-" + eventId);
                var event = new SessionEventDraft(new EventId(eventId), "inbound.message", "v1", "fingerprint-" + eventId,
                        new TraceEnvelope("trace-" + eventId, "span-" + eventId, input, Optional.empty()),
                        new TypedPayload("skillforge.session.inbound.v1", payload.toString()), Instant.now());
                var result = new JdbcSessionLogPort(dataSource).append(new SessionLogPort.AppendSessionEvent(
                        new SessionProjectionTarget(owner, input, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()), event));
                assertTrue(result instanceof SessionLogPort.AppendResult.Appended);
            }
        }
        // Worker 每个 poll 对每个租户只领取一项，不能用原单消息用例的八次轮询假定六十项已完成。
        for (int attempt = 0; attempt < 80; attempt++) extraction.poll();
        assertNoFailedExtraction();
        assertEquals(0L, queryCount(jdbc, tenant, "select count(*) from agent_runtime_memory_extraction_jobs where tenant_id=? and status in ('READY','PROCESSING')", tenant));
        // 共享夹具通过显式管理确认后才进入 ACTIVE，不把模型 scope 提议当成已授权共享。
        for (MemoryFact pending : governance.list(tenant, "PENDING_CONFIRM", 200)) {
            assertEquals(MemoryScope.USER, pending.scope());
            governance.review(tenant, pending.ref().factId(), pending.revision(), true,
                    MemoryScope.USER, "confirm-complex-user-scope:" + pending.ref().factId());
        }
        List<MemoryFact> facts = governance.list(tenant, "ACTIVE", 200);
        assertEquals(60, facts.size());
        assertEquals(20L,facts.stream().filter(fact->fact.scope()==MemoryScope.USER).count());
        assertEquals(40L,facts.stream().filter(fact->fact.scope()==MemoryScope.USER_AGENT).count());
        assertEquals(20L,facts.stream().filter(fact->fact.type().value().equals("episode")).count());
        assertTrue(facts.stream().filter(fact->fact.type().value().equals("episode"))
                .allMatch(fact->fact.expiresAt().equals(Optional.of(fact.validFrom().plusSeconds(24*60*60)))));
        return facts;
    }

    /** 预算显式容纳六十事实，检索意图与夹具正文一致，空结果不能被当作隔离成功。 */
    private MemoryQuery complexQuery(SessionOwner reader, Set<MemoryScope> scopes) {
        // episode未注册USER范围；纯共享偏好查询只请求合法的preference类型。
        Set<MemoryTypeRef> types = scopes.equals(Set.of(MemoryScope.USER))
                ? Set.of(new MemoryTypeRef("preference"))
                : Set.of(new MemoryTypeRef("preference"), new MemoryTypeRef("episode"));
        return new MemoryQuery(
                "complex-query-" + SnowflakeIds.nextId(),
                new MemoryOwner.Session(reader.sessionRef()),
                Optional.empty(),
                "complex",
                Optional.empty(),
                scopes,
                types,
                MemoryQueryProfile.DEFAULT,
                Instant.now(),
                MemoryRetrievalPolicy.lexicalV1(),
                100,
                40_000,
                160_000,
                true);
    }

    @Test
    void complexSixtyFactsScopeOverlayCrossAgentAndCrossUserHaveIndependentExpectedSets() {
        createComplexSixtyFacts();
        var receipt = recorded(runtime.retrieve(new MemoryRuntime.RetrievalCall(chatContext(owner),
                complexQuery(owner, Set.of(MemoryScope.USER, MemoryScope.USER_AGENT, MemoryScope.SESSION)))));
        Set<String> expected = new java.util.HashSet<>();
        for (int i = 0; i < 20; i++) { expected.add("complex-preference-" + i); expected.add("complex-episode-" + i); }
        assertEquals(40, receipt.candidateFacts().size());
        assertEquals(expected, receipt.candidateFacts().stream().map(MemoryFact::predicate).collect(java.util.stream.Collectors.toSet()));
        assertTrue(receipt.candidateFacts().stream().noneMatch(fact -> fact.scope() == MemoryScope.USER));
        SessionOwner otherAgent = memoryOwner(Map.of("memory", Map.of("automatic_extraction", false)), "published");
        appendInbound(otherAgent, "complex-other-agent-" + SnowflakeIds.nextId(), "你好"); drainExtraction();
        var shared = recorded(runtime.retrieve(new MemoryRuntime.RetrievalCall(chatContext(otherAgent),
                complexQuery(otherAgent, Set.of(MemoryScope.USER, MemoryScope.USER_AGENT)))));
        assertEquals(20, shared.candidateFacts().size());
        assertTrue(shared.candidateFacts().stream().allMatch(fact -> fact.scope() == MemoryScope.USER));
        long stranger = endUsers.create(tenant, "complex 陌生用户").getId();
        SessionOwner differentUser = SessionOwner.user(new SessionRef(new TenantId(tenant), "complex-stranger-session-" + SnowflakeIds.nextId()),
                otherAgent.target().requireAgent(), new EndUserId(stranger));
        appendInbound(differentUser, "complex-stranger-input-" + SnowflakeIds.nextId(), "你好"); drainExtraction();
        var hidden = recorded(runtime.retrieve(new MemoryRuntime.RetrievalCall(chatContext(differentUser),
                complexQuery(differentUser, Set.of(MemoryScope.USER)))));
        assertTrue(hidden.candidateFacts().isEmpty());
    }

    @Test
    void complexSixtyFactsCorrectionForgetRestoreAndStaleCasNeverOverwriteUnrelatedFacts() {
        List<MemoryFact> baseline = createComplexSixtyFacts();
        MemoryFact target = baseline.stream().filter(f -> f.scope() == MemoryScope.USER_AGENT && f.predicate().equals("complex-preference-7")).findFirst().orElseThrow();
        MemoryFact corrected = governance.correct(tenant, target.ref().factId(), target.revision(), target.subject(), target.predicate(),
                explicitContent("text","complex 仅此规则已纠正，责任人仍待确认"), .9, .7, "complex-correct");
        assertEquals(target.ref(), corrected.derivedFrom().orElseThrow());
        var forgotten = governance.transition(tenant, corrected.ref().factId(), corrected.revision(), FactStatus.FORGOTTEN, "forgotten", "complex-forget");
        assertThrows(ApiException.class, () -> governance.correct(tenant, forgotten.ref().factId(), corrected.revision(), null, null,
                explicitContent("text","complex stale overwrite"), .9, .7, "complex-stale"));
        var restored = governance.transition(tenant, forgotten.ref().factId(), forgotten.revision(), FactStatus.ACTIVE, "restored", "complex-restore");
        assertEquals(corrected.ref(), restored.ref());
        List<MemoryFact> current = governance.list(tenant, null, 200);
        for (MemoryFact unchanged : baseline) {
            if (unchanged.ref().equals(target.ref())) continue;
            MemoryFact actual = current.stream().filter(f -> f.ref().equals(unchanged.ref())).findFirst().orElseThrow();
            assertEquals(unchanged.revision(), actual.revision());
            assertEquals(unchanged.object(), actual.object());
        }
        assertEquals(corrected.object(), restored.object());
        assertEquals(FactStatus.ACTIVE, restored.status());
    }

    @Test
    void complexSixtyFactsRemainStableAcrossRepeatedWorkerPollingAndJdbcAdapterReconstruction() {
        List<MemoryFact> baseline = createComplexSixtyFacts();
        extraction.poll(); extraction.poll();
        assertEquals(Set.copyOf(baseline.stream().map(MemoryFact::ref).toList()),
                Set.copyOf(governance.list(tenant, "ACTIVE", 200).stream().map(MemoryFact::ref).toList()));
        var query = complexQuery(owner, Set.of(MemoryScope.USER, MemoryScope.USER_AGENT, MemoryScope.SESSION));
        var context = chatContext(owner);
        var rebuilt = new com.skillforge.runtime.adapter.jdbc.memory.JdbcMemoryPort(dataSource, this::fixtureSearchDocument);
        var recovered = assertInstanceOf(com.skillforge.runtime.api.port.MemoryPort.MemoryQueryOutcome.Retrieved.class,
                rebuilt.query(new com.skillforge.runtime.api.port.MemoryPort.MemoryQueryCall(context, query))).result();
        assertEquals(60, recovered.candidates().size());
        assertEquals(Set.copyOf(baseline.stream().map(MemoryFact::ref).toList()), Set.copyOf(recovered.candidates().stream().map(MemoryFact::ref).toList()));
        var receipt = recorded(runtime.retrieve(new MemoryRuntime.RetrievalCall(context, query)));
        assertEquals(40, receipt.candidateFacts().size());
        assertEquals(receipt.candidateFacts(), runtime.replayRetrieval(context, receipt.committedEvent()).candidateFacts());
    }

    private void append(String eventId, boolean sensitive, String signal, String value) {
        append(owner, eventId, sensitive, signal, value);
    }

    private SessionOwner memoryOwner(Map<String,Object> config,String status) {
        var agent=new com.skillforge.core.agent.model.AgentDO();
        long id=SnowflakeIds.nextId();
        agent.setId(id);agent.setTenantId(Long.valueOf(tenant));agent.setAgentKey("memory-"+id);
        agent.setName("Memory 测试助手");agent.setSystemPrompt("测试记忆提取");
        agent.setConfig(config);agent.setStatus("published".equals(status) ? "draft" : status);
        if(!"draft".equals(status))agent.setPublishedAt(Instant.now());
        agents.save(agent);
        // published 夹具必须拥有真实不可变快照，Worker 从当前发布指针核验自动提取开关。
        if ("published".equals(status)) publications.publish(agent);
        return SessionOwner.user(new SessionRef(new TenantId(tenant),"memory-session-"+id),
                new AgentId(Long.toString(id)),new EndUserId(endUser));
    }

    private void append(SessionOwner sessionOwner, String eventId, boolean sensitive, String signal, String value) {
        var body=json.createObjectNode();
        String text="用户明确提供的测试正文："+value;
        body.putArray("content").addObject().put("type","text").put("text",text);
        var candidate=body.putObject("memoryCandidate");
        candidate.put("type","observed".equals(signal)?"episode":"preference").put("subject","user:"+sessionOwner.requireUser().value()).put("predicate","theme")
                .put("signal",signal).put("scope","user_agent").put("sensitive",sensitive);
        candidate.putObject("object").put("value",value);
        completeCandidate(candidate,"s0",text,"observed".equals(signal)?"task":"stable");
        String payload=body.toString();
        InputId input = new InputId("input-" + eventId);
        var target = new SessionProjectionTarget(sessionOwner, input, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
        var event = new SessionEventDraft(new EventId(eventId), "inbound.message", "v1",
                "fingerprint-" + eventId,
                new TraceEnvelope("trace-" + eventId, "span-" + eventId, input, Optional.empty()),
                new TypedPayload("skillforge.session.inbound.v1", payload), Instant.now());
        var result = new JdbcSessionLogPort(dataSource).append(
                new SessionLogPort.AppendSessionEvent(target, event));
        assertTrue(result instanceof SessionLogPort.AppendResult.Appended
                || result instanceof SessionLogPort.AppendResult.Replayed);
    }

    private void appendInbound(String eventId, String text) {
        appendInbound(owner, eventId, text);
    }

    private void appendInbound(SessionOwner sessionOwner, String eventId, String text) {
        var payload = json.createObjectNode();
        payload.putObject("extraParameters");
        // 当前入站契约从 content 中读取文本；JSON 构造同时保留引号和 Unicode 正文。
        payload.putArray("content").addObject().put("type", "text").put("text", text);
        InputId input = new InputId("input-" + eventId);
        var target = new SessionProjectionTarget(sessionOwner, input, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
        var event = new SessionEventDraft(new EventId(eventId), "inbound.message", "v1",
                "fingerprint-" + eventId,
                new TraceEnvelope("trace-" + eventId, "span-" + eventId, input, Optional.empty()),
                new TypedPayload("input.v1", payload.toString()), Instant.now());
        var result = new JdbcSessionLogPort(dataSource).append(
                new SessionLogPort.AppendSessionEvent(target, event));
        assertTrue(result instanceof SessionLogPort.AppendResult.Appended
                || result instanceof SessionLogPort.AppendResult.Replayed);
    }

    private PortCallContext chatContext(SessionOwner sessionOwner) {
        TenantId tenantId = sessionOwner.sessionRef().tenantId();
        String suffix = Long.toUnsignedString(SnowflakeIds.nextId());
        InputId input = new InputId("memory-it:" + suffix);
        RunIdentity run = new RunIdentity(sessionOwner, new AgentId((sessionOwner).target().id()), input, new RunRef(tenantId, "run-" + suffix), new TraceEnvelope("trace-" + suffix, "span-" + suffix, input, Optional.empty()), new ResultRef("cause-" + suffix), new RunRequester.EndUser((sessionOwner).requireUser()), java.util.Optional.empty(), new RunOriginRef((sessionOwner).sessionRef().tenantId(), "input:" + (input).value()));
        VerifiedIdentity identity = new VerifiedIdentity(
                new PrincipalRef.EndUserPrincipal(tenantId, sessionOwner.requireUser()), sessionOwner,
                Set.of("sessions.write"), 1, new ResultRef("verified-" + suffix));
        return new PortCallContext(run, identity,
                new InvocationControl(Instant.now().plusSeconds(30), () -> false));
    }

    /** 重建适配器的夹具投影只索引已知业务正文，不把字段名、schema或责任元数据当正文。 */
    private MemorySearchDocument fixtureSearchDocument(MemoryFact fact) {
        if (!Set.of("skillforge.memory.preference.v2", "skillforge.memory.episode.v2")
                .contains(fact.object().schemaId())) throw new IllegalArgumentException("unknown_fixture_memory_schema");
        try {
            var value=MemoryContent.decode(fact.object().canonicalJson()).value();
            var text = value.has("text") ? value.get("text") : value.get("value");
            if (text == null || !text.isTextual()) throw new IllegalArgumentException("fixture_memory_text_required");
            return new MemorySearchDocument(text.textValue(), MemorySearchDocument.VERSION);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("fixture_memory_payload_invalid", failure);
        }
    }

    private MemoryQuery query(SessionOwner sessionOwner, Set<MemoryScope> scopes) {
        return new MemoryQuery(
                "query-" + SnowflakeIds.nextId(),
                new MemoryOwner.Session(sessionOwner.sessionRef()),
                Optional.empty(),
                "深色 light",
                Optional.empty(),
                scopes,
                Set.of(new MemoryTypeRef("preference")),
                MemoryQueryProfile.DEFAULT,
                Instant.now(),
                MemoryRetrievalPolicy.lexicalV1(),
                20,
                4_000,
                16_000,
                true);
    }

    private com.fasterxml.jackson.databind.JsonNode readContent(MemoryFact fact) {
        try { return json.readTree(fact.object().canonicalJson()); }
        catch(java.io.IOException invalid) { throw new AssertionError("invalid persisted host memory",invalid); }
    }

    private String explicitContent(String field,String value) {
        return MemoryContent.encode(MemoryContent.explicitContent(json.createObjectNode().put(field,value),"preference"));
    }

    private MemoryFactDraft userDraft(SessionOwner sessionOwner, String sourceEventId) {
        return new MemoryFactDraft(
                new MemoryOwner.User(sessionOwner.sessionRef().tenantId(), sessionOwner.requireUser()),
                new MemoryTypeRef("preference"),
                new RetentionClassRef("standard"),
                "v2",
                "user:" + endUser,
                "theme",
                new TypedPayload("skillforge.memory.preference.v2", explicitContent("text", "x")),
                List.of(new EventId(sourceEventId)),
                sessionOwner.sessionRef(),
                Optional.empty(),
                Optional.empty(),
                "memory-extractor-v1",
                Instant.now(),
                Optional.empty(),
                Optional.empty(),
                0.8,
                0.5,
                false,
                Optional.empty(),
                FactStatus.ACTIVE);
    }

    private MemoryRuntime.RetrievalReceipt recorded(MemoryRuntime.RetrievalResult result) {
        return assertInstanceOf(MemoryRuntime.RetrievalResult.Recorded.class, result,
                () -> "真实Memory检索未完成：" + result).receipt();
    }

    /** 失败诊断只记录公开错误字段，不将成功结果、JWT或整个请求体带入断言。 */
    private String publicHttpError(HttpResponse<String> response) {
        try {
            var body = json.readTree(response.body());
            return "code=" + body.path("code").asText() + ", message=" + body.path("message").asText();
        } catch (java.io.IOException invalid) {
            return "non-json-http-response";
        }
    }

    private void drainExtraction() {
        for (int attempt = 0; attempt < 8; attempt++) {
            extraction.poll();
        }
        assertNoFailedExtraction();
    }

    private void assertNoFailedExtraction() {
        var failures = queryRows(jdbc, tenant,
                "select memory_type,error_code from agent_runtime_memory_extraction_jobs where tenant_id=? and status='FAILED'",
                tenant);
        assertTrue(failures.isEmpty(), () -> "记忆任务失败回执：" + failures);
    }

    private String jobStatus(String eventId) {
        return String.valueOf(extractionJob(eventId).get("status"));
    }

    /** 精确断言当前来源任务的终态与稳定原因，防止其他 SKIPPED 分支误报提取成功。 */
    private void assertJobOutcome(String eventId, String expectedStatus, String expectedError) {
        Map<String, Object> job = extractionJob(eventId);
        assertEquals(expectedStatus, job.get("status"), () -> "记忆任务回执：" + job);
        assertEquals(expectedError, job.get("error_code"), () -> "记忆任务回执：" + job);
    }

    /** 只观察当前隔离租户的事实、操作及 memory.written 数量，配合完整当前事实等值断言检查拒绝无写。 */
    private Map<String, Long> memoryWriteCounts() {
        return Map.of(
                "heads", queryCount(jdbc, tenant, "select count(*) from agent_runtime_memory_fact_heads where tenant_id=?", tenant),
                "revisions", queryCount(jdbc, tenant, "select count(*) from agent_runtime_memory_fact_revisions where tenant_id=?", tenant),
                "operations", queryCount(jdbc, tenant, "select count(*) from agent_runtime_memory_operations where tenant_id=?", tenant),
                "writtenEvents", queryCount(jdbc, tenant, "select count(*) from agent_runtime_session_events where tenant_id=? and event_type='memory.written'", tenant));
    }

    /** 本类计数在原业务DataSource的事务内绑定租户并查询；默认REQUIRED复用已有外层事务。 */
    protected static long queryCount(JdbcTemplate jdbc, String tenantId, String sql, Object... args) {
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(
                Objects.requireNonNull(jdbc.getDataSource(), "memory_test_datasource_missing")));
        return transactions.execute(ignored -> AbstractIntegrationTest.queryCount(jdbc, tenantId, sql, args));
    }

    /** 本类诊断行与transaction-local租户绑定共用事务，不改变SQL、参数或业务连接角色。 */
    protected static List<Map<String, Object>> queryRows(
            JdbcTemplate jdbc, String tenantId, String sql, Object... args) {
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(
                Objects.requireNonNull(jdbc.getDataSource(), "memory_test_datasource_missing")));
        return transactions.execute(ignored -> AbstractIntegrationTest.queryRows(jdbc, tenantId, sql, args));
    }

    /** 同连接绑定租户后读取唯一来源任务，不以空行代替没有失败。 */
    private Map<String, Object> extractionJob(String eventId) {
        List<Map<String, Object>> rows = queryRows(jdbc, tenant,
                "select status,error_code from agent_runtime_memory_extraction_jobs "
                        + "where tenant_id=? and source_event_id=? and memory_type='inbound-hydrate'",
                tenant, eventId);
        assertEquals(1, rows.size());
        return rows.getFirst();
    }
}
