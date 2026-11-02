package com.skillforge.institution.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.common.context.RequestContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SkillForgeProperties;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.institution.security.HmacSignatureVerifier;
import com.skillforge.testing.groupmessages.GroupMessageModelCatalogConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** GI-03/GI-04真实HTTP群来源、前台、公开历史及独立发送进程的持久控制与恢复。 */
@Import(GroupMessageModelCatalogConfiguration.class)
class GroupMessageHttpIntegrationTest extends AbstractIntegrationTest {
    private static final String GROUPS="/api/v1/open-platform/groups";
    private static final String USERNAME="group-message-admin",PASSWORD="GroupMessageFixture1234";
    private static final List<String> GROUP_SCOPES=List.of("groups.write","groups.members.write","groups.agents.write",
            "groups.messages.write","groups.triggers.write","groups.triggers.read","groups.runs.respond","groups.runs.read","groups.runs.control",
            "groups.history.read","groups.controls.write","groups.controls.read","groups.controls.reconcile",
            "groups.controls.release","groups.controls.ack","groups.senders.manage");
    private static final Pattern LISTENING=Pattern.compile("Tomcat started on port (\\d+)");
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformModelCatalogService models;
    @Autowired private SkillForgeProperties properties;
    @Autowired private org.springframework.core.env.Environment environment;
    @Autowired private org.springframework.context.ApplicationContext parentContext;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<Host> ownedHosts=new ArrayList<>();
    private final List<Map<String,Object>> httpErrors=new CopyOnWriteArrayList<>();
    private Host platformHost,institutionHost;
    private String platformToken;
    private Path evidence;
    private Path modelCatalogFixtureJar;
    private String tenantDatabaseUser,platformDatabaseUser,hostDatabasePassword;

    /** 父Spring不自动pump；测试开始还会正式关闭调度与Worker，禁止wake路径领取执行。 */
    @DynamicPropertySource static void workers(DynamicPropertyRegistry registry) {
        registry.add("agent-runtime.workers.auto-startup",()->"false");
    }

    /** 仅显式浏览器验收启用；实际回复必须来自Demo，不由测试代提交。 */
    @Test @Timeout(900)
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="GI12_GROUP_BROWSER_HOLD",matches="1")
    void holdGroupWaitingRunForExplicitBrowserReply() throws Exception {
        String marker="gi12-group-browser-"+SnowflakeIds.nextId();
        String configured=System.getenv("GI12_GROUP_BROWSER_EVIDENCE_DIR");
        evidence=configured==null||configured.isBlank()?repositoryRoot().resolve(".runtime-logs/gi12-group-browser"):Path.of(configured).toAbsolutePath();
        Files.createDirectories(evidence);Throwable failure=null;
        try(var provider=new NativeChatOpenAiProvider(json,marker)) {
            try {
                stopParentRuntime();provider.enableAgentHitl();provider.start();prepareDatabaseRoles();startHosts("browser");
                String approval="agent-hitl-approved-"+marker;
                var fixture=controlFixture(marker,"browser",provider,approval);
                var credential=fixture.credential();var actor=fixture.actor();
                JsonNode group=machine(credential,"POST",GROUPS+"/ensure",
                        ensure(marker,"browser-life",fixture.scope().path("business_scope_id").asText()),marker+":group",200);
                String groupId=group.path("group_binding_id").asText();
                machine(credential,"POST",GROUPS+"/members/update",
                        member(groupId,actor.user(),"manager","active",0,"browser-join",1),marker+":join",200);
                machine(credential,"POST",GROUPS+"/agents/update",
                        agent(groupId,actor.agent(),"active",0,"browser-agent",1),marker+":agent",200);
                JsonNode source=machine(credential,"POST",GROUPS+"/messages",
                        message(groupId,actor.user(),"browser-source",1,"请等待浏览器页面中的人工确认"),marker+":source",200);
                JsonNode receipt=machine(credential,"POST",GROUPS+"/triggers",
                        trigger(groupId,source,actor.user(),actor.agent()),marker+":trigger",202);
                String run=awaitAccepted(credential,groupId,receipt).path("run_id").asText();
                awaitHumanWait(fixture.owner().id(),run);humanCard(runDetail(credential,groupId,run));
                assertThat(provider.requestCount()).isEqualTo(1);
                var secrets=new GroupBrowserPrivate(credential.path("api_key").asText(),
                        credential.path("signing_secret").asText(),approval);
                Files.writeString(evidence.resolve("private.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(secrets),StandardCharsets.UTF_8);
                var ready=new GroupBrowserReady("WAITING","http://127.0.0.1:"+institutionHost.port,
                        fixture.owner().id(),credential.path("principal").path("integration_id").asText(),groupId,
                        group.path("session_id").asText(),actor.user().path("end_user_id").asText(),actor.agent(),run,
                        source.path("source_message_id").asText());
                Files.writeString(evidence.resolve("ready.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(ready),StandardCharsets.UTF_8);
                Instant deadline=Instant.now().plus(Duration.ofMinutes(10));
                boolean completed=false;
                while(Instant.now().isBefore(deadline)) {
                    assertThat(institutionHost.process.isAlive()).as("浏览器验收期间机构宿主须存活").isTrue();
                    if(count("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND status='COMPLETED' AND phase='FINISHED' AND lease_claim_id IS NULL",fixture.owner().id(),run)==1) {
                        completed=true;break;
                    }
                    assertThat(count("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND status IN ('FAILED','CANCELLED')",fixture.owner().id(),run)).isZero();
                    Thread.sleep(1000);
                }
                assertThat(completed).as("必须由真实浏览器回复推动原Run完成").isTrue();
                assertThat(provider.requestCount()).isEqualTo(2);
                JsonNode output=formalFinal(credential,groupId,actor.user(),run);
                var result=new GroupBrowserCompleted("COMPLETED",run,provider.requestCount(),output.path("message_id").asText(),output.path("message_version").asLong());
                Files.writeString(evidence.resolve("completed.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(result),StandardCharsets.UTF_8);
                boolean finished=false;
                Path finish=evidence.resolve("finish.json");
                while(Instant.now().isBefore(deadline)) {
                    if(Files.isRegularFile(finish)&&run.equals(json.readTree(Files.readString(finish)).path("run_id").asText())) {finished=true;break;}
                    Thread.sleep(1000);
                }
                assertThat(finished).as("浏览器核验须提交匹配原Run的finish.json后方可清理").isTrue();
            } catch(Exception|Error problem) {failure=problem;throw problem;}
            finally {try{stopHosts();}catch(Exception|Error cleanup){if(failure!=null)failure.addSuppressed(cleanup);else throw cleanup;}}
        } finally {RequestContext.clear();TenantContext.clear();}
    }

    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    private record GroupBrowserReady(String state,String origin,String tenantId,String integrationId,String groupBindingId,
            String sessionId,String endUserId,String agentId,String runId,String sourceMessageId) { }
    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    private record GroupBrowserPrivate(String apiKey,String signingSecret,String expectedApproval) {
        @Override public String toString(){return "GroupBrowserPrivate[synthetic credential]";}
    }
    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    private record GroupBrowserCompleted(String state,String runId,int providerCalls,String messageId,long messageVersion) { }

    /** 独立 fixture：人工回复身份含合成私有值，验证真实群 HTTP/PG/SSE 披露边界。 */
    @Test @Timeout(600)
    void sensitiveReplyIdentityMustNotBeDisclosedByGroupEvents() throws Exception {
        String marker="group-sensitive-identity-"+SnowflakeIds.nextId();
        String sensitive="password=syntheticsecret";
        evidence=repositoryRoot().resolve("backend-java/skillforge-institution-service/build/reports/group-sensitive-identity/"+marker);
        Files.createDirectories(evidence);Throwable failure=null;
        try(var provider=new NativeChatOpenAiProvider(json,marker)) {
            try {
                stopParentRuntime();provider.enableAgentHitl();provider.start();prepareDatabaseRoles();startHosts("sensitive");
                String approval="agent-hitl-approved-"+marker;
                var fixture=controlFixture(marker,"sensitive",provider,approval);
                var credential=fixture.credential();var actor=fixture.actor();
                JsonNode group=machine(credential,"POST",GROUPS+"/ensure",
                        ensure(marker,"sensitive-life",fixture.scope().path("business_scope_id").asText()),marker+":group",200);
                String groupId=group.path("group_binding_id").asText();
                machine(credential,"POST",GROUPS+"/members/update",member(groupId,actor.user(),"manager","active",0,"join",1),marker+":join",200);
                machine(credential,"POST",GROUPS+"/agents/update",agent(groupId,actor.agent(),"active",0,"agent",1),marker+":agent",200);
                JsonNode source=machine(credential,"POST",GROUPS+"/messages",message(groupId,actor.user(),"source",1,"请等待人工确认"),marker+":source",200);
                JsonNode receipt=machine(credential,"POST",GROUPS+"/triggers",trigger(groupId,source,actor.user(),actor.agent()),marker+":trigger",202);
                String run=awaitAccepted(credential,groupId,receipt).path("run_id").asText();
                awaitHumanWait(fixture.owner().id(),run);JsonNode card=humanCard(runDetail(credential,groupId,run));
                String query=historyQuery(groupId,actor.user());
                JsonNode before=machine(credential,"GET",GROUPS+"/events"+query+"&page_size=200",null,null,200);
                String cursor=before.path("next_cursor").asText();
                try(var stream=new GroupStream(signedStream(credential,GROUPS+"/stream"+query+"&cursor="+url(cursor)))) {
                    machine(credential,"POST",GROUPS+"/runs/respond",response(groupId,run,actor.user(),card,approval,sensitive),sensitive,200);
                    awaitCompleted(fixture.owner().id(),run);
                    await().atMost(Duration.ofSeconds(10)).untilAsserted(()->assertThat(stream.lines()).contains("GROUP_EVENT_ID_UNDISCLOSABLE"));
                    await().atMost(Duration.ofSeconds(10)).untilAsserted(()->assertThat(stream.ended()).isTrue());
                    String eventId=jdbc.queryForObject("SELECT event_id FROM nw_chat_session_outbox WHERE tenant_id=? AND session_id=? AND input_id=? AND event_type='message.delta' ORDER BY session_sequence LIMIT 1",String.class,
                            Long.parseLong(fixture.owner().id()),group.path("session_id").asText(),sensitive);
                    assertThat(eventId).isNotBlank();
                    var events=signed(credential,"GET",GROUPS+"/events"+query+"&cursor="+url(cursor)+"&page_size=200",null,null);
                    assertThat(events.status()).isEqualTo(422);assertThat(events.body().path("code").asText()).isEqualTo("GROUP_EVENT_ID_UNDISCLOSABLE");
                    assertThat(events.body().path("retryable").asBoolean()).isFalse();
                    assertThat(events.body().toString()).doesNotContain(sensitive);
                    assertThat(stream.lines()).contains("retryable\":false").doesNotContain(sensitive,eventId);
                    assertThat(signedStreamStatus(credential,GROUPS+"/stream"+query+"&cursor="+url(cursor))).isEqualTo(204);
                    assertThat(jdbc.queryForObject("SELECT input_id FROM nw_chat_session_outbox WHERE tenant_id=? AND session_id=? AND event_id=?",String.class,
                            Long.parseLong(fixture.owner().id()),group.path("session_id").asText(),eventId)).isEqualTo(sensitive);
                    var historyResult=signed(credential,"GET",GROUPS+"/history"+query+"&page_size=200",null,null);
                    assertThat(historyResult.status()).as("群历史不得披露身份").isEqualTo(422);
                    assertThat(historyResult.body().path("code").asText()).isEqualTo("GROUP_EVENT_ID_UNDISCLOSABLE");
                    assertThat(historyResult.body().path("retryable").asBoolean()).isFalse();
                    assertThat(historyResult.body().toString()).doesNotContain(sensitive);
                }
            } catch(Exception|Error problem) {failure=problem;throw problem;}
            finally {try{stopHosts();}catch(Exception|Error cleanup){if(failure!=null)failure.addSuppressed(cleanup);else throw cleanup;}}
        } finally {RequestContext.clear();TenantContext.clear();}
    }

    /** 每个Provider只服务一个测试Agent，历史中的其他成员正文不会改变其控制脚本。 */
    @Test @Timeout(600)
    void recordsWithoutRunsAndSerializesGroupTriggersAcrossWaitingCrashAndUnknownRecovery() throws Exception {
        String marker="group-messages-"+SnowflakeIds.nextId();
        evidence=repositoryRoot().resolve("backend-java/skillforge-institution-service/build/reports/group-messages/"+marker);
        Files.createDirectories(evidence);Throwable failure=null;
        try(var firstProvider=new NativeChatOpenAiProvider(json,marker+"-first");
            var secondProvider=new NativeChatOpenAiProvider(json,marker+"-second");
            var uncertainProvider=new NativeChatOpenAiProvider(json,marker+"-uncertain");
            var personalProvider=new NativeChatOpenAiProvider(json,marker+"-personal")) {
            try {
            stopParentRuntime();
            firstProvider.enableAgentHitl();secondProvider.enableAgentHitl();uncertainProvider.holdStreamCompletion();
            firstProvider.start();secondProvider.start();uncertainProvider.start();personalProvider.start();
            prepareDatabaseRoles();startHosts("first");
            Account owner=account(marker,"owner"),foreign=account(marker,"foreign");
            String integration=association(owner,marker,"first"),otherIntegration=association(owner,marker,"second");
            JsonNode credential=credential(owner,integration,GROUP_SCOPES,marker+":credential");
            JsonNode setupCredential=credential(owner,integration,List.of("business.scopes.write","end_users.write"),marker+":setup-credential");
            JsonNode personalCredential=credential(owner,integration,List.of("sessions.write"),marker+":personal-credential");
            JsonNode other=credential(owner,otherIntegration,GROUP_SCOPES,marker+":other-credential");
            JsonNode foreignCredential=credential(foreign,association(foreign,marker,"foreign"),GROUP_SCOPES,marker+":foreign-credential");
            JsonNode scope=scope(setupCredential,"clinic",marker+":scope"),userA=user(setupCredential,"author-a"),userB=user(setupCredential,"author-b");
            String firstAgent=nativeAgent(owner,model(owner,firstProvider.endpoint(),marker+"-first"),marker+":first-agent");
            JsonNode source=sharedSource(model(owner,secondProvider.endpoint(),marker+"-second"),marker);
            JsonNode grant=platform("POST","/api/v1/platform/agent-grants",Map.of("definition_id",source.path("definition_id").asText(),
                    "target_tenant_id",owner.id(),"actions",List.of("deploy","execute"),"allow_regrant",false,"remaining_depth",0),marker+":grant",200);
            String secondAgent=managedAgent(owner,source,grant,null,marker+":second-agent").path("agent_id").asText();
            String uncertainAgent=nativeAgent(owner,model(owner,uncertainProvider.endpoint(),marker+"-uncertain"),marker+":uncertain-agent");
            String personalAgent=nativeAgent(owner,model(owner,personalProvider.endpoint(),marker+"-personal"),marker+":personal-agent");
            JsonNode group=machine(credential,"POST",GROUPS+"/ensure",ensure(marker,"group-life",scope.path("business_scope_id").asText()),marker+":group",200);
            String groupId=group.path("group_binding_id").asText();
            machine(credential,"POST",GROUPS+"/members/update",member(groupId,userA,"member","active",0,"join-a",1),marker+":join-a",200);
            machine(credential,"POST",GROUPS+"/members/update",member(groupId,userB,"member","active",0,"join-b",1),marker+":join-b",200);
            machine(credential,"POST",GROUPS+"/agents/update",agent(groupId,firstAgent,"active",0,"add-first",1),marker+":add-first",200);
            machine(credential,"POST",GROUPS+"/agents/update",agent(groupId,secondAgent,"active",0,"add-second",1),marker+":add-second",200);

            var noRunBefore=executionCounts();
            Map<String,Object> recordA=message(groupId,userA,"message-a",1,"请先等待本人的准确确认。");
            Map<String,Object> recordB=message(groupId,userB,"message-b",1,"由另一位群助手处理本条。");
            JsonNode messageA=machine(credential,"POST",GROUPS+"/messages",recordA,marker+":message-a",200);
            JsonNode messageB=machine(credential,"POST",GROUPS+"/messages",recordB,marker+":message-b",200);
            assertThat(messageA.size()).isEqualTo(6);
            assertThat(messageA.path("session_id")).isEqualTo(group.path("session_id"));
            assertThat(messageB.path("session_id")).isEqualTo(group.path("session_id"));
            assertThat(messageB.path("source_sequence").asLong()).isGreaterThan(messageA.path("source_sequence").asLong());
            assertThat(executionCounts()).isEqualTo(noRunBefore);
            assertThat(firstProvider.requestCount()+secondProvider.requestCount()).isZero();
            assertThat(machine(credential,"POST",GROUPS+"/messages",recordA,marker+":message-a",200)).isEqualTo(messageA);
            var changed=new LinkedHashMap<>(recordA);changed.put("content",List.of(Map.of("type","text","text","不能覆盖原群消息")));
            rejects(credential,"POST",GROUPS+"/messages",changed,marker+":message-a",409);
            var edited=new LinkedHashMap<>(recordA);edited.put("source_revision",2);
            rejects(credential,"POST",GROUPS+"/messages",edited,marker+":message-edit",409);
            rejects(other,"POST",GROUPS+"/messages",recordA,marker+":other-message",403);
            rejects(foreignCredential,"POST",GROUPS+"/messages",recordA,marker+":foreign-message",403);
            rejects(credential,"POST",GROUPS+"/triggers",trigger(groupId,messageA,userB,firstAgent),marker+":wrong-requester",403);
            rejects(personalCredential,"POST",GROUPS+"/triggers",trigger(groupId,messageA,userA,firstAgent),marker+":personal-scope-trigger",403);
            assertHistorySnapshotAndCurrentStreamMembership(credential,setupCredential,other,foreignCredential,personalCredential,
                    group,userA,scope,marker);

            var pair=concurrentTriggers(credential,trigger(groupId,messageA,userA,firstAgent),trigger(groupId,messageB,userB,secondAgent),marker);
            Front a=new Front(messageA,pair.get(0),userA,firstAgent,firstProvider,"agent-hitl-approved-"+marker+"-first");
            Front b=new Front(messageB,pair.get(1),userB,secondAgent,secondProvider,"agent-hitl-approved-"+marker+"-second");
            Front head=a.receipt().path("trigger_sequence").asLong()<b.receipt().path("trigger_sequence").asLong()?a:b;
            Front tail=head==a?b:a;
            for(var front:List.of(a,b)) {
                assertThat(front.receipt().path("status").asText()).isEqualTo("queued");
                assertThat(front.receipt().path("run_id").isNull()).isTrue();
                assertThat(front.receipt().path("input_id").isNull()).isTrue();
            }
            JsonNode headCurrent=awaitAccepted(credential,groupId,head.receipt());
            String headRun=headCurrent.path("run_id").asText();
            assertThat(headCurrent.path("requester_end_user_id")).isEqualTo(head.user().path("end_user_id"));assertThat(headCurrent.path("agent_id").asText()).isEqualTo(head.agent());
            Map<String,Object> originalWait=awaitHumanWait(owner.id(),headRun);
            assertThat(head.provider().requestCount()).isEqualTo(1);assertThat(tail.provider().requestCount()).isZero();
            assertThat(triggerDetail(credential,groupId,tail.receipt()).path("status").asText()).isEqualTo("queued");
            assertThat(nonTerminalRuns(owner.id(),group.path("session_id").asText())).isEqualTo(1);
            String steerActor=assertWaitingCommands(credential,owner.id(),groupId,headRun,head,tail,originalWait,marker);
            JsonNode originalCard=humanCard(runDetail(credential,groupId,headRun));
            rejects(credential,"POST",GROUPS+"/runs/respond",response(groupId,headRun,tail.user(),originalCard,head.approval(),marker+":wrong-responder"),marker+":wrong-responder",403);
            assertThat(machine(credential,"POST",GROUPS+"/triggers",trigger(groupId,head.message(),head.user(),head.agent()),head==a?marker+":trigger-a":marker+":trigger-b",202)).isEqualTo(head.receipt());
            rejects(credential,"POST",GROUPS+"/triggers",trigger(groupId,head.message(),head.user(),tail.agent()),marker+":retarget-same-source",409);
            machine(credential,"POST",GROUPS+"/messages",message(groupId,userA,"while-waiting",1,"普通消息不能确认正在等待的任务"),marker+":while-waiting",200);
            assertThat(waitFact(owner.id(),headRun)).isEqualTo(originalWait);

            long firstPid=institutionHost.process.pid();killInstitution();
            institutionHost=startHost(false,"waiting-restart");assertThat(institutionHost.process.pid()).isNotEqualTo(firstPid);
            assertThat(awaitHumanWait(owner.id(),headRun)).isEqualTo(originalWait);
            JsonNode restoredCard=humanCard(runDetail(credential,groupId,headRun));
            assertThat(restoredCard).isEqualTo(originalCard);
            assertThat(head.provider().requestCount()).isEqualTo(1);assertThat(tail.provider().requestCount()).isZero();
            Map<String,Object> exactResponse=response(groupId,headRun,head.user(),restoredCard,head.approval(),marker+":respond-head");
            JsonNode firstResponse=machine(credential,"POST",GROUPS+"/runs/respond",exactResponse,marker+":respond-head",200);
            awaitCompleted(owner.id(),headRun);assertThat(head.provider().requestCount()).isEqualTo(2);
            var appliedSteer=jdbc.queryForMap("""
                    SELECT r.actor_verification_ref AS actor_ref,a.source,a.channel,s.actor_ref AS supplement_actor_ref,s.applied_revision
                    FROM agent_runtime_execution_runs r
                    JOIN agent_runtime_execution_supplements s ON (s.tenant_id,s.run_id)=(r.tenant_id,r.run_id)
                    JOIN nw_runtime_identity_verifications v ON v.tenant_id=r.tenant_id AND v.verification_ref=r.actor_verification_ref
                    JOIN nw_runtime_authentication_facts a ON (a.tenant_id,a.authentication_ref)=(v.tenant_id,v.authentication_ref)
                    WHERE r.tenant_id=? AND r.run_id=? AND s.input_id=?
                    """,owner.id(),headRun,marker+":steer-head");
            assertThat(appliedSteer.get("source")).isEqualTo("group-control:"+head.user().path("end_user_id").asText());
            assertThat(appliedSteer.get("channel")).isEqualTo("open-platform");
            assertThat(appliedSteer.get("actor_ref")).isEqualTo(steerActor);
            assertThat(appliedSteer.get("supplement_actor_ref")).isEqualTo(steerActor);
            assertThat(appliedSteer.get("applied_revision")).isInstanceOf(Number.class);
            assertThat(((Number)appliedSteer.get("applied_revision")).longValue()).isPositive();
            var originalRunCounts=runCounts(owner.id(),headRun);
            JsonNode replay=machine(credential,"POST",GROUPS+"/runs/respond",exactResponse,marker+":respond-head",200);
            for(String field:List.of("group_binding_id","session_id","run_id","input_id"))assertThat(replay.path(field)).isEqualTo(firstResponse.path(field));
            assertThat(replay.path("replayed").asBoolean()).isTrue();assertThat(runCounts(owner.id(),headRun)).isEqualTo(originalRunCounts);
            JsonNode tailCurrent=awaitAccepted(credential,groupId,tail.receipt());String tailRun=tailCurrent.path("run_id").asText();
            assertThat(tailCurrent.path("requester_end_user_id")).isEqualTo(tail.user().path("end_user_id"));assertThat(tailCurrent.path("agent_id").asText()).isEqualTo(tail.agent());
            awaitHumanWait(owner.id(),tailRun);assertThat(tail.provider().requestCount()).isEqualTo(1);
            assertThat(nonTerminalRuns(owner.id(),group.path("session_id").asText())).isEqualTo(1);
            JsonNode tailCard=humanCard(runDetail(credential,groupId,tailRun));
            machine(credential,"POST",GROUPS+"/runs/respond",response(groupId,tailRun,tail.user(),tailCard,tail.approval(),marker+":respond-tail"),marker+":respond-tail",200);
            awaitCompleted(owner.id(),tailRun);assertThat(tail.provider().requestCount()).isEqualTo(2);
            assertThat(count("SELECT count(*) FROM nw_group_source_messages WHERE group_binding_id=? AND source_event_id IN ('message-a','message-b')",Long.parseLong(groupId))).isEqualTo(2);
            assertThat(count("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND session_id=?",owner.id(),group.path("session_id").asText())).isEqualTo(2);
            assertAgentPublicSource(credential,owner,group,head,headRun,tail,tailRun,
                    List.of(firstProvider,secondProvider,uncertainProvider,personalProvider),marker);
            ControlFixture executionControl=controlFixture(marker,"execution-control",a.provider(),a.approval());
            assertThat(executionControl.owner().id()).isNotEqualTo(owner.id());
            executionPauseResumesOriginalRun(executionControl.credential(),executionControl.owner(),executionControl.scope(),executionControl.actor(),marker);
            ControlFixture senderControl=controlFixture(marker,"sender-control",a.provider(),a.approval());
            assertThat(senderControl.owner().id()).isNotIn(owner.id(),executionControl.owner().id());
            senderAckRootBoundariesGenerationsAndUnknown(senderControl.credential(),senderControl.owner(),senderControl.scope(),senderControl.actor(),marker);
            localUnknownIntentTerminalizationAndAuthorizedSuccessor(marker);
            cancelAndDequeue(credential,owner,scope,a,b,marker);
            assertCurrentQualifications(credential,other,foreignCredential,group,userA,firstAgent,marker);
            personalRegression(credential,personalCredential,userA,personalAgent,personalProvider,marker);
            unknownRecovery(credential,owner,scope,userA,uncertainAgent,uncertainProvider,marker);
            } catch(Exception|Error problem) {
                failure=problem;
                try{saveFailureFacts(marker,problem,List.of(firstProvider,secondProvider,uncertainProvider,personalProvider));}
                catch(Exception|Error diagnostic){problem.addSuppressed(diagnostic);}
                throw problem;
            }
            finally {
                // 先停产品调度并等待进程退出，再释放Provider屏障，防止清理时继续产生新调用。
                try{stopHosts();}catch(Exception|Error cleanup){if(failure!=null)failure.addSuppressed(cleanup);else throw cleanup;}
            }
        } catch(Exception|Error problem) {failure=problem;throw problem;}
        finally {
            RequestContext.clear();TenantContext.clear();
            try{stopHosts();}catch(Exception|Error cleanup){if(failure!=null)failure.addSuppressed(cleanup);else throw cleanup;}
        }
    }

    /** 记录即公开；固定快照分页不混入后续来源，实时订阅逐次检查当前成员身份。 */
    private void assertHistorySnapshotAndCurrentStreamMembership(JsonNode credential,JsonNode setup,JsonNode other,
            JsonNode foreign,JsonNode personal,JsonNode group,JsonNode author,JsonNode scope,String marker)throws Exception {
        String groupId=group.path("group_binding_id").asText();
        JsonNode reader=user(setup,"history-reader"),outsider=user(setup,"history-outsider");
        machine(credential,"POST",GROUPS+"/members/update",member(groupId,reader,"member","active",0,"history-join",1),marker+":history-join",200);
        String query=historyQuery(groupId,reader);
        var before=executionCounts();
        JsonNode first=machine(credential,"GET",GROUPS+"/history"+query+"&page_size=1",null,null,200);
        assertThat(first.path("group_binding_id").asText()).isEqualTo(groupId);
        assertThat(first.path("session_id")).isEqualTo(group.path("session_id"));
        assertThat(first.path("items").size()).isEqualTo(1);assertThat(first.path("has_more").asBoolean()).isTrue();
        String cursor=first.path("next_cursor").asText(),eventCursor=first.path("event_cursor").asText();
        assertThat(cursor).isNotBlank();assertThat(eventCursor).isNotBlank();
        String lateText="分页快照之后新增公开来源-"+marker;
        machine(credential,"POST",GROUPS+"/messages",message(groupId,author,"history-after-snapshot",1,lateText),marker+":history-after-snapshot",200);
        var snapshot=new ArrayList<JsonNode>();first.path("items").forEach(snapshot::add);
        for(int page=0;!cursor.isBlank()&&page<20;page++) {
            JsonNode next=machine(credential,"GET",GROUPS+"/history"+query+"&page_size=1&cursor="+url(cursor),null,null,200);
            next.path("items").forEach(snapshot::add);
            cursor=next.path("has_more").asBoolean()?next.path("next_cursor").asText():"";
        }
        assertThat(cursor).as("固定快照必须有界遍历完成").isEmpty();
        assertThat(snapshot.stream().map(item->item.path("content").asText()).toList())
                .contains("请先等待本人的准确确认。","由另一位群助手处理本条。")
                .doesNotContain(lateText);
        assertThat(snapshot.stream().map(item->item.path("message_id").asText()).distinct().count()).isEqualTo(snapshot.size());
        JsonNode events=machine(credential,"GET",GROUPS+"/events"+query+"&cursor="+url(eventCursor),null,null,200);
        assertThat(events.path("items").toString()).contains(lateText);
        rejects(other,"GET",GROUPS+"/history"+query,null,null,403);
        rejects(foreign,"GET",GROUPS+"/history"+query,null,null,403);
        rejects(personal,"GET",GROUPS+"/history"+query,null,null,403);
        rejects(credential,"GET",GROUPS+"/history"+historyQuery(groupId,outsider),null,null,403);
        rejects(credential,"GET",GROUPS+"/history"+query+"&page_size=0",null,null,400);
        rejects(credential,"GET",GROUPS+"/history"+query+"&limit=1",null,null,400);
        JsonNode secondGroup=machine(credential,"POST",GROUPS+"/ensure",ensure(marker+"-history-other","history-other",scope.path("business_scope_id").asText()),marker+":history-other",200);
        String secondId=secondGroup.path("group_binding_id").asText();
        machine(credential,"POST",GROUPS+"/members/update",member(secondId,reader,"member","active",0,"history-other-join",1),marker+":history-other-join",200);
        rejects(credential,"GET",GROUPS+"/history"+historyQuery(secondId,reader)+"&page_size=1&cursor="+url(first.path("next_cursor").asText()),null,null,409);
        String streamPath=GROUPS+"/stream"+query+"&cursor="+url(eventCursor);
        try(var stream=new GroupStream(signedStream(credential,streamPath))) {
            await().atMost(Duration.ofSeconds(10)).untilAsserted(()->assertThat(stream.lines()).contains(lateText));
            String allowed="在当前成员有效期间可读-"+marker;
            machine(credential,"POST",GROUPS+"/messages",message(groupId,author,"history-stream-allowed",1,allowed),marker+":history-stream-allowed",200);
            await().atMost(Duration.ofSeconds(10)).untilAsserted(()->assertThat(stream.lines()).contains(allowed));
            machine(credential,"POST",GROUPS+"/members/update",member(groupId,reader,"member","left",1,"history-reader-left",2),marker+":history-reader-left",200);
            String forbidden="离群之后绝不能披露-"+marker;
            machine(credential,"POST",GROUPS+"/messages",message(groupId,author,"history-stream-forbidden",1,forbidden),marker+":history-stream-forbidden",200);
            await().atMost(Duration.ofSeconds(10)).untilAsserted(()->assertThat(stream.ended()).isTrue());
            assertThat(stream.lines()).doesNotContain(forbidden);
        }
        rejects(credential,"GET",GROUPS+"/history"+query+"&cursor="+url(first.path("next_cursor").asText()),null,null,403);
        rejects(credential,"GET",GROUPS+"/events"+query+"&cursor="+url(eventCursor),null,null,403);
        assertThat(executionCounts()).as("公开历史与订阅不得创建Run或Attempt").isEqualTo(before);
    }

    private String historyQuery(String group,JsonNode reader) {
        return "?group_binding_id="+url(group)+"&reader_end_user_id="+url(reader.path("end_user_id").asText());
    }
    private static String url(String value){return java.net.URLEncoder.encode(value,StandardCharsets.UTF_8);}

    /** 使用当前SP签真实SSE请求；连接和消费不借用普通JSON响应解析。 */
    private InputStream signedStream(JsonNode credential,String path)throws Exception {
        String timestamp=Long.toString(Instant.now().getEpochSecond());
        String signature=HmacSignatureVerifier.sign(credential.path("signing_secret").asText(),HmacSignatureVerifier.canonical(timestamp,"GET",path,""));
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+institutionHost.port+path)).timeout(Duration.ofSeconds(20))
                .header("Accept","text/event-stream").header("X-Api-Key",credential.path("api_key").asText())
                .header("X-Timestamp",timestamp).header("X-Signature",signature).GET().build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
        if(response.statusCode()!=200){response.body().close();throw new AssertionError("group_stream_http_"+response.statusCode());}
        return response.body();
    }

    private int signedStreamStatus(JsonNode credential,String path)throws Exception {
        String timestamp=Long.toString(Instant.now().getEpochSecond());
        String signature=HmacSignatureVerifier.sign(credential.path("signing_secret").asText(),HmacSignatureVerifier.canonical(timestamp,"GET",path,""));
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+institutionHost.port+path)).timeout(Duration.ofSeconds(20))
                .header("Accept","text/event-stream").header("X-Api-Key",credential.path("api_key").asText())
                .header("X-Timestamp",timestamp).header("X-Signature",signature).GET().build();
        return http.send(request,HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    /** 订阅线程只保留公开SSE；close实际释放输入流并等待线程静止。 */
    private static final class GroupStream implements AutoCloseable {
        private final InputStream input;private final ExecutorService executor=Executors.newSingleThreadExecutor();
        private final List<String> lines=new CopyOnWriteArrayList<>();private final AtomicBoolean ended=new AtomicBoolean();
        private final Future<?> reader;
        private GroupStream(InputStream input){this.input=input;reader=executor.submit(()->{
            try(var source=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8))){String line;while((line=source.readLine())!=null)lines.add(line);}
            catch(java.io.IOException closed){lines.add("stream_transport_closed");}finally{ended.set(true);}
        });}
        private String lines(){return String.join("\n",lines);}private boolean ended(){return ended.get();}
        @Override public void close()throws Exception{input.close();reader.cancel(true);executor.shutdownNow();assertThat(executor.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
    }

    /** 在任何业务事实和owned子宿主创建前取消父调度，并等待唯一执行Worker完成正式关闭。 */
    private void stopParentRuntime() {
        var scheduled=parentContext.getBean(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class);
        scheduled.destroy();assertThat(scheduled.getScheduledTasks()).isEmpty();
        var worker=parentContext.getBean(com.skillforge.runtime.starter.ExecutionHostWorker.class);
        worker.close();assertThat(worker.isClosed()).isTrue();assertThat(worker.isRunning()).isFalse();
    }

    /** execution暂停只阻止后续派发；人工回复仍绑定原等待，恢复新版本继续原Run。 */
    private void executionPauseResumesOriginalRun(JsonNode credential,Account owner,JsonNode scope,ControlActor actor,String marker)throws Exception {
        String prefix=marker+":execution-pause";
        JsonNode group=machine(credential,"POST",GROUPS+"/ensure",ensure(marker+"-execution-pause","execution-pause",scope.path("business_scope_id").asText()),prefix+":group",200);
        String id=group.path("group_binding_id").asText();
        machine(credential,"POST",GROUPS+"/members/update",member(id,actor.user(),"manager","active",0,"pause-join",1),prefix+":join",200);
        machine(credential,"POST",GROUPS+"/agents/update",agent(id,actor.agent(),"active",0,"pause-agent",1),prefix+":agent",200);
        int calls=actor.provider().requestCount();
        JsonNode source=machine(credential,"POST",GROUPS+"/messages",message(id,actor.user(),"pause-source",1,"人工确认后继续原任务，不创建替代任务"),prefix+":source",200);
        JsonNode original=machine(credential,"POST",GROUPS+"/triggers",trigger(id,source,actor.user(),actor.agent()),prefix+":trigger",202);
        String run=awaitAccepted(credential,id,original).path("run_id").asText();awaitHumanWait(owner.id(),run);
        JsonNode card=humanCard(runDetail(credential,id,run));
        JsonNode current=controlCurrent(credential,id);
        long revision=current.path("control_revision").asLong();
        var pause=controlCommand(id,run,prefix+":pause",revision,"paused",List.of("execution"));
        JsonNode paused=machine(credential,"POST",GROUPS+"/controls",pause,prefix+":pause",202);
        assertThat(paused.path("command_id").asText()).isEqualTo(prefix+":pause");
        assertThat(paused.path("control_revision").asLong()).isGreaterThan(revision);
        assertThat(machine(credential,"POST",GROUPS+"/controls",pause,prefix+":pause",202)).isEqualTo(paused);
        var drift=new LinkedHashMap<>(pause);drift.put("desired_state","running");
        rejects(credential,"POST",GROUPS+"/controls",drift,prefix+":pause",409);
        rejects(credential,"POST",GROUPS+"/controls",controlCommand(id,run,prefix+":stale",revision,"running",List.of("execution")),prefix+":stale",409);
        awaitControlApplied(credential,id,prefix+":pause");
        long attempts=count("SELECT count(*) FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=?",owner.id(),run);
        machine(credential,"POST",GROUPS+"/runs/respond",response(id,run,actor.user(),card,actor.approval(),prefix+":respond"),prefix+":respond",200);
        await().during(Duration.ofSeconds(6)).atMost(Duration.ofSeconds(8)).untilAsserted(()->{
            assertThat(actor.provider().requestCount()).isEqualTo(calls+1);
            assertThat(count("SELECT count(*) FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=?",owner.id(),run)).isEqualTo(attempts);
            assertThat(count("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND session_id=?",owner.id(),group.path("session_id").asText())).isEqualTo(1);
        });
        JsonNode resumed=machine(credential,"POST",GROUPS+"/controls",controlCommand(id,run,prefix+":resume",paused.path("control_revision").asLong(),"running",List.of("execution")),prefix+":resume",202);
        assertThat(resumed.path("control_revision").asLong()).isGreaterThan(paused.path("control_revision").asLong());
        awaitControlApplied(credential,id,prefix+":resume");awaitCompleted(owner.id(),run);
        assertThat(actor.provider().requestCount()).isEqualTo(calls+2);
        assertThat(triggerDetail(credential,id,original).path("run_id").asText()).isEqualTo(run);
        assertThat(count("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND session_id=?",owner.id(),group.path("session_id").asText())).isEqualTo(1);
        assertThat(machine(credential,"POST",GROUPS+"/controls",pause,prefix+":pause",202)).as("恢复后重投仍返回首次暂停回执").isEqualTo(paused);
        assertThat(controlCurrent(credential,id).path("control_revision")).isEqualTo(resumed.path("control_revision"));
    }

    private JsonNode controlCurrent(JsonNode credential,String group)throws Exception {
        return machine(credential,"GET",GROUPS+"/controls/current?group_binding_id="+url(group),null,null,200);
    }
    private Map<String,Object> controlCommand(String group,String run,String command,long revision,String state,List<String> scopes) {
        var value=new LinkedHashMap<String,Object>();value.put("group_binding_id",group);value.put("command_id",command);
        value.put("expected_revision",revision);value.put("desired_state",state);value.put("scopes",scopes);
        if(run!=null)value.put("run_id",run);return value;
    }
    private JsonNode awaitControlApplied(JsonNode credential,String group,String command)throws Exception {
        String path=GROUPS+"/controls/detail?group_binding_id="+url(group)+"&command_id="+url(command);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(()->assertThat(machine(credential,"GET",path,null,null,200).path("status").asText()).isEqualTo("APPLIED"));
        return machine(credential,"GET",path,null,null,200);
    }

    /** 真实sender持久ACK、双活世代fence及原UNKNOWN；暂停边界绑定根触发而非final入队时刻。 */
    private void senderAckRootBoundariesGenerationsAndUnknown(JsonNode credential,Account owner,JsonNode scope,ControlActor actor,String marker)throws Exception {
        String prefix=marker+":sender";
        JsonNode group=machine(credential,"POST",GROUPS+"/ensure",ensure(marker+"-sender","sender",scope.path("business_scope_id").asText()),prefix+":group",200);
        String id=group.path("group_binding_id").asText();
        machine(credential,"POST",GROUPS+"/members/update",member(id,actor.user(),"manager","active",0,"sender-join",1),prefix+":join",200);
        machine(credential,"POST",GROUPS+"/agents/update",agent(id,actor.agent(),"active",0,"sender-agent",1),prefix+":agent",200);
        Path directory=evidence.resolve("independent-sender");Files.createDirectories(directory);
        Path state=directory.resolve("sender.sqlite3");String endpoint=prefix+":endpoint";
        try(var sink=new GroupControlHttpSender.Sink(json);
            var first=new GroupControlHttpSender(json,repositoryRoot(),directory,state,"http://127.0.0.1:"+institutionHost.port,sink.origin(),id,endpoint,1,credential,credential)) {
            first.start();assertThat(first.alive()).isTrue();
            JsonNode oldTrigger=sourceTrigger(credential,id,actor,"sender-before-pause",prefix+":before");
            String oldRun=awaitAccepted(credential,id,oldTrigger).path("run_id").asText();awaitHumanWait(owner.id(),oldRun);
            long initialRevision=controlCurrent(credential,id).path("control_revision").asLong();
            var pause=controlCommand(id,null,prefix+":pause",initialRevision,"paused",List.of("send"));
            JsonNode accepted=machine(credential,"POST",GROUPS+"/controls",pause,prefix+":pause",202);
            assertThat(accepted.path("status").asText()).isIn("ACCEPTED","PARTIAL");
            assertThat(accepted.path("required_participants").toString()).contains(endpoint);
            assertThat(sink.sends()).isZero();
            first.apply(id);JsonNode applied=awaitControlApplied(credential,id,prefix+":pause");
            assertThat(applied.path("durable_acks").toString()).contains(endpoint);
            assertThat(machine(credential,"POST",GROUPS+"/controls",pause,prefix+":pause",202)).isEqualTo(accepted);
            JsonNode during=sourceTrigger(credential,id,actor,"sender-during-pause",prefix+":during");
            assertThat(triggerDetail(credential,id,during).path("status").asText()).isEqualTo("queued");
            JsonNode resumed=machine(credential,"POST",GROUPS+"/controls",controlCommand(id,null,prefix+":resume",accepted.path("control_revision").asLong(),"running",List.of("send")),prefix+":resume",202);
            assertThat(resumed.path("status").asText()).isIn("ACCEPTED","PARTIAL");first.apply(id);awaitControlApplied(credential,id,prefix+":resume");
            completeWaiting(credential,owner,id,oldRun,actor,prefix+":old-response");
            String duringRun=awaitAccepted(credential,id,during).path("run_id").asText();awaitHumanWait(owner.id(),duringRun);
            completeWaiting(credential,owner,id,duringRun,actor,prefix+":during-response");
            JsonNode oldFinal=formalFinal(credential,id,actor.user(),oldRun),duringFinal=formalFinal(credential,id,actor.user(),duringRun);
            var oldDelivery=delivery(id,oldRun,oldFinal,prefix+":old-delivery");
            var duringDelivery=delivery(id,duringRun,duringFinal,prefix+":during-delivery");
            JsonNode blocked=first.deliver(oldDelivery);assertThat(blocked.path("status").asText()).isEqualTo("BLOCKED");
            assertThat(first.deliver(duringDelivery).path("status").asText()).isEqualTo("BLOCKED");assertThat(sink.sends()).isZero();
            // 本地控制读权只返回安全投递事实；正式正文与中央资格不从私有authorization字段取得。
            var qualify=new LinkedHashMap<String,Object>(oldDelivery);qualify.put("endpoint_id",endpoint);qualify.put("generation",1);
            JsonNode qualification=machine(credential,"POST",GROUPS+"/deliveries/qualify",qualify,null,200);
            assertThat(qualification.path("eligible").asBoolean()).isFalse();
            assertThat(qualification.path("reason").asText()).isEqualTo("ROOT_BOUNDARY_BLOCKED");
            assertThat(qualification.path("delivery_id").asText()).isEqualTo(prefix+":old-delivery");
            assertThat(qualification.path("run_id").asText()).isEqualTo(oldRun);
            assertThat(qualification.path("message_id")).isEqualTo(oldFinal.path("message_id"));
            assertThat(blocked.path("blocked_refs")).as("真实sender阻止与中央原根边界一致").isEqualTo(qualification.path("blocked_boundary_refs"));
            var blockedRefs=new ArrayList<String>();qualification.path("blocked_boundary_refs").forEach(value->blockedRefs.add(value.asText()));
            assertThat(blockedRefs).isNotEmpty();
            var release=Map.of("group_binding_id",id,"run_id",oldRun,"expected_revision",resumed.path("control_revision").asLong(),
                    "messages",List.of(Map.of("message_id",oldFinal.path("message_id").asText(),"message_version",oldFinal.path("message_version").asLong(),"delivery_id",prefix+":old-delivery")),"blocked_boundary_refs",blockedRefs);
            JsonNode released=machine(credential,"POST",GROUPS+"/controls/release",release,prefix+":release",200);
            assertThat(released.path("messages").size()).isEqualTo(1);
            assertThat(machine(credential,"POST",GROUPS+"/controls/release",release,prefix+":release",200)).isEqualTo(released);
            assertThat(first.deliver(oldDelivery).path("status").asText()).isEqualTo("DELIVERED");assertThat(sink.sends()).isEqualTo(1);
            assertThat(sink.sent(prefix+":old-delivery").path("content").asText()).isEqualTo(oldFinal.path("content").asText());
            assertThat(sink.sent(prefix+":old-delivery").path("content_digest").asText()).matches("[0-9a-f]{64}");
            assertThat(first.deliver(oldDelivery).path("status").asText()).isEqualTo("DELIVERED");assertThat(sink.sends()).isEqualTo(1);
            assertThat(first.deliver(duringDelivery).path("status").asText()).isEqualTo("BLOCKED");assertThat(sink.sends()).isEqualTo(1);
            JsonNode future=sourceTrigger(credential,id,actor,"sender-after-resume",prefix+":future");
            String futureRun=awaitAccepted(credential,id,future).path("run_id").asText();awaitHumanWait(owner.id(),futureRun);
            completeWaiting(credential,owner,id,futureRun,actor,prefix+":future-response");
            JsonNode futureFinal=formalFinal(credential,id,actor.user(),futureRun);
            var uncertain=delivery(id,futureRun,futureFinal,prefix+":uncertain-delivery");
            sink.loseNextResponse();JsonNode unknown=first.deliver(uncertain);
            assertThat(unknown.path("status").asText()).isEqualTo("UNKNOWN");assertThat(sink.sends()).isEqualTo(2);
            assertThat(first.deliver(uncertain)).isEqualTo(unknown);assertThat(sink.sends()).isEqualTo(2);
            try(var second=new GroupControlHttpSender(json,repositoryRoot(),directory,state,"http://127.0.0.1:"+institutionHost.port,sink.origin(),id,endpoint,2,credential,credential)) {
                second.start();assertThat(first.alive()).as("旧发送进程与新世代真实并存").isTrue();assertThat(second.alive()).isTrue();
                JsonNode current=controlCurrent(credential,id);
                var generations=new ArrayList<JsonNode>();current.path("endpoints").forEach(generations::add);
                assertThat(generations.stream().anyMatch(value->value.path("generation").asLong()==1&&"RETIRED".equals(value.path("status").asText()))).isTrue();
                assertThat(generations.stream().anyMatch(value->value.path("generation").asLong()==2&&"ACTIVE".equals(value.path("status").asText()))).isTrue();
                first.request("POST","/api/v1/group-deliveries",duringDelivery,null,409);assertThat(sink.sends()).isEqualTo(2);
                assertThat(second.deliver(uncertain).path("status").asText()).isEqualTo("UNKNOWN");assertThat(sink.sends()).isEqualTo(2);
                JsonNode reconciled=second.request("POST","/api/v1/group-deliveries/reconcile",Map.of("delivery_id",prefix+":uncertain-delivery"),null,200);
                assertThat(reconciled.path("status").asText()).isEqualTo("DELIVERED");assertThat(sink.sends()).isEqualTo(2);
                assertThat(second.deliver(uncertain).path("status").asText()).isEqualTo("DELIVERED");assertThat(sink.sends()).isEqualTo(2);
                Files.writeString(directory.resolve("final-sender-snapshot.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(second.snapshot()),StandardCharsets.UTF_8);
            }
        }
    }

    private JsonNode sourceTrigger(JsonNode credential,String group,ControlActor actor,String event,String key)throws Exception {
        JsonNode source=machine(credential,"POST",GROUPS+"/messages",message(group,actor.user(),event,1,"请等待准确人工确认-"+event),key+":source",200);
        return machine(credential,"POST",GROUPS+"/triggers",trigger(group,source,actor.user(),actor.agent()),key+":trigger",202);
    }
    private void completeWaiting(JsonNode credential,Account owner,String group,String run,ControlActor actor,String key)throws Exception {
        JsonNode card=humanCard(runDetail(credential,group,run));
        machine(credential,"POST",GROUPS+"/runs/respond",response(group,run,actor.user(),card,actor.approval(),key),key,200);awaitCompleted(owner.id(),run);
    }
    /** 只由正式HTTP公开历史定位原Run已采纳final，不从私有结果拼正文或猜message身份。 */
    private JsonNode formalFinal(JsonNode credential,String group,JsonNode reader,String run)throws Exception {
        var messages=new ArrayList<JsonNode>();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(()->{
            messages.clear();JsonNode history=machine(credential,"GET",GROUPS+"/history"+historyQuery(group,reader)+"&page_size=200",null,null,200);
            for(JsonNode message:history.path("items"))if("final".equals(message.path("phase").asText())&&"completed".equals(message.path("state").asText())
                    &&run.equals(message.path("metadata").path("run_id").asText()))messages.add(message);
            assertThat(messages).hasSize(1);
        });
        JsonNode result=messages.getFirst();assertThat(result.path("message_id").asText()).isNotBlank();assertThat(result.path("message_version").asLong()).isPositive();return result;
    }
    private Map<String,Object> delivery(String group,String run,JsonNode message,String id) {
        return Map.of("group_binding_id",group,"run_id",run,"message_id",message.path("message_id").asText(),"message_version",message.path("message_version").asLong(),"delivery_id",id);
    }


    /** 各控制场景拥有真实独立执行域；共用Provider只复用已验设施，不共享租户限流和业务身份。 */
    private ControlFixture controlFixture(String marker,String name,NativeChatOpenAiProvider provider,String approval)throws Exception {
        String prefix=marker+":"+name;
        Account owner=account(marker,name);
        String integration=association(owner,marker,name);
        JsonNode credential=credential(owner,integration,GROUP_SCOPES,prefix+":credential");
        JsonNode setup=credential(owner,integration,List.of("business.scopes.write","end_users.write"),prefix+":setup");
        JsonNode scope=scope(setup,name+"-clinic",prefix+":scope"),user=user(setup,name+"-operator");
        String agent=nativeAgent(owner,model(owner,provider.endpoint(),marker+"-"+name),prefix+":agent");
        assertThat(credential.path("principal").path("tenant_id").asText()).isEqualTo(owner.id());
        assertThat(credential.path("principal").path("integration_id").asText()).isEqualTo(integration);
        return new ControlFixture(owner,credential,scope,new ControlActor(user,agent,provider,approval));
    }
    /** 控制用例只需要真实操作者与Agent，不用虚构message/receipt填充前台触发夹具。 */
    private record ControlActor(JsonNode user,String agent,NativeChatOpenAiProvider provider,String approval) { }
    private record ControlFixture(Account owner,JsonNode credential,JsonNode scope,ControlActor actor) {
        @Override public String toString(){return "Control fixture "+owner.id();}
    }

    /** 本地先阻止；网络UNKNOWN不等于未受理，获准接替者以中央终结证据接续原意图。 */
    private void localUnknownIntentTerminalizationAndAuthorizedSuccessor(String marker)throws Exception {
        String prefix=marker+":local-intent";
        // HMAC请求按Tenant+IP共用真实分钟限额；独立业务场景不消费先前sender的同一租户窗口。
        Account owner=account(marker,"local-intent-owner");
        String integration=association(owner,marker,"local-intent");
        JsonNode credential=credential(owner,integration,GROUP_SCOPES,prefix+":sender-credential");
        JsonNode setup=credential(owner,integration,List.of("business.scopes.write"),prefix+":scope-credential");
        JsonNode scope=scope(setup,"local-intent-clinic",prefix+":scope");
        JsonNode group=machine(credential,"POST",GROUPS+"/ensure",ensure(marker+"-local-intent","local-intent",scope.path("business_scope_id").asText()),prefix+":group",200);
        String id=group.path("group_binding_id").asText();
        assertThat(credential.path("principal").path("tenant_id").asText()).isEqualTo(owner.id());
        assertThat(credential.path("principal").path("integration_id").asText()).isEqualTo(integration);
        JsonNode origin=credential(owner,integration,GROUP_SCOPES,prefix+":origin-credential");
        assertThat(origin.path("principal").path("tenant_id")).isEqualTo(credential.path("principal").path("tenant_id"));
        assertThat(origin.path("principal").path("integration_id")).isEqualTo(credential.path("principal").path("integration_id"));
        assertThat(origin.path("principal").path("id")).isNotEqualTo(credential.path("principal").path("id"));
        Path directory=evidence.resolve("local-control-intents");Files.createDirectories(directory);
        var before=executionCounts();
        try(var sink=new GroupControlHttpSender.Sink(json);
            var proxy=new GroupControlHttpSender.ControlProxy("http://127.0.0.1:"+institutionHost.port);
            var sender=new GroupControlHttpSender(json,repositoryRoot(),directory,directory.resolve("sender.sqlite3"),proxy.origin(),sink.origin(),id,prefix+":endpoint",1,credential,origin)) {
            sender.start();proxy.dropControls(true);
            String abandonedId=prefix+":abandon";long originalRevision=controlCurrent(credential,id).path("control_revision").asLong();
            var abandonCreate=Map.of("local_intent_id",abandonedId,"group_binding_id",id,"scopes",List.of("send"),"expected_revision",0);
            JsonNode originalCreate=sender.request("POST","/api/v1/local-control-intents",abandonCreate,prefix+":create-abandon",200);
            assertThat(sender.request("POST","/api/v1/local-control-intents",abandonCreate,prefix+":create-abandon",200)).isEqualTo(originalCreate);
            JsonNode uncertain=sender.request("GET","/api/v1/local-control-intents/detail?intent_id="+url(abandonedId),null,null,200);
            assertUnknownLocalIntent(uncertain);assertThat(proxy.dropped()).isEqualTo(1);
            assertThat(controlCurrent(credential,id).path("control_revision").asLong()).isEqualTo(originalRevision);
            proxy.dropControls(false);
            var abandonRequest=Map.of("intent_id",abandonedId,"expected_revision",uncertain.path("revision").asLong(),"action","abandon");
            proxy.loseNextTerminalizationResponse();
            sender.request("POST","/api/v1/local-control-intents/reconcile",abandonRequest,prefix+":abandon-proof",503);
            assertThat(proxy.terminalizationResponsesLost()).as("中央成功提交后确实丢失一次终结响应").isEqualTo(1);
            JsonNode terminalized=machine(credential,"GET",GROUPS+"/controls/detail?group_binding_id="+url(id)+"&command_id="+url(uncertain.path("command_id").asText()),null,null,200);
            assertThat(terminalized.path("result_kind").asText()).isEqualTo("terminal_not_accepted");
            JsonNode unresolved=sender.request("GET","/api/v1/local-control-intents/detail?intent_id="+url(abandonedId),null,null,200);
            assertUnknownLocalIntent(unresolved);assertThat(unresolved.path("revision")).isEqualTo(uncertain.path("revision"));
            JsonNode advanced=machine(credential,"POST",GROUPS+"/controls",controlCommand(id,null,prefix+":advance-after-terminalize",originalRevision,"running",List.of("execution")),prefix+":advance-after-terminalize",202);
            assertThat(advanced.path("control_revision").asLong()).isGreaterThan(terminalized.path("control_revision").asLong());
            JsonNode abandoned=sender.request("POST","/api/v1/local-control-intents/reconcile",abandonRequest,prefix+":abandon-recover",200);
            assertThat(proxy.terminalizations()).hasSize(2);
            var initialTerminalize=proxy.terminalizations().getFirst();var resumedTerminalize=proxy.terminalizations().get(1);
            assertThat(resumedTerminalize.get("key")).as("恢复沿原terminalize幂等键重读").isEqualTo(initialTerminalize.get("key"));
            assertThat(json.readTree(resumedTerminalize.get("body"))).as("恢复沿原terminalize完整内容重读，不取新expected_revision")
                    .isEqualTo(json.readTree(initialTerminalize.get("body")));
            assertThat(abandoned.path("active").asInt()).isZero();assertThat(abandoned.path("status").asText()).isEqualTo("ABANDONED");
            assertThat(abandoned.path("attempts").get(0).path("status").asText()).isEqualTo("TERMINAL_NOT_ACCEPTED");
            JsonNode proof=abandoned.path("attempts").get(0).path("receipt");assertThat(proof.path("result_kind").asText()).isEqualTo("terminal_not_accepted");
            assertThat(proof.path("boundary_ref").asText()).isNotBlank();
            for(String field:List.of("result_ref","boundary_ref","through_trigger_sequence","control_revision","fingerprint"))
                assertThat(proof.path(field)).as("终结恢复保留原%s",field).isEqualTo(terminalized.path(field));
            var late=controlCommand(id,null,uncertain.path("command_id").asText(),originalRevision,"paused",List.of("send"));late.put("run_id",null);
            rejects(credential,"POST",GROUPS+"/controls",late,uncertain.path("command_id").asText(),409);
            proxy.dropControls(true);String continuedId=prefix+":successor";
            sender.requestAsOrigin("POST","/api/v1/local-control-intents",Map.of("local_intent_id",continuedId,"group_binding_id",id,"scopes",List.of("send"),"expected_revision",0),prefix+":create-successor",200);
            JsonNode original=sender.request("GET","/api/v1/local-control-intents/detail?intent_id="+url(continuedId),null,null,200);
            assertUnknownLocalIntent(original);assertThat(proxy.dropped()).isEqualTo(2);proxy.dropControls(false);
            platform("POST","/api/v1/platform/institution-credentials/revoke",Map.of("tenant_id",owner.id(),"integration_id",integration,
                    "principal_id",origin.path("principal").path("id").asText(),"expected_revision",origin.path("principal").path("credential_version").asLong()),prefix+":revoke-origin",200);
            var reconcile=Map.of("intent_id",continuedId,"expected_revision",original.path("revision").asLong(),"action","resubmit");
            sender.requestAsOrigin("POST","/api/v1/local-control-intents/reconcile",reconcile,prefix+":revoked-reconcile",401);
            JsonNode stillUnknown=sender.request("GET","/api/v1/local-control-intents/detail?intent_id="+url(continuedId),null,null,200);
            assertUnknownLocalIntent(stillUnknown);assertThat(stillUnknown.path("revision")).isEqualTo(original.path("revision"));
            JsonNode resubmitReceipt=sender.request("POST","/api/v1/local-control-intents/reconcile",reconcile,prefix+":manager-resubmit",200);
            assertThat(sender.request("POST","/api/v1/local-control-intents/reconcile",reconcile,prefix+":manager-resubmit",200)).isEqualTo(resubmitReceipt);
            JsonNode successor=sender.request("GET","/api/v1/local-control-intents/detail?intent_id="+url(continuedId),null,null,200);
            assertThat(successor.path("active").asInt()).isEqualTo(1);assertThat(successor.path("status").asText()).isEqualTo("REMOTE_ACCEPTED");
            assertThat(successor.path("attempts").size()).isEqualTo(2);
            assertThat(successor.path("attempts").get(0).path("status").asText()).isEqualTo("TERMINAL_NOT_ACCEPTED");
            assertThat(successor.path("attempts").get(1).path("status").asText()).isEqualTo("ACCEPTED");
            assertThat(successor.path("attempts").get(0).path("actor_id").asText()).isEqualTo("http-origin");
            assertThat(successor.path("attempts").get(1).path("actor_id").asText()).isEqualTo("http-manager");
            assertThat(successor.path("command_id")).isNotEqualTo(original.path("command_id"));
            sender.apply(id);awaitControlApplied(credential,id,successor.path("command_id").asText());
            long revision=controlCurrent(credential,id).path("control_revision").asLong();
            machine(credential,"POST",GROUPS+"/controls",controlCommand(id,null,prefix+":resume",revision,"running",List.of("send")),prefix+":resume",202);
            sender.apply(id);awaitControlApplied(credential,id,prefix+":resume");
            JsonNode released=sender.request("POST","/api/v1/local-control-intents/release",Map.of("intent_id",continuedId,"expected_revision",successor.path("revision").asLong(),"resume_command_id",prefix+":resume"),prefix+":release",200);
            assertThat(released.path("active").asInt()).isZero();assertThat(released.path("status").asText()).isEqualTo("RELEASED");
            assertThat(sink.sends()).isZero();assertThat(executionCounts()).isEqualTo(before);
            Files.writeString(directory.resolve("final-local-intents.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(sender.snapshot()),StandardCharsets.UTF_8);
        }
    }
    private void assertUnknownLocalIntent(JsonNode intent) {
        assertThat(intent.path("active").asInt()).isEqualTo(1);assertThat(intent.path("status").asText()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(intent.path("attempts").size()).isEqualTo(1);assertThat(intent.path("attempts").get(0).path("status").asText()).isEqualTo("UNKNOWN");
    }

    /** 在owned宿主停止和私有库释放前保存只读旁证；逐项查询失败也保留其余事实，绝不替换首因。 */
    private void saveFailureFacts(String marker,Throwable failure,List<NativeChatOpenAiProvider> providers)throws Exception {
        var facts=new LinkedHashMap<String,Object>();facts.put("test_marker",marker);facts.put("captured_at",Instant.now().toString());
        facts.put("capture_consistency","sequential_read_only_observations");facts.put("failure_type",failure.getClass().getName());
        var causes=new ArrayList<String>();for(Throwable cause=failure;cause!=null&&causes.size()<8;cause=cause.getCause())causes.add(cause.getClass().getName());
        facts.put("failure_cause_types",causes);facts.put("http_errors",List.copyOf(httpErrors));
        facts.put("parent_scheduled_tasks",parentContext.getBean(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks().size());
        facts.put("parent_execution_worker_closed",parentContext.getBean(com.skillforge.runtime.starter.ExecutionHostWorker.class).isClosed());
        facts.put("parent_execution_worker_running",parentContext.getBean(com.skillforge.runtime.starter.ExecutionHostWorker.class).isRunning());
        var calls=new LinkedHashMap<String,Integer>();var names=List.of("first","second","uncertain","personal");
        for(int i=0;i<providers.size();i++)calls.put(names.get(i),providers.get(i).requestCount());facts.put("provider_requests",calls);
        facts.put("owned_hosts",ownedHosts.stream().map(host->Map.of("pid",host.process.pid(),"alive",host.process.isAlive(),"platform",host.platform,"worker_id",host.workerId,"log",host.log.toString())).toList());
        String prefix=marker+"%";
        failureQuery(facts,"groups","""
                SELECT g.tenant_id,g.integration_id,g.id AS group_binding_id,g.session_id,g.status,g.revision
                FROM nw_group_bindings g WHERE g.namespace='group-fixture' AND g.external_group_ref LIKE ? ORDER BY g.id
                """,prefix);
        failureQuery(facts,"slots","""
                SELECT s.tenant_id,s.integration_id,s.group_binding_id,g.session_id,s.source_sequence,s.trigger_sequence,s.active_trigger_id,s.active_run_id
                FROM nw_group_front_slots s JOIN nw_group_bindings g ON (g.tenant_id,g.integration_id,g.id)=(s.tenant_id,s.integration_id,s.group_binding_id)
                WHERE g.namespace='group-fixture' AND g.external_group_ref LIKE ? ORDER BY s.group_binding_id
                """,prefix);
        failureQuery(facts,"triggers","""
                SELECT t.tenant_id,t.integration_id,t.group_binding_id,t.session_id,t.trigger_id,t.trigger_sequence,t.source_message_id,t.source_sequence,
                       t.requester_end_user_id,t.agent_id,t.status,t.input_id,t.run_id,t.rejection_code
                FROM nw_group_triggers t JOIN nw_group_bindings g ON (g.tenant_id,g.integration_id,g.id)=(t.tenant_id,t.integration_id,t.group_binding_id)
                WHERE g.namespace='group-fixture' AND g.external_group_ref LIKE ? ORDER BY t.group_binding_id,t.trigger_sequence
                """,prefix);
        failureQuery(facts,"runs","""
                SELECT r.tenant_id,g.integration_id,g.id AS group_binding_id,r.session_id,r.run_id,r.agent_id,r.requester_kind,r.requester_end_user_id,
                       r.source_message_id,r.input_id,r.revision,r.phase,r.status,r.cancelled,r.fence,r.current_action_id,r.waiting_action_id,
                       r.waiting_generation,r.lease_worker_id,r.lease_until_second,r.next_due_second,r.actor_verification_ref AS actor_ref,auth.source,auth.channel
                FROM agent_runtime_execution_runs r JOIN nw_group_bindings g ON r.tenant_id=g.tenant_id::text AND r.session_id=g.session_id
                LEFT JOIN nw_runtime_identity_verifications verification ON verification.tenant_id=r.tenant_id AND verification.verification_ref=r.actor_verification_ref
                LEFT JOIN nw_runtime_authentication_facts auth ON (auth.tenant_id,auth.authentication_ref)=(verification.tenant_id,verification.authentication_ref)
                WHERE g.namespace='group-fixture' AND g.external_group_ref LIKE ? ORDER BY g.id,r.run_id
                """,prefix);
        failureQuery(facts,"supplements","""
                SELECT s.tenant_id,s.run_id,s.input_id,s.actor_ref,s.ordinal,s.applied_revision,auth.source,auth.channel
                FROM agent_runtime_execution_supplements s JOIN agent_runtime_execution_runs r ON (r.tenant_id,r.run_id)=(s.tenant_id,s.run_id)
                JOIN nw_group_bindings g ON r.tenant_id=g.tenant_id::text AND r.session_id=g.session_id
                LEFT JOIN nw_runtime_identity_verifications verification ON verification.tenant_id=s.tenant_id AND verification.verification_ref=s.actor_ref
                LEFT JOIN nw_runtime_authentication_facts auth ON (auth.tenant_id,auth.authentication_ref)=(verification.tenant_id,verification.authentication_ref)
                WHERE g.namespace='group-fixture' AND g.external_group_ref LIKE ? ORDER BY s.run_id,s.ordinal
                """,prefix);
        failureQuery(facts,"waits","""
                SELECT w.tenant_id,w.run_id,w.action_id,w.generation,w.kind,w.source_attempt,w.input_id,w.accepted_revision,w.continuation_attempt,w.consumed_revision
                FROM agent_runtime_execution_waits w JOIN agent_runtime_execution_runs r ON (r.tenant_id,r.run_id)=(w.tenant_id,w.run_id)
                JOIN nw_group_bindings g ON r.tenant_id=g.tenant_id::text AND r.session_id=g.session_id
                WHERE g.namespace='group-fixture' AND g.external_group_ref LIKE ? ORDER BY w.run_id,w.action_id,w.generation
                """,prefix);
        failureQuery(facts,"actions","""
                SELECT a.tenant_id,a.run_id,a.action_id,a.action_ordinal,a.kind,a.status,a.current_attempt,a.selected_attempt,a.request_ref,a.fingerprint
                FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_runs r ON (r.tenant_id,r.run_id)=(a.tenant_id,a.run_id)
                JOIN nw_group_bindings g ON r.tenant_id=g.tenant_id::text AND r.session_id=g.session_id
                WHERE g.namespace='group-fixture' AND g.external_group_ref LIKE ? ORDER BY a.run_id,a.action_ordinal
                """,prefix);
        failureQuery(facts,"attempts","""
                SELECT a.tenant_id,a.run_id,a.action_id,a.attempt_id,a.fence,a.continuation_generation,a.result_kind,a.result_ref,a.uncertainty_ref,a.adopted,
                       CASE WHEN COALESCE(m.canonical_json::jsonb->>'code',m.canonical_json::jsonb->'failure'->>'code',m.canonical_json::jsonb->'error'->>'code') ~ '^[A-Za-z0-9_.:-]{1,128}$'
                            THEN COALESCE(m.canonical_json::jsonb->>'code',m.canonical_json::jsonb->'failure'->>'code',m.canonical_json::jsonb->'error'->>'code') END AS result_error_code
                FROM agent_runtime_execution_attempts a JOIN agent_runtime_execution_runs r ON (r.tenant_id,r.run_id)=(a.tenant_id,a.run_id)
                JOIN nw_group_bindings g ON r.tenant_id=g.tenant_id::text AND r.session_id=g.session_id
                LEFT JOIN agent_runtime_execution_materials m ON (m.tenant_id,m.reference)=(a.tenant_id,a.result_ref)
                WHERE g.namespace='group-fixture' AND g.external_group_ref LIKE ? ORDER BY a.run_id,a.action_id,a.attempt_id
                """,prefix);
        failureQuery(facts,"events","""
                SELECT e.tenant_id,e.session_id,e.run_id,e.event_id,e.event_sequence,e.revision,e.type,e.schema_id,
                       CASE WHEN COALESCE(e.canonical_json::jsonb->>'reason',e.canonical_json::jsonb->>'code',e.canonical_json::jsonb->'failure'->>'code') ~ '^[A-Za-z0-9_.:-]{1,128}$'
                            THEN COALESCE(e.canonical_json::jsonb->>'reason',e.canonical_json::jsonb->>'code',e.canonical_json::jsonb->'failure'->>'code') END AS error_code
                FROM agent_runtime_execution_events e JOIN nw_group_bindings g ON e.tenant_id=g.tenant_id::text AND e.session_id=g.session_id
                WHERE g.namespace='group-fixture' AND g.external_group_ref LIKE ? ORDER BY g.id,e.event_sequence
                """,prefix);
        Files.writeString(evidence.resolve("failure-facts.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(facts),StandardCharsets.UTF_8);
    }
    private void failureQuery(Map<String,Object> facts,String name,String sql,Object... arguments) {
        try{facts.put(name,jdbc.queryForList(sql,arguments));}
        catch(RuntimeException failure){facts.put(name+"_diagnostic_error_type",failure.getClass().getName());}
    }

    /** 两个真实HTTP请求从同一屏障发起；执行先后只采用服务器触发序号。 */
    private List<JsonNode> concurrentTriggers(JsonNode credential,Object first,Object second,String marker)throws Exception {
        var ready=new CountDownLatch(2);var go=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
        try {
            var a=executor.submit(()->{ready.countDown();if(!go.await(10,TimeUnit.SECONDS))throw new AssertionError("trigger barrier timeout");return machine(credential,"POST",GROUPS+"/triggers",first,marker+":trigger-a",202);});
            var b=executor.submit(()->{ready.countDown();if(!go.await(10,TimeUnit.SECONDS))throw new AssertionError("trigger barrier timeout");return machine(credential,"POST",GROUPS+"/triggers",second,marker+":trigger-b",202);});
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();go.countDown();
            return List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));
        } finally {go.countDown();executor.shutdownNow();assertThat(executor.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
    }

    /** steer登记原Run补充输入；等待事实、前台slot和外调均不被普通命令恢复。 */
    private String assertWaitingCommands(JsonNode credential,String tenant,String group,String run,Front requester,Front ordinary,
            Map<String,Object> originalWait,String marker)throws Exception {
        String input=marker+":steer-head";
        var command=runCommand(group,run,requester.user(),input,"steer","继续保留原确认等待，确认后的答复保持简洁");
        var before=businessCounts();int headCalls=requester.provider().requestCount(),tailCalls=ordinary.provider().requestCount();
        rejects(credential,"POST",GROUPS+"/runs/commands",runCommand(group,run,ordinary.user(),marker+":wrong-steer","steer","不能代替原请求者补充"),marker+":wrong-steer",403);
        rejects(credential,"POST",GROUPS+"/runs/commands",runCommand(group,run,ordinary.user(),marker+":wrong-cancel","cancel_execution",null),marker+":wrong-cancel",403);
        JsonNode first=machine(credential,"POST",GROUPS+"/runs/commands",command,input,200);
        assertThat(first.path("run_id").asText()).isEqualTo(run);assertThat(first.path("input_id").asText()).isEqualTo(input);
        assertThat(first.path("status").asText()).isEqualTo("suspended");assertThat(first.path("replayed").asBoolean()).isFalse();
        String actorRef=jdbc.queryForObject("SELECT actor_ref FROM agent_runtime_execution_supplements WHERE tenant_id=? AND run_id=? AND input_id=?",String.class,tenant,run,input);
        assertThat(actorRef).isNotBlank();
        JsonNode replay=machine(credential,"POST",GROUPS+"/runs/commands",command,input,200);
        for(String field:List.of("group_binding_id","session_id","run_id","input_id","status"))assertThat(replay.path(field)).isEqualTo(first.path(field));
        assertThat(replay.path("replayed").asBoolean()).isTrue();
        rejects(credential,"POST",GROUPS+"/runs/commands",runCommand(group,run,requester.user(),input,"steer","同一输入不得替换正文"),input,409);
        rejects(credential,"POST",GROUPS+"/runs/commands",runCommand(group,run,requester.user(),input,"cancel_execution",null),input,409);
        assertThat(count("SELECT count(*) FROM agent_runtime_execution_supplements WHERE tenant_id=? AND run_id=? AND input_id=?",tenant,run,input)).isEqualTo(1);
        assertThat(waitFact(tenant,run)).isEqualTo(originalWait);
        assertThat(businessCounts()).isEqualTo(before);
        assertThat(requester.provider().requestCount()).isEqualTo(headCalls);assertThat(ordinary.provider().requestCount()).isEqualTo(tailCalls);
        return actorRef;
    }

    /** 独立群中manager定向取消原等待Run；真实终态事件提交后后继才获准入。 */
    private void cancelAndDequeue(JsonNode credential,Account owner,JsonNode scope,Front first,Front second,String marker)throws Exception {
        JsonNode group=machine(credential,"POST",GROUPS+"/ensure",ensure(marker+"-cancel","cancel-life",scope.path("business_scope_id").asText()),marker+":cancel-group",200);
        String id=group.path("group_binding_id").asText();
        machine(credential,"POST",GROUPS+"/members/update",member(id,first.user(),"member","active",0,"cancel-requester",1),marker+":cancel-requester",200);
        machine(credential,"POST",GROUPS+"/members/update",member(id,second.user(),"manager","active",0,"cancel-manager",1),marker+":cancel-manager",200);
        machine(credential,"POST",GROUPS+"/agents/update",agent(id,first.agent(),"active",0,"cancel-first-agent",1),marker+":cancel-first-agent",200);
        machine(credential,"POST",GROUPS+"/agents/update",agent(id,second.agent(),"active",0,"cancel-next-agent",1),marker+":cancel-next-agent",200);
        int firstCalls=first.provider().requestCount(),secondCalls=second.provider().requestCount();
        JsonNode firstSource=machine(credential,"POST",GROUPS+"/messages",message(id,first.user(),"cancel-first-source",1,"保留准确确认等待以验证原运行取消"),marker+":cancel-first-source",200);
        JsonNode firstTrigger=machine(credential,"POST",GROUPS+"/triggers",trigger(id,firstSource,first.user(),first.agent()),marker+":cancel-first-trigger",202);
        String firstRun=awaitAccepted(credential,id,firstTrigger).path("run_id").asText();awaitHumanWait(owner.id(),firstRun);
        assertThat(first.provider().requestCount()).isEqualTo(firstCalls+1);
        JsonNode nextSource=machine(credential,"POST",GROUPS+"/messages",message(id,second.user(),"cancel-next-source",1,"仅在原任务终态后受理后继"),marker+":cancel-next-source",200);
        JsonNode nextTrigger=machine(credential,"POST",GROUPS+"/triggers",trigger(id,nextSource,second.user(),second.agent()),marker+":cancel-next-trigger",202);
        assertThat(triggerDetail(credential,id,nextTrigger).path("status").asText()).isEqualTo("queued");
        assertThat(second.provider().requestCount()).isEqualTo(secondCalls);assertThat(nonTerminalRuns(owner.id(),group.path("session_id").asText())).isEqualTo(1);
        String input=marker+":manager-cancel";var command=runCommand(id,firstRun,second.user(),input,"cancel_execution",null);
        JsonNode cancelled=machine(credential,"POST",GROUPS+"/runs/commands",command,input,200);
        assertThat(cancelled.path("run_id").asText()).isEqualTo(firstRun);assertThat(cancelled.path("status").asText()).isEqualTo("cancelled");
        await().atMost(Duration.ofSeconds(45)).untilAsserted(()->assertThat(count("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND status='CANCELLED' AND phase='FINISHED' AND cancelled AND lease_claim_id IS NULL",owner.id(),firstRun)).isEqualTo(1));
        var cancelledCounts=runCounts(owner.id(),firstRun);
        JsonNode replay=machine(credential,"POST",GROUPS+"/runs/commands",command,input,200);
        assertThat(replay.path("replayed").asBoolean()).isTrue();assertThat(replay.path("run_id")).isEqualTo(cancelled.path("run_id"));
        assertThat(runCounts(owner.id(),firstRun)).isEqualTo(cancelledCounts);
        String nextRun=awaitAccepted(credential,id,nextTrigger).path("run_id").asText();assertThat(nextRun).isNotEqualTo(firstRun);
        awaitHumanWait(owner.id(),nextRun);assertThat(second.provider().requestCount()).isEqualTo(secondCalls+1);
        long cancellation=count("SELECT event_sequence FROM agent_runtime_execution_events WHERE tenant_id=? AND run_id=? AND type='execution.completed'",owner.id(),firstRun);
        long nextAcceptance=count("SELECT event_sequence FROM agent_runtime_execution_events WHERE tenant_id=? AND run_id=? AND type='execution.accepted'",owner.id(),nextRun);
        assertThat(nextAcceptance).isGreaterThan(cancellation);
        assertThat(count("SELECT count(*) FROM nw_group_triggers WHERE tenant_id=? AND group_binding_id=?",Long.parseLong(owner.id()),Long.parseLong(id))).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND session_id=?",owner.id(),group.path("session_id").asText())).isEqualTo(2);
        JsonNode card=humanCard(runDetail(credential,id,nextRun));String replyInput=marker+":cancel-next-response";
        machine(credential,"POST",GROUPS+"/runs/respond",response(id,nextRun,second.user(),card,second.approval(),replyInput),replyInput,200);
        awaitCompleted(owner.id(),nextRun);assertThat(second.provider().requestCount()).isEqualTo(secondCalls+2);
        assertThat(first.provider().requestCount()).isEqualTo(firstCalls+1);
    }

    /** Agent回流只采用原Run正式completed公开事件；完整正文一致才新增来源与pending。 */
    private void assertAgentPublicSource(JsonNode credential,Account owner,JsonNode group,Front original,String run,
            Front other,String otherRun,List<NativeChatOpenAiProvider> providers,String marker)throws Exception {
        String groupId=group.path("group_binding_id").asText();
        var event=jdbc.queryForMap("SELECT event_id,canonical_json FROM agent_runtime_execution_events WHERE tenant_id=? AND session_id=? AND run_id=? AND type='execution.completed' AND schema_id=?",
                owner.id(),group.path("session_id").asText(),run,com.skillforge.runtime.session.AgentExecutionProgram.COMPLETED_SCHEMA);
        JsonNode payload=json.readTree(event.get("canonical_json").toString());
        assertThat(payload.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(payload.path("text").isTextual()).isTrue();
        String text=payload.path("text").asText(),sourceRef=event.get("event_id").toString();
        assertThat(text).isNotBlank();assertThat(sourceRef).isNotBlank();
        var author=Map.of("kind","agent","agent_id",original.agent(),"run_id",run,"source_ref",sourceRef);
        var command=Map.<String,Object>of("group_binding_id",groupId,"source_event_id",marker+":agent-public-source","source_revision",1,
                "author",author,"content",List.of(Map.of("type","text","text",text)));
        var executionBefore=executionCounts();var countsBefore=businessCounts();
        JsonNode publicBefore=machine(credential,"GET",GROUPS+"/history"+historyQuery(groupId,original.user())+"&page_size=200",null,null,200);
        var callsBefore=providers.stream().map(NativeChatOpenAiProvider::requestCount).toList();
        long pendingBefore=count("SELECT count(*) FROM nw_group_memory_extraction_pending WHERE tenant_id=? AND group_binding_id=?",Long.parseLong(owner.id()),Long.parseLong(groupId));
        JsonNode receipt=machine(credential,"POST",GROUPS+"/messages",command,marker+":agent-public-source",200);
        assertThat(receipt.path("session_id")).isEqualTo(group.path("session_id"));
        assertThat(count("SELECT count(*) FROM nw_group_source_messages WHERE tenant_id=? AND group_binding_id=? AND source_message_id=? AND author_kind='agent' AND author_agent_id=? AND author_run_id=? AND source_ref=?",
                Long.parseLong(owner.id()),Long.parseLong(groupId),receipt.path("source_message_id").asText(),Long.parseLong(original.agent()),run,sourceRef)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM nw_group_memory_extraction_pending WHERE tenant_id=? AND group_binding_id=?",Long.parseLong(owner.id()),Long.parseLong(groupId))).isEqualTo(pendingBefore+1);
        assertThat(count("SELECT count(*) FROM nw_group_memory_extraction_pending WHERE tenant_id=? AND group_binding_id=? AND source_message_id=? AND status='pending'",Long.parseLong(owner.id()),Long.parseLong(groupId),receipt.path("source_message_id").asText())).isEqualTo(1);
        assertThat(((Number)businessCounts().get("messages")).longValue()).isEqualTo(((Number)countsBefore.get("messages")).longValue()+1);
        assertThat(businessCounts().get("triggers")).isEqualTo(countsBefore.get("triggers"));
        assertThat(executionCounts()).isEqualTo(executionBefore);
        assertThat(machine(credential,"POST",GROUPS+"/messages",command,marker+":agent-public-source",200)).isEqualTo(receipt);
        JsonNode publicAfter=machine(credential,"GET",GROUPS+"/history"+historyQuery(groupId,original.user())+"&page_size=200",null,null,200);
        assertThat(publicAfter.path("items")).as("Agent来源回流不重复发布既有正式final正文").isEqualTo(publicBefore.path("items"));
        var changedAgent=new LinkedHashMap<>(command);changedAgent.put("source_event_id",marker+":wrong-agent-source");
        changedAgent.put("author",Map.of("kind","agent","agent_id",other.agent(),"run_id",run,"source_ref",sourceRef));
        rejects(credential,"POST",GROUPS+"/messages",changedAgent,marker+":wrong-agent-source",403);
        var changedRun=new LinkedHashMap<>(command);changedRun.put("source_event_id",marker+":wrong-run-source");
        changedRun.put("author",Map.of("kind","agent","agent_id",original.agent(),"run_id",otherRun,"source_ref",sourceRef));
        rejects(credential,"POST",GROUPS+"/messages",changedRun,marker+":wrong-run-source",403);
        var changedContent=new LinkedHashMap<>(command);changedContent.put("source_event_id",marker+":wrong-content-source");
        changedContent.put("content",List.of(Map.of("type","text","text",text+"（篡改）")));
        rejects(credential,"POST",GROUPS+"/messages",changedContent,marker+":wrong-content-source",403);
        assertThat(count("SELECT count(*) FROM nw_group_memory_extraction_pending WHERE tenant_id=? AND group_binding_id=?",Long.parseLong(owner.id()),Long.parseLong(groupId))).isEqualTo(pendingBefore+1);
        assertThat(executionCounts()).isEqualTo(executionBefore);
        assertThat(providers.stream().map(NativeChatOpenAiProvider::requestCount).toList()).isEqualTo(callsBefore);
    }

    /** 当前成员和服务资格分别撤回，新的业务写入不借旧来源或原回执获得权限。 */
    private void assertCurrentQualifications(JsonNode credential,JsonNode other,JsonNode foreign,JsonNode group,JsonNode user,String agent,String marker)throws Exception {
        String id=group.path("group_binding_id").asText();
        JsonNode untriggered=machine(credential,"POST",GROUPS+"/messages",message(id,user,"qualification-source",1,"保留一个尚未触发的真实来源"),marker+":qualification-source",200);
        machine(credential,"POST",GROUPS+"/members/update",member(id,user,"member","left",1,"leave-author",2),marker+":leave-author",200);
        rejects(credential,"POST",GROUPS+"/messages",message(id,user,"after-leave",1,"退出不得继续写群"),marker+":after-leave",403);
        rejects(credential,"POST",GROUPS+"/triggers",trigger(id,untriggered,user,agent),marker+":trigger-after-leave",403);
        machine(credential,"POST",GROUPS+"/members/update",member(id,user,"manager","active",2,"rejoin-author",3),marker+":rejoin-author",200);
        machine(credential,"POST",GROUPS+"/agents/update",agent(id,agent,"removed",1,"remove-service",2),marker+":remove-service",200);
        rejects(credential,"POST",GROUPS+"/triggers",trigger(id,untriggered,user,agent),marker+":trigger-after-remove",403);
        rejects(other,"POST",GROUPS+"/triggers",trigger(id,untriggered,user,agent),marker+":other-trigger",403);
        rejects(foreign,"POST",GROUPS+"/triggers",trigger(id,untriggered,user,agent),marker+":foreign-trigger",403);
        machine(credential,"POST",GROUPS+"/close",Map.of("group_binding_id",id,"expected_revision",1),marker+":close",200);
        rejects(credential,"POST",GROUPS+"/messages",message(id,user,"after-close",1,"已关闭群不能新增来源"),marker+":after-close",409);
        rejects(credential,"POST",GROUPS+"/triggers",trigger(id,untriggered,user,agent),marker+":trigger-after-close",409);
    }

    /** 群scope不能创建个人会话；独立个人凭据仍通过同一准入与Worker完成。 */
    private void personalRegression(JsonNode groupCredential,JsonNode personalCredential,JsonNode user,String agent,NativeChatOpenAiProvider provider,String marker)throws Exception {
        var command=Map.of("external_user_ref",user.path("external_user_ref").asText(),
                "external_conversation_ref",marker+":personal","agent_id",agent,"end_user_id",user.path("end_user_id").asText());
        long sessionsBefore=count("SELECT count(*) FROM nw_chat_sessions");
        rejects(groupCredential,"POST","/api/v1/open-platform/sessions",command,null,403);
        assertThat(count("SELECT count(*) FROM nw_chat_sessions")).isEqualTo(sessionsBefore);
        JsonNode session=machine(personalCredential,"POST","/api/v1/open-platform/sessions",command,null,200);
        JsonNode receipt=machine(personalCredential,"POST","/api/v1/open-platform/sessions/messages",Map.of("session_id",session.path("session_id").asText(),
                "payload",Map.of("content",List.of(Map.of("type","text","text","个人独立会话回归")))),marker+":personal-message",200);
        String run=receipt.path("run_id").asText();assertThat(run).isNotBlank();
        awaitCompleted(personalCredential.path("principal").path("tenant_id").asText(),run);assertThat(provider.requestCount()).isEqualTo(1);
    }

    /** 已收到真实SSE但未完成的原Attempt在杀进程后保持UNKNOWN；不重提输入或调用模型。 */
    private void unknownRecovery(JsonNode credential,Account owner,JsonNode scope,JsonNode user,String agent,NativeChatOpenAiProvider provider,String marker)throws Exception {
        JsonNode group=machine(credential,"POST",GROUPS+"/ensure",ensure(marker+"-uncertain","unknown-life",scope.path("business_scope_id").asText()),marker+":unknown-group",200);
        String id=group.path("group_binding_id").asText();
        machine(credential,"POST",GROUPS+"/members/update",member(id,user,"member","active",0,"unknown-join",1),marker+":unknown-join",200);
        machine(credential,"POST",GROUPS+"/agents/update",agent(id,agent,"active",0,"unknown-agent",1),marker+":unknown-agent",200);
        JsonNode source=machine(credential,"POST",GROUPS+"/messages",message(id,user,"unknown-message",1,"观测原调用未完成响应的恢复"),marker+":unknown-message",200);
        Map<String,Object> command=trigger(id,source,user,agent);
        JsonNode original=machine(credential,"POST",GROUPS+"/triggers",command,marker+":unknown-trigger",202);
        JsonNode current=awaitAccepted(credential,id,original);String run=current.path("run_id").asText();
        await().atMost(Duration.ofSeconds(45)).untilAsserted(()->assertThat(provider.heldStreamStage()).isEqualTo(1));
        Map<String,Object> dispatched=actionAttempt(owner.id(),run);
        assertThat(dispatched.get("result_kind")).isNull();assertThat(dispatched.get("result_ref")).isNull();
        String worker=jdbc.queryForObject("SELECT lease_worker_id FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=?",String.class,owner.id(),run);
        String processPrefix=institutionHost.workerId+":execution:";
        assertThat(worker).as("原派发确实属于即将被杀的机构JVM").startsWith(processPrefix);
        // 正式Worker在配置的进程唯一前缀后追加启动代次；不能把父测试或其他JVM的领取当成本进程派发。
        String generation=worker.substring(processPrefix.length());
        assertThat(java.util.UUID.fromString(generation).toString()).isEqualTo(generation);
        long killedPid=institutionHost.process.pid();killInstitution();provider.abortHeldStream();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(()->assertThat(provider.heldStreamStage()).isEqualTo(-1));
        institutionHost=startHost(false,"unknown-restart");assertThat(institutionHost.process.pid()).isNotEqualTo(killedPid);
        await().atMost(Duration.ofSeconds(45)).untilAsserted(()->assertThat(count("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND status='SUSPENDED' AND phase='UNCERTAIN' AND lease_claim_id IS NULL AND next_due_second IS NULL",owner.id(),run)).isEqualTo(1));
        JsonNode successorSource=machine(credential,"POST",GROUPS+"/messages",message(id,user,"behind-unknown",1,"普通新消息不能越过未确认结果的前台"),marker+":behind-unknown-message",200);
        JsonNode successor=machine(credential,"POST",GROUPS+"/triggers",trigger(id,successorSource,user,agent),marker+":behind-unknown-trigger",202);
        // 连续覆盖唯一Worker的一个完整due兜底周期；每次观察都核原账本与实际外调计数。
        await().during(Duration.ofSeconds(6)).atMost(Duration.ofSeconds(8)).untilAsserted(()->{
            assertThat(provider.requestCount()).isEqualTo(1);
            JsonNode queued=triggerDetail(credential,id,successor);assertThat(queued.path("status").asText()).isEqualTo("queued");assertThat(queued.path("run_id").isNull()).isTrue();
            Map<String,Object> after=actionAttempt(owner.id(),run);
            for(String field:List.of("action_id","attempt_id","request_ref","fingerprint","attempt_request_ref","attempt_fingerprint","result_kind","result_ref","uncertainty_ref","adopted"))
                assertThat(after.get(field)).as("原UNKNOWN字段 %s",field).isEqualTo(dispatched.get(field));
            assertThat(after.get("status")).isEqualTo("UNKNOWN");assertThat(after.get("selected_attempt")).isNull();
            assertThat(count("SELECT count(*) FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=?",owner.id(),run)).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM agent_runtime_execution_events WHERE tenant_id=? AND run_id=? AND type='execution.completed'",owner.id(),run)).isZero();
        });
        assertThat(machine(credential,"POST",GROUPS+"/triggers",command,marker+":unknown-trigger",202)).isEqualTo(original);
        assertThat(triggerDetail(credential,id,original).path("run_id").asText()).isEqualTo(run);
        assertThat(provider.requestCount()).isEqualTo(1);
    }

    /** 强制终止仅测试拥有的机构子进程；等待原PID消失后才建立新JVM。 */
    private void killInstitution()throws Exception {
        assertThat(institutionHost.process.isAlive()).isTrue();institutionHost.process.destroyForcibly();
        assertThat(institutionHost.process.waitFor(10,TimeUnit.SECONDS)).as("原机构进程必须确实退出").isTrue();
    }

    /** 只读精确等待事实旁证，不用SQL内容拼接外部业务响应。 */
    private Map<String,Object> waitFact(String tenant,String run) {
        var rows=jdbc.queryForList("SELECT w.action_id,w.generation,w.source_attempt,w.kind,w.input_id,w.response_ref,w.accepted_revision,w.continuation_attempt,w.consumed_revision FROM agent_runtime_execution_runs r JOIN agent_runtime_execution_waits w ON w.tenant_id=r.tenant_id AND w.run_id=r.run_id AND w.action_id=r.waiting_action_id AND w.generation=r.waiting_generation WHERE r.tenant_id=? AND r.run_id=? AND r.phase='WAITING' AND r.status='SUSPENDED' AND r.lease_claim_id IS NULL AND w.kind='HUMAN' AND w.input_id IS NULL",tenant,run);
        assertThat(rows.size()).isLessThanOrEqualTo(1);return rows.isEmpty()?null:rows.getFirst();
    }
    private Map<String,Object> awaitHumanWait(String tenant,String run) {
        await().atMost(Duration.ofSeconds(45)).untilAsserted(()->assertThat(waitFact(tenant,run)).isNotNull());return waitFact(tenant,run);
    }
    private void awaitCompleted(String tenant,String run) {
        await().atMost(Duration.ofSeconds(45)).untilAsserted(()->assertThat(count("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND status='COMPLETED' AND phase='FINISHED' AND lease_claim_id IS NULL",tenant,run)).isEqualTo(1));
    }
    private long nonTerminalRuns(String tenant,String session) {return count("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND session_id=? AND status NOT IN ('COMPLETED','FAILED','CANCELLED')",tenant,session);}
    private long count(String sql,Object... arguments) {return jdbc.queryForObject(sql,Long.class,arguments);}
    private Map<String,Object> actionAttempt(String tenant,String run) {
        var rows=jdbc.queryForList("SELECT a.action_id,a.status,a.selected_attempt,a.request_ref,a.fingerprint,t.attempt_id,t.request_ref AS attempt_request_ref,t.fingerprint AS attempt_fingerprint,t.result_kind,t.result_ref,t.uncertainty_ref,t.adopted FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.run_id=a.run_id AND t.action_id=a.action_id AND t.attempt_id=a.current_attempt WHERE a.tenant_id=? AND a.run_id=?",tenant,run);
        assertThat(rows).hasSize(1);return rows.getFirst();
    }
    private Map<String,Object> executionCounts() {return jdbc.queryForMap("SELECT (SELECT count(*) FROM agent_runtime_execution_runs) AS runs,(SELECT count(*) FROM agent_runtime_execution_actions) AS actions,(SELECT count(*) FROM agent_runtime_execution_attempts) AS attempts");}
    private Map<String,Object> runCounts(String tenant,String run) {return jdbc.queryForMap("SELECT (SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=?) AS actions,(SELECT count(*) FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=?) AS attempts",tenant,run,tenant,run);}
    private Map<String,Object> businessCounts() {return jdbc.queryForMap("SELECT (SELECT count(*) FROM nw_group_source_messages) AS messages,(SELECT count(*) FROM nw_group_triggers) AS triggers,(SELECT count(*) FROM agent_runtime_execution_runs) AS runs,(SELECT count(*) FROM agent_runtime_execution_actions) AS actions,(SELECT count(*) FROM agent_runtime_execution_attempts) AS attempts");}
    private void rejects(JsonNode credential,String method,String path,Object command,String key,int expected)throws Exception {
        var before=businessCounts();machine(credential,method,path,command,key,expected);assertThat(businessCounts()).isEqualTo(before);
    }

    /** 群来源在record时提交；触发只引用其身份且保留真实作者=requester。 */
    private Map<String,Object> message(String group,JsonNode user,String event,long revision,String text) {return Map.of("group_binding_id",group,"source_event_id",event,"source_revision",revision,"author",Map.of("kind","end_user","end_user_id",user.path("end_user_id").asText()),"content",List.of(Map.of("type","text","text",text)));}
    private Map<String,Object> trigger(String group,JsonNode message,JsonNode requester,String agent) {return Map.of("group_binding_id",group,"source_message_id",message.path("source_message_id").asText(),"requester_end_user_id",requester.path("end_user_id").asText(),"agent_id",agent);}
    private JsonNode triggerDetail(JsonNode credential,String group,JsonNode receipt)throws Exception {return machine(credential,"GET",GROUPS+"/triggers/detail?group_binding_id="+group+"&trigger_id="+receipt.path("trigger_id").asText(),null,null,200);}
    private JsonNode awaitAccepted(JsonNode credential,String group,JsonNode receipt)throws Exception {
        await().atMost(Duration.ofSeconds(45)).untilAsserted(()->{var current=triggerDetail(credential,group,receipt);assertThat(current.path("status").asText()).isEqualTo("accepted");assertThat(current.path("run_id").isTextual()).isTrue();});return triggerDetail(credential,group,receipt);
    }
    private JsonNode runDetail(JsonNode credential,String group,String run)throws Exception {return machine(credential,"GET",GROUPS+"/runs/detail?group_binding_id="+group+"&run_id="+java.net.URLEncoder.encode(run,StandardCharsets.UTF_8),null,null,200);}
    /** 从正式公开卡原样取准确等待，不查询数据库来补造响应身份。 */
    private JsonNode humanCard(JsonNode detail) {
        var cards=new ArrayList<JsonNode>();for(var card:detail.path("pending_interactions"))if("user_input".equals(card.path("interaction_type").asText()))cards.add(card);
        assertThat(cards).hasSize(1);return cards.getFirst();
    }
    private Map<String,Object> response(String group,String run,JsonNode user,JsonNode card,String text,String input) {
        return Map.of("group_binding_id",group,"run_id",run,"input_id",input,"responder_end_user_id",user.path("end_user_id").asText(),"hitl_response",Map.of("execution_id",run,"run_revision",card.path("run_revision").asText(),"human_input",Map.of("wait",Map.of("action_id",card.path("tool_wait").path("action_id").asText(),"generation",card.path("tool_wait").path("generation").asText()),"text",text)));
    }
    private Map<String,Object> runCommand(String group,String run,JsonNode user,String input,String action,String text) {
        var command=new LinkedHashMap<String,Object>();command.put("group_binding_id",group);command.put("run_id",run);command.put("input_id",input);
        command.put("operator_end_user_id",user.path("end_user_id").asText());command.put("action",action);if(text!=null)command.put("text",text);return command;
    }
    /** 原消息、触发和实例对应关系用于并发测试；没有凭据或模拟执行权。 */
    private record Front(JsonNode message,JsonNode receipt,JsonNode user,String agent,NativeChatOpenAiProvider provider,String approval) { }

    /** 首次稳定键明确包含来源生命周期与已登记业务范围。 */
    private Map<String, Object> ensure(String external, String lifecycle, String scope) {
        return Map.of("namespace", "group-fixture", "external_group_ref", external, "lifecycle_ref", lifecycle, "business_scope_id", scope);
    }
    /** CAS与来源顺序独立；退出不改变EndUser或共享Session身份。 */
    private Map<String, Object> member(String group, JsonNode user, String role, String status, long expected, String event, long source) {
        return Map.of("group_binding_id", group, "end_user_id", user.path("end_user_id").asText(), "role", role,
                "status", status, "expected_revision", expected, "source_event_id", event, "source_revision", source);
    }
    /** 服务名单只表达当前群资格，不冻结新Run或准备公共工具。 */
    private Map<String, Object> agent(String group, String agent, String status, long expected, String event, long source) {
        return Map.of("group_binding_id", group, "agent_id", agent, "status", status,
                "expected_revision", expected, "source_event_id", event, "source_revision", source);
    }
    private JsonNode scope(JsonNode credential, String external, String key) throws Exception {
        return machine(credential, "POST", "/api/v1/open-platform/business-scopes/ensure", Map.of(
                "scope_type", "store", "external_ref", external, "display_name", external), key, 200);
    }
    private JsonNode user(JsonNode credential, String external) throws Exception {
        return machine(credential, "POST", "/api/v1/open-platform/end-users/ensure", Map.of("external_user_ref", external, "display_name", external), null, 200);
    }
    /** 外部integration由正式平台关联建立，不在群请求中创建或切换。 */
    private String association(Account tenant, String namespace, String external) throws Exception {
        return platform("POST", "/api/v1/platform/institutions/associate", Map.of("tenant_id", tenant.id(),
                "namespace", namespace, "external_institution_ref", external), namespace + ":associate:" + external, 200).path("integration_id").asText();
    }
    private JsonNode credential(Account tenant, String integration, List<String> scopes, String key) throws Exception {
        return platform("POST", "/api/v1/platform/institution-credentials/issue", Map.of("tenant_id", tenant.id(), "integration_id", integration, "scopes", scopes), key, 200);
    }
    /** 原生机构Agent经真实创建和发布，不借群服务名单产生源grant。 */
    private String nativeAgent(Account tenant, String model, String key) throws Exception {
        JsonNode created = tenant(tenant, "POST", "/api/v1/agents", Map.of("agent_key", key, "name", key,
                "system_prompt", "本地机构助手", "model_config_id", model, "config", Map.of()), null, 200);
        String id = created.path("id").asText();
        tenant(tenant, "POST", "/api/v1/agents/publish", Map.of("agent_id", id), null, 200); return id;
    }
    /** 公共源只登记可运行发布元数据；没有模型或业务工具调用。 */
    private JsonNode sharedSource(String model, String marker) throws Exception {
        JsonNode created = platform("POST", "/api/v1/platform/agent-definitions", Map.of("name", marker + ":source", "price_type", "free",
                "price_cents", 0, "distribution_policy", "AUTHORIZED_DEPLOY", "definition", Map.of("system_prompt", "公共机构助手",
                        "role_description", "群服务角色", "model_config_id", model, "execution_config", Map.of(), "capabilities", List.of(),
                        "resource_slots", List.of(), "delegation_slots", List.of())), marker + ":source", 200);
        return platform("POST", "/api/v1/platform/agent-definitions/publish", Map.of("definition_id", created.path("definition_id").asText(),
                "expected_revision", created.path("draft_revision").asLong()), marker + ":publish", 200);
    }
    private JsonNode managedAgent(Account tenant, JsonNode source, JsonNode grant, String integration, String key) throws Exception {
        var command = new LinkedHashMap<String, Object>(); command.put("definition_id", source.path("definition_id").asText());
        command.put("grant_id", grant.path("grant_id").asText()); command.put("integration_id", integration); command.put("external_agent_ref", key);
        command.put("resource_bindings", List.of()); command.put("delegation_bindings", List.of()); command.put("parameters", Map.of());
        return tenant(tenant, "POST", "/api/v1/tenant/agent-deployments", command, key, 200);
    }
    /** 每次重试使用当前秘密签实际方法、完整请求目标和相同业务正文。 */
    private JsonNode machine(JsonNode credential, String method, String path, Object value, String key, int expected) throws Exception {
        return success(signed(credential, method, path, value, key), method, path, expected);
    }
    private Reply signed(JsonNode credential, String method, String path, Object value, String key) throws Exception {
        String body = value == null ? "" : json.writeValueAsString(value), timestamp = Long.toString(Instant.now().getEpochSecond());
        String signature = HmacSignatureVerifier.sign(credential.path("signing_secret").asText(), HmacSignatureVerifier.canonical(timestamp, method, path, body));
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + institutionHost.port + path)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json").header("X-Api-Key", credential.path("api_key").asText()).header("X-Timestamp", timestamp).header("X-Signature", signature);
        if (key != null) builder.header("Idempotency-Key", key);
        var response = http.send(builder.method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), json.readTree(response.body()));
    }


    /** 只向本测试拥有的loopback Provider登记模型，真实生产适配器负责所有调用。 */
    private String model(Account tenant,String endpoint,String marker) {
        TenantContext.setTenantId(tenant.id());
        try {return PlatformModelFixtures.selectLlm(models,tenant.id(),endpoint,marker+"-model","synthetic-unused-key","custom","custom-openai-compatible","custom-v1");}
        finally {TenantContext.clear();}
    }

    /** 创建free机构与本地管理员，完整八项quota由正式当前覆盖读取后只改本fixture所需数量。 */
    private Account account(String marker,String name)throws Exception {
        String slug=marker+"-"+name;
        JsonNode created=platform("POST","/api/v1/admin/tenants",Map.of("tenant_name",name,"tenant_slug",slug,"plan_code","free",
                "admin_username",USERNAME,"admin_password",PASSWORD,"admin_email",name+"@example.test"),null,201);
        Account account=login(new Account(created.path("id").asText(),slug,null));
        JsonNode quota=platform("GET","/api/v1/admin/foundation/catalog/tenant-entitlements?tenant_id="+account.id(),null,null,200);
        ObjectNode overrides=((ObjectNode)quota.path("overrides")).deepCopy();
        assertThat(overrides.size()).isEqualTo(8);overrides.put("agents",8);overrides.put("sub_institutions",8);
        platform("PUT","/api/v1/admin/foundation/catalog/tenant-quota",Map.of("tenant_id",account.id(),"limits",overrides,
                "expected_revision",quota.path("revision").asLong()),null,200);
        return account;
    }

    /** 原生登录重新取得安全会话，不将平台JWT或其他Tenant身份传入机构realm。 */
    private Account login(Account account)throws Exception {
        JsonNode result=success(exchange(institutionHost,null,"POST","/api/v1/auth/login",Map.of("tenant_slug",account.slug(),"username",USERNAME,"password",PASSWORD),null),"POST","tenant login",200);
        assertThat(result.path("tenant_id").asText()).isEqualTo(account.id());
        assertThat(result.path("access_token").asText()).isNotBlank();
        return new Account(account.id(),account.slug(),result.path("access_token").asText());
    }


    /** 机构请求只携带当前账号的JWT，expected在唯一封装中校验。 */
    private JsonNode tenant(Account account,String method,String path,Object body,String key,int expected)throws Exception {
        return success(exchange(institutionHost,"Bearer "+account.token(),method,path,body,key),method,path,expected);
    }

    /** 平台realm使用独立JWT头，不能作为机构请求身份。 */
    private JsonNode platform(String method,String path,Object body,String key,int expected)throws Exception {
        return success(exchange(platformHost,"Bearer "+platformToken,method,path,body,key),method,path,expected);
    }

    /** 原始HTTP保留JSON线格式；异常和断言不打印请求体、JWT或秘密。 */
    private Reply exchange(Host host,String token,String method,String path,Object value,String key)throws Exception {
        String body=value==null?"":json.writeValueAsString(value);
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+host.port+path)).timeout(Duration.ofSeconds(20))
                .header("Content-Type","application/json").header("Accept","application/json");
        if(token!=null)builder.header(host.platform?"X-Platform-Token":"Authorization",token);if(key!=null)builder.header("Idempotency-Key",key);
        var response=http.send(builder.method(method,body.isEmpty()?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build(),HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(),json.readTree(response.body()));
    }

    /** 错误只报告Host异常边界的公开字段；成功凭据data、请求体和秘密不进入诊断。 */
    private JsonNode success(Reply response,String method,String path,int expected) {
        if(response.status()>=400)httpErrors.add(Map.of("method",method,"path",path,"status",response.status(),"expected_status",expected,
                "code",response.body().path("code").asText()));
        if(response.status()>=400)assertThat(response.status()).as("%s %s code=%s message=%s field_errors=%s",method,path,
                response.body().path("code").asText(),response.body().path("message").asText(),response.body().path("field_errors")).isEqualTo(expected);
        else assertThat(response.status()).as("%s %s",method,path).isEqualTo(expected);
        return response.body().path("data");
    }

    /** 两个正式bootJar共用当前私有PG/Redis，端口由OS原子分配，重启不重建业务库。 */
    private void startHosts(String label)throws Exception {
        platformHost=startHost(true,label);institutionHost=startHost(false,label);
        platformToken=success(exchange(platformHost,null,"POST","/api/v1/platform/auth/login",Map.of(
                "username","group-message-platform-admin","password","SyntheticPlatform1234"),null),"POST","platform login",200).path("access_token").asText();
        assertThat(platformToken).isNotBlank();
    }

    /** 迁移owner只准备独立测试角色；两个实际宿主分别使用机构RLS及平台BYPASSRLS角色。 */
    private void prepareDatabaseRoles() {
        assertThat(jdbc.queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname=current_user",Boolean.class))
                .as("本套件由私有Testcontainers迁移owner创建宿主角色").isTrue();
        String suffix=Long.toString(SnowflakeIds.nextId());
        tenantDatabaseUser="gi03_tenant_"+suffix;platformDatabaseUser="gi03_platform_"+suffix;
        hostDatabasePassword=java.util.UUID.randomUUID().toString();
        jdbc.execute("CREATE ROLE "+tenantDatabaseUser+" LOGIN PASSWORD '"+hostDatabasePassword+"' NOSUPERUSER NOBYPASSRLS");
        jdbc.execute("CREATE ROLE "+platformDatabaseUser+" LOGIN PASSWORD '"+hostDatabasePassword+"' NOSUPERUSER BYPASSRLS");
        for(String role:List.of(tenantDatabaseUser,platformDatabaseUser)) {
            jdbc.execute("GRANT USAGE ON SCHEMA public TO "+role);
            jdbc.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO "+role);
            jdbc.execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO "+role);
        }
        jdbc.execute("GRANT EXECUTE ON FUNCTION public.resolve_runtime_authentication_tenant(text) TO "+tenantDatabaseUser);
        // V319 的用量 RLS 会调用总部许可谓词；执行权限不等于获得总部目标读取许可。
        jdbc.execute("GRANT EXECUTE ON FUNCTION public.headquarters_usage_target_allowed(bigint) TO "+tenantDatabaseUser);
        jdbc.execute("GRANT EXECUTE ON FUNCTION public.publish_committed_usage(integer) TO "+tenantDatabaseUser);
        assertThat(jdbc.queryForObject("SELECT has_function_privilege(CAST(? AS name), 'public.resolve_runtime_authentication_tenant(text)', 'EXECUTE')", Boolean.class,tenantDatabaseUser)).isTrue();
        // V69认证解析器收回PUBLIC执行权；自有机构角色仅获这条正式认证函数，不提升普通数据访问。
        jdbc.execute("GRANT EXECUTE ON FUNCTION public.resolve_service_principal_by_api_key_hash(text) TO "+tenantDatabaseUser);
        assertThat(jdbc.queryForObject("SELECT has_function_privilege(CAST(? AS name), 'public.resolve_service_principal_by_api_key_hash(text)', 'EXECUTE')",
                Boolean.class,tenantDatabaseUser)).as("机构角色必须能调用唯一SP认证解析器").isTrue();
    }

    /** 只附加一份无TestConfiguration依赖的配置class，不把测试目录加入正式组件扫描。 */
    private Path modelCatalogFixtureJar()throws Exception {
        if(modelCatalogFixtureJar!=null)return modelCatalogFixtureJar;
        Path fixture=evidence.resolve("deterministic-model-catalog.jar");
        String resource=GroupMessageModelCatalogConfiguration.class.getName().replace('.','/')+".class";
        try(var bytecode=GroupMessageModelCatalogConfiguration.class.getClassLoader().getResourceAsStream(resource);
            var jar=new JarOutputStream(Files.newOutputStream(fixture))) {
            if(bytecode==null)throw new IllegalStateException("group_message_fixture_configuration_missing");
            jar.putNextEntry(new JarEntry(resource));bytecode.transferTo(jar);jar.closeEntry();
        }
        modelCatalogFixtureJar=fixture;return fixture;
    }

    /** 获取进程后立即登记清理；显式夹具目录、监听日志与真实HTTP共同ready后返回。 */
    private Host startHost(boolean platform,String label)throws Exception {
        Path root=repositoryRoot();String module=platform?"skillforge-platform-service":"skillforge-institution-service";
        Path jar=root.resolve("backend-java/"+module+"/build/libs/"+module+".jar");assertThat(jar).exists();
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java");
        Path log=evidence.resolve((platform?"platform-":"institution-")+label+".log");
        String workerId="gi03-"+SnowflakeIds.nextId();
        var builder=new ProcessBuilder(java.toString(),"-Dfile.encoding=UTF-8","-Dstdout.encoding=UTF-8","-Dstderr.encoding=UTF-8",
                "-Dloader.path="+modelCatalogFixtureJar().toUri(),
                "-Dloader.main="+(platform?"com.skillforge.platform.PlatformApplication":"com.skillforge.institution.InstitutionApplication"),
                "-cp",jar.toString(),"org.springframework.boot.loader.launch.PropertiesLauncher",
                "--spring.main.sources="+GroupMessageModelCatalogConfiguration.class.getName(),"--server.port=0","--server.address=127.0.0.1",
                "--agent-runtime.workers.auto-startup="+!platform,"--agent-runtime.worker-id="+workerId,
                "--agent-runtime.worker-lease-duration=30s",
                "--spring.flyway.enabled=false",
                "--spring.cloud.nacos.discovery.enabled=false","--spring.cloud.nacos.config.enabled=false");
        builder.directory(root.resolve("backend-java/"+module).toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        var env=builder.environment();env.put("SPRING_DATASOURCE_URL",environment.getRequiredProperty("spring.datasource.url"));
        env.put("SPRING_DATASOURCE_USERNAME",tenantDatabaseUser);env.put("SPRING_DATASOURCE_PASSWORD",hostDatabasePassword);
        env.put("PLATFORM_DATASOURCE_USERNAME",platformDatabaseUser);env.put("PLATFORM_DATASOURCE_PASSWORD",hostDatabasePassword);
        env.put("REDIS_HOST",environment.getRequiredProperty("spring.data.redis.host"));env.put("REDIS_PORT",environment.getRequiredProperty("spring.data.redis.port"));
        env.put("REDIS_DATABASE",environment.getProperty("spring.data.redis.database","0"));
        env.put("SECRET_KEY",properties.getSecretKey());env.put("PLATFORM_SECRET_KEY",TEST_PLATFORM_JWT_SECRET);
        env.put("PLATFORM_ADMIN_USERNAME","group-message-platform-admin");env.put("PLATFORM_ADMIN_PASSWORD","SyntheticPlatform1234");
        Host host=new Host(builder.start(),log,platform,workerId);ownedHosts.add(host);
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500))
                .failFast("宿主在ready前已退出，原诊断日志 "+host.log,()->!host.process.isAlive()).ignoreExceptions().untilAsserted(()->{
            assertThat(host.process.isAlive()).as("宿主应存活，诊断日志 %s",host.log).isTrue();
            // 正在追加的末尾UTF-8字符可能尚未完整写入；只匹配完整ASCII监听事件，不把日志切片当解码失败。
            String startup=new String(Files.readAllBytes(log),StandardCharsets.UTF_8);
            assertThat(startup).as("owned宿主必须显式加载与父测试相同的能力目录").contains(GroupMessageModelCatalogConfiguration.ACTIVATION_MARKER);
            var match=LISTENING.matcher(startup);assertThat(match.find()).as("宿主必须报告实际监听端口").isTrue();
            host.port=Integer.parseInt(match.group(1));
            var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+host.port+"/v3/api-docs")).timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.discarding());
            assertThat(response.statusCode()).isEqualTo(200);
        });
        return host;
    }

    /** 即使一个进程清理失败仍关闭其他owned宿主，并等待进程静止。 */
    private void stopHosts()throws Exception {
        Throwable failure=null;
        for(int i=ownedHosts.size()-1;i>=0;i--) {
            Host host=ownedHosts.get(i);
            try {host.process.destroy();if(!host.process.waitFor(10,TimeUnit.SECONDS)){host.process.destroyForcibly();assertThat(host.process.waitFor(10,TimeUnit.SECONDS)).as("宿主须完成退出 %s",host.log).isTrue();}}
            catch(Throwable problem){if(failure==null)failure=problem;else failure.addSuppressed(problem);}
        }
        ownedHosts.clear();if(failure instanceof Error error)throw error;if(failure instanceof Exception problem)throw problem;
    }

    /** 从模块或仓库目录定位已构建的正式产物，不依赖机器绝对工作目录。 */
    private static Path repositoryRoot() {
        Path root=Path.of("").toAbsolutePath();while(root!=null&&!Files.exists(root.resolve("settings.gradle.kts"))&&!Files.isDirectory(root.resolve(".git")))root=root.getParent();
        if(root!=null&&Files.exists(root.resolve("settings.gradle.kts")))root=root.getParent();if(root==null)throw new IllegalStateException("repository_root_missing");return root;
    }

    /** 测试内的真实普通机构和本地安全会话；toString不输出JWT。 */
    private record Account(String id,String slug,String token) { @Override public String toString(){return "Tenant "+id;} }
    /** 测试拥有的OS宿主、日志和原子分配后的监听端口。 */
    private static final class Host {
        private final Process process;private final Path log;private final boolean platform;private final String workerId;private int port;
        /** 进程启动即持有其回收责任，port只在监听事件后赋值。 */
        private Host(Process process,Path log,boolean platform,String workerId){this.process=process;this.log=log;this.platform=platform;this.workerId=workerId;}
    }
    /** 原始响应只供expected校验，不输出响应正文或一次秘密。 */
    private record Reply(int status,JsonNode body) { @Override public String toString(){return "HTTP "+status;} }
}
