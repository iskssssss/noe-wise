package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.AgentPublicationService;
import com.skillforge.core.agent.AgentService;
import com.skillforge.core.agent.memory.MemoryContent;
import com.skillforge.core.agent.memory.MemoryJsonCodec;
import com.skillforge.core.agent.model.AgentDO;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates.*;
import com.skillforge.core.agent.systemagent.SystemAgentService;
import com.skillforge.core.agent.systemagent.SystemAgentTypes;
import com.skillforge.core.chat.contract.ChatContent;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.model.invocation.LlmResult;
import com.skillforge.core.model.invocation.LlmService;
import com.skillforge.core.billing.metering.LlmUsage;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.model.PlatformModelConfig;
import com.skillforge.core.foundation.application.EndUserService;
import com.skillforge.institution.group.*;
import com.skillforge.institution.memory.MemoryExtractionWorker;
import com.skillforge.institution.runtime.AgentRuntimeHostProvisioner;
import com.skillforge.runtime.api.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 正式来源登记→通用提取 Worker→系统用途模型→群事实；只控制外部模型，不替换业务执行链。 */
class GroupMemoryExtractionIntegrationTest extends AbstractIntegrationTest {
    private static final Set<String> SCOPES = Set.of("groups.write", "groups.read", "groups.members.write",
            "groups.agents.write", "groups.messages.write", "groups.memory.read", "groups.memory.write",
            "groups.controls.write", "groups.controls.read");
    @Autowired TenantMapper tenants;
    @Autowired PlanMapper plans;
    @Autowired EndUserService users;
    @Autowired AgentService agents;
    @Autowired AgentPublicationService publications;
    @Autowired SystemAgentService systemAgents;
    @Autowired GroupBindingService groups;
    @Autowired GroupMessageService messages;
    @Autowired GroupMemoryService memories;
    @Autowired GroupControlService controls;
    @Autowired GroupMemoryExtractionPolicyService policies;
    @Autowired MemoryExtractionWorker worker;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean LlmService llm;
    @MockitoBean PlatformModelCatalogService models;
    @MockitoBean AgentRuntimeHostProvisioner hostProvisioner;
    // 每例显式 poll，正式调度器不抢占本例的 pending/job。
    @MockitoBean TaskScheduler scheduler;
    private Domain domain;
    private Fixture fixture;
    private final AtomicInteger modelCalls = new AtomicInteger();
    private final MemoryExtractionCandidates contract = new MemoryExtractionCandidates();

    @BeforeEach
    void prepare() throws Exception {
        var model = mock(PlatformModelConfig.class);
        when(model.revision()).thenReturn(1L);
        when(models.requireEnabled("1", "LLM")).thenReturn(model);
        var current = systemAgents.detail("900000000000000003");
        var draft = current.draft();
        var changed = systemAgents.update(current.id(), current.revision(), new SystemAgentTypes.Config(
                draft.name(), draft.description(), draft.systemPrompt(), "1", 30, 1, List.of()), "gi06-test");
        systemAgents.transition(changed.id(), changed.revision(), "publish", "gi06-test");
        String tenant = createTenant(tenants, plans, "group-extraction-" + SnowflakeIds.nextId());
        domain = new Domain(tenant, "memory-it-" + SnowflakeIds.nextId(), Long.toString(SnowflakeIds.nextId()),
                "sp_group_extract_" + SnowflakeIds.nextId());
        in(() -> {
            jdbc.update("INSERT INTO nw_institution_integrations(tenant_id,integration_id) VALUES(?,?)",
                    Long.valueOf(domain.tenant()), domain.integration());
            jdbc.update("""
                    INSERT INTO nw_integration_business_scopes(scope_id,tenant_id,integration_id,scope_type,external_ref,
                        display_name,status,revision,actor_ref) VALUES(?,?,?,'business',?,'群记忆测试','active',1,?)
                    """, domain.scope(), Long.valueOf(domain.tenant()), domain.integration(), "scope-" + domain.scope(),
                    "service_principal:" + domain.principal());
            jdbc.update("""
                    INSERT INTO nw_service_principals(id,tenant_id,principal_id,integration_id,api_key_hash,api_key_prefix,
                        signing_secret_encrypted,scopes,status,credential_version)
                    VALUES(?,?,?,?,?,?,?,?::jsonb,'active',1)
                    """, SnowflakeIds.nextId(), Long.valueOf(domain.tenant()), domain.principal(), domain.integration(),
                    String.format("%064d", SnowflakeIds.nextId()), "group_mem_test", "unused-test-secret", encode(SCOPES));
            return null;
        });
        fixture = in(() -> {
            var group = groups.ensure(new GroupContracts.Ensure("memory-it", "group-" + SnowflakeIds.nextId(),
                    "life-1", domain.scope()), "ensure-group");
            long author = users.ensureExternal(domain.tenant(), domain.integration(), "author", "作者").getId();
            long manager = users.ensureExternal(domain.tenant(), domain.integration(), "manager", "管理员").getId();
            for (long user : List.of(author, manager)) groups.updateMember(new GroupContracts.UpdateMember(
                    group.groupBindingId(), user, user == manager ? "manager" : "member", "active", 0L,
                    "join-" + user, 1L), "join-" + user);
            AgentDO agent = new AgentDO();
            agent.setId(SnowflakeIds.nextId()); agent.setTenantId(Long.valueOf(domain.tenant()));
            agent.setAgentKey("group-extract-" + agent.getId()); agent.setName("群记忆服务 Agent");
            agent.setSystemPrompt("测试自动提取"); agent.setStatus("draft");
            agent.setConfig(Map.of("memory", Map.of("automatic_extraction", true)));
            agents.save(agent); publications.publish(agent);
            groups.updateAgent(new GroupContracts.UpdateAgent(group.groupBindingId(), agent.getId(), "active", 0L,
                    "serve-" + agent.getId(), 1L), "serve-agent");
            return new Fixture(group.groupBindingId(), group.sessionId(), author, manager, agent.getId());
        });
    }

    @AfterEach
    void clear() { ServicePrincipalContext.clear(); TenantContext.clear(); }

    @Test
    void explicitPolicyConsumesUntriggeredSourcesAndKeepsSharedMemberAndPendingOwnership() {
        var source = record("请记住：本群周五复盘；我偏好下午提醒；我的病历需要确认；管理员偏好上午。", fixture.author());
        var disabled = in(() -> policies.get(fixture.group(), fixture.manager()));
        assertFalse(disabled.enabled());
        assertEquals(0, disabled.revision());
        assertNull(disabled.extractorAgentId());
        assertEquals(1, count("SELECT count(*) FROM nw_group_memory_extraction_pending WHERE tenant_id::text=?"));
        worker.poll();
        assertEquals(0, modelCalls.get(), "未配置群策略默认不得调用模型");
        enablePolicy();
        controlled(input -> List.of(
                candidate(input, "group", "group:" + fixture.group(), "weekly_review", false),
                candidate(input, "group_member", "user:" + fixture.author(), "own_reminder", false),
                candidate(input, "group_member", "user:" + fixture.author(), "sensitive_medical", true),
                candidate(input, "group_member", "user:" + fixture.manager(), "third_party_reminder", false)));
        drain();
        assertEquals(1, modelCalls.get());
        assertEquals(0, count("SELECT count(*) FROM nw_group_triggers WHERE tenant_id::text=?"), "群记忆不依赖前台触发");
        assertEquals(0, count("SELECT count(*) FROM nw_group_memory_extraction_pending WHERE tenant_id::text=?"));
        List<MemoryFact> active = list(fixture.author(), FactStatus.ACTIVE);
        assertEquals(Set.of("weekly_review", "own_reminder"), active.stream().map(MemoryFact::predicate).collect(java.util.stream.Collectors.toSet()));
        MemoryFact shared = active.stream().filter(f -> f.scope() == MemoryScope.GROUP).findFirst().orElseThrow();
        assertEquals(new MemoryOwner.Group(new GroupBindingRef(new TenantId(domain.tenant()), fixture.group())), shared.owner());
        MemoryFact own = active.stream().filter(f -> f.scope() == MemoryScope.GROUP_MEMBER).findFirst().orElseThrow();
        assertEquals(new MemoryOwner.GroupMember(new GroupBindingRef(new TenantId(domain.tenant()), fixture.group()), new EndUserId(fixture.author())), own.owner());
        for (MemoryFact fact : active) assertEquals(List.of(GroupMemorySources.eventId(source.sourceMessageId(), 1)), fact.sourceEventIds());
        assertEquals(List.of("sensitive_medical"), list(fixture.author(), FactStatus.PENDING_CONFIRM).stream()
                .map(MemoryFact::predicate).toList());
        assertEquals(List.of("third_party_reminder"), list(fixture.manager(), FactStatus.PENDING_CONFIRM).stream()
                .map(MemoryFact::predicate).toList());
        long revisions = count("SELECT count(*) FROM agent_runtime_memory_fact_revisions WHERE tenant_id::text=?");
        worker.poll(); worker.poll();
        assertEquals(1, modelCalls.get());
        assertEquals(revisions, count("SELECT count(*) FROM agent_runtime_memory_fact_revisions WHERE tenant_id::text=?"));
        assertJob(source, "COMPLETED", null);
    }

    @Test
    void sendPauseDoesNotBlockExtractionAndHistoricalAuthorDoesNotNeedCurrentMembership() {
        enablePolicy();
        controlled(input -> List.of(candidate(input, "group_member", "user:" + fixture.author(), "historical_preference", false)));
        control("send-paused", "send", "paused", 0);
        var source = record("请记住我偏好下午提醒。", fixture.author());
        in(() -> groups.updateMember(new GroupContracts.UpdateMember(fixture.group(), fixture.author(), "member", "left",
                1L, "author-left", 2L), "author-left"));
        drain();
        assertEquals(1, modelCalls.get());
        assertEquals(List.of("historical_preference"), list(fixture.author(), FactStatus.ACTIVE).stream().map(MemoryFact::predicate).toList());
        assertJob(source, "COMPLETED", null);
        assertEquals(0, count("SELECT count(*) FROM nw_group_triggers WHERE tenant_id::text=?"));
    }

    @Test
    void memoryPausePreservesPendingUntilExplicitResumeWithoutDispatchingModel() {
        enablePolicy();
        controlled(input -> List.of(candidate(input, "group", "group:" + fixture.group(), "resume_preference", false)));
        control("memory-paused", "memory_extraction", "paused", 0);
        var source = record("请记住本群周五复盘。", fixture.author());
        worker.poll(); worker.poll();
        assertEquals(0, modelCalls.get());
        assertEquals(1, count("SELECT count(*) FROM nw_group_memory_extraction_pending WHERE tenant_id::text=?"));
        assertEquals(0, count("SELECT count(*) FROM agent_runtime_memory_fact_heads WHERE tenant_id::text=?"));
        control("memory-resumed", "memory_extraction", "running", 1);
        drain();
        assertEquals(1, modelCalls.get());
        assertJob(source, "COMPLETED", null);
    }

    @Test
    void forgottenSourceCannotBeRebuiltUnderNewTaskWhileNewStatementRequiresConfirmation() {
        enablePolicy();
        controlled(input -> List.of(candidate(input, "group_member", "user:" + fixture.author(), "forgotten_preference", false)));
        var original = record("请记住我偏好下午提醒。", fixture.author());
        drain();
        MemoryFact fact = list(fixture.author(), FactStatus.ACTIVE).getFirst();
        MemoryFact forgotten = in(() -> memories.forget(new GroupMemoryContracts.Revision(fixture.group(),
                Long.toString(fixture.author()), Long.toString(fixture.agent()), fact.ref().factId(), fact.revision()), "forget-own-memory"));
        assertEquals(FactStatus.FORGOTTEN, forgotten.status());
        // 新任务具有独立键及模型调用，原终态任务、来源和事实修订均保留。
        in(() -> jdbc.update("""
                INSERT INTO agent_runtime_memory_extraction_jobs(tenant_id,source_event_id,extractor_version,policy_version,
                    memory_type,canonical_fact_key,agent_id,end_user_id,source_session_id,source_target_kind,source_target_id,
                    source_event_type,integration_id,group_binding_id)
                SELECT tenant_id,source_event_id,extractor_version,policy_version,memory_type,'rebuild-source',agent_id,
                    end_user_id,source_session_id,source_target_kind,source_target_id,source_event_type,integration_id,group_binding_id
                FROM agent_runtime_memory_extraction_jobs WHERE tenant_id=? AND source_event_id=? AND canonical_fact_key='source'
                """, domain.tenant(), GroupMemorySources.eventId(original.sourceMessageId(), original.sourceRevision()).value()));
        drain();
        assertEquals(2, modelCalls.get(), "新任务确实经过独立模型解释");
        var rebuilt = in(() -> jdbc.queryForMap("""
                SELECT status,error_code FROM agent_runtime_memory_extraction_jobs
                WHERE tenant_id=? AND canonical_fact_key='rebuild-source'
                """, domain.tenant()));
        assertEquals("SKIPPED", rebuilt.get("status"));
        assertEquals("memory_forgotten_source_suppressed", rebuilt.get("error_code"));
        assertEquals(1, count("SELECT count(*) FROM agent_runtime_memory_fact_heads WHERE tenant_id::text=?"));
        assertTrue(list(fixture.author(), FactStatus.ACTIVE).isEmpty());
        assertEquals(List.of(forgotten.ref()), list(fixture.author(), FactStatus.FORGOTTEN).stream().map(MemoryFact::ref).toList());
        var fresh = record("请记住我偏好下午提醒。", fixture.author());
        drain();
        assertEquals(3, modelCalls.get());
        assertJob(fresh, "COMPLETED", null);
        assertTrue(list(fixture.author(), FactStatus.ACTIVE).isEmpty());
        assertEquals(List.of("forgotten_preference"), list(fixture.author(), FactStatus.PENDING_CONFIRM).stream().map(MemoryFact::predicate).toList());
    }

    @Test
    void ownCorrectionReviewArchivesOriginalAndOldSourceCannotCreateAnotherActiveFact() {
        enablePolicy();
        controlled(input -> List.of(candidate(input, "group_member", "user:" + fixture.author(), "contact_channel", false)));
        var originalSource = record("请记住我通常希望通过电话联系。", fixture.author());
        drain();
        MemoryFact original = list(fixture.author(), FactStatus.ACTIVE).getFirst();
        controlled(input -> {
            List<ExistingFact> targets = input.existingFacts().stream().filter(fact -> fact.scope().equals("group_member")
                    && fact.subject().equals(original.subject()) && fact.predicate().equals(original.predicate())).toList();
            assertEquals(1, targets.size());
            assertEquals(original.revision(), targets.getFirst().revision());
            Source source = input.sources().getFirst();
            return List.of(new Candidate("preference", original.subject(), original.predicate(),
                    json.createObjectNode().put("text", "电子邮件"), "explicit_save", false, "group_member",
                    new Temporal("stable", null, null), List.of(), List.of(new Evidence(source.id(), source.text())),
                    new Relation("correction", targets.getFirst().id()), List.of()));
        });
        var correctionSource = record("更正一下：以后不要电话联系，请通过电子邮件联系我。", fixture.author());
        drain();
        assertJob(correctionSource, "COMPLETED", null);
        assertEquals(List.of(original), list(fixture.author(), FactStatus.ACTIVE));
        MemoryFact proposal = list(fixture.author(), FactStatus.PENDING_CONFIRM).getFirst();
        assertEquals(java.util.Optional.of(original.ref()), proposal.derivedFrom());
        var target = MemoryContent.revisionProposal(MemoryContent.decode(proposal.object().canonicalJson())).orElseThrow();
        assertEquals(original.ref().factId(), target.factId());
        assertEquals(original.revision(), target.expectedRevision());
        assertEquals(List.of(GroupMemorySources.eventId(correctionSource.sourceMessageId(), correctionSource.sourceRevision())),
                proposal.sourceEventIds());
        var review = new GroupMemoryContracts.Review(fixture.group(), Long.toString(fixture.author()),
                Long.toString(fixture.agent()), proposal.ref().factId(), proposal.revision(), true);
        MemoryFact accepted = in(() -> memories.review(review, "accept-own-correction"));
        assertEquals(FactStatus.ACTIVE, accepted.status());
        assertEquals(proposal.ref(), accepted.ref());
        assertEquals(proposal.revision() + 1, accepted.revision());
        assertEquals(proposal.object(), accepted.object());
        assertEquals(proposal.sourceEventIds(), accepted.sourceEventIds());
        assertEquals(java.util.Optional.of(original.ref()), accepted.derivedFrom());
        assertEquals(List.of(accepted), list(fixture.author(), FactStatus.ACTIVE));
        MemoryFact archived = list(fixture.author(), FactStatus.ARCHIVED).getFirst();
        assertEquals(original.ref(), archived.ref());
        assertEquals(original.revision() + 1, archived.revision());
        assertEquals(original.object(), archived.object());
        assertEquals(original.sourceEventIds(), archived.sourceEventIds());
        assertEquals(java.util.Optional.of(accepted.ref()), archived.supersededBy());
        assertTrue(archived.validTo().isPresent());
        long revisions = count("SELECT count(*) FROM agent_runtime_memory_fact_revisions WHERE tenant_id::text=?");
        assertEquals(accepted, in(() -> memories.review(review, "accept-own-correction")));
        assertEquals(revisions, count("SELECT count(*) FROM agent_runtime_memory_fact_revisions WHERE tenant_id::text=?"));
        // 新任务再次解释旧消息，不复用原模型回执，也不能将旧偏好变成新的 ACTIVE。
        controlled(input -> List.of(candidate(input, "group_member", "user:" + fixture.author(), "contact_channel", false)));
        in(() -> jdbc.update("""
                INSERT INTO agent_runtime_memory_extraction_jobs(tenant_id,source_event_id,extractor_version,policy_version,
                    memory_type,canonical_fact_key,agent_id,end_user_id,source_session_id,source_target_kind,source_target_id,
                    source_event_type,integration_id,group_binding_id)
                SELECT tenant_id,source_event_id,extractor_version,policy_version,memory_type,'rebuild-before-correction',agent_id,
                    end_user_id,source_session_id,source_target_kind,source_target_id,source_event_type,integration_id,group_binding_id
                FROM agent_runtime_memory_extraction_jobs WHERE tenant_id=? AND source_event_id=? AND canonical_fact_key='source'
                """, domain.tenant(), GroupMemorySources.eventId(originalSource.sourceMessageId(), originalSource.sourceRevision()).value()));
        drain();
        assertEquals(3, modelCalls.get());
        var rebuilt = in(() -> jdbc.queryForMap("""
                SELECT status,error_code FROM agent_runtime_memory_extraction_jobs
                WHERE tenant_id=? AND canonical_fact_key='rebuild-before-correction'
                """, domain.tenant()));
        assertEquals("SKIPPED", rebuilt.get("status"));
        assertEquals("memory_superseded_source_suppressed", rebuilt.get("error_code"));
        assertEquals(List.of(accepted), list(fixture.author(), FactStatus.ACTIVE));
        assertEquals(List.of(archived), list(fixture.author(), FactStatus.ARCHIVED));
        assertTrue(list(fixture.author(), FactStatus.PENDING_CONFIRM).isEmpty());
        assertEquals(2, count("SELECT count(*) FROM agent_runtime_memory_fact_heads WHERE tenant_id::text=?"));
    }

    private void enablePolicy() {
        var changed = in(() -> policies.put(new GroupMemoryExtractionPolicy.Command(fixture.group(), fixture.manager(),
                true, fixture.agent(), 0L)));
        assertTrue(changed.enabled());
        assertEquals(1, changed.revision());
        assertEquals(Long.toString(fixture.agent()), changed.extractorAgentId());
        assertEquals(changed, in(() -> policies.get(fixture.group(), fixture.manager())));
    }

    private GroupMessageContracts.MessageReceipt record(String text, long author) {
        String event = "source-" + SnowflakeIds.nextId();
        return in(() -> messages.record(new GroupMessageContracts.MessageRecord(fixture.group(), event, 1L,
                new GroupMessageContracts.EndUserAuthor(author), List.of(new ChatContent.Text(text))), event));
    }

    private void control(String command, String scope, String desired, long revision) {
        in(() -> controls.command(new GroupControlContracts.Command(command, fixture.group(), null, revision, desired,
                List.of(scope)), command));
    }

    private List<MemoryFact> list(long subject, FactStatus status) {
        return in(() -> memories.list(fixture.group(), Long.toString(fixture.manager()), Long.toString(fixture.agent()),
                Long.toString(subject), null, status.name(), 50));
    }

    private void controlled(Function<ExtractionInput, List<Candidate>> response) {
        doAnswer(invocation -> {
            ExtractionInput input = contract.decodeInput(invocation.getArgument(4, String.class));
            assertEquals(3, input.schemaVersion());
            assertEquals(fixture.author(), input.userId());
            assertEquals(fixture.group(), input.group().groupBindingId());
            assertEquals(Set.of(fixture.author(), fixture.manager()), Set.copyOf(input.group().subjectEndUserIds()));
            modelCalls.incrementAndGet();
            return new LlmResult(MemoryJsonCodec.encode(new ExtractionOutput(3, response.apply(input))),
                    "controlled-group-extraction", LlmUsage.empty());
        }).when(llm).generatePinned(anyString(), anyString(), anyLong(), anyString(), anyString(), any(), any(), anyInt(), any());
    }

    private Candidate candidate(ExtractionInput input, String scope, String subject, String predicate, boolean sensitive) {
        Source source = input.sources().getFirst();
        return new Candidate("preference", subject, predicate, json.createObjectNode().put("text", source.text()),
                "explicit_save", sensitive, scope, new Temporal("stable", null, null), List.of(),
                List.of(new Evidence(source.id(), source.text())), new Relation("new", null), List.of());
    }

    private void drain() {
        for (int i = 0; i < 6; i++) worker.poll();
        assertTrue(rows("SELECT status,error_code FROM agent_runtime_memory_extraction_jobs WHERE tenant_id::text=? AND status IN('FAILED','UNKNOWN')").isEmpty(),
                () -> "任务失败：" + rows("SELECT status,error_code FROM agent_runtime_memory_extraction_jobs WHERE tenant_id::text=?"));
    }

    private void assertJob(GroupMessageContracts.MessageReceipt source, String status, String error) {
        var jobs = in(() -> jdbc.queryForList("SELECT status,error_code FROM agent_runtime_memory_extraction_jobs WHERE tenant_id::text=? AND source_event_id=?",
                domain.tenant(), GroupMemorySources.eventId(source.sourceMessageId(), source.sourceRevision()).value()));
        assertEquals(1, jobs.size());
        assertEquals(status, jobs.getFirst().get("status"), () -> "任务回执：" + jobs);
        assertEquals(error, jobs.getFirst().get("error_code"));
    }

    private long count(String sql) { return in(() -> jdbc.queryForObject(sql, Long.class, domain.tenant())); }
    private List<Map<String,Object>> rows(String sql) { return in(() -> jdbc.queryForList(sql, domain.tenant())); }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }

    /** 同原群持久化夹具：可信固定 SP 与租户只由服务调用上下文传入，SQL 观察与业务写入共用事务。 */
    private <T> T in(Supplier<T> action) {
        String previous = TenantContext.getTenantId();
        ServicePrincipalContext.Identity prior = ServicePrincipalContext.get();
        TenantContext.setTenantId(domain.tenant());
        ServicePrincipalContext.set(new ServicePrincipalContext.Identity(domain.principal(), domain.tenant(), domain.integration(), 1, SCOPES));
        try {
            return new TransactionTemplate(transactionManager).execute(ignored -> {
                jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)", String.class, domain.tenant());
                return action.get();
            });
        } finally {
            if (previous == null) TenantContext.clear(); else TenantContext.setTenantId(previous);
            if (prior == null) ServicePrincipalContext.clear(); else ServicePrincipalContext.set(prior);
        }
    }

    private record Domain(String tenant, String integration, String scope, String principal) { }
    private record Fixture(String group, String session, long author, long manager, long agent) { }
}
