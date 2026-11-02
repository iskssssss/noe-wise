package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.AgentPublicationService;
import com.skillforge.core.agent.AgentService;
import com.skillforge.core.agent.memory.MemoryContent;
import com.skillforge.core.agent.model.AgentDO;
import com.skillforge.core.chat.contract.ChatContent;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.application.EndUserService;
import com.skillforge.institution.group.*;
import com.skillforge.institution.runtime.AgentRuntimeHostProvisioner;
import com.skillforge.institution.runtime.MemorySearchProperties;
import com.skillforge.institution.runtime.MemorySearchToolHandler;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.adapter.jdbc.memory.JdbcMemoryPort;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.memory.MemoryRuntime;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 正式群来源、归属事实和真实检索/冻结链；MODEL仅在Program夹具边界受控。 */
class GroupMemoryRecallIntegrationTest extends AbstractIntegrationTest {
    private static final Set<String> SCOPES = Set.of("groups.write", "groups.read", "groups.members.write",
            "groups.agents.write", "groups.messages.write", "groups.memory.read", "groups.memory.write");
    @Autowired TenantMapper tenants;
    @Autowired PlanMapper plans;
    @Autowired EndUserService users;
    @Autowired AgentService agents;
    @Autowired AgentPublicationService publications;
    @Autowired GroupBindingService groups;
    @Autowired GroupMessageService messages;
    @Autowired GroupMemoryService memories;
    @Autowired MemoryRuntime runtime;
    @Autowired JdbcMemoryPort memoryStore;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean AgentRuntimeHostProvisioner hostProvisioner;
    @MockitoBean TaskScheduler scheduler;
    private Domain domain;
    private Fixture fixture;
    private MemorySearchToolHandler handler;

    @DynamicPropertySource
    static void ownedExecution(DynamicPropertyRegistry registry) {
        registry.add("agent-runtime.workers.auto-startup", () -> false);
    }

    @BeforeEach
    void prepare() {
        String tenant = createTenant(tenants, plans, "group-recall-" + SnowflakeIds.nextId());
        domain = new Domain(tenant, "memory-recall-" + SnowflakeIds.nextId(), Long.toString(SnowflakeIds.nextId()),
                "sp_group_recall_" + SnowflakeIds.nextId());
        in(() -> {
            jdbc.update("INSERT INTO nw_institution_integrations(tenant_id,integration_id) VALUES(?,?)",
                    Long.valueOf(domain.tenant()), domain.integration());
            jdbc.update("""
                    INSERT INTO nw_integration_business_scopes(scope_id,tenant_id,integration_id,scope_type,external_ref,
                        display_name,status,revision,actor_ref) VALUES(?,?,?,'business',?,'群召回测试','active',1,?)
                    """, domain.scope(), Long.valueOf(domain.tenant()), domain.integration(), "scope-" + domain.scope(),
                    "service_principal:" + domain.principal());
            jdbc.update("""
                    INSERT INTO nw_service_principals(id,tenant_id,principal_id,integration_id,api_key_hash,api_key_prefix,
                        signing_secret_encrypted,scopes,status,credential_version)
                    VALUES(?,?,?,?,?,?,?,?::jsonb,'active',1)
                    """, SnowflakeIds.nextId(), Long.valueOf(domain.tenant()), domain.principal(), domain.integration(),
                    String.format("%064d", SnowflakeIds.nextId()), "group_recall", "unused-test-secret", encode(SCOPES));
            return null;
        });
        fixture = in(() -> {
            long author = user("author"), manager = user("manager"), subject = user("subject");
            long firstAgent = agent(), secondAgent = agent();
            var first = groups.ensure(new GroupContracts.Ensure("memory-recall", "first", "life-1", domain.scope()), "first-group");
            var other = groups.ensure(new GroupContracts.Ensure("memory-recall", "other", "life-1", domain.scope()), "other-group");
            for (var group : List.of(first, other)) {
                for (long user : List.of(author, manager, subject)) groups.updateMember(new GroupContracts.UpdateMember(
                        group.groupBindingId(), user, user == manager ? "manager" : "member", "active", 0L,
                        "join-" + group.groupBindingId() + "-" + user, 1L), "join-" + group.groupBindingId() + "-" + user);
                for (long agent : List.of(firstAgent, secondAgent)) groups.updateAgent(new GroupContracts.UpdateAgent(
                        group.groupBindingId(), agent, "active", 0L, "serve-" + agent, 1L), "serve-" + group.groupBindingId() + "-" + agent);
            }
            return new Fixture(first.groupBindingId(), first.sessionId(), other.groupBindingId(), other.sessionId(),
                    author, manager, subject, firstAgent, secondAgent);
        });
        handler = new MemorySearchToolHandler(runtime, Clock.systemUTC(), new MemorySearchProperties(), groups);
    }

    @AfterEach
    void clear() { ServicePrincipalContext.clear(); TenantContext.clear(); }

    @Test
    void sameGroupFactsAreSharedAcrossAgentsAndExplicitSubjectCanHaveLeftTheGroup() {
        MemoryFact shared = create(fixture.group(), fixture.manager(), null, "group_report", "经营周报统一使用表格汇报");
        MemoryFact own = create(fixture.group(), fixture.author(), fixture.author(), "own_report", "经营周报本人偏好周一发送");
        MemoryFact other = create(fixture.group(), fixture.subject(), fixture.subject(), "subject_report", "经营周报另一成员偏好周二复核");
        MemoryFact foreign = create(fixture.otherGroup(), fixture.manager(), null, "foreign_report", "经营周报另一群绝密报价");
        var first = search(context(fixture.firstAgent(), fixture.author()), Map.of("query", "经营周报"));
        var second = search(context(fixture.secondAgent(), fixture.author()), Map.of("query", "经营周报"));
        assertEquals(Set.of(shared.ref().factId(), own.ref().factId()), factIds(first));
        assertEquals(factIds(first), factIds(second));
        assertFalse(factIds(second).contains(other.ref().factId()));
        assertFalse(factIds(second).contains(foreign.ref().factId()));
        in(() -> groups.updateMember(new GroupContracts.UpdateMember(fixture.group(), fixture.subject(), "member", "left",
                1L, "subject-left", 2L), "subject-left"));
        var selected = search(context(fixture.secondAgent(), fixture.author()), Map.of("query", "经营周报",
                "subject_end_user_id", Long.toString(fixture.subject())));
        assertEquals(Set.of(shared.ref().factId(), other.ref().factId()), factIds(selected));
        var missing = search(context(fixture.secondAgent(), fixture.author()), Map.of("query", "经营周报",
                "subject_end_user_id", Long.toString(SnowflakeIds.nextId())));
        assertEquals(false, missing.get("success"));
    }

    @Test
    void crossGroupAndPersonalBranchesAreRejectedAndFrozenRetrievalKeepsOriginalFactRevision() {
        MemoryFact original = create(fixture.group(), fixture.manager(), null, "group_report", "经营周报冻结原版内容");
        var call = context(fixture.secondAgent(), fixture.author());
        var first = search(call, Map.of("query", "经营周报"));
        assertEquals(Set.of(original.ref().factId()), factIds(first));
        String revised = MemoryContent.encode(MemoryContent.explicitContent(json.createObjectNode().put("text", "经营周报新版替代内容"), "preference"));
        MemoryFact newer = in(() -> memories.correct(new GroupMemoryContracts.Correct(fixture.group(),
                Long.toString(fixture.manager()), Long.toString(fixture.firstAgent()), original.ref().factId(), original.revision(),
                original.subject(), original.predicate(), revised, .8, .5), "correct-shared"));
        assertNotEquals(original.ref(), newer.ref());
        assertEquals(first, search(call, Map.of("query", "经营周报")), "相同调用保持原冻结材料，不替换为最新事实");
        var forbidden = query("cross-group", new MemoryOwner.Group(new GroupBindingRef(new TenantId(domain.tenant()), fixture.otherGroup())),
                Set.of(MemoryScope.GROUP, MemoryScope.GROUP_MEMBER));
        assertInstanceOf(MemoryRuntime.RetrievalResult.Stopped.class,
                as(() -> runtime.retrieve(new MemoryRuntime.RetrievalCall(call.callContext(), forbidden))));
        var personal = query("personal-in-group", new MemoryOwner.Session(call.run().owner().sessionRef()), Set.of(MemoryScope.USER_AGENT));
        assertInstanceOf(MemoryRuntime.RetrievalResult.Stopped.class,
                as(() -> runtime.retrieve(new MemoryRuntime.RetrievalCall(call.callContext(), personal))));
    }

    @Test
    void currentRequesterAgentAndForgottenFactChangesRevokeFrozenReuseWithoutReselection() {
        MemoryFact fact = create(fixture.group(), fixture.manager(), null, "group_report", "经营周报随当前权限读取");
        var left = context(fixture.secondAgent(), fixture.author());
        var removed = context(fixture.secondAgent(), fixture.manager());
        var forgotten = context(fixture.firstAgent(), fixture.manager());
        for (var call : List.of(left, removed, forgotten)) assertEquals(Set.of(fact.ref().factId()), factIds(search(call, Map.of("query", "经营周报"))));
        in(() -> groups.updateMember(new GroupContracts.UpdateMember(fixture.group(), fixture.author(), "member", "left", 1L,
                "requester-left", 2L), "requester-left"));
        assertEquals(false, search(left, Map.of("query", "经营周报")).get("success"));
        in(() -> groups.updateAgent(new GroupContracts.UpdateAgent(fixture.group(), fixture.secondAgent(), "removed", 1L,
                "service-removed", 2L), "service-removed"));
        assertEquals(false, search(removed, Map.of("query", "经营周报")).get("success"));
        in(() -> memories.forget(new GroupMemoryContracts.Revision(fixture.group(), Long.toString(fixture.manager()),
                Long.toString(fixture.firstAgent()), fact.ref().factId(), fact.revision()), "forget-shared"));
        var rejected = search(forgotten, Map.of("query", "经营周报"));
        assertEquals(false, rejected.get("success"));
        assertEquals("memory_frozen_fact_no_longer_usable", rejected.get("error"));
        assertTrue(factIds(search(context(fixture.firstAgent(), fixture.manager()), Map.of("query", "经营周报"))).isEmpty());
    }

    @Test
    void actualProgramRestoresDefaultAndToolMemoryWithoutSearchingAgainOrReplacingFrozenBody() {
        String frozenText = "经营周报统一每周一上午提交并使用表格";
        MemoryFact original = create(fixture.group(), fixture.manager(), null, "report_format", frozenText);
        MemoryFact own = create(fixture.group(), fixture.author(), fixture.author(), "report_reminder", "经营周报本人下午接收提醒");
        String privateText = "经营周报另一群未公开私有报价";
        create(fixture.otherGroup(), fixture.manager(), null, "foreign_report", privateText);
        String unrelated = "饮品偏好仅为无糖热咖啡";
        create(fixture.group(), fixture.author(), fixture.author(), "drink", unrelated);
        var execution = program(fixture.secondAgent(), fixture.author());
        assertTrue(execution.actor.scopes().contains("groups.memory.read"), "正式群模型上下文需要当前凭据中的群记忆读取权限");
        as(() -> execution.drive(2));
        assertEquals(1, execution.modelCalls());
        assertEquals(1, execution.toolCalls());
        assertEquals(Set.of(original.ref().factId(), own.ref().factId()), factIds(execution.toolResult()));
        String firstModel = modelText(execution.modelRequests().getFirst());
        assertTrue(firstModel.contains(frozenText), "默认群记忆必须进入第一次实际MODEL请求");
        assertTrue(firstModel.contains(own.ref().factId()));
        assertFalse(firstModel.contains(privateText));
        assertFalse(firstModel.contains(unrelated));
        long retrievals = retrievalEvents(execution.identity.runRef());
        assertTrue(retrievals > 0, "实际默认召回和工具检索必须留下持久事件");
        String newText = "经营周报改为周三晚间并使用新格式";
        in(() -> memories.correct(new GroupMemoryContracts.Correct(fixture.group(), Long.toString(fixture.manager()),
                Long.toString(fixture.firstAgent()), original.ref().factId(), original.revision(), original.subject(), original.predicate(),
                MemoryContent.encode(MemoryContent.explicitContent(json.createObjectNode().put("text", newText), "preference")), .8, .5), "update-report-before-recovery"));
        execution.restart();
        as(() -> execution.drive(20));
        assertEquals(RunStatus.COMPLETED, execution.store().load(execution.identity.runRef()).orElseThrow().status());
        assertEquals(1, execution.toolCalls(), "恢复使用已提交工具feedback，不重新调用search_memory");
        assertEquals(2, execution.modelCalls());
        assertEquals(retrievals, retrievalEvents(execution.identity.runRef()), "恢复不追加新的候选或注入选择");
        String nextModel = modelText(execution.nextModelRequest());
        assertTrue(nextModel.contains(original.ref().factId()));
        assertTrue(nextModel.contains(frozenText));
        assertFalse(nextModel.contains(newText));
        assertFalse(nextModel.contains(privateText));
        assertFalse(nextModel.contains(unrelated));
        assertFalse(nextModel.contains("source_event_ids"));
    }

    @Test
    void programRecoveryStopsBeforeNextModelWhenRequesterAgentOrFrozenMemoryIsRevoked() {
        MemoryFact fact = create(fixture.group(), fixture.manager(), null, "report_access", "经营周报只能按当前权限使用");
        var departedRequester = program(fixture.secondAgent(), fixture.author());
        var removedAgent = program(fixture.secondAgent(), fixture.manager());
        var forgottenFact = program(fixture.firstAgent(), fixture.manager());
        for (var execution : List.of(departedRequester, removedAgent, forgottenFact)) {
            as(() -> execution.drive(2));
            assertEquals(1, execution.modelCalls());
            assertEquals(1, execution.toolCalls());
        }
        in(() -> groups.updateMember(new GroupContracts.UpdateMember(fixture.group(), fixture.author(), "member", "left", 1L,
                "program-requester-left", 2L), "program-requester-left"));
        assertRecoveryRejected(departedRequester);
        in(() -> groups.updateAgent(new GroupContracts.UpdateAgent(fixture.group(), fixture.secondAgent(), "removed", 1L,
                "program-agent-removed", 2L), "program-agent-removed"));
        assertRecoveryRejected(removedAgent);
        in(() -> memories.forget(new GroupMemoryContracts.Revision(fixture.group(), Long.toString(fixture.manager()),
                Long.toString(fixture.firstAgent()), fact.ref().factId(), fact.revision()), "program-forget-shared"));
        assertRecoveryRejected(forgottenFact);
    }

    private MemoryInjectionProgramFixture program(long agent, long requester) {
        String event="program-input-"+SnowflakeIds.nextId();
        var source=in(()->messages.record(new GroupMessageContracts.MessageRecord(fixture.group(),event,1L,
                new GroupMessageContracts.EndUserAuthor(requester),List.of(new ChatContent.Text("经营周报"))),event));
        var trusted = context(agent, requester).callContext();
        var original=trusted.runIdentity();
        var identity=new RunIdentity(original.owner(),original.agentId(),original.causationInputId(),original.runRef(),original.trace(),
                original.causationIdentityRef(),original.requester(),Optional.of(new SourceMessageRef(original.owner().sessionRef(),source.sourceMessageId())),original.originRef());
        return as(() -> new MemoryInjectionProgramFixture(dataSource, runtime, memoryStore, groups, identity, trusted.identity(),
                "经营周报", Optional.empty()));
    }

    private void assertRecoveryRejected(MemoryInjectionProgramFixture execution) {
        long retrievals = retrievalEvents(execution.identity.runRef());
        assertTrue(retrievals > 0,"已提交的检索事件必须真实存在");
        execution.restart();
        IllegalStateException rejected = assertThrows(IllegalStateException.class, () -> as(() -> execution.drive(20)));
        assertEquals("memory_model_material_no_longer_usable", rejected.getMessage());
        assertEquals(1, execution.modelCalls(), "拒绝必须发生于下一次MODEL Provider之前");
        assertEquals(1, execution.toolCalls());
        assertEquals(retrievals, retrievalEvents(execution.identity.runRef()));
    }

    private long retrievalEvents(RunRef run) {
        return in(() -> jdbc.queryForObject("""
                SELECT count(*) FROM agent_runtime_session_events
                WHERE tenant_id=? AND run_id=? AND event_type='retrieval.performed'
                """, Long.class, domain.tenant(), run.runId()));
    }

    private String modelText(com.skillforge.runtime.session.AgentExecutionCodec.ModelCallRequest request) {
        return request.request().messages().stream().map(com.skillforge.runtime.api.port.ModelPort.ModelMessage::textContent)
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private MemoryQuery query(String key, MemoryOwner owner, Set<MemoryScope> scopes) {
        return new MemoryQuery(key, owner, Optional.empty(), "经营周报", Optional.empty(), scopes,
                Set.of(new MemoryTypeRef("preference")), MemoryQueryProfile.DEFAULT, Instant.now(),
                MemoryRetrievalPolicy.lexicalV1(), 20, 4000, 16000, true);
    }

    private Map<String,Object> search(PlatformToolExecutionContext call, Map<String,Object> arguments) {
        return as(() -> handler.execute(arguments, call));
    }

    private Set<String> factIds(Map<String,Object> result) {
        assertEquals(true, result.get("success"), () -> "正式检索未成功：" + result.get("error"));
        return ((List<?>) result.get("facts")).stream().map(value -> (Map<?,?>) value)
                .map(value -> (String) value.get("fact_id")).collect(java.util.stream.Collectors.toSet());
    }

    private PlatformToolExecutionContext context(long agent, long requester) {
        String suffix = UUID.randomUUID().toString();
        TenantId tenant = new TenantId(domain.tenant());
        GroupBindingRef group = new GroupBindingRef(tenant, fixture.group());
        var owner = new SessionOwner(new SessionRef(tenant, fixture.session()), SessionSubject.group(group), SessionTarget.group(group));
        InputId input = new InputId("group-recall:" + suffix);
        var identity = new RunIdentity(owner, new AgentId(Long.toString(agent)), input, new RunRef(tenant, "group-recall-run:" + suffix),
                new TraceEnvelope("group-recall-trace:" + suffix, "group-recall-span:" + suffix, input, Optional.empty()),
                new ResultRef("group-recall-identity:" + suffix), new RunRequester.EndUser(new EndUserId(requester)), Optional.empty(),
                new RunOriginRef(tenant, "input:" + input.value()));
        var principal = new PrincipalRef.ServicePrincipal(tenant, domain.principal(), domain.integration(), 1);
        var actor = new VerifiedIdentity(principal, owner, Set.of("sessions.write", "memory:query", "groups.memory.read"), 1, identity.causationIdentityRef());
        return new PlatformToolExecutionContext(new PortCallContext(identity, actor,
                new InvocationControl(Instant.now().plusSeconds(120), () -> false)), "group-recall:" + suffix, "attempt:" + suffix);
    }

    private MemoryFact create(String group, long author, Long subject, String predicate, String text) {
        String source = "source-" + SnowflakeIds.nextId();
        var message = in(() -> messages.record(new GroupMessageContracts.MessageRecord(group, source, 1L,
                new GroupMessageContracts.EndUserAuthor(author), List.of(new ChatContent.Text(text))), source));
        return in(() -> memories.create(new GroupMemoryContracts.Create(group, Long.toString(author), Long.toString(fixture.firstAgent()),
                subject == null ? "GROUP" : "GROUP_MEMBER", subject == null ? null : Long.toString(subject),
                List.of(message.sourceMessageId()), "preference", subject == null ? "group:" + group : "user:" + subject,
                predicate, json.createObjectNode().put("text", text).toString(), .8, .5, false), "create:" + source));
    }

    private long user(String external) { return users.ensureExternal(domain.tenant(), domain.integration(), external, external).getId(); }
    private long agent() {
        var agent = new AgentDO(); agent.setId(SnowflakeIds.nextId()); agent.setTenantId(Long.valueOf(domain.tenant()));
        agent.setAgentKey("group-recall-" + agent.getId()); agent.setName("群召回 Agent"); agent.setSystemPrompt("正式群记忆检索测试");
        agent.setStatus("draft"); agent.setConfig(Map.of()); agents.save(agent); publications.publish(agent); return agent.getId();
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
    private <T> T in(Supplier<T> action) {
        return as(() -> new TransactionTemplate(transactionManager).execute(ignored -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)", String.class, domain.tenant()); return action.get();
        }));
    }
    private <T> T as(Supplier<T> action) {
        String previous = TenantContext.getTenantId(); var principal = ServicePrincipalContext.get();
        TenantContext.setTenantId(domain.tenant());
        ServicePrincipalContext.set(new ServicePrincipalContext.Identity(domain.principal(), domain.tenant(), domain.integration(), 1, SCOPES));
        try { return action.get(); } finally {
            if (previous == null) TenantContext.clear(); else TenantContext.setTenantId(previous);
            if (principal == null) ServicePrincipalContext.clear(); else ServicePrincipalContext.set(principal);
        }
    }
    private record Domain(String tenant, String integration, String scope, String principal) { }
    private record Fixture(String group, String session, String otherGroup, String otherSession,
            long author, long manager, long subject, long firstAgent, long secondAgent) { }
}
