package com.skillforge.institution.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

/** GI-05 的真实 HMAC HTTP、受限数据库角色、群记忆治理与宿主重建验收。 */
@Import(GroupMessageModelCatalogConfiguration.class)
class GroupMemoryGovernanceIntegrationTest extends AbstractIntegrationTest {
    private static final String GROUPS="/api/v1/open-platform/groups";
    private static final String MEMORIES=GROUPS+"/memories";
    private static final String USERNAME="group-memory-admin",PASSWORD="GroupMemoryFixture1234";
    private static final List<String> GROUP_SCOPES=List.of("groups.write","groups.members.write","groups.agents.write",
            "groups.messages.write","groups.memory.read","groups.memory.write");
    private static final Pattern LISTENING=Pattern.compile("Tomcat started on port (\\d+)");
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformModelCatalogService models;
    @Autowired private SkillForgeProperties properties;
    @Autowired private org.springframework.core.env.Environment environment;
    @Autowired private org.springframework.context.ApplicationContext parentContext;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<Host> ownedHosts=new ArrayList<>();
    private final List<Map<String,Object>> httpErrors=new ArrayList<>();
    private Host platformHost,institutionHost;
    private String platformToken;
    private Path evidence,modelCatalogFixtureJar;
    private String tenantDatabaseUser,platformDatabaseUser,hostDatabasePassword;

    @DynamicPropertySource static void workers(DynamicPropertyRegistry registry) {
        registry.add("agent-runtime.workers.auto-startup",()->"false");
    }

    @Test @Timeout(600)
    void officialSourcesGovernSharedOwnershipWithCurrentAuthorizationAndDurableReadback() throws Exception {
        String marker="group-memory-"+SnowflakeIds.nextId();
        evidence=repositoryRoot().resolve("backend-java/skillforge-institution-service/build/reports/group-memory/"+marker);
        Files.createDirectories(evidence);Throwable failure=null;
        try {
            stopParentRuntime();prepareDatabaseRoles();startHosts("initial");
            assertThat(jdbc.queryForObject("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname=?",
                    Boolean.class,tenantDatabaseUser)).isTrue();
            Account owner=account(marker,"owner"),foreign=account(marker,"foreign");
            String integration=association(owner,marker,"primary"),otherIntegration=association(owner,marker,"other");
            JsonNode access=credential(owner,integration,GROUP_SCOPES,marker+":access");
            JsonNode setup=credential(owner,integration,List.of("business.scopes.write","end_users.write"),marker+":setup");
            JsonNode other=credential(owner,otherIntegration,GROUP_SCOPES,marker+":other");
            JsonNode foreignAccess=credential(foreign,association(foreign,marker,"foreign"),GROUP_SCOPES,marker+":foreign");
            JsonNode noMemory=credential(owner,integration,List.of("groups.messages.write"),marker+":no-memory");
            JsonNode business=scope(setup,"clinic",marker+":scope");
            JsonNode manager=user(setup,"manager"),subject=user(setup,"subject"),reader=user(setup,"reader");
            String model=model(owner,"http://127.0.0.1:1/v1",marker);
            String firstAgent=nativeAgent(owner,model,marker+":agent-one"),secondAgent=nativeAgent(owner,model,marker+":agent-two");
            JsonNode group=machine(access,"POST",GROUPS+"/ensure",ensure(marker,"primary",business),marker+":group",200);
            JsonNode otherGroup=machine(access,"POST",GROUPS+"/ensure",ensure(marker+"-other","other",business),marker+":group-other",200);
            String id=group.path("group_binding_id").asText(),otherId=otherGroup.path("group_binding_id").asText();
            for(String binding:List.of(id,otherId)) {
                updateMember(access,binding,manager,"manager","active",0,binding+":manager",1);
                updateMember(access,binding,subject,"member","active",0,binding+":subject",1);
                updateMember(access,binding,reader,"member","active",0,binding+":reader",1);
                updateAgent(access,binding,firstAgent,"active",0,binding+":first",1);
                updateAgent(access,binding,secondAgent,"active",0,binding+":second",1);
            }
            // 同一正式 HMAC 入口验证显式开关及作用域；读到默认关闭后才配置，不借 SQL 绕过授权。
            String policyPath=MEMORIES+"/extraction-policy";
            String policyQuery=policyPath+"?group_binding_id="+id+"&operator_end_user_id="+manager.path("end_user_id").asText();
            JsonNode defaultPolicy=machine(access,"GET",policyQuery,null,null,200);
            assertThat(defaultPolicy.path("enabled").asBoolean()).isFalse();
            assertThat(defaultPolicy.path("revision").asLong()).isZero();
            rejects(noMemory,"GET",policyQuery,null,null,403);
            var enablePolicy=new com.skillforge.institution.group.GroupMemoryExtractionPolicy.Command(id,
                    manager.path("end_user_id").asLong(),true,Long.valueOf(firstAgent),0L);
            rejects(noMemory,"PUT",policyPath,enablePolicy,null,403);
            JsonNode enabledPolicy=machine(access,"PUT",policyPath,enablePolicy,null,200);
            assertThat(enabledPolicy.path("enabled").asBoolean()).isTrue();
            assertThat(enabledPolicy.path("extractor_agent_id").asText()).isEqualTo(firstAgent);
            assertThat(enabledPolicy.path("revision").asLong()).isEqualTo(1);
            assertThat(machine(access,"GET",policyQuery,null,null,200)).isEqualTo(enabledPolicy);
            JsonNode disabledPolicy=machine(access,"PUT",policyPath,new com.skillforge.institution.group.GroupMemoryExtractionPolicy.Command(
                    id,manager.path("end_user_id").asLong(),false,Long.valueOf(firstAgent),1L),null,200);
            assertThat(disabledPolicy.path("enabled").asBoolean()).isFalse();
            assertThat(disabledPolicy.path("revision").asLong()).isEqualTo(2);
            long actualUsers=jdbc.queryForObject("SELECT count(*) FROM nw_end_users WHERE tenant_id=?",Long.class,Long.valueOf(owner.id()));
            JsonNode publicSource=source(access,id,manager,marker+":public-source","本群服务时间为每周一至周五。"),
                    memberSource=source(access,id,subject,marker+":member-source","我偏好下午接收随访提醒。"),
                    sensitiveSource=source(access,id,subject,marker+":sensitive-source","我要求确认后才保存本次随访偏好。");
            var groupCommand=create(id,manager,firstAgent,"GROUP",null,publicSource,"service_hours","weekdays",false);
            groupCommand.remove("confidence");groupCommand.remove("importance");groupCommand.remove("review_required");
            JsonNode publicFact=machine(access,"POST",MEMORIES+"/create",groupCommand,marker+":create-public",200);
            assertThat(machine(access,"POST",MEMORIES+"/create",groupCommand,marker+":create-public",200)).isEqualTo(publicFact);
            assertThat(publicFact.path("confidence").asDouble()).isZero();
            assertThat(publicFact.path("importance").asDouble()).isZero();
            assertThat(publicFact.path("review_required").asBoolean()).isFalse();
            assertOwner(publicFact,id,null);
            assertThat(json.readTree(publicFact.path("object_json").asText()).path("value").path("value").asText()).isEqualTo("weekdays");
            assertThat(publicFact.has("agent_id")).isFalse();
            assertThat(publicFact.has("end_user_id")).isFalse();
            assertThat(publicFact.path("source_session_id")).isEqualTo(group.path("session_id"));
            assertThat(publicFact.path("source_event_ids").get(0).asText()).isEqualTo("group-memory-source:"+publicSource.path("source_message_id").asText()+":1");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_end_users WHERE tenant_id=?",Long.class,Long.valueOf(owner.id()))).isEqualTo(actualUsers);
            var changed=new LinkedHashMap<>(groupCommand);changed.put("object_json","{\"value\":\"weekends\"}");
            rejects(access,"POST",MEMORIES+"/create",changed,marker+":create-public",409);
            var numericOperator=new LinkedHashMap<>(groupCommand);
            numericOperator.put("operator_end_user_id",manager.path("end_user_id").asLong());
            rejects(access,"POST",MEMORIES+"/create",numericOperator,marker+":numeric-operator",400);
            for(String field:List.of("confidence","importance","review_required")) {
                var explicitNull=new LinkedHashMap<>(groupCommand);explicitNull.put(field,null);
                rejects(access,"POST",MEMORIES+"/create",explicitNull,marker+":null-"+field,400);
            }
            rejects(noMemory,"GET",query(id,reader,firstAgent,null,null),null,null,403);
            rejects(other,"GET",query(id,reader,firstAgent,null,null),null,null,403);
            rejects(foreignAccess,"GET",query(id,reader,firstAgent,null,null),null,null,403);
            rejects(access,"POST",MEMORIES+"/create",create(otherId,manager,firstAgent,"GROUP",null,publicSource,"foreign_source","no",false),marker+":cross-source",403);

            JsonNode memberFact=machine(access,"POST",MEMORIES+"/create",
                    create(id,subject,firstAgent,"GROUP_MEMBER",subject,memberSource,"followup_time","afternoon",false),marker+":create-member",200);
            assertOwner(memberFact,id,subject);
            assertThat(ids(list(access,id,reader,secondAgent,null,null))).containsExactly(factId(publicFact));
            assertThat(ids(list(access,id,reader,secondAgent,subject,null))).containsExactlyInAnyOrder(factId(publicFact),factId(memberFact));
            assertThat(ids(list(access,otherId,reader,secondAgent,subject,null))).isEmpty();
            // 管理员不能将另一位成员的声明变为自己的个人事实，也不能代本人治理。
            rejects(access,"POST",MEMORIES+"/create",create(id,manager,firstAgent,"GROUP_MEMBER",subject,memberSource,"forged_active","no",false),marker+":manager-create",403);
            JsonNode pending=machine(access,"POST",MEMORIES+"/create",
                    create(id,subject,firstAgent,"GROUP_MEMBER",subject,sensitiveSource,"sensitive_preference","confirm-first",true),marker+":create-pending",200);
            assertThat(pending.path("status").asText()).isEqualTo("PENDING_CONFIRM");
            assertThat(ids(list(access,id,reader,secondAgent,subject,null))).doesNotContain(factId(pending));
            assertThat(ids(list(access,id,subject,firstAgent,null,"PENDING_CONFIRM"))).containsExactly(factId(pending));
            JsonNode thirdParty=machine(access,"POST",MEMORIES+"/create",
                    create(id,manager,firstAgent,"GROUP_MEMBER",subject,publicSource,"third_party_assertion","unconfirmed",true),marker+":third-party",200);
            var thirdPartyReview=govern(id,subject,firstAgent,thirdParty);thirdPartyReview.put("approved",true);
            rejects(access,"POST",MEMORIES+"/review",thirdPartyReview,marker+":third-party-review",403);
            assertThat(ids(list(access,id,reader,secondAgent,subject,null))).doesNotContain(factId(thirdParty));
            var managerReject=govern(id,manager,firstAgent,thirdParty);managerReject.put("approved",false);
            rejects(access,"POST",MEMORIES+"/review",managerReject,marker+":manager-third-party-reject",403);
            rejects(access,"POST",MEMORIES+"/forget",govern(id,manager,firstAgent,thirdParty),marker+":manager-third-party-forget",403);
            var subjectReject=govern(id,subject,firstAgent,thirdParty);subjectReject.put("approved",false);
            JsonNode rejectedCandidate=machine(access,"POST",MEMORIES+"/review",subjectReject,marker+":subject-third-party-reject",200);
            assertThat(rejectedCandidate.path("status").asText()).isEqualTo("REJECTED");
            assertThat(machine(access,"POST",MEMORIES+"/review",subjectReject,marker+":subject-third-party-reject",200)).isEqualTo(rejectedCandidate);
            JsonNode forgetCandidate=machine(access,"POST",MEMORIES+"/create",
                    create(id,manager,firstAgent,"GROUP_MEMBER",subject,publicSource,"third_party_forget","unconfirmed",true),marker+":third-party-forget-candidate",200);
            JsonNode forgottenCandidate=machine(access,"POST",MEMORIES+"/forget",govern(id,subject,firstAgent,forgetCandidate),marker+":subject-third-party-forget",200);
            assertThat(forgottenCandidate.path("status").asText()).isEqualTo("FORGOTTEN");
            rejects(access,"POST",MEMORIES+"/restore",govern(id,subject,firstAgent,forgottenCandidate),marker+":third-party-restore",403);
            var managerReview=govern(id,manager,firstAgent,pending);managerReview.put("approved",true);
            rejects(access,"POST",MEMORIES+"/review",managerReview,marker+":manager-review",403);
            var ownReview=govern(id,subject,firstAgent,pending);ownReview.put("approved",true);
            var stringRevision=new LinkedHashMap<>(ownReview);
            stringRevision.put("expected_revision",Long.toString(pending.path("revision").asLong()));
            rejects(access,"POST",MEMORIES+"/review",stringRevision,marker+":string-revision",400);
            var stringApproval=new LinkedHashMap<>(ownReview);stringApproval.put("approved","true");
            rejects(access,"POST",MEMORIES+"/review",stringApproval,marker+":string-approval",400);
            JsonNode approved=machine(access,"POST",MEMORIES+"/review",ownReview,marker+":review",200);
            assertThat(approved.path("status").asText()).isEqualTo("ACTIVE");
            assertThat(machine(access,"POST",MEMORIES+"/review",ownReview,marker+":review",200)).isEqualTo(approved);

            var correction=govern(id,subject,firstAgent,memberFact);
            correction.put("subject","member");correction.put("predicate","followup_time");
            correction.put("object_json",com.skillforge.core.agent.memory.MemoryContent.explicit(
                    json.readTree("{\"value\":\"evening\"}"),"preference").toString());correction.put("confidence",0.95);correction.put("importance",0.7);
            var legacyCorrection=new LinkedHashMap<>(correction);legacyCorrection.put("object_json","{\"value\":\"legacy\"}");
            rejects(access,"POST",MEMORIES+"/correct",legacyCorrection,marker+":legacy-correct",400);
            JsonNode corrected=machine(access,"POST",MEMORIES+"/correct",correction,marker+":correct",200);
            assertThat(factId(corrected)).isNotEqualTo(factId(memberFact));
            assertThat(corrected.path("derived_from").asText()).isEqualTo(factId(memberFact));
            assertThat(machine(access,"POST",MEMORIES+"/correct",correction,marker+":correct",200)).isEqualTo(corrected);
            rejects(access,"POST",MEMORIES+"/correct",correction,marker+":stale-correct",409);
            rejects(access,"POST",MEMORIES+"/forget",govern(id,manager,secondAgent,corrected),marker+":manager-forget",403);
            JsonNode forgotten=machine(access,"POST",MEMORIES+"/forget",govern(id,subject,secondAgent,corrected),marker+":forget",200);
            assertThat(forgotten.path("status").asText()).isEqualTo("FORGOTTEN");
            assertThat(ids(list(access,id,reader,secondAgent,subject,null))).doesNotContain(factId(corrected),factId(memberFact));
            rejects(access,"POST",MEMORIES+"/restore",govern(id,subject,secondAgent,corrected),marker+":stale-restore",409);
            JsonNode restored=machine(access,"POST",MEMORIES+"/restore",govern(id,subject,secondAgent,forgotten),marker+":restore",200);
            assertThat(restored.path("status").asText()).isEqualTo("ACTIVE");
            assertOwner(restored,id,subject);

            updateMember(access,id,subject,"member","left",1,marker+":subject-left",2);
            assertThat(ids(list(access,id,reader,secondAgent,subject,null))).contains(factId(restored));
            rejects(access,"GET",query(id,subject,firstAgent,subject,null),null,null,403);
            updateMember(access,id,reader,"member","left",1,marker+":reader-left",2);
            rejects(access,"GET",query(id,reader,secondAgent,subject,null),null,null,403);
            updateAgent(access,id,secondAgent,"removed",1,marker+":agent-removed",2);
            rejects(access,"GET",query(id,manager,secondAgent,subject,null),null,null,403);
            JsonNode beforeRestart=list(access,id,manager,firstAgent,subject,null);
            assertThat(ids(beforeRestart)).containsExactlyInAnyOrder(factId(publicFact),factId(restored),factId(approved));
            long previousPid=institutionHost.process.pid();
            institutionHost.process.destroy();assertThat(institutionHost.process.waitFor(15,TimeUnit.SECONDS)).isTrue();
            institutionHost=startHost(false,"reconstructed");assertThat(institutionHost.process.pid()).isNotEqualTo(previousPid);
            assertThat(list(access,id,manager,firstAgent,subject,null)).isEqualTo(beforeRestart);
            rejects(access,"GET",query(id,reader,firstAgent,subject,null),null,null,403);
            Files.writeString(evidence.resolve("verification.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "tenant_id",owner.id(),"group_binding_id",id,"public_fact_id",factId(publicFact),"member_fact_id",factId(restored),
                    "actual_http",true,"restricted_database_role",tenantDatabaseUser,"host_reconstructed",true,"model_provider","not_started",
                    "rejection_count",httpErrors.size())),StandardCharsets.UTF_8);
        } catch(Exception|Error problem) {
            failure=problem;
            try { Files.writeString(evidence.resolve("failure.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "failure_type",problem.getClass().getName(),"http_errors",httpErrors)),StandardCharsets.UTF_8); }
            catch(Exception diagnostic){problem.addSuppressed(diagnostic);}
            throw problem;
        } finally {
            try { stopHosts(); } catch(Exception|Error cleanup) { if(failure!=null)failure.addSuppressed(cleanup);else throw cleanup; }
        }
    }

    private Map<String,Object> ensure(String external,String lifecycle,JsonNode scope) {
        return Map.of("namespace","memory-fixture","external_group_ref",external,"lifecycle_ref",lifecycle,
                "business_scope_id",scope.path("business_scope_id").asText());
    }
    private JsonNode updateMember(JsonNode access,String group,JsonNode user,String role,String status,long revision,String event,long source) throws Exception {
        return machine(access,"POST",GROUPS+"/members/update",Map.of("group_binding_id",group,"end_user_id",user.path("end_user_id").asText(),
                "role",role,"status",status,"expected_revision",revision,"source_event_id",event,"source_revision",source),event,200);
    }
    private void updateAgent(JsonNode access,String group,String agent,String status,long revision,String event,long source) throws Exception {
        machine(access,"POST",GROUPS+"/agents/update",Map.of("group_binding_id",group,"agent_id",agent,"status",status,
                "expected_revision",revision,"source_event_id",event,"source_revision",source),event,200);
    }
    private JsonNode source(JsonNode access,String group,JsonNode author,String event,String text) throws Exception {
        return machine(access,"POST",GROUPS+"/messages",Map.of("group_binding_id",group,"source_event_id",event,"source_revision",1,
                "author",Map.of("kind","end_user","end_user_id",author.path("end_user_id").asText()),"content",List.of(Map.of("type","text","text",text))),event,200);
    }
    private Map<String,Object> create(String group,JsonNode operator,String agent,String scope,JsonNode subject,JsonNode source,String predicate,String value,boolean review) {
        var command=new LinkedHashMap<String,Object>();command.put("group_binding_id",group);command.put("operator_end_user_id",operator.path("end_user_id").asText());
        command.put("agent_id",agent);command.put("scope",scope);if(subject!=null)command.put("subject_end_user_id",subject.path("end_user_id").asText());
        command.put("source_message_ids",List.of(source.path("source_message_id").asText()));command.put("type","preference");
        command.put("subject",subject==null?"group":"member");command.put("predicate",predicate);command.put("object_json","{\"value\":\""+value+"\"}");
        command.put("confidence",0.9);command.put("importance",0.7);command.put("review_required",review);return command;
    }
    private Map<String,Object> govern(String group,JsonNode operator,String agent,JsonNode fact) {
        var command=new LinkedHashMap<String,Object>();command.put("group_binding_id",group);command.put("operator_end_user_id",operator.path("end_user_id").asText());
        command.put("agent_id",agent);command.put("fact_id",factId(fact));command.put("expected_revision",fact.path("revision").asLong());return command;
    }
    private String query(String group,JsonNode reader,String agent,JsonNode subject,String status) {
        String path=MEMORIES+"?group_binding_id="+group+"&reader_end_user_id="+reader.path("end_user_id").asText()+"&agent_id="+agent;
        if(subject!=null)path+="&subject_end_user_id="+subject.path("end_user_id").asText();if(status!=null)path+="&status="+status;return path;
    }
    private JsonNode list(JsonNode access,String group,JsonNode reader,String agent,JsonNode subject,String status) throws Exception {
        JsonNode facts=machine(access,"GET",query(group,reader,agent,subject,status),null,null,200);assertThat(facts.isArray()).isTrue();return facts;
    }
    private List<String> ids(JsonNode facts) {var values=new ArrayList<String>();facts.forEach(fact->values.add(factId(fact)));return values;}
    private String factId(JsonNode fact) {assertThat(fact.path("fact_id").asText()).isNotBlank();return fact.path("fact_id").asText();}
    private void assertOwner(JsonNode fact,String group,JsonNode subject) {
        assertThat(fact.path("group_binding_id").asText()).isEqualTo(group);
        assertThat(fact.path("scope").asText()).isEqualTo(subject==null?"GROUP":"GROUP_MEMBER");
        if(subject==null)assertThat(fact.path("subject_end_user_id").isNull()).isTrue();
        else assertThat(fact.path("subject_end_user_id").asText()).isEqualTo(subject.path("end_user_id").asText());
        var stored=jdbc.queryForMap("SELECT end_user_id,agent_id,subject_end_user_id,group_binding_id FROM agent_runtime_memory_fact_heads WHERE fact_id=?",factId(fact));
        assertThat(stored.get("agent_id")).isNull();
        assertThat(stored.get("end_user_id")).isNull();
        if(subject==null)assertThat(stored.get("subject_end_user_id")).isNull();
        else assertThat(((Number)stored.get("subject_end_user_id")).longValue()).isEqualTo(subject.path("end_user_id").asLong());
        assertThat(stored.get("group_binding_id").toString()).isEqualTo(group);
    }
    /** 拒绝不得写入事实、治理事件或新演进链；真实已提交源消息保持独立。 */
    private void rejects(JsonNode access,String method,String path,Object command,String key,int expected) throws Exception {
        long facts=jdbc.queryForObject("SELECT count(*) FROM agent_runtime_memory_fact_revisions",Long.class);
        long events=jdbc.queryForObject("SELECT count(*) FROM agent_runtime_memory_operations",Long.class);
        machine(access,method,path,command,key,expected);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_runtime_memory_fact_revisions",Long.class)).isEqualTo(facts);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_runtime_memory_operations",Long.class)).isEqualTo(events);
    }

    private void stopParentRuntime() {
        var scheduled=parentContext.getBean(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class);
        scheduled.destroy();assertThat(scheduled.getScheduledTasks()).isEmpty();
        var worker=parentContext.getBean(com.skillforge.runtime.starter.ExecutionHostWorker.class);
        worker.close();assertThat(worker.isClosed()).isTrue();assertThat(worker.isRunning()).isFalse();
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
                "system_prompt", "本地机构助手", "model_config_id", model, "config", Map.of("memory",Map.of("automatic_extraction",true))), null, 200);
        String id = created.path("id").asText();
        tenant(tenant, "POST", "/api/v1/agents/publish", Map.of("agent_id", id), null, 200); return id;
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


    /** 仅登记可发布的模型元数据，端口 1 无 Provider，所有 Runtime worker 关闭。 */
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

    /** 两个正式 bootJar 共用私有 PG/Redis，端口由 OS 原子分配；重启不重建业务库。 */
    private void startHosts(String label)throws Exception {
        platformHost=startHost(true,label);institutionHost=startHost(false,label);
        platformToken=success(exchange(platformHost,null,"POST","/api/v1/platform/auth/login",Map.of(
                "username","group-memory-platform-admin","password","SyntheticPlatform1234"),null),"POST","platform login",200).path("access_token").asText();
        assertThat(platformToken).isNotBlank();
    }

    /** 迁移owner只准备独立测试角色；两个实际宿主分别使用机构RLS及平台BYPASSRLS角色。 */
    private void prepareDatabaseRoles() {
        assertThat(jdbc.queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname=current_user",Boolean.class))
                .as("本套件由私有Testcontainers迁移owner创建宿主角色").isTrue();
        String suffix=Long.toString(SnowflakeIds.nextId());
        tenantDatabaseUser="gi05_tenant_"+suffix;platformDatabaseUser="gi05_platform_"+suffix;
        hostDatabasePassword=java.util.UUID.randomUUID().toString();
        jdbc.execute("CREATE ROLE "+tenantDatabaseUser+" LOGIN PASSWORD '"+hostDatabasePassword+"' NOSUPERUSER NOBYPASSRLS");
        jdbc.execute("CREATE ROLE "+platformDatabaseUser+" LOGIN PASSWORD '"+hostDatabasePassword+"' NOSUPERUSER BYPASSRLS");
        for(String role:List.of(tenantDatabaseUser,platformDatabaseUser)) {
            jdbc.execute("GRANT USAGE ON SCHEMA public TO "+role);
            jdbc.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO "+role);
            jdbc.execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO "+role);
        }
        jdbc.execute("GRANT EXECUTE ON FUNCTION public.resolve_runtime_authentication_tenant(text) TO "+tenantDatabaseUser);
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
        String workerId="gi05-"+SnowflakeIds.nextId();
        var builder=new ProcessBuilder(java.toString(),"-Dfile.encoding=UTF-8","-Dstdout.encoding=UTF-8","-Dstderr.encoding=UTF-8",
                "-Dloader.path="+modelCatalogFixtureJar().toUri(),
                "-Dloader.main="+(platform?"com.skillforge.platform.PlatformApplication":"com.skillforge.institution.InstitutionApplication"),
                "-cp",jar.toString(),"org.springframework.boot.loader.launch.PropertiesLauncher",
                "--spring.main.sources="+GroupMessageModelCatalogConfiguration.class.getName(),"--server.port=0","--server.address=127.0.0.1",
                "--agent-runtime.workers.auto-startup=false","--agent-runtime.worker-id="+workerId,
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
        env.put("PLATFORM_ADMIN_USERNAME","group-memory-platform-admin");env.put("PLATFORM_ADMIN_PASSWORD","SyntheticPlatform1234");
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
