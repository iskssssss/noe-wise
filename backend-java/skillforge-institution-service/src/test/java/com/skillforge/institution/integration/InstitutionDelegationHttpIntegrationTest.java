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
import com.skillforge.core.billing.export.PlatformUsageContracts;
import com.skillforge.core.chat.contract.ChatContent;
import com.skillforge.testing.groupmessages.GroupMessageModelCatalogConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** GI-16正式HTTP组合：机构所有权、逐级授权、目标管理、稳定部署和双宿主重启回执。 */
@Import(GroupMessageModelCatalogConfiguration.class)
class InstitutionDelegationHttpIntegrationTest extends AbstractIntegrationTest {
    private static final String DEFINITIONS="/api/v1/tenant/agent-definitions";
    private static final String GRANTS="/api/v1/tenant/agent-grants";
    private static final String DEPLOYMENTS="/api/v1/tenant/agent-deployments";
    private static final String MANAGEMENT="/api/v1/platform/institution-management-grants";
    private static final String USERNAME="delegation-admin",PASSWORD="DelegationFixture1234";
    private static final Pattern LISTENING=Pattern.compile("Tomcat started on port (\\d+)");
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformModelCatalogService models;
    @Autowired private SkillForgeProperties properties;
    @Autowired private org.springframework.core.env.Environment environment;
    @Autowired private com.skillforge.runtime.session.spi.ExecutionStore executionStore;
    @Autowired private com.skillforge.institution.runtime.AgentRunBindings runBindings;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<Host> ownedHosts=new ArrayList<>();
    private Host platformHost,institutionHost;
    private String platformToken;
    private Path evidence;
    private Path modelCatalogFixtureJar;
    private String tenantDatabaseUser,platformDatabaseUser,hostDatabasePassword;
    private boolean enableInstitutionWorker;

    /** 只登记模型目录元数据，关闭Runtime worker，不启动Run或真实外调。 */
    @DynamicPropertySource static void workers(DynamicPropertyRegistry registry) {
        registry.add("agent-runtime.workers.auto-startup",()->"false");
    }

    /** 一组私有拓扑贯穿真实API和持久恢复，不借旧环境或其他用例的业务数据。 */
    @Test @Timeout(480)
    void institutionSourcesDelegationAndExplicitAuthorizationSurviveRevocationMigrationAndRestart() throws Exception {
        String marker="delegation-"+SnowflakeIds.nextId();
        evidence=repositoryRoot().resolve("backend-java/skillforge-institution-service/build/reports/institution-delegation/"+marker);
        Files.createDirectories(evidence);
        Throwable failure=null;
        try {
            prepareDatabaseRoles();
            startHosts("first");
            Account h1=account(marker,"h1"),c1=account(marker,"c1"),c2=account(marker,"c2");
            Account c11=account(marker,"c11"),h2=account(marker,"h2"),c3=account(marker,"c3");
            JsonNode originalRelation=relation("attach",c1,h1,1,marker+":attach-c1");
            relation("attach",c2,h1,1,marker+":attach-c2");
            relation("attach",c11,c1,1,marker+":attach-c11");
            relation("attach",c3,h2,1,marker+":attach-c3");
            String model=model(h1,marker);
            JsonNode source=source(h1,marker+":source",model);
            String definition=source.path("definition_id").asText();
            assertThat(definition).matches("[1-9][0-9]*");
            verifiesOwnerAndInstallBoundaries(h2,c11,source,marker);

            String parentExpiry=Instant.now().plus(2,ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS).toString();
            String childExpiry=Instant.now().plus(1,ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS).toString();
            Map<String,Object> originalGrant=grantCommand(definition,c1,null,List.of("deploy","execute"),parentExpiry,true,2);
            JsonNode root=tenant(h1,"POST",GRANTS,originalGrant,marker+":root-grant",200);
            assertThat(root.path("owner_tenant_id").asText()).isEqualTo(h1.id());
            assertThat(root.path("parent_grant_id").isNull()).isTrue();
            Map<String,Object> originalLeaf=grantCommand(definition,c11,root,List.of("deploy","execute"),childExpiry,true,1);
            JsonNode leaf=tenant(c1,"POST",GRANTS,originalLeaf,marker+":leaf-grant",200);
            assertThat(leaf.path("parent_grant_id").asText()).isEqualTo(root.path("grant_id").asText());
            assertThat(leaf.path("parent_grant_revision").asLong()).isEqualTo(root.path("revision").asLong());
            verifiesRejectedDelegation(h1,h2,c1,c11,c3,definition,root,parentExpiry,childExpiry,marker);

            Map<String,Object> c1Deploy=deployment(definition,root,"clinic-c1", "C1");
            JsonNode first=tenant(c1,"POST",DEPLOYMENTS,c1Deploy,marker+":deploy-c1",200);
            Map<String,Object> originalDeployment=deployment(definition,leaf,"clinic-c11","C11");
            JsonNode original=tenant(c11,"POST",DEPLOYMENTS,originalDeployment,marker+":deploy-c11",200);
            assertThat(original.path("ready").asBoolean()).isTrue();
            assertThat(original.path("agent_id").asText()).isNotEqualTo(first.path("agent_id").asText());
            assertThat(original.path("grant_chain").path("hops").findValuesAsText("grant_id"))
                    .containsExactly(root.path("grant_id").asText(),leaf.path("grant_id").asText());
            assertThat(original.path("parameters").path("clinic").asText()).isEqualTo("C11");
            var beforeForeign=businessFacts();
            tenant(c3,"GET",DEPLOYMENTS+"/detail?deployment_id="+original.path("deployment_id").asText(),null,null,404);
            assertThat(businessFacts()).as("跨机构读取拒绝不写入实例、授权或业务事实").isEqualTo(beforeForeign);
            verifiesHeadquartersMetadataOnly(h1,c2,c3,definition,parentExpiry,marker);

            JsonNode revoked=tenant(h1,"POST",GRANTS+"/revoke",Map.of("grant_id",root.path("grant_id").asText(),
                    "expected_revision",root.path("revision").asLong()),marker+":revoke-root",200);
            assertThat(revoked.path("status").asText()).isEqualTo("revoked");
            assertUnavailable(c11,original);
            JsonNode oldGrant=tenant(h1,"POST",GRANTS,originalGrant,marker+":root-grant",200);
            assertThat(oldGrant.path("grant_id")).isEqualTo(root.path("grant_id"));
            assertThat(oldGrant.path("status").asText()).as("旧创建键读当前撤销事实，不能复活").isEqualTo("revoked");
            var beforeRevoked=businessFacts();
            tenant(c11,"PUT",DEPLOYMENTS+"/bindings",bindings(original,1,"C11"),marker+":revoked-bind",403);
            assertThat(businessFacts()).isEqualTo(beforeRevoked);
            JsonNode replacementRoot=tenant(h1,"POST",GRANTS,originalGrant,marker+":replacement-root",200);
            JsonNode replacementLeaf=tenant(c1,"POST",GRANTS,grantCommand(definition,c11,replacementRoot,
                    List.of("deploy","execute"),childExpiry,true,1),marker+":replacement-leaf",200);
            assertThat(replacementLeaf.path("grant_id")).isNotEqualTo(leaf.path("grant_id"));
            assertUnavailable(c11,original);
            assertThat(tenant(c11,"POST",DEPLOYMENTS,originalDeployment,marker+":deploy-c11",200))
                    .as("普通部署重试只返回首次创建回执，不切换当前实例授权").isEqualTo(original);
            assertUnavailable(c11,original);

            Map<String,Object> switchCommand=authorization(original,1,replacementLeaf);
            JsonNode switched=tenant(c11,"POST",DEPLOYMENTS+"/authorization",switchCommand,marker+":switch",200);
            assertStable(original,switched,2,"C11");
            assertThat(switched.path("grant_id")).isEqualTo(replacementLeaf.path("grant_id"));
            assertThat(tenant(c11,"POST",DEPLOYMENTS+"/authorization",switchCommand,marker+":switch",200)).isEqualTo(switched);
            JsonNode rebound=tenant(c11,"PUT",DEPLOYMENTS+"/bindings",bindings(original,2,"C11-local"),marker+":bind-after-switch",200);
            assertStable(original,rebound,3,"C11-local");
            assertThat(tenant(c11,"POST",DEPLOYMENTS+"/authorization",switchCommand,marker+":switch",200)).isEqualTo(switched);
            assertStable(original,detail(c11,original),3,"C11-local");
            var beforeCas=businessFacts();
            tenant(c11,"POST",DEPLOYMENTS+"/authorization",authorization(original,3,leaf),marker+":switch",409);
            tenant(c11,"POST",DEPLOYMENTS+"/authorization",switchCommand,marker+":stale-switch",409);
            assertThat(businessFacts()).isEqualTo(beforeCas);
            JsonNode otherSource=source(h1,marker+":other-source",model);
            JsonNode otherRoot=tenant(h1,"POST",GRANTS,grantCommand(otherSource.path("definition_id").asText(),c1,null,
                    List.of("deploy","execute"),parentExpiry,true,2),marker+":other-root",200);
            JsonNode otherLeaf=tenant(c1,"POST",GRANTS,grantCommand(otherSource.path("definition_id").asText(),c11,otherRoot,
                    List.of("deploy","execute"),childExpiry,false,0),marker+":other-leaf",200);
            var beforeCrossSource=businessFacts();
            tenant(c11,"POST",DEPLOYMENTS+"/authorization",authorization(original,3,otherLeaf),marker+":cross-source",403);
            assertThat(businessFacts()).isEqualTo(beforeCrossSource);

            JsonNode moved=relation("move",c1,h2,originalRelation.path("revision").asLong(),marker+":move-c1");
            assertUnavailable(c11,original);
            relation("move",c1,h1,moved.path("revision").asLong(),marker+":return-c1");
            assertUnavailable(c11,original);
            JsonNode finalRoot=tenant(h1,"POST",GRANTS,originalGrant,marker+":post-return-root",200);
            JsonNode finalLeaf=tenant(c1,"POST",GRANTS,grantCommand(definition,c11,finalRoot,
                    List.of("deploy","execute"),childExpiry,false,0),marker+":post-return-leaf",200);
            assertUnavailable(c11,original);
            JsonNode repaired=tenant(c11,"POST",DEPLOYMENTS+"/authorization",authorization(original,3,finalLeaf),marker+":repair",200);
            assertStable(original,repaired,4,"C11-local");

            stopHosts();startHosts("restarted");
            h1=login(h1);c1=login(c1);c11=login(c11);
            assertThat(tenant(h1,"POST",DEFINITIONS+"/publish",Map.of("definition_id",definition,
                    "expected_revision",source.path("draft_revision").asLong()),marker+":source:publish",200)).isEqualTo(source);
            assertThat(tenant(h1,"POST",GRANTS,originalGrant,marker+":root-grant",200).path("status").asText()).isEqualTo("revoked");
            assertThat(tenant(c11,"POST",DEPLOYMENTS,originalDeployment,marker+":deploy-c11",200)).isEqualTo(original);
            assertThat(tenant(c11,"POST",DEPLOYMENTS+"/authorization",switchCommand,marker+":switch",200)).isEqualTo(switched);
            assertStable(original,detail(c11,original),4,"C11-local");
            assertThat(platform("POST","/api/v1/platform/institution-relations/attach",relationCommand(c1,h1,1),marker+":attach-c1",200))
                    .as("双宿主重启仍返回原关系回执，不能覆盖已变更关系").isEqualTo(originalRelation);
        } catch(Exception|Error problem) {failure=problem;throw problem;}
        finally {
            try {stopHosts();}catch(Throwable cleanup){if(failure!=null)failure.addSuppressed(cleanup);else if(cleanup instanceof Error error)throw error;else throw (Exception)cleanup;}
            finally {TenantContext.clear();RequestContext.clear();}
        }
    }

    @Test @Timeout(240)
    void unsavedDefinitionPreviewIsReadOnlyAndRespectsOwnerAndRevision() throws Exception {
        String marker="definition-preview-"+SnowflakeIds.nextId();
        evidence=repositoryRoot().resolve("backend-java/skillforge-institution-service/build/reports/institution-delegation/"+marker);
        Files.createDirectories(evidence);
        Throwable failure=null;
        try {
            prepareDatabaseRoles();
            startHosts("preview");
            Account owner=account(marker,"owner"), foreign=account(marker,"foreign");
            String model=model(owner,marker);
            JsonNode published=source(owner,marker+":source",model);
            String id=published.path("definition_id").asText();
            JsonNode before=tenant(owner,"GET",DEFINITIONS+"/detail?definition_id="+id,null,null,200);
            JsonNode savedPreview=tenant(owner,"POST",DEFINITIONS+"/preview",Map.of("definition_id",id,
                    "expected_revision",before.path("draft_revision").asLong()),null,200);
            assertThat(savedPreview.path("validation").path("valid").asBoolean()).isTrue();
            assertThat(savedPreview.path("impact").path("changes").path("added_resource_slots").isEmpty()).isTrue();
            tenant(foreign,"POST",DEFINITIONS+"/preview",Map.of("definition_id",id,"expected_revision",before.path("draft_revision").asLong()),null,404);
            tenant(owner,"POST",DEFINITIONS+"/preview",Map.of("definition_id",id,"expected_revision",999),null,409);
            var candidate=(com.fasterxml.jackson.databind.node.ObjectNode)before.path("definition").deepCopy();
            candidate.put("system_prompt","预览当前输入 {{parameters.new_field}}");
            candidate.withArray("resource_slots").addObject().put("key","new_data").put("label","资料").put("description","").put("capability_type","KNOWLEDGE_SOURCE");
            var command=Map.of("definition_id",id,"expected_revision",before.path("draft_revision").asLong(),"definition",candidate);
            JsonNode preview=tenant(owner,"POST",DEFINITIONS+"/preview-content",command,null,200);
            assertThat(preview.path("validation").path("valid").asBoolean()).isTrue();
            assertThat(preview.path("impact").path("changes").path("added_parameter_keys").toString()).contains("new_field");
            assertThat(preview.path("impact").path("changes").path("added_resource_slots").toString()).contains("new_data");
            assertThat(tenant(owner,"GET",DEFINITIONS+"/detail?definition_id="+id,null,null,200)).isEqualTo(before);
            tenant(foreign,"POST",DEFINITIONS+"/preview-content",command,null,404);
            tenant(owner,"POST",DEFINITIONS+"/preview-content",Map.of("definition_id",id,"expected_revision",999,"definition",candidate),null,409);
            candidate.putNull("model_config_id");
            JsonNode invalid=tenant(owner,"POST",DEFINITIONS+"/preview-content",command,null,200);
            assertThat(invalid.path("validation").path("valid").asBoolean()).isFalse();
            assertThat(invalid.path("impact").isNull()).isTrue();
            assertThat(tenant(owner,"GET",DEFINITIONS+"/detail?definition_id="+id,null,null,200)).isEqualTo(before);
        } catch(Exception|Error problem) {failure=problem;throw problem;}
        finally {
            try {stopHosts();}catch(Throwable cleanup){if(failure!=null)failure.addSuppressed(cleanup);else if(cleanup instanceof Error error)throw error;else throw (Exception)cleanup;}
            finally {TenantContext.clear();RequestContext.clear();}
        }
    }

    /** 正式换授只影响新Run；旧等待仍沿原链，撤销旧链不能借新链恢复或改写已发生费用。 */
    @Test @Timeout(360)
    void explicitAuthorizationSwitchSeparatesNewRunFromWaitingRunAndRevocationPreservesOriginalUsage() throws Exception {
        String marker="delegation-runtime-"+SnowflakeIds.nextId();
        evidence=repositoryRoot().resolve("backend-java/skillforge-institution-service/build/reports/institution-delegation/"+marker);
        Files.createDirectories(evidence);
        enableInstitutionWorker=true;
        Throwable failure=null;
        try(var provider=new NativeChatOpenAiProvider(json,marker)) {
            provider.enableAgentHitl();provider.start();prepareDatabaseRoles();startHosts("runtime");
            Account owner=account(marker,"owner"),clinic=account(marker,"clinic");
            relation("attach",clinic,owner,1,marker+":attach");
            TenantContext.setTenantId(owner.id());
            String model=PlatformModelFixtures.selectLlm(models,owner.id(),provider.endpoint(),"delegation-runtime-model",
                    "synthetic-runtime-key","custom","custom-openai-compatible","custom-v1");
            var priced=models.update(model,new com.skillforge.core.model.ModelWrite(models.detail(model).revision(),
                    "换授组合模型","LLM",true,"custom","custom-openai-compatible","custom-v1","openai-chat-completions",
                    "bearer-api-key",provider.endpoint(),"delegation-runtime-model","synthetic-runtime-key",
                    4096,32768,1024,0.2,"DISABLED",30,null,null,java.math.BigDecimal.valueOf(1_000_000L),java.math.BigDecimal.valueOf(2_000_000L),null,"CNY"),"integration-fixture");
            models.publish(model,priced.revision(),"integration-fixture");
            TenantContext.setTenantId(clinic.id());
            models.select(clinic.id(),new com.skillforge.core.model.ModelSelections(model,null,null));
            TenantContext.clear();
            JsonNode published=source(owner,marker+":source",model);
            String definition=published.path("definition_id").asText();
            String expiry=Instant.now().plus(1,ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS).toString();
            var grantBody=grantCommand(definition,clinic,null,List.of("deploy","execute"),expiry,false,0);
            JsonNode originalGrant=tenant(owner,"POST",GRANTS,grantBody,marker+":old-grant",200);
            JsonNode deployed=tenant(clinic,"POST",DEPLOYMENTS,deployment(definition,originalGrant,"runtime-clinic","runtime"),marker+":deploy",200);
            String agent=deployed.path("agent_id").asText();
            RuntimeChat old=submitRuntimeChat(clinic,agent,marker+":old-input");
            var oldWaiting=awaitRuntimeWaiting(old.run());
            var oldBinding=runBindings.find(old.run()).orElseThrow();
            var oldAuthority=oldBinding.scope().authorizations().get(agent);
            assertThat(oldAuthority.grantId()).isEqualTo(Long.parseLong(originalGrant.path("grant_id").asText()));
            assertThat(provider.requestCount()).isEqualTo(1);

            JsonNode replacementGrant=tenant(owner,"POST",GRANTS,grantBody,marker+":new-grant",200);
            JsonNode switched=tenant(clinic,"POST",DEPLOYMENTS+"/authorization",authorization(deployed,1,replacementGrant),marker+":switch",200);
            assertStable(deployed,switched,2,"runtime");
            assertThat(runBindings.find(old.run()).orElseThrow()).isEqualTo(oldBinding);
            runBindings.authorize(oldBinding);
            RuntimeChat fresh=submitRuntimeChat(clinic,agent,marker+":new-input");
            var freshWaiting=awaitRuntimeWaiting(fresh.run());
            var freshAuthority=runBindings.find(fresh.run()).orElseThrow().scope().authorizations().get(agent);
            assertThat(freshAuthority.deploymentId()).isEqualTo(oldAuthority.deploymentId());
            assertThat(freshAuthority.deploymentRevision()).isEqualTo(2);
            assertThat(freshAuthority.grantId()).isEqualTo(Long.parseLong(replacementGrant.path("grant_id").asText()));
            assertThat(freshAuthority.grantChain()).isNotEqualTo(oldAuthority.grantChain());
            assertThat(runBindings.find(old.run()).orElseThrow().scope().authorizations().get(agent)).isEqualTo(oldAuthority);
            assertThat(provider.requestCount()).isEqualTo(2);
            var originalActions=executionStore.actions(old.run(),0,100);
            var originalFacts=runtimeFacts(clinic,old.run());
            assertThat(originalFacts.usage()).isNotEmpty();
            assertThat(queryCount(jdbc,clinic.id(),"SELECT count(*) FROM nw_usage_events WHERE run_id=? AND usage_event_kind='measured' AND amount_status='priced' AND total_amount>0",old.run().runId())).isEqualTo(1);

            tenant(owner,"POST",GRANTS+"/revoke",Map.of("grant_id",originalGrant.path("grant_id").asText(),
                    "expected_revision",originalGrant.path("revision").asLong()),marker+":revoke-old",200);
            tenant(clinic,"POST","/api/v1/chat",runtimeReply(old,agent,oldWaiting,marker),null,403);
            assertThat(executionStore.load(old.run()).orElseThrow()).isEqualTo(oldWaiting);
            assertThat(executionStore.actions(old.run(),0,100)).isEqualTo(originalActions);
            assertThat(runtimeFacts(clinic,old.run())).isEqualTo(originalFacts);
            assertThat(runBindings.find(old.run()).orElseThrow()).isEqualTo(oldBinding);
            assertThat(provider.requestCount()).isEqualTo(2);
            assertThat(tenant(clinic,"GET",DEPLOYMENTS+"/detail?deployment_id="+deployed.path("deployment_id").asText(),null,null,200).path("ready").asBoolean()).isTrue();
            tenant(clinic,"POST","/api/v1/chat",runtimeReply(fresh,agent,freshWaiting,marker),null,202);
            await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(100)).untilAsserted(()->
                    assertThat(executionStore.load(fresh.run()).orElseThrow().status()).isEqualTo(com.skillforge.runtime.api.RunStatus.COMPLETED));
            assertThat(provider.requestCount()).isEqualTo(3);
            assertThat(runtimeFacts(clinic,old.run())).isEqualTo(originalFacts);
            var oldUsage=runtimeUsagePage(clinic,old.run(),2);
            var newUsage=runtimeUsagePage(clinic,fresh.run(),3);
            var result=new RuntimeSwitchReport("PASS",clinic.id(),old.run().runId(),fresh.run().runId(),
                    oldAuthority.grantId(),freshAuthority.grantId(),provider.requestCount(),true,false,oldUsage,newUsage);
            Files.writeString(evidence.resolve("runtime-switch-verification.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(result),StandardCharsets.UTF_8);
        } catch(Exception|Error problem) {failure=problem;throw problem;}
        finally {
            try {stopHosts();}catch(Throwable cleanup){if(failure!=null)failure.addSuppressed(cleanup);else if(cleanup instanceof Error error)throw error;else throw (Exception)cleanup;}
            finally {enableInstitutionWorker=false;TenantContext.clear();RequestContext.clear();}
        }
    }

    /** 私聊主体和Session均由正式API创建；不向数据库插入Run、Action或Attempt。 */
    private RuntimeChat submitRuntimeChat(Account account,String agent,String input)throws Exception {
        JsonNode user=tenant(account,"POST","/api/v1/chat/end-users",new RuntimeUserCommand("换授验收用户"),null,200);
        String userId=user.path("id").asText();
        JsonNode session=tenant(account,"POST","/api/v1/chat/sessions",new RuntimeSessionCommand(agent,userId),null,200);
        String sessionId=session.path("session_id").asText();
        JsonNode accepted=tenant(account,"POST","/api/v1/chat",new RuntimeChatCommand(sessionId,agent,input,
                ChatContent.text("请先确认后继续"),new RuntimeChatContext(userId),List.of(),null),null,202);
        String run=accepted.path("execution_id").asText();assertThat(run).isNotBlank();
        return new RuntimeChat(sessionId,userId,new com.skillforge.runtime.api.RunRef(new com.skillforge.runtime.api.TenantId(account.id()),run));
    }

    private com.skillforge.runtime.session.spi.ExecutionStore.Run awaitRuntimeWaiting(com.skillforge.runtime.api.RunRef run) {
        await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(100)).untilAsserted(()->{
            var current=executionStore.load(run).orElseThrow();
            assertThat(current.phase()).isEqualTo(com.skillforge.runtime.session.spi.ExecutionStore.Phase.WAITING);
            assertThat(current.waiting()).isPresent();
        });
        return executionStore.load(run).orElseThrow();
    }

    private RuntimeChatCommand runtimeReply(RuntimeChat chat,String agent,com.skillforge.runtime.session.spi.ExecutionStore.Run waiting,String marker) {
        var wait=waiting.waiting().orElseThrow();
        // 产品消息标识上限64；原Run和等待身份属于hitl_response，不拼接到消息主键。
        String input="reply:"+java.util.UUID.nameUUIDFromBytes(chat.run().runId().getBytes(StandardCharsets.UTF_8));
        return new RuntimeChatCommand(chat.session(),agent,input,ChatContent.text("确认继续"),
                new RuntimeChatContext(chat.user()),List.of(),new RuntimeHitlResponse(chat.run().runId(),Long.toString(waiting.revision()),
                new RuntimeHumanInput(new RuntimeWait(wait.action().actionId(),Long.toString(wait.generation())),"agent-hitl-approved-"+marker)));
    }

    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    private record RuntimeUserCommand(String displayName) { }
    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    private record RuntimeSessionCommand(String agentId,String endUserId) { }
    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    private record RuntimeChatContext(String endUserId) { }
    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    private record RuntimeChatCommand(String sessionId,String agentId,String clientMessageId,List<ChatContent> content,
            RuntimeChatContext context,List<com.skillforge.core.chat.contract.ChatInputReference> references,RuntimeHitlResponse hitlResponse) { }
    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    private record RuntimeHitlResponse(String executionId,String runRevision,RuntimeHumanInput humanInput) { }
    private record RuntimeHumanInput(@com.fasterxml.jackson.annotation.JsonProperty("wait") RuntimeWait waiting,String text) { }
    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    private record RuntimeWait(String actionId,String generation) { }

    /** 正式Store读原Attempt；计量只比较稳定事件、修订、数量及金额。 */
    private RuntimeFacts runtimeFacts(Account account,com.skillforge.runtime.api.RunRef run) {
        var attempts=executionStore.actions(run,0,100).stream()
                .map(action->executionStore.attempt(run,action.currentAttempt()).orElseThrow()).toList();
        java.util.function.Function<Object,Long> number=value->value==null?null:((Number)value).longValue();
        var usage=queryRows(jdbc,account.id(),"""
                SELECT id,attempt_id,usage_event_kind,report_revision,usage_snapshot_revision,
                       prompt_tokens,completion_tokens,total_amount,amount_status,currency
                  FROM nw_usage_events WHERE run_id=? ORDER BY id
                """,run.runId()).stream().map(row->new RuntimeUsage(number.apply(row.get("id")),
                        (String)row.get("attempt_id"),(String)row.get("usage_event_kind"),number.apply(row.get("report_revision")),
                        number.apply(row.get("usage_snapshot_revision")),number.apply(row.get("prompt_tokens")),
                        number.apply(row.get("completion_tokens")),(java.math.BigDecimal)row.get("total_amount"),
                        (String)row.get("amount_status"),(String)row.get("currency"))).toList();
        return new RuntimeFacts(attempts,usage);
    }

    private record RuntimeFacts(List<com.skillforge.runtime.session.spi.ExecutionStore.Attempt> attempts,List<RuntimeUsage> usage) { }
    private record RuntimeUsage(long id,String attempt,String kind,Long reportRevision,Long snapshotRevision,
            Long promptTokens,Long completionTokens,java.math.BigDecimal amount,String amountStatus,String currency) { }
    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    private record RuntimeSwitchReport(String state,String tenantId,String oldRun,String newRun,long oldGrant,long newGrant,
            int providerCalls,boolean oldUsageUnchanged,boolean childScopeExecuted,PlatformUsageContracts.Page oldUsage,PlatformUsageContracts.Page newUsage) { }
    private record RuntimeChat(String session,String user,com.skillforge.runtime.api.RunRef run) { }

    /** 返回正式导出原Page，包含主调用和输出契约系统用途；不按测试预期重构事件。 */
    private PlatformUsageContracts.Page runtimeUsagePage(Account account,com.skillforge.runtime.api.RunRef run,int expectedModelFacts)throws Exception {
        var page=new java.util.concurrent.atomic.AtomicReference<PlatformUsageContracts.Page>();
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).untilAsserted(()->{
            var value=json.treeToValue(tenant(account,"GET","/api/v1/admin/usage/events?run_id="+
                    java.net.URLEncoder.encode(run.runId(),StandardCharsets.UTF_8)+"&limit=500",null,null,200),PlatformUsageContracts.Page.class);
            assertThat(value.hasMore()).isFalse();
            int measured=0;
            for(var event:value.items()) {
                assertThat(event.runId()).isEqualTo(run.runId());
                if("llm.chat".equals(event.eventType())&&"measured".equals(event.kind()))measured++;
            }
            assertThat(measured).isEqualTo(expectedModelFacts);page.set(value);
        });
        return page.get();
    }

    /** 受让或其他所有者不能读取/编辑源草稿，也不能将受控源安装为独立副本。 */
    private void verifiesOwnerAndInstallBoundaries(Account foreign,Account recipient,JsonNode source,String marker)throws Exception {
        String id=source.path("definition_id").asText();
        var before=businessFacts();
        tenant(foreign,"GET",DEFINITIONS+"/detail?definition_id="+id,null,null,404);
        tenant(recipient,"GET",DEFINITIONS+"/detail?definition_id="+id,null,null,404);
        tenant(foreign,"PUT",DEFINITIONS+"/update",Map.of("definition_id",id,"expected_revision",source.path("draft_revision").asLong(),
                "payload",Map.of("description","不能接管源")),marker+":foreign-edit",404);
        tenant(recipient,"POST","/api/v1/agents/install-template",Map.of("template_id",Long.parseLong(id),
                "request_id","denied_"+SnowflakeIds.nextId(),"name","不能复制机构受控源","resource_bindings",List.of(),"delegation_bindings",List.of()),null,403);
        assertThat(tenant(foreign,"GET",DEFINITIONS,null,null,200).path("items").findValuesAsText("definition_id")).doesNotContain(id);
        assertThat(businessFacts()).as("越权源维护和独立安装均无业务副作用").isEqualTo(before);
    }

    /** 从合法基线只改变目标、父许可动作、期限或层数，拒绝不能留下半授权。 */
    private void verifiesRejectedDelegation(Account h1,Account h2,Account c1,Account c11,Account c3,String definition,
            JsonNode root,String parentExpiry,String childExpiry,String marker)throws Exception {
        JsonNode deployOnly=tenant(h1,"POST",GRANTS,grantCommand(definition,c1,null,List.of("deploy"),parentExpiry,true,2),marker+":deploy-only",200);
        var before=businessFacts();
        tenant(h1,"POST",GRANTS,grantCommand(definition,c11,null,List.of("deploy","execute"),childExpiry,false,0),marker+":skip-level",403);
        tenant(h2,"POST",GRANTS,grantCommand(definition,c3,null,List.of("deploy","execute"),childExpiry,false,0),marker+":foreign-owner-grant",403);
        tenant(c1,"POST",GRANTS,grantCommand(definition,c11,deployOnly,List.of("deploy","execute"),childExpiry,false,0),marker+":expand-actions",403);
        tenant(c1,"POST",GRANTS,grantCommand(definition,c11,root,List.of("deploy","execute"),
                Instant.parse(parentExpiry).plus(1,ChronoUnit.HOURS).toString(),false,0),marker+":extend-expiry",403);
        tenant(c1,"POST",GRANTS,grantCommand(definition,c11,root,List.of("deploy","execute"),childExpiry,true,2),marker+":expand-depth",403);
        tenant(c1,"POST",GRANTS+"/narrow",Map.of("grant_id",root.path("grant_id").asText(),"expected_revision",1,
                "actions",List.of("deploy","execute"),"expires_at",childExpiry,"allow_regrant",false,"remaining_depth",0),marker+":foreign-narrow",403);
        tenant(h1,"POST",GRANTS,grantCommand(definition,c1,null,List.of("deploy"),parentExpiry,true,2),marker+":root-grant",409);
        assertThat(businessFacts()).isEqualTo(before);
    }

    /** 独立目标管理许可只开放部署元数据，不创建下级成员或开放下级会话。 */
    private void verifiesHeadquartersMetadataOnly(Account h1,Account c2,Account foreign,String definition,String expiry,String marker)throws Exception {
        JsonNode sourceGrant=tenant(h1,"POST",GRANTS,grantCommand(definition,c2,null,List.of("deploy","execute"),expiry,false,0),marker+":c2-source-grant",200);
        JsonNode mapping=platform("POST","/api/v1/platform/institutions/associate",Map.of("tenant_id",c2.id(),
                "namespace",marker,"external_institution_ref","headquarters-c2"),marker+":c2-mapping",200);
        String integration=mapping.path("integration_id").asText();assertThat(integration).isNotBlank();
        var command=new LinkedHashMap<String,Object>(deployment(definition,sourceGrant,"headquarters-c2","C2"));command.put("tenant_id",c2.id());
        command.put("integration_id",integration);
        long members=count("SELECT count(*) FROM nw_tenant_members WHERE tenant_id=?",c2.id());
        var before=businessFacts();
        tenant(h1,"POST",DEPLOYMENTS,command,marker+":hq-deploy",403);
        tenant(h1,"GET",DEPLOYMENTS+"?tenant_id="+c2.id(),null,null,403);
        assertThat(businessFacts()).isEqualTo(before);
        JsonNode createPermit=managementGrant(h1,c2,"create",marker+":manage-create");
        JsonNode deployed=tenant(h1,"POST",DEPLOYMENTS,command,marker+":hq-deploy",200);
        assertThat(deployed.path("tenant_id").asText()).isEqualTo(c2.id());
        assertThat(deployed.path("integration_id").asText()).isEqualTo(integration);
        assertThat(deployed.path("ready").asBoolean()).isTrue();
        assertThat(count("SELECT count(*) FROM nw_tenant_members WHERE tenant_id=?",c2.id())).isEqualTo(members);
        tenant(h1,"GET",DEPLOYMENTS+"/detail?tenant_id="+c2.id()+"&deployment_id="+deployed.path("deployment_id").asText(),null,null,403);
        JsonNode source=tenant(h1,"GET",DEFINITIONS+"/detail?definition_id="+definition,null,null,200);
        String impactPath=DEFINITIONS+"/impact?definition_id="+definition+"&draft_revision="+source.path("draft_revision").asLong();
        assertThat(tenant(h1,"GET",impactPath,null,null,200).path("visible_deployment_count").asInt()).isZero();
        JsonNode readPermit=managementGrant(h1,c2,"read",marker+":manage-read");
        JsonNode current=tenant(h1,"GET",DEPLOYMENTS+"/detail?tenant_id="+c2.id()+"&deployment_id="+deployed.path("deployment_id").asText(),null,null,200);
        assertThat(current.path("agent_id")).isEqualTo(deployed.path("agent_id"));
        assertThat(current.path("integration_id").asText()).isEqualTo(integration);
        JsonNode impact=tenant(h1,"GET",impactPath,null,null,200);
        assertThat(impact.path("visible_deployment_count").asInt()).isEqualTo(1);
        assertThat(impact.path("deployments").findValuesAsText("deployment_id")).containsExactly(deployed.path("deployment_id").asText());
        assertThat(impact.toString()).doesNotContain("resource_bindings","parameters","system_prompt");
        var candidate=(com.fasterxml.jackson.databind.node.ObjectNode)source.path("definition").deepCopy();
        candidate.withArray("resource_slots").addObject().put("key","new_docs").put("label","新资料")
                .put("description","").put("capability_type","KNOWLEDGE_SOURCE");
        var candidateCommand=Map.of("definition_id",definition,"expected_revision",source.path("draft_revision").asLong(),"definition",candidate);
        JsonNode candidatePreview=tenant(h1,"POST",DEFINITIONS+"/preview-content",candidateCommand,null,200);
        assertThat(candidatePreview.path("validation").path("valid").asBoolean()).isTrue();
        assertThat(candidatePreview.path("impact").path("visible_deployment_count").asInt()).isEqualTo(1);
        assertThat(candidatePreview.path("impact").path("deployments").get(0).path("draft_declarations_satisfied").asBoolean()).isFalse();
        assertThat(tenant(h1,"GET",DEFINITIONS+"/detail?definition_id="+definition,null,null,200)).isEqualTo(source);
        tenant(h1,"GET",DEPLOYMENTS+"?tenant_id="+foreign.id(),null,null,403);
        JsonNode user=tenant(c2,"POST","/api/v1/chat/end-users",Map.of("display_name",marker+":private-c2"),null,200);
        JsonNode session=tenant(c2,"POST","/api/v1/chat/sessions",Map.of("agent_id",deployed.path("agent_id").asText(),
                "end_user_id",user.path("id").asLong()),null,200);
        tenant(h1,"GET","/api/v1/chat/history?session_id="+session.path("session_id").asText(),null,null,404);
        assertThat(tenant(h1,"GET","/api/v1/chat/end-users",null,null,200).toString()).doesNotContain(marker+":private-c2");
        assertThat(restrictedAgentCount(c2.id(),c2.id())).as("RLS负控的目标实例必须真实存在").isPositive();
        assertThat(restrictedAgentCount(h1.id(),c2.id()))
                .as("目标部署窗口结束后普通RLS不能读取下级Agent").isZero();
        platform("POST",MANAGEMENT+"/revoke",Map.of("grant_id",createPermit.path("grant_id").asText(),"expected_revision",createPermit.path("revision").asLong()),marker+":revoke-management",200);
        var rejected=new LinkedHashMap<String,Object>(command);rejected.put("external_agent_ref","headquarters-after-revoke");
        before=businessFacts();tenant(h1,"POST",DEPLOYMENTS,rejected,marker+":hq-after-revoke",403);assertThat(businessFacts()).isEqualTo(before);
        platform("POST",MANAGEMENT+"/revoke",Map.of("grant_id",readPermit.path("grant_id").asText(),"expected_revision",readPermit.path("revision").asLong()),marker+":revoke-management-read",200);
        assertThat(tenant(h1,"GET",impactPath,null,null,200).path("visible_deployment_count").asInt()).isZero();
        assertThat(tenant(h1,"POST",DEFINITIONS+"/preview-content",candidateCommand,null,200).path("impact").path("visible_deployment_count").asInt()).isZero();
    }

    /** 正式平台许可端点精确绑定当前直属关系和一项完整动作。 */
    private JsonNode managementGrant(Account actor,Account target,String action,String key)throws Exception {
        return platform("POST",MANAGEMENT,Map.of("actor_tenant_id",actor.id(),"target_tenant_id",target.id(),
                "action","tenant.agent_deployments."+action,"expected_relation_revision",2),key,200);
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

    /** 只用不可连接的loopback端点登记已发布模型元数据；本套件不调用模型。 */
    private String model(Account tenant,String marker) {
        TenantContext.setTenantId(tenant.id());
        try{return PlatformModelFixtures.selectLlm(models,tenant.id(),"http://127.0.0.1:9/v1",marker+"-metadata-model","synthetic-unused-key","custom","custom-openai-compatible","custom-v1");}
        finally{TenantContext.clear();}
    }

    /** 源创建、CAS编辑和不可变发布均经当前机构真实HTTP，保留最终发布原回执。 */
    private JsonNode source(Account owner,String key,String model)throws Exception {
        JsonNode created=tenant(owner,"POST",DEFINITIONS,sourceCommand(key,model),key+":create",200);
        JsonNode edited=tenant(owner,"PUT",DEFINITIONS+"/update",Map.of("definition_id",created.path("definition_id").asText(),
                "expected_revision",created.path("draft_revision").asLong(),"payload",Map.of("description","共享声明不含机构私有资料")),key+":edit",200);
        JsonNode published=tenant(owner,"POST",DEFINITIONS+"/publish",Map.of("definition_id",created.path("definition_id").asText(),
                "expected_revision",edited.path("draft_revision").asLong()),key+":publish",200);
        assertThat(published.path("version").asText()).isEqualTo("1.0.0");return published;
    }

    /** 机构共享源仅引用声明参数和已发布模型，不携带私有资源或上级实例。 */
    private Map<String,Object> sourceCommand(String name,String model) {
        return Map.of("name",name,"price_type","free","price_cents",0,"distribution_policy","AUTHORIZED_DEPLOY","definition",Map.of(
                "system_prompt","机构资料助手 {{parameters.clinic}}","role_description","机构共享角色","model_config_id",model,
                "execution_config",Map.of(),"capabilities",List.of(),"resource_slots",List.of(),"delegation_slots",List.of()));
    }

    /** 授权边界完整显式提交，父授权字段缺省只用于真正源所有者原授。 */
    private Map<String,Object> grantCommand(String definition,Account target,JsonNode parent,List<String> actions,String expiry,boolean regrant,int depth) {
        var command=new LinkedHashMap<String,Object>();command.put("definition_id",definition);command.put("target_tenant_id",target.id());
        command.put("actions",actions);command.put("expires_at",expiry);command.put("allow_regrant",regrant);command.put("remaining_depth",depth);
        command.put("parent_grant_id",parent==null?null:parent.path("grant_id").asText());return command;
    }

    /** 原生实例不绑定integration，稳定外部键和本地参数都进入幂等指纹。 */
    private Map<String,Object> deployment(String definition,JsonNode grant,String external,String clinic) {
        var command=new LinkedHashMap<String,Object>();command.put("definition_id",definition);command.put("grant_id",grant.path("grant_id").asText());
        command.put("integration_id",null);command.put("external_agent_ref",external);command.put("resource_bindings",List.of());
        command.put("delegation_bindings",List.of());command.put("parameters",Map.of("clinic",clinic));return command;
    }

    /** CAS本地绑定不替换授权依据。 */
    private Map<String,Object> bindings(JsonNode deployment,long revision,String clinic) {
        return Map.of("deployment_id",deployment.path("deployment_id").asText(),"expected_revision",revision,
                "resource_bindings",List.of(),"delegation_bindings",List.of(),"parameters",Map.of("clinic",clinic));
    }

    /** 同源切换只指定实例、部署CAS和新的明确grant，不接收换源字段。 */
    private Map<String,Object> authorization(JsonNode deployment,long revision,JsonNode grant) {
        return Map.of("deployment_id",deployment.path("deployment_id").asText(),"expected_revision",revision,"grant_id",grant.path("grant_id").asText());
    }

    /** 平台关系变化有独立CAS和命令身份。 */
    private JsonNode relation(String operation,Account child,Account parent,long revision,String key)throws Exception {
        return platform("POST","/api/v1/platform/institution-relations/"+operation,relationCommand(child,parent,revision),key,200);
    }

    /** 正文ID均为十进制字符串，关系修订保留JSON数字。 */
    private Map<String,Object> relationCommand(Account child,Account parent,long revision) {
        return Map.of("child_tenant_id",child.id(),"parent_tenant_id",parent.id(),"expected_revision",revision);
    }

    /** 读取当前实例状态，不能用旧创建或换授回执推定当前ready。 */
    private JsonNode detail(Account account,JsonNode deployment)throws Exception {
        return tenant(account,"GET",DEPLOYMENTS+"/detail?deployment_id="+deployment.path("deployment_id").asText(),null,null,200);
    }

    /** 当前撤权必须体现在可诊断安全原因，保持实例和历史。 */
    private void assertUnavailable(Account account,JsonNode deployment)throws Exception {
        JsonNode current=detail(account,deployment);assertThat(current.path("ready").asBoolean()).isFalse();
        assertThat(current.path("issues").findValuesAsText("code")).contains("authorization_unavailable");
        assertThat(current.path("agent_id")).isEqualTo(deployment.path("agent_id"));
    }

    /** 换授和绑定保留同一实例、外部键和本地配置，部署修订严格递增。 */
    private void assertStable(JsonNode original,JsonNode current,long revision,String clinic) {
        for(String field:List.of("deployment_id","tenant_id","agent_id","definition_id","external_agent_ref","integration_id"))
            assertThat(current.path(field)).as("稳定实例字段 %s",field).isEqualTo(original.path(field));
        assertThat(current.path("deployment_revision").asLong()).isEqualTo(revision);
        assertThat(current.path("ready").asBoolean()).isTrue();assertThat(current.path("parameters").path("clinic").asText()).isEqualTo(clinic);
    }

    /** 拒绝前后比较业务分母，认证与拒绝审计不属于非法业务写入。 */
    private Map<String,Object> businessFacts() {
        return jdbc.queryForMap("""
                SELECT (SELECT count(*) FROM nw_agent_definition_grants) AS grants,
                    (SELECT coalesce(sum(revision),0) FROM nw_agent_definition_grants) AS grant_revisions,
                    (SELECT count(*) FROM nw_agent_deployments) AS deployments,
                    (SELECT count(*) FROM nw_agent_deployment_revisions) AS deployment_revisions,
                    (SELECT count(*) FROM nw_agents) AS agents,
                    (SELECT count(*) FROM nw_agent_templates) AS definitions,
                    (SELECT count(*) FROM nw_agent_definition_operations) AS definition_operations,
                    (SELECT count(*) FROM nw_usage_events) AS usage
                """);
    }

    /** 仅查询本fixture已知目标，不写测试权威表绕过正式API。 */
    private long count(String sql,String tenant) {return jdbc.queryForObject(sql,Long.class,Long.parseLong(tenant));}

    /** 直接使用机构宿主的受限连接，避免测试容器owner绕过RLS造成虚假验收。 */
    private long restrictedAgentCount(String actorTenant,String targetTenant) {
        var restricted=new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                environment.getRequiredProperty("spring.datasource.url"),tenantDatabaseUser,hostDatabasePassword));
        return restricted.execute((org.springframework.jdbc.core.ConnectionCallback<Long>) connection->{
            boolean original=connection.getAutoCommit();assertThat(original).as("RLS负控独占短读事务").isTrue();connection.setAutoCommit(false);
            try {
                try(var bind=connection.prepareStatement("SELECT set_config('app.tenant_id',?,true)")){bind.setString(1,actorTenant);bind.execute();}
                try(var role=connection.createStatement();var result=role.executeQuery("SELECT current_user, row_security_active('nw_agents'), NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname=current_user")) {
                    assertThat(result.next()).isTrue();assertThat(result.getString(1)).isEqualTo(tenantDatabaseUser);
                    assertThat(result.getBoolean(2)).isTrue();assertThat(result.getBoolean(3)).isTrue();
                }
                try(var query=connection.prepareStatement("SELECT count(*) FROM nw_agents WHERE tenant_id=?")) {
                    query.setLong(1,Long.parseLong(targetTenant));try(var rows=query.executeQuery()){assertThat(rows.next()).isTrue();return rows.getLong(1);}
                }
            } finally {try{connection.rollback();}finally{connection.setAutoCommit(original);}}
        });
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

    /** 失败仅报告端点、状态和安全错误码，秘密响应不会出现在断言描述。 */
    private JsonNode success(Reply response,String method,String path,int expected) {
        assertThat(response.status()).as("%s %s code=%s",method,path,response.body().path("code").asText()).isEqualTo(expected);
        return response.body().path("data");
    }

    /** 两个正式bootJar共用当前私有PG/Redis，端口由OS原子分配，重启不重建业务库。 */
    private void startHosts(String label)throws Exception {
        platformHost=startHost(true,label);institutionHost=startHost(false,label);
        platformToken=success(exchange(platformHost,null,"POST","/api/v1/platform/auth/login",Map.of(
                "username","delegation-platform-admin","password","SyntheticPlatform1234"),null),"POST","platform login",200).path("access_token").asText();
        assertThat(platformToken).isNotBlank();
    }

    /** 迁移owner只准备独立测试角色；两个实际宿主分别使用机构RLS及平台BYPASSRLS角色。 */
    private void prepareDatabaseRoles() {
        assertThat(jdbc.queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname=current_user",Boolean.class))
                .as("本套件由私有Testcontainers迁移owner创建宿主角色").isTrue();
        String suffix=Long.toString(SnowflakeIds.nextId());
        tenantDatabaseUser="gi16_tenant_"+suffix;platformDatabaseUser="gi16_platform_"+suffix;
        hostDatabasePassword=java.util.UUID.randomUUID().toString();
        jdbc.execute("CREATE ROLE "+tenantDatabaseUser+" LOGIN PASSWORD '"+hostDatabasePassword+"' NOSUPERUSER NOBYPASSRLS");
        jdbc.execute("CREATE ROLE "+platformDatabaseUser+" LOGIN PASSWORD '"+hostDatabasePassword+"' NOSUPERUSER BYPASSRLS");
        for(String role:List.of(tenantDatabaseUser,platformDatabaseUser)) {
            jdbc.execute("GRANT USAGE ON SCHEMA public TO "+role);
            jdbc.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO "+role);
            jdbc.execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO "+role);
        }
        // V102收回PUBLIC执行权；真实受限宿主仍须能解析既有随机认证引用的原Tenant。
        jdbc.execute("GRANT EXECUTE ON FUNCTION public.resolve_runtime_authentication_tenant(text) TO "+tenantDatabaseUser);
        assertThat(jdbc.queryForObject("SELECT has_function_privilege(CAST(? AS name), 'public.resolve_runtime_authentication_tenant(text)', 'EXECUTE')",
                Boolean.class,tenantDatabaseUser)).isTrue();
        jdbc.execute("GRANT EXECUTE ON FUNCTION public.headquarters_usage_target_allowed(bigint) TO "+tenantDatabaseUser);
        jdbc.execute("GRANT EXECUTE ON FUNCTION public.publish_committed_usage(integer) TO "+tenantDatabaseUser);
    }

    /** 与群组合复用同一确定性目录；附加配置仅进入测试拥有的子JVM。 */
    private Path modelCatalogFixtureJar()throws Exception {
        if(modelCatalogFixtureJar!=null)return modelCatalogFixtureJar;
        Path fixture=evidence.resolve("deterministic-model-catalog.jar");
        String resource=GroupMessageModelCatalogConfiguration.class.getName().replace('.','/')+".class";
        try(var bytecode=GroupMessageModelCatalogConfiguration.class.getClassLoader().getResourceAsStream(resource);
            var jar=new JarOutputStream(Files.newOutputStream(fixture))) {
            if(bytecode==null)throw new IllegalStateException("delegation_fixture_configuration_missing");
            jar.putNextEntry(new JarEntry(resource));bytecode.transferTo(jar);jar.closeEntry();
        }
        modelCatalogFixtureJar=fixture;return fixture;
    }

    /** 获取进程后立即登记清理；只在目录激活、监听日志与实际HTTP共同ready后返回。 */
    private Host startHost(boolean platform,String label)throws Exception {
        Path root=repositoryRoot();String module=platform?"skillforge-platform-service":"skillforge-institution-service";
        Path jar=root.resolve("backend-java/"+module+"/build/libs/"+module+".jar");assertThat(jar).exists();
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java");
        Path log=evidence.resolve((platform?"platform-":"institution-")+label+".log");
        var builder=new ProcessBuilder(java.toString(),"-Dfile.encoding=UTF-8","-Dstdout.encoding=UTF-8","-Dstderr.encoding=UTF-8",
                "-Dloader.path="+modelCatalogFixtureJar().toUri(),
                "-Dloader.main="+(platform?"com.skillforge.platform.PlatformApplication":"com.skillforge.institution.InstitutionApplication"),
                "-cp",jar.toString(),"org.springframework.boot.loader.launch.PropertiesLauncher",
                "--spring.main.sources="+GroupMessageModelCatalogConfiguration.class.getName(),"--server.port=0","--server.address=127.0.0.1",
                "--agent-runtime.workers.auto-startup="+(!platform&&enableInstitutionWorker),"--spring.flyway.enabled=false",
                "--spring.cloud.nacos.discovery.enabled=false","--spring.cloud.nacos.config.enabled=false");
        builder.directory(root.resolve("backend-java/"+module).toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        var env=builder.environment();env.put("SPRING_DATASOURCE_URL",environment.getRequiredProperty("spring.datasource.url"));
        env.put("SPRING_DATASOURCE_USERNAME",tenantDatabaseUser);env.put("SPRING_DATASOURCE_PASSWORD",hostDatabasePassword);
        env.put("PLATFORM_DATASOURCE_USERNAME",platformDatabaseUser);env.put("PLATFORM_DATASOURCE_PASSWORD",hostDatabasePassword);
        env.put("REDIS_HOST",environment.getRequiredProperty("spring.data.redis.host"));env.put("REDIS_PORT",environment.getRequiredProperty("spring.data.redis.port"));
        env.put("REDIS_DATABASE",environment.getProperty("spring.data.redis.database","0"));
        env.put("SECRET_KEY",properties.getSecretKey());env.put("PLATFORM_SECRET_KEY",TEST_PLATFORM_JWT_SECRET);
        env.put("PLATFORM_ADMIN_USERNAME","delegation-platform-admin");env.put("PLATFORM_ADMIN_PASSWORD","SyntheticPlatform1234");
        Host host=new Host(builder.start(),log,platform);ownedHosts.add(host);
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500)).ignoreExceptions().untilAsserted(()->{
            assertThat(host.process.isAlive()).as("宿主应存活，诊断日志 %s",host.log).isTrue();
            String startup=new String(Files.readAllBytes(log),StandardCharsets.UTF_8);
            assertThat(startup).as("owned宿主必须加载父测试声明的确定性模型能力").contains(GroupMessageModelCatalogConfiguration.ACTIVATION_MARKER);
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
        private final Process process;private final Path log;private final boolean platform;private int port;
        /** 进程启动即持有其回收责任，port只在监听事件后赋值。 */
        private Host(Process process,Path log,boolean platform){this.process=process;this.log=log;this.platform=platform;}
    }
    /** 原始响应只供expected校验，不输出响应正文或一次秘密。 */
    private record Reply(int status,JsonNode body) { @Override public String toString(){return "HTTP "+status;} }
}
