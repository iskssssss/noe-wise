package com.skillforge.institution.integration;

import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.chat.persistence.SessionDO;
import com.skillforge.core.chat.persistence.SessionMapper;
import com.skillforge.core.foundation.persistence.EndUserDO;
import com.skillforge.core.foundation.persistence.EndUserMapper;
import com.skillforge.core.foundation.persistence.PlatformMemberDO;
import com.skillforge.core.foundation.persistence.PlatformMemberMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.runtimeconfig.AgentExecutionConfig;
import com.skillforge.core.foundation.runtimeconfig.ChatBudgetSettings;
import com.skillforge.core.foundation.runtimeconfig.RunSettingsService;
import com.skillforge.core.model.ModelWrite;
import com.skillforge.core.model.PlatformModelCatalogService;

import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.RequestContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.AgentPublicationService;
import com.skillforge.core.agent.AgentTemplateService;
import com.skillforge.core.agent.deployment.AgentDeploymentContracts.LocalBindings;
import com.skillforge.core.agent.mapper.AgentMapper;
import com.skillforge.core.agent.model.*;
import com.skillforge.core.chat.ChatRuntimeGateway;
import com.skillforge.core.chat.dto.request.ChatRequest;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationOrganizationService;
import com.skillforge.core.foundation.integration.IntegrationProvenanceService;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.institution.runtime.*;
import com.skillforge.runtime.adapter.jdbc.model.JdbcModelBindings;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.spi.SessionExecutionAuthorizationRevokedException;
import com.skillforge.runtime.starter.AgentRuntimeModelConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 原Run等待、当前公共发布变更与恢复的真实Gateway/Worker/PostgreSQL组合。 */
@Import(DeterministicModelCatalogConfiguration.class)
class AgentDefinitionRuntimeFreezeIntegrationTest extends AbstractIntegrationTest {
    @DynamicPropertySource static void runtime(DynamicPropertyRegistry properties) {
        properties.add("agent-runtime.workers.auto-startup",()->"true");
    }
    @Autowired org.springframework.context.ApplicationContext application;
    @Autowired com.skillforge.runtime.starter.AgentRuntimeProperties runtimeProperties;
    @Autowired com.skillforge.runtime.starter.ExecutionHostWorker sharedWorker;
    @Autowired com.skillforge.runtime.starter.ExecutionTenantDiscovery tenantDiscovery;
    @Autowired com.skillforge.runtime.starter.ExecutionHostRegistry executionHosts;
    @Autowired TenantMapper tenants;
    @Autowired PlanMapper plans;
    @Autowired AgentMapper agents;
    @Autowired EndUserMapper users;
    @Autowired SessionMapper sessions;
    @Autowired PlatformMemberMapper platformMembers;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper json;
    @Autowired AgentTemplateService templates;
    @Autowired AgentPublicationService publications;
    @Autowired FoundationOrganizationService organization;
    @Autowired PlatformModelCatalogService models;
    @Autowired OperationalSettingsService settings;
    @Autowired RunSettingsService runSettings;
    @Autowired AgentRunBindings bindings;
    @Autowired AgentRuntimeHostProvisioner provisioner;
    @Autowired AgentRuntimeModelConfiguration preparation;
    @Autowired JdbcModelBindings modelBindings;
    @Autowired AgentRuntimeExecutionGateway gateway;
    @Autowired ExecutionStore store;
    @Autowired ExecutionBudgetPort budgets;
    @Autowired IntegrationProvenanceService provenance;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private AgentRuntimeExecutionSources sources;
    // 本例只验收主Run；辅助会话认知不参与本次来源/恢复组合。
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.skillforge.institution.tasks.ConversationTaskCoordinator taskContext;
    @org.junit.jupiter.api.BeforeEach
    void requireStartedWorker() throws Exception {
        requireRunningRuntimeWorkers(application, runtimeProperties);
    }
    @AfterEach void clear() {
        TenantContext.clear();RequestContext.clear();
    }

    /** 原Run真正等待后改发布/模型/运营/预算；恢复仍调用旧Provider，新Run消费新发布。 */
    @Test @Timeout(150)
    void waitingRunRestoresOriginalConfigurationWhileNewRunUsesNewPublication() throws Exception {
        String marker="gi01-freeze-"+SnowflakeIds.nextId();
        try(var oldProvider=new NativeChatOpenAiProvider(json,marker);var newProvider=new NativeChatOpenAiProvider(json,marker+"-new")) {
            oldProvider.enableAgentHitl();oldProvider.start();newProvider.start();
            var f=fixture(oldProvider.endpoint());var old=submit(f,"original");
            until(old,()->waiting(old),"原Run必须通过真实模型ask_user进入等待");
            var waiting=store.load(old).orElseThrow();
            var host=bindings.find(old).orElseThrow();var frozen=runSettings.requireFrozen(f.tenant(),old.runId());
            var originalBudget=budgets.find(old).orElseThrow();var originalBinding=modelBinding(old,frozen);
            assertEquals("1.0.0",host.member().agentDefinitionVersion());
            assertEquals(oldProvider.endpoint(),originalBinding.endpoint());assertEquals(1,oldProvider.requestCount());
            assertMeasurements(f,old,1);
            var draft=models.update(f.model(),modelWrite(models.detail(f.model()).revision(),newProvider.endpoint()),"fixture");
            models.publish(f.model(),draft.revision(),"fixture");
            long settingsActor=settingsActor();
            updateSettings("calls",Map.of("chat_timeout_seconds",12),settingsActor);
            updateSettings("prompts",Map.of("agent_decision_policy","新的平台执行策略"),settingsActor);
            updateSettings("task_budget",Map.of(ChatBudgetSettings.Profile.STANDARD_ROOT.field(ExecutionBudgetPort.Dimension.BUSINESS_MODELS),1024),settingsActor);
            publish(f,"1.1.0","新版源说明",new AgentExecutionConfig(20,10,10,100_000L,600L));
            var restored=provisioner.prepareOperation(f.tenant(),f.agent().getId().toString(),old.runId(),"resume-proof");
            assertEquals(waiting.configuration(),restored.profile());
            assertEquals(frozen,runSettings.requireFrozen(f.tenant(),old.runId()));
            assertEquals(originalBudget,budgets.find(old).orElseThrow());
            assertEquals(originalBinding,modelBinding(old,frozen));assertEquals(host,bindings.find(old).orElseThrow());
            resume(f,waiting,"agent-hitl-approved-"+marker);
            until(old,()->completed(old),"原等待恢复后应由正式Coordinator完成");
            assertEquals(2,oldProvider.requestCount());assertEquals(0,newProvider.requestCount());
            assertEquals(frozen,runSettings.requireFrozen(f.tenant(),old.runId()));
            var fresh=submit(f,"new-publication");until(fresh,()->completed(fresh),"新Run应消费新发布和Provider");
            var freshFrozen=runSettings.requireFrozen(f.tenant(),fresh.runId());var freshBinding=modelBinding(fresh,freshFrozen);
            assertEquals("1.1.0",bindings.find(fresh).orElseThrow().member().agentDefinitionVersion());
            assertEquals(newProvider.endpoint(),freshBinding.endpoint());assertEquals("model-v2",freshBinding.target().effectiveModelId());
            assertNotEquals(originalBinding.encryptedCredential(),freshBinding.encryptedCredential());
            assertNotEquals(frozen.groups(),freshFrozen.groups());
            assertNotEquals(originalBudget.policy().requireEnforced(),budgets.find(fresh).orElseThrow().policy().requireEnforced());
            assertEquals(2,oldProvider.requestCount());assertEquals(1,newProvider.requestCount());
            assertMeasurements(f,old,2);
            assertMeasurements(f,fresh,1);
        }
    }

    /** 当前源下架后原等待不可恢复；失败不采纳输入、不调用Provider、不删除已有计量。 */
    @Test @Timeout(90)
    void currentDeprecationRejectsActualHumanResumeWithoutChangingOriginalFacts() throws Exception {
        String marker="gi01-revoked-"+SnowflakeIds.nextId();
        try(var provider=new NativeChatOpenAiProvider(json,marker)) {
            provider.enableAgentHitl();provider.start();var f=fixture(provider.endpoint());var run=submit(f,"revoked");
            until(run,()->waiting(run),"撤权前应存在真实人类等待");
            var before=store.load(run).orElseThrow();var actions=store.actions(run,0,100);var frozen=runSettings.requireFrozen(f.tenant(),run.runId());
            long measured=count(f,"SELECT count(*) FROM nw_usage_events WHERE run_id=? AND usage_event_kind='measured'",run.runId());
            assertEquals(2,measured);assertMeasurements(f,run,1);update(f.tenant(),"UPDATE nw_agent_templates SET status='deprecated',updated_at=now() WHERE id=?",f.definition());
            assertThrows(SessionExecutionAuthorizationRevokedException.class,()->bindings.authorize(bindings.find(run).orElseThrow()));
            assertThrows(SessionExecutionAuthorizationRevokedException.class,()->provisioner.prepareOperation(f.tenant(),f.agent().getId().toString(),run.runId(),"blocked-resume"));
            var rejected=assertThrows(ApiException.class,()->resume(f,before,"agent-hitl-approved-"+marker));
            assertEquals(403,rejected.getStatus().value());
            assertEquals(before,store.load(run).orElseThrow());assertEquals(actions,store.actions(run,0,100));
            assertEquals(frozen,runSettings.requireFrozen(f.tenant(),run.runId()));
            assertEquals(measured,count(f,"SELECT count(*) FROM nw_usage_events WHERE run_id=? AND usage_event_kind='measured'",run.runId()));
            assertEquals(1,provider.requestCount());
            assertTrue(store.response(run,new InputId("reply-"+run.runId())).isEmpty());
        }
    }

    /** 下架在原派发事务的真实当前授权读前提交，未取得发送权的动作不能触达Provider。 */
    @Test @Timeout(90)
    void deprecationCommittedBeforeDispatchAuthorizationDoesNotCreateAnAttempt() throws Exception {
        String marker="gi01-before-dispatch-"+SnowflakeIds.nextId();
        try(var provider=new NativeChatOpenAiProvider(json,marker)) {
            provider.start();var f=fixture(provider.endpoint());String input="gi01-before-"+SnowflakeIds.nextId();
            var expected=new RunRef(new TenantId(f.tenant()),"run:"+input);
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var ended=new CountDownLatch(1);
            var gate=new AtomicBoolean();var denied=new AtomicBoolean();
            doAnswer(invocation->{
                var run=invocation.<ExecutionStore.Run>getArgument(1);
                boolean target=expected.equals(run.identity().runRef());
                if(target&&gate.compareAndSet(false,true)) {
                    entered.countDown();
                    if(!release.await(20,TimeUnit.SECONDS))throw new IllegalStateException("fixture_dispatch_release_timeout");
                }
                boolean allowed=(Boolean)invocation.callRealMethod();
                if(target&&!allowed)denied.set(true);
                return allowed;
            }).when(sources).allowed(any(org.apache.ibatis.session.SqlSession.class),any(ExecutionStore.Run.class),any(VerifiedIdentity.class),any(ExecutionStore.Prepared.class));
            doAnswer(invocation->{try{return invocation.callRealMethod();}finally{ended.countDown();}}).when(sources).end(eq(expected));
            var run=submitInput(f,input);assertEquals(expected,run);
            try {
                assertTrue(entered.await(20,TimeUnit.SECONDS),()->"必须先到达正式Store当前授权边界；"+executionDiagnostic(run));
                update(f.tenant(),"UPDATE nw_agent_templates SET status='deprecated',updated_at=now() WHERE id=?",f.definition());
            } finally {release.countDown();}
            assertTrue(ended.await(20,TimeUnit.SECONDS),()->"拒绝后正式drive应退出；"+executionDiagnostic(run));
            assertTrue(denied.get(),"真实sources.allowed必须观察已提交的下架并拒绝");
            assertTrue(store.actions(run,0,100).isEmpty());assertEquals(0,provider.requestCount());
            assertEquals(0,count(f,"SELECT count(*) FROM agent_runtime_execution_attempts WHERE run_id=?",run.runId()));
            assertEquals(0,count(f,"SELECT count(*) FROM nw_external_invocations WHERE attempt_id IN (SELECT attempt_id FROM agent_runtime_execution_attempts WHERE run_id=?)",run.runId()));
            assertEquals(0,budgets.snapshot(run).runConsumed().businessModels());
            assertEquals("1.0.0",bindings.find(run).orElseThrow().member().agentDefinitionVersion());
            assertNotNull(provenance.resolveRun(f.tenant(),run.runId()).grantId());
        }
    }

    /** 已发送真实流在下架后截断；未知不补零，先前真实费用、原来源及版本保留且恢复不重放。 */
    @Test @Timeout(120)
    void dispatchedStreamTruncationAfterDeprecationKeepsUnknownAndMeasuredFees() throws Exception {
        String marker="gi01-after-dispatch-"+SnowflakeIds.nextId();
        try(var provider=new NativeChatOpenAiProvider(json,marker)) {
            provider.enableAgentHitl();provider.start();var f=fixture(provider.endpoint());
            var priced=models.update(f.model(),new ModelWrite(models.detail(f.model()).revision(),"已计价冻结模型","LLM",true,
                    "custom","custom-openai-compatible","custom-v1","openai-chat-completions","bearer-api-key",provider.endpoint(),"model-v1","old-synthetic-key",
                    4096,32768,1024,0.2,"DISABLED",30,null,null,java.math.BigDecimal.valueOf(1_000_000L),java.math.BigDecimal.valueOf(2_000_000L),null,"CNY"),"fixture");
            models.publish(f.model(),priced.revision(),"fixture");
            String input="gi01-inflight-"+SnowflakeIds.nextId();var run=submitInput(f,input);
            until(run,()->waiting(run),"首个真实请求应建立人类等待及measured费用");
            var waiting=store.load(run).orElseThrow();var host=bindings.find(run).orElseThrow();
            var frozen=runSettings.requireFrozen(f.tenant(),run.runId());var source=provenance.resolveRun(f.tenant(),run.runId());
            var measured=measuredFacts(f,run);assertEquals(2,measured.size());assertMeasurements(f,run,1);
            assertEquals(1,count(f,"SELECT count(*) FROM nw_usage_events WHERE run_id=? AND usage_event_kind='measured' AND call_point='agent-runtime.model' AND amount_status='priced' AND total_amount>0",run.runId()));
            var ended=new AtomicInteger();
            doAnswer(invocation->{try{return invocation.callRealMethod();}finally{ended.incrementAndGet();}}).when(sources).end(eq(run));
            provider.holdStreamCompletion();resume(f,waiting,"agent-hitl-approved-"+marker);
            until(run,()->provider.heldStreamStage()==1,"第二个真实请求必须已经flush首段且未发送finish/usage/DONE");
            assertEquals(2,provider.requestCount());
            var dispatched=store.load(run).orElseThrow();assertEquals(ExecutionStore.Phase.AWAITING_RESULT,dispatched.phase());
            var action=store.action(dispatched.currentAction().orElseThrow()).orElseThrow();
            var attempt=store.attempt(run,action.currentAttempt()).orElseThrow();assertTrue(attempt.resultKind().isEmpty());
            update(f.tenant(),"UPDATE nw_agent_templates SET status='deprecated',updated_at=now() WHERE id=?",f.definition());
            assertThrows(SessionExecutionAuthorizationRevokedException.class,()->bindings.authorize(host));
            // 读取/治理身份仍可验证；真正受限执行由原 Run 链门禁拒绝。
            assertNotNull(gateway.verify(dispatched,new InputId("current-identity-"+input)));
            provider.abortHeldStream();
            until(run,()->store.load(run).filter(value->value.phase()==ExecutionStore.Phase.UNCERTAIN).isPresent(),"真实无finish流必须由正式适配器保存UNKNOWN");
            until(run,()->ended.get()>0,"原在途drive应完成真实结果收口并退出");
            var unknown=store.load(run).orElseThrow();var unknownAttempt=store.attempt(run,attempt.id()).orElseThrow();
            assertEquals(RunStatus.SUSPENDED,unknown.status());assertTrue(unknown.lease().isEmpty());assertTrue(unknown.nextDueAt().isEmpty());
            assertEquals(Optional.of(ExecutionStore.ResultKind.UNKNOWN),unknownAttempt.resultKind());
            assertEquals(attempt.request(),unknownAttempt.request());assertEquals(attempt.action(),unknownAttempt.action());assertEquals(attempt.fence(),unknownAttempt.fence());
            assertEquals(1,count(f,"SELECT count(*) FROM nw_external_invocations WHERE attempt_id=? AND status='UNRESOLVED'",attempt.id()));
            assertEquals(1,count(f,"SELECT count(*) FROM nw_usage_events WHERE attempt_id=? AND usage_event_kind='usage_report' AND usage_source='UNKNOWN' AND prompt_tokens IS NULL AND completion_tokens IS NULL AND total_amount IS NULL AND total_amount_cents IS NULL AND amount_status='unresolved'",attempt.id()));
            assertEquals(0,count(f,"SELECT count(*) FROM nw_usage_events WHERE attempt_id=? AND usage_event_kind='measured'",attempt.id()));
            assertFalse(store.usage(run).complete());assertTrue(budgets.snapshot(run).runReserved().inputTokens()>0);
            assertEquals(measured,measuredFacts(f,run));assertEquals(source,provenance.resolveRun(f.tenant(),run.runId()));
            assertEquals(host,bindings.find(run).orElseThrow());assertEquals(frozen,runSettings.requireFrozen(f.tenant(),run.runId()));
            assertEquals(-1,provider.heldStreamStage());assertFalse(provider.heldStreamCompleted());
            var actions=store.actions(run,0,100);var budget=budgets.snapshot(run);
            publish(f,"1.1.0","历史源说明",host.member().executionConfig());
            assertEquals(unknown,store.load(run).orElseThrow());assertFalse(store.due(run.tenantId(),100).contains(run));
            assertEquals(run,submitInput(f,input),"显式重投原输入只能返回原Run");
            assertEquals(unknown,store.load(run).orElseThrow());assertEquals(actions,store.actions(run,0,100));
            assertEquals(unknownAttempt,store.attempt(run,attempt.id()).orElseThrow());assertEquals(budget,budgets.snapshot(run));
            assertEquals(measured,measuredFacts(f,run));assertEquals(source,provenance.resolveRun(f.tenant(),run.runId()));
            assertEquals("1.0.0",bindings.find(run).orElseThrow().member().agentDefinitionVersion());
            assertEquals(List.of("1.0.0","1.1.0"),templates.publications(f.definition()).stream().map(AgentTemplateDO::getVersion).sorted().toList());
            assertEquals(2,provider.requestCount(),"重新发布和显式重投均不得重放未知请求");
        }
    }

    /** 建独立真实机构与空能力部署；平台生命周期API/CAS由管理HTTP验收覆盖。 */
    private Fixture fixture(String endpoint) throws Exception {
        String suffix=Long.toString(SnowflakeIds.nextId());String tenant=createTenant(tenants,plans,"gi01-freeze-"+suffix);
        TenantContext.setTenantId(tenant);RequestContext.setTraceId("gi01-freeze-"+suffix);
        long actor=createTestTenantAdministrator(tenant,"freeze-admin-"+suffix,"synthetic-password");
        String model=PlatformModelFixtures.selectLlm(models,tenant,endpoint,"model-v1","old-synthetic-key","custom","custom-openai-compatible","custom-v1");
        provisioner.provision(tenant);
        long definition=SnowflakeIds.nextId(),grant=SnowflakeIds.nextId(),deployment=SnowflakeIds.nextId();
        var source=new AgentTemplateDO();source.setId(definition);source.setTemplateKey("freeze-"+suffix);source.setName("冻结源");
        source.setVersion("1.0.0");source.setDistributionPolicy(AgentDistributionPolicy.AUTHORIZED_DEPLOY);source.setOwnerKind("platform");source.setDraftRevision(1L);
        source.setDefinition(definition(model,"历史源说明",new AgentExecutionConfig(8,6,6,50_000L,300L)));source.setPriceType("free");source.setPriceCents(0);
        source.setInstallCount(0);source.setTags(List.of());source.setStatus("published");source.setPublishedAt(Instant.now());source.setCreatedAt(Instant.now());source.setUpdatedAt(Instant.now());
        templates.save(source);templates.appendPublication(source);
        var agent=new AgentDO();agent.setId(SnowflakeIds.nextId());agent.setAgentKey("freeze-"+suffix);agent.setTenantId(Long.parseLong(tenant));agent.setName("冻结验证助手");
        agent.setSystemPrompt("历史源说明");agent.setRoleDescription("冻结验证");agent.setConfig(Map.of());agent.setTemplateId(definition);agent.setTemplateVersion("1.0.0");
        agent.setStatus("draft");agent.setCreatedAt(Instant.now());agent.setUpdatedAt(Instant.now());agents.insert(agent);
        withAuthenticatedMember(actor,()->{organization.registerResource("agent",agent.getId().toString());return publications.publish(agent);});
        update(tenant,"INSERT INTO nw_agent_definition_grants(grant_id,definition_id,target_tenant_id,actions,status,revision,actor_ref,request_key,fingerprint,created_at) VALUES(?,?,?,'deploy,execute','active',1,?,?,?,now())",grant,definition,Long.parseLong(tenant),"member:"+actor,"grant-"+grant,"a".repeat(64));
        update(tenant,"INSERT INTO nw_agent_definition_grant_revisions SELECT g.*,'fixture','create',request_key FROM nw_agent_definition_grants g WHERE grant_id=?",grant);
        String chain=json.writeValueAsString(new com.skillforge.core.agent.deployment.AgentDeploymentContracts.GrantChain(definition,"platform",null,
                List.of(new com.skillforge.core.agent.deployment.AgentDeploymentContracts.GrantHop(grant,1,"platform",null,Long.parseLong(tenant),null,null,null,null))));
        String local=json.writeValueAsString(new LocalBindings(List.of(),List.of(),Map.of()));
        update(tenant,"INSERT INTO nw_agent_deployments(deployment_id,tenant_id,agent_id,definition_id,grant_id,grant_revision,external_agent_ref,fingerprint,bindings,revision,created_at,grant_chain) VALUES(?,?,?,?,?,1,?,?,?::jsonb,1,now(),?::jsonb)",deployment,Long.parseLong(tenant),agent.getId(),definition,grant,"agent-"+agent.getId(),"b".repeat(64),local,chain);
        update(tenant,"INSERT INTO nw_agent_deployment_revisions(deployment_id,revision,tenant_id,grant_id,grant_revision,bindings,actor_ref,command_ref,grant_chain) VALUES(?,1,?,?,1,?::jsonb,?,?,?::jsonb)",deployment,Long.parseLong(tenant),grant,local,"member:"+actor,"create-"+deployment,chain);
        var user=new EndUserDO();user.setId(SnowflakeIds.nextId());user.setTenantId(Long.parseLong(tenant));user.setDisplayName("冻结验证用户");user.setStatus("active");user.setFirstSeenAt(Instant.now());user.setLastActiveAt(Instant.now());user.setCreatedAt(Instant.now());users.insert(user);
        var session=new SessionDO();session.setId(SnowflakeIds.nextId());session.setSessionId("freeze-session-"+suffix);session.setTenantId(Long.parseLong(tenant));session.setAgentId(agent.getId());session.setEndUserId(user.getId());
        session.setOriginKind("native");session.setActorRef("member:"+actor);session.setResponsibilityRef("tenant:"+tenant);session.setMetadata(Map.of());session.setMessageCount(0);session.setLastEventId(0L);session.setCreatedAt(Instant.now());session.setUpdatedAt(Instant.now());sessions.insert(session);
        withAuthenticatedMember(actor,()->{organization.registerSessionOwnership(session.getSessionId());return null;});
        return new Fixture(tenant,actor,model,definition,agent, com.skillforge.institution.TestSessions.view(session));
    }


    /** 每次输入经同一正式Gateway受理，由唯一Worker/Coordinator实际推进。 */
    private RunRef submit(Fixture f,String label) {
        String input="gi01-"+label+"-"+SnowflakeIds.nextId();
        return submitInput(f,input);
    }
    private RunRef submitInput(Fixture f,String input) {
        var request=new ChatRequest("请先确认后继续",f.session().getSessionId(),f.agent().getId().toString(),null,null,null,null,null,input,Map.of(),Map.of("input_id",input,"delegation_requester_id",Long.toString(f.actor())));
        var accepted=withAuthenticatedMember(f.actor(),()->gateway.submit(new ChatRuntimeGateway.Submission(request,f.session(),f.agent().getId().toString(),f.session().getEndUserId(),null)));
        return new RunRef(new TenantId(f.tenant()),accepted.executionId());
    }
    /** 当前身份验证和精确等待响应都沿生产入口；不从旧验证结果绕过撤权。 */
    private void resume(Fixture f,ExecutionStore.Run run,String text) {
        var input=new InputId("reply-"+run.identity().runRef().runId());
        withAuthenticatedMember(f.actor(),()->gateway.resumeHuman(run,gateway.verify(run,input),input,run.waiting().orElseThrow(),run.revision(),text));
    }
    private boolean waiting(RunRef run) {return store.load(run).filter(value->value.phase()==ExecutionStore.Phase.WAITING&&value.waiting().isPresent()).isPresent();}
    private boolean completed(RunRef run) {return store.load(run).filter(value->value.status()==RunStatus.COMPLETED).isPresent();}
    private void until(RunRef run,BooleanSupplier condition,String message) {
        try {await(message).atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(100)).until(()->{
            if(condition.getAsBoolean())return true;
            var current=store.load(run);
            if(current.filter(value->value.phase()==ExecutionStore.Phase.FINISHED&&value.status()!=RunStatus.COMPLETED).isPresent()) {
                String diagnostic=executionDiagnostic(run);
                System.err.println("GI01_EXECUTION_DIAGNOSTIC "+diagnostic);
                throw new AssertionError(message+"前Run已进入失败终态；"+diagnostic);
            }
            return false;
        });}
        catch(org.awaitility.core.ConditionTimeoutException failure) {
            // 在测试专用容器被afterTestClass清理前保留事实，原超时与业务断言保持不变。
            String diagnostic=executionDiagnostic(run);
            System.err.println("GI01_EXECUTION_DIAGNOSTIC "+diagnostic);
            failure.addSuppressed(new AssertionError(diagnostic));
            throw failure;
        }
    }
    /** 只读执行身份和状态；不输出请求正文、模型绑定或凭据。 */
    private String executionDiagnostic(RunRef run) {
        var details=new ArrayList<String>();
        details.add("run="+run.runId()+" tenant="+run.tenantId().value());
        details.add("workerRunning="+sharedWorker.isRunning()+" workerClosed="+sharedWorker.isClosed()
                +" autoStartup="+runtimeProperties.getWorkers().isAutoStartup());
        try {details.add("discoveredTenants="+tenantDiscovery.page("",100));}
        catch(RuntimeException failure) {details.add("tenantDiscoveryFailure="+failure);}
        try {
            var current=store.load(run);
            if(current.isEmpty())details.add("runMissing=true");
            else {
                var value=current.orElseThrow();
                details.add("status="+value.status()+" phase="+value.phase()+" revision="+value.revision()+" fence="+value.fence()
                        +" lease="+value.lease()+" due="+value.nextDueAt()+" currentAction="+value.currentAction()+" waiting="+value.waiting());
                details.add("context="+value.progress().context()+" contextSchema="+store.material(run.tenantId(),value.progress().context())
                        .map(material->material.content().schemaId()));
                var terminal=store.event(run,com.skillforge.runtime.session.AgentExecutionProgram.finishedEventId(run));
                if(terminal.isPresent()) {
                    var saved=terminal.orElseThrow();var content=json.readTree(saved.event().data().canonicalJson());
                    details.add("terminalEvent="+saved.event().type()+" sequence="+saved.sequence()+" revision="+saved.revision()
                            +" terminalStatus="+content.path("status").asText()+" terminalReason="+content.path("reason").asText());
                }
                details.add("dueRuns="+store.due(run.tenantId(),20));
                try {details.add("selectedHost="+executionHosts.host(value).id());}
                catch(RuntimeException failure) {details.add("hostSelectionFailure="+failure);}
                for(var action:store.actions(run,0,20)) {
                    details.add("action="+action.reference()+" kind="+action.kind()+" target="+action.target()+" status="+action.status());
                    store.attempt(run,action.currentAttempt()).ifPresent(attempt->details.add("attempt="+attempt.id()
                            +" fence="+attempt.fence()+" resultKind="+attempt.resultKind()+" adopted="+attempt.adopted()));
                }
            }
        } catch(Exception failure) {details.add("executionReadFailure="+failure);}
        Thread.getAllStackTraces().entrySet().stream().filter(entry->entry.getKey().getName().startsWith("runtime-execution-"))
                .sorted(java.util.Comparator.comparing(entry->entry.getKey().getName())).forEach(entry->details.add(
                        "thread="+entry.getKey().getName()+" state="+entry.getKey().getState()+" stack="
                                +Arrays.stream(entry.getValue()).limit(12).toList()));
        return String.join(" | ",details);
    }
    private JdbcModelBindings.Binding modelBinding(RunRef run,RunSettingsService.FrozenConfig frozen) {
        var profile=preparation.preparationProfile(run.tenantId(),new ResultRef(frozen.preparationProfile())).orElseThrow();
        return modelBindings.find(profile.binding()).orElseThrow();
    }
    private void publish(Fixture f,String version,String prompt,AgentExecutionConfig budget) {
        var source=templates.getById(f.definition());source.setVersion(version);source.setStatus("published");source.setDefinition(definition(f.model(),prompt,budget));source.setPublishedAt(Instant.now());source.setUpdatedAt(Instant.now());
        templates.updateById(source);templates.appendPublication(source);
    }
    private AgentTemplateDefinition definition(String model,String prompt,AgentExecutionConfig budget) {return new AgentTemplateDefinition(prompt,"冻结验证",model,budget,List.of(),List.of(),List.of());}
    private long settingsActor() {
        return platformMembers.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<PlatformMemberDO>().eq("status","active"))
                .stream().findFirst().map(PlatformMemberDO::getId).orElseGet(()->ensureTestPlatformAdministrator("gi01-runtime-platform","synthetic-password"));
    }
    private void updateSettings(String group,Map<String,Object> changed,long actor) {
        var current=settings.read(group);var values=new LinkedHashMap<String,Object>(current.values());values.putAll(changed);
        settings.update(group,current.revision(),values,actor);
    }
    /** fixture写入显式绑定同一Tenant事务，兼容受限RLS测试角色。 */
    private void update(String tenant,String sql,Object...args) {new TransactionTemplate(transactions).executeWithoutResult(status->{jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant);jdbc.update(sql,args);});}
    private long count(Fixture f,String sql,Object...args) {return queryCount(jdbc,f.tenant(),sql,args);}
    /** 本场景仅发生主业务模型调用；恢复不得引入额外调用或改写真实费用。 */
    private void assertMeasurements(Fixture f,RunRef run,long businessCalls) {
        assertEquals(businessCalls,count(f,"SELECT count(*) FROM nw_usage_events WHERE run_id=? AND usage_event_kind='measured' AND call_point='agent-runtime.model'",run.runId()));
        assertEquals(businessCalls,count(f,"SELECT count(*) FROM nw_usage_events WHERE run_id=? AND usage_event_kind='measured'",run.runId()));
    }
    private List<String> measuredFacts(Fixture f,RunRef run) {
        return queryRows(jdbc,f.tenant(),"SELECT row_to_json(usage)::text AS fact FROM nw_usage_events usage WHERE run_id=? AND usage_event_kind='measured' ORDER BY id",run.runId())
                .stream().map(row->row.get("fact").toString()).toList();
    }
    private ModelWrite modelWrite(long revision,String endpoint) {return new ModelWrite(revision,"冻结测试模型","LLM",true,"custom","custom-openai-compatible","custom-v1","openai-chat-completions","bearer-api-key",endpoint,"model-v2","new-synthetic-key",4096,32768,1024,0.2,"DISABLED",30,null,null,java.math.BigDecimal.valueOf(100L),java.math.BigDecimal.valueOf(200L),null,"CNY");}
    private record Fixture(String tenant,long actor,String model,long definition,AgentDO agent,com.skillforge.core.chat.session.SessionView session) {}
}
