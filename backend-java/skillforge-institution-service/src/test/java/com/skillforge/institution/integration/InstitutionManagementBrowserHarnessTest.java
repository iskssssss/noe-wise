package com.skillforge.institution.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.core.foundation.institution.usage.HeadquartersUsageActions;
import com.skillforge.core.model.ModelWrite;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import com.skillforge.runtime.api.port.ModelPort.ResponseFormatKind;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** GI11 双宿主人工浏览器夹具；独立容器及合成账号，服务驻留不等于产品验收通过。 */
@EnabledIfEnvironmentVariable(named="SKILLFORGE_GI11_BROWSER_HARNESS",matches="1")
class InstitutionManagementBrowserHarnessTest {
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    private static final Pattern LISTENING=Pattern.compile("Tomcat started on port (\\d+)");
    private static final String DEFINITIONS="/api/v1/tenant/agent-definitions";
    private static final String GRANTS="/api/v1/tenant/agent-grants";
    private static final String DEPLOYMENTS="/api/v1/tenant/agent-deployments";
    private static final List<String> PLATFORM_READ=List.of("foundation.tenants.read","foundation.models.read",
            "platform.agent_definitions.read","platform.institution_relations.read","platform.institution_management_grants.read","platform.usage.summary.read");
    private static final List<String> TENANT_READ=List.of("foundation.models.read","tenant.sub_institutions.read",
            "tenant.agent_definitions.read","tenant.agent_grants.read","tenant.agent_deployments.read",HeadquartersUsageActions.SUMMARY);
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<Host> hosts=new ArrayList<>();
    private final AtomicInteger httpFailures=new AtomicInteger();
    private final String run=UUID.randomUUID().toString().replace("-","");
    private final String platformUser="gi11-admin-"+run.substring(0,8);
    private final String platformPassword=generatedPassword();
    private final String tenantPassword=generatedPassword();
    private final String hostDatabasePassword=generatedPassword();
    private final String institutionSecret=secret(),platformSecret=secret();
    private Path evidence;
    private Host platform,institution;
    private Account platformAdmin;
    private boolean receiptTest() { return "1".equals(System.getenv("SKILLFORGE_AGENT_RECEIPT_HTTP_TEST")); }

    /** 显式 finish.json 或总窗口到期退出；所有宿主/容器在 finally 按本夹具所有权回收。 */
    @Test @Timeout(value=40,unit=TimeUnit.MINUTES)
    void serveIsolatedManagementUntilExplicitFinishReceipt() throws Exception {
        Path root=repositoryRoot();
        evidence=Path.of(required("SKILLFORGE_GI11_BROWSER_EVIDENCE_DIR")).toAbsolutePath().normalize();
        if(!evidence.startsWith(root.resolve(".runtime-logs")))throw new IllegalArgumentException("gi11_evidence_must_be_ignored_runtime_logs");
        if(Files.exists(evidence))throw new IllegalStateException("gi11_evidence_directory_already_used");
        Files.createDirectories(evidence);
        var postgres=new PostgreSQLContainer<>("pgvector/pgvector:pg16").withDatabaseName("gi11_browser").withUsername("skillforge").withPassword(secret());
        var redis=new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
        String status="SETUP_FAILED";Throwable failure=null;
        try {
            postgres.start();redis.start();
            Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())
                    .locations("classpath:db/migration").load().migrate();
            var jdbc=new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
            String tenantRole="gi11_tenant_"+run.substring(0,12),platformRole="gi11_platform_"+run.substring(0,12);
            if(!receiptTest())prepareRoles(jdbc,tenantRole,platformRole);
            platform=startHost(root,true,postgres,redis,receiptTest()?postgres.getUsername():platformRole);
            institution=startHost(root,false,postgres,redis,receiptTest()?postgres.getUsername():tenantRole);
            platformAdmin=login(true,null,platformUser,platformPassword);
            if(receiptTest()) {
                verifyAgentCreateReceipts(jdbc);
                status="RECEIPT_HTTP_PASSED";return;
            }
            Map<String,Object> fixture=initialize();
            fixture.put("status","AWAITING_BROWSER_ACCEPTANCE");fixture.put("run_id",run);
            fixture.put("platform_backend",platform.url());fixture.put("institution_backend",institution.url());
            fixture.put("ready_at",Instant.now().toString());fixture.put("deadline_at",Instant.now().plus(30,ChronoUnit.MINUTES).toString());
            fixture.put("runtime_workers_enabled",false);fixture.put("model_calls",0);
            fixture.put("platform_admin",credentials(platformAdmin));
            writePrivate("ready.private.json",fixture);
            writePrivate("ready.json",Map.of("status","AWAITING_BROWSER_ACCEPTANCE","run_id",run,
                    "platform_backend",platform.url(),"institution_backend",institution.url(),"credentials_file","ready.private.json"));
            status="BROWSER_NOT_COMPLETED";
            long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(30);
            while(System.nanoTime()<deadline) {
                if(Files.isRegularFile(evidence.resolve("finish.json"))) {
                    JsonNode finish=JSON.readTree(Files.readString(evidence.resolve("finish.json")));
                    if(!run.equals(finish.path("run_id").asText()))throw new IllegalStateException("gi11_finish_run_mismatch");
                    status="BROWSER_FINISH_RECEIPT_RECEIVED";return;
                }
                Thread.sleep(500);
            }
        } catch(Exception|Error problem) { failure=problem;throw problem; }
        finally {
            List<Throwable> cleanup=new ArrayList<>();
            for(int index=hosts.size()-1;index>=0;index--)try{hosts.get(index).close();}catch(Throwable problem){cleanup.add(problem);}
            try{redis.stop();}catch(Throwable problem){cleanup.add(problem);}
            try{postgres.stop();}catch(Throwable problem){cleanup.add(problem);}
            writePrivate("harness-result.json",Map.of("status",status,"run_id",run,"finished_at",Instant.now().toString(),
                    "cleanup_failures",cleanup.size(),"product_acceptance_verdict","NOT_ASSERTED_BY_HARNESS"));
            if(!cleanup.isEmpty()) {
                var problem=new IllegalStateException("gi11_owned_resource_cleanup_failed");cleanup.forEach(problem::addSuppressed);
                if(failure!=null)failure.addSuppressed(problem);else throw problem;
            }
        }
    }

    /** 全新隔离库中经双宿主真实 HTTP 核对，模型只登记元数据，不调用模型。 */
    private void verifyAgentCreateReceipts(JdbcTemplate jdbc) throws Exception {
        var target=tenant("receipt","回执测试机构");
        String model=model();
        var definition=call(platformAdmin,"POST","/api/v1/platform/agent-definitions",definition("回执测试助手",model),"receipt-definition",200);
        String definitionId=definition.path("definition_id").asText();
        call(platformAdmin,"POST","/api/v1/platform/agent-definitions/publish",Map.of("definition_id",definitionId,"expected_revision",definition.path("draft_revision").asLong()),"receipt-publish",200);
        var grantBody=map("definition_id",definitionId,"target_tenant_id",target.tenantId(),"actions",List.of("deploy","execute"),"allow_regrant",false,"remaining_depth",0);
        String grantPath="/api/v1/platform/agent-grants";
        assertEquals("NOT_FOUND",call(platformAdmin,"POST",grantPath+"/receipt/query",grantBody,"receipt-grant",200).path("status").asText());
        var grant=call(platformAdmin,"POST",grantPath,grantBody,"receipt-grant",200);
        assertEquals(grant.path("grant_id"),call(platformAdmin,"POST",grantPath+"/receipt/query",grantBody,"receipt-grant",200).path("result").path("grant_id"));
        var changedGrant=new LinkedHashMap<>(grantBody);changedGrant.put("actions",List.of("deploy"));
        raw(platformAdmin,"POST",grantPath+"/receipt/query",changedGrant,"receipt-grant",409);
        var payload=map("definition_id",definitionId,"grant_id",grant.path("grant_id").asText(),"integration_id",null,"external_agent_ref","receipt-instance",
                "resource_bindings",List.of(),"delegation_bindings",List.of(),"parameters",Map.of());
        var body=Map.of("tenant_id",target.tenantId(),"payload",payload);
        String path="/api/v1/platform/agent-deployments";
        assertEquals("NOT_FOUND",call(platformAdmin,"POST",path+"/receipt/query",body,"receipt-deploy",200).path("status").asText());
        var deployed=call(platformAdmin,"POST",path,body,"receipt-deploy",200);
        assertEquals(deployed.path("deployment_id"),call(platformAdmin,"POST",path+"/receipt/query",body,"receipt-deploy",200).path("result").path("deployment_id"));
        call(platformAdmin,"POST",path,body,"receipt-second-key",200);
        assertEquals(deployed.path("deployment_id"),call(platformAdmin,"POST",path+"/receipt/query",body,"receipt-second-key",200).path("result").path("deployment_id"));
        var other=reader(platformAdmin,"receipt-reader",List.of("platform.agent_grants.read","platform.agent_deployments.read"));
        assertEquals("NOT_FOUND",call(other,"POST",grantPath+"/receipt/query",grantBody,"receipt-grant",200).path("status").asText());
        assertEquals("NOT_FOUND",call(other,"POST",path+"/receipt/query",body,"receipt-deploy",200).path("status").asText());
        raw(other,"POST",path,body,"receipt-denied",403);
        var changedPayload=new LinkedHashMap<>(payload);changedPayload.put("name","另一个名字");
        raw(platformAdmin,"POST",path+"/receipt/query",Map.of("tenant_id",target.tenantId(),"payload",changedPayload),"receipt-deploy",409);
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM nw_agent_definition_grants WHERE target_tenant_id=?",Integer.class,Long.valueOf(target.tenantId())));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM nw_agent_deployments WHERE tenant_id=?",Integer.class,Long.valueOf(target.tenantId())));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM nw_agent_deployment_create_requests WHERE tenant_id=?",Integer.class,Long.valueOf(target.tenantId())));
    }

    /** 仅使用正式管理接口创建可读、可变更及跨层授权样本，不直接补写业务事实。 */
    private Map<String,Object> initialize() throws Exception {
        var hq=tenant("hq","总部一");var otherHq=tenant("other-hq","总部二");
        var child=tenant("child","直属机构");var grandchild=tenant("leaf","下级机构");var unattached=tenant("unattached","待挂接机构");
        var childRelation=relation(child,hq);var leafRelation=relation(grandchild,child);
        String model=model();
        for(var account:List.of(hq,otherHq,child,grandchild,unattached))
            call(account,"PUT","/api/v1/settings/model-selections",Map.of("llm_model_config_id",model),null,200);
        var draft=call(hq,"POST",DEFINITIONS,definition("GI11可发布草稿",model),"draft-create",200);
        var created=call(hq,"POST",DEFINITIONS,definition("GI11已发布共享助手",model),"definition-create",200);
        var published=call(hq,"POST",DEFINITIONS+"/publish",Map.of("definition_id",created.path("definition_id").asText(),
                "expected_revision",created.path("draft_revision").asLong()),"definition-publish",200);
        String definition=published.path("definition_id").asText();
        var rootGrant=grant(hq,child,definition,null,true,2,"root-grant");
        var alternativeRoot=grant(hq,child,definition,null,true,2,"alternative-root-grant");
        var leafGrant=grant(child,grandchild,definition,rootGrant,true,1,"leaf-grant");
        var alternative=grant(child,grandchild,definition,rootGrant,false,0,"alternative-leaf-grant");
        var childDeployment=call(child,"POST",DEPLOYMENTS,map("definition_id",definition,"grant_id",rootGrant.path("grant_id").asText(),
                "integration_id",null,"external_agent_ref","gi11-child-deployment","resource_bindings",List.of(),
                "delegation_bindings",List.of(),"parameters",Map.of("clinic","直属门店")),"child-deployment",200);
        var deployed=call(grandchild,"POST",DEPLOYMENTS,map("definition_id",definition,"grant_id",leafGrant.path("grant_id").asText(),
                "integration_id",null,"external_agent_ref","gi11-leaf-deployment","resource_bindings",List.of(),
                "delegation_bindings",List.of(),"parameters",Map.of("clinic","下级门店")),"leaf-deployment",200);
        List<JsonNode> permits=new ArrayList<>();
        for(String action:List.of("tenant.agent_deployments.read","tenant.agent_deployments.create","tenant.agent_deployments.bind",
                "tenant.agent_deployments.switch_authorization",HeadquartersUsageActions.SUMMARY,HeadquartersUsageActions.DETAILS,HeadquartersUsageActions.EVENTS))
            permits.add(call(platformAdmin,"POST","/api/v1/platform/institution-management-grants",Map.of(
                    "actor_tenant_id",hq.tenantId(),"target_tenant_id",child.tenantId(),"action",action,
                    "expected_relation_revision",childRelation.path("revision").asLong()),"permit-"+action,200));
        var platformReader=reader(platformAdmin,"platform-reader",PLATFORM_READ);
        var hqReader=reader(hq,"hq-reader",TENANT_READ);
        var detailsReader=reader(hq,"hq-details-reader",List.of(HeadquartersUsageActions.DETAILS));
        var eventsReader=reader(hq,"hq-events-reader",List.of(HeadquartersUsageActions.EVENTS));
        call(platformReader,"GET","/api/v1/platform/institution-relations?page=1&page_size=100",null,null,200);
        for(String view:List.of("summary","details","events"))call(hq,"GET","/api/v1/tenant/usage/"+view,null,null,200);
        call(hqReader,"GET","/api/v1/tenant/usage/summary",null,null,200);
        raw(hqReader,"GET","/api/v1/tenant/usage/details",null,null,403);
        call(detailsReader,"GET","/api/v1/tenant/usage/details",null,null,200);
        raw(detailsReader,"GET","/api/v1/tenant/usage/summary",null,null,403);
        raw(detailsReader,"GET","/api/v1/tenant/usage/events",null,null,403);
        call(eventsReader,"GET","/api/v1/tenant/usage/events",null,null,200);
        raw(eventsReader,"GET","/api/v1/tenant/usage/summary",null,null,403);
        raw(eventsReader,"GET","/api/v1/tenant/usage/details",null,null,403);
        raw(hqReader,"POST",DEFINITIONS,definition("无权限操作",model),"reader-denied",403);
        raw(platformReader,"POST","/api/v1/platform/institution-relations/attach",Map.of("child_tenant_id",unattached.tenantId(),
                "parent_tenant_id",hq.tenantId(),"expected_revision",1),"platform-reader-denied",403);
        var result=new LinkedHashMap<String,Object>();
        result.put("headquarters",credentials(hq));result.put("other_headquarters",credentials(otherHq));
        result.put("child",credentials(child));result.put("grandchild",credentials(grandchild));result.put("unattached",credentials(unattached));
        result.put("platform_reader",credentials(platformReader));result.put("headquarters_reader",credentials(hqReader));
        result.put("headquarters_details_reader",credentials(detailsReader));result.put("headquarters_events_reader",credentials(eventsReader));
        result.put("platform_reader_permissions",PLATFORM_READ);result.put("headquarters_reader_permissions",TENANT_READ);
        result.put("headquarters_details_reader_permissions",List.of(HeadquartersUsageActions.DETAILS));
        result.put("headquarters_events_reader_permissions",List.of(HeadquartersUsageActions.EVENTS));
        result.put("model_id",model);result.put("definition",published);result.put("draft_definition",draft);
        result.put("root_grant",rootGrant);result.put("alternative_root_grant",alternativeRoot);
        result.put("leaf_grant",leafGrant);result.put("alternative_leaf_grant",alternative);
        result.put("deployment",deployed);result.put("child_deployment",childDeployment);result.put("child_relation",childRelation);result.put("leaf_relation",leafRelation);
        result.put("headquarters_management_permits",permits);
        result.put("usage_evidence","empty_real_ledger_no_synthetic_usage_events");
        return result;
    }

    private Account tenant(String label,String name) throws Exception {
        String slug="gi11-"+run.substring(0,10)+"-"+label;
        var created=call(platformAdmin,"POST","/api/v1/admin/tenants",Map.of("tenant_name",name,"tenant_slug",slug,
                "plan_code","free","admin_username","gi11-admin","admin_password",tenantPassword),null,201);
        var account=login(false,slug,"gi11-admin",tenantPassword);
        assertEquals(created.path("id").asText(),account.tenantId());
        var quotas=call(platformAdmin,"GET","/api/v1/admin/foundation/catalog/tenant-entitlements?tenant_id="+account.tenantId(),null,null,200);
        ObjectNode overrides=((ObjectNode)quotas.path("overrides")).deepCopy();overrides.put("agents",12);overrides.put("members",12);overrides.put("sub_institutions",12);
        call(platformAdmin,"PUT","/api/v1/admin/foundation/catalog/tenant-quota",Map.of("tenant_id",account.tenantId(),
                "limits",overrides,"expected_revision",quotas.path("revision").asLong()),null,200);
        return account;
    }

    private JsonNode relation(Account child,Account parent) throws Exception {
        return call(platformAdmin,"POST","/api/v1/platform/institution-relations/attach",Map.of("child_tenant_id",child.tenantId(),
                "parent_tenant_id",parent.tenantId(),"expected_revision",1),"attach-"+child.tenantId(),200);
    }

    private JsonNode grant(Account actor,Account target,String definition,JsonNode parent,boolean regrant,int depth,String key) throws Exception {
        Instant expiry=parent==null?Instant.now().plus(3,ChronoUnit.HOURS):Instant.parse(parent.path("expires_at").asText()).minusSeconds(60);
        return call(actor,"POST",GRANTS,map("definition_id",definition,"target_tenant_id",target.tenantId(),
                "parent_grant_id",parent==null?null:parent.path("grant_id").asText(),"actions",List.of("deploy","execute"),
                "expires_at",expiry.truncatedTo(ChronoUnit.SECONDS).toString(),
                "allow_regrant",regrant,"remaining_depth",depth),key,200);
    }

    private Map<String,Object> definition(String name,String model) {
        return Map.of("name",name,"price_type","free","price_cents",0,"distribution_policy","AUTHORIZED_DEPLOY","definition",Map.of(
                "system_prompt","管理验收助手 {{parameters.clinic}}","role_description","共享管理助手","model_config_id",model,
                "execution_config",Map.of(),"capabilities",List.of(),"resource_slots",List.of(),"delegation_slots",List.of()));
    }

    private String model() throws Exception {
        // 正式宿主不承认自定义服务未经核实的JSON_OBJECT；使用已有核实能力，只登记元数据，不探测或执行。
        var value=new ModelWrite(null,"GI11元数据模型（不执行）","LLM",true,"aliyun-bailian","bailian-cn-beijing","2026-09-15-global-v1",
                "openai-chat-completions","bearer-api-key","https://dashscope.aliyuncs.com/compatible-mode/v1","qwen-plus",secret(),
                4096,32768,1024,0.2,"DISABLED",30,null,null,null,null,null,"CNY");
        var catalog=ModelProviderCatalog.standard();
        var resolved=catalog.resolve(new ModelProviderCatalog.Selection(value.providerId(),value.serviceId(),value.profileVersion(),value.modelId(),
                ModelProviderCatalog.ModelProtocol.fromId(value.protocol()),ModelProviderCatalog.AuthenticationScheme.fromId(value.authenticationScheme()),value.endpoint()));
        catalog.validateCapabilities(resolved,Set.of(ResponseFormatKind.TEXT,ResponseFormatKind.JSON_OBJECT),true,false);
        JsonNode command=JSON.copy().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).valueToTree(value);
        var model=call(platformAdmin,"POST","/api/v1/admin/models",command,null,201);
        call(platformAdmin,"POST","/api/v1/admin/models/publish",Map.of("id",model.path("id").asText(),
                "expected_revision",model.path("revision").asLong()),null,200);
        return model.path("id").asText();
    }

    private Account reader(Account administrator,String username,List<String> permissions) throws Exception {
        String access=administrator.platform()?"/api/v1/admin/foundation/access":"/api/v1/tenant/foundation/access";
        var role=call(administrator,"POST",access+"/role",Map.of("code",username.replace('-','_'),"name",username,
                "data_scope","all","permissions",permissions,"department_ids",List.of()),null,200);
        assertFalse(role.path("administrator").asBoolean());
        var actual=new TreeSet<String>();role.path("permissions").forEach(value->actual.add(value.asText()));
        assertEquals(new TreeSet<>(permissions),actual,"受限角色必须仅持有本fixture声明的精确权限");
        String password=generatedPassword();
        call(administrator,"POST",access+"/member",Map.of("username",username,"display_name",username,
                "password",password,"role_ids",List.of(role.path("id").asText())),null,200);
        return login(administrator.platform(),administrator.slug(),username,password);
    }

    private Account login(boolean isPlatform,String slug,String username,String password) throws Exception {
        var account=new Account(isPlatform,null,slug,username,password,null);
        var body=map("username",username,"password",password);if(!isPlatform)body.put("tenant_slug",slug);
        var response=call(account,"POST",isPlatform?"/api/v1/platform/auth/login":"/api/v1/auth/login",body,null,200);
        return new Account(isPlatform,isPlatform?null:response.path("tenant_id").asText(),slug,username,password,response.path("access_token").asText());
    }

    private JsonNode call(Account actor,String method,String path,Object body,String key,int expected) throws Exception {
        return raw(actor,method,path,body,key,expected).path("data");
    }

    /** 错误仅包含端点、状态和安全错误码，禁止断言输出账号、令牌或请求正文。 */
    private JsonNode raw(Account actor,String method,String path,Object body,String key,int expected) throws Exception {
        var host=actor.platform()&&!path.startsWith("/api/v1/platform/agent-deployments")?platform:institution;
        var request=HttpRequest.newBuilder(URI.create(host.url()+path)).timeout(Duration.ofSeconds(30)).header("Content-Type","application/json");
        if(actor.token()!=null)request.header(actor.platform()?"X-Platform-Token":"Authorization","Bearer "+actor.token());
        if(key!=null)request.header("Idempotency-Key",run+":"+key);
        var response=http.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        var json=JSON.readTree(response.body());
        if(expected!=response.statusCode())writePrivate("http-failure-"+httpFailures.incrementAndGet()+".private.json",
                map("method",method,"path",path,"expected_status",expected,"actual_status",response.statusCode(),"response",json));
        assertEquals(expected,response.statusCode(),method+" "+path+" code="+json.path("code").asText());
        return json;
    }

    private void prepareRoles(JdbcTemplate jdbc,String tenant,String platformRole) {
        jdbc.execute("CREATE ROLE "+tenant+" LOGIN PASSWORD '"+hostDatabasePassword+"' NOSUPERUSER NOBYPASSRLS");
        jdbc.execute("CREATE ROLE "+platformRole+" LOGIN PASSWORD '"+hostDatabasePassword+"' NOSUPERUSER BYPASSRLS");
        for(String role:List.of(tenant,platformRole)) {
            jdbc.execute("GRANT USAGE ON SCHEMA public TO "+role);
            jdbc.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO "+role);
            jdbc.execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO "+role);
            jdbc.execute("GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO "+role);
        }
    }

    private Host startHost(Path root,boolean isPlatform,PostgreSQLContainer<?> postgres,GenericContainer<?> redis,String databaseRole) throws Exception {
        String module=isPlatform?"skillforge-platform-service":"skillforge-institution-service";
        Path directory=evidence.resolve(isPlatform?"platform":"institution");Files.createDirectories(directory);
        Path jar=directory.resolve(module+".jar");Files.copy(root.resolve("backend-java/"+module+"/build/libs/"+module+".jar"),jar);
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java");
        Path log=directory.resolve("host.log");
        var command=new ProcessBuilder(java.toString(),"-jar",jar.toString(),"--server.port=0","--server.address=127.0.0.1",
                "--spring.flyway.enabled=false","--agent-runtime.workers.auto-startup=false",
                "--spring.cloud.nacos.discovery.enabled=false","--spring.cloud.nacos.config.enabled=false");
        command.directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        var env=command.environment();var inherited=new HashMap<>(env);env.clear();
        for(String name:List.of("PATH","Path","SystemRoot","WINDIR","TEMP","TMP","USERPROFILE","HOME","APPDATA","LOCALAPPDATA","LANG"))
            if(inherited.containsKey(name))env.put(name,inherited.get(name));
        String databasePassword=receiptTest()?postgres.getPassword():hostDatabasePassword;
        env.put("SPRING_DATASOURCE_URL",postgres.getJdbcUrl());env.put("SPRING_DATASOURCE_USERNAME",databaseRole);env.put("SPRING_DATASOURCE_PASSWORD",databasePassword);
        env.put("PLATFORM_DATASOURCE_USERNAME",databaseRole);env.put("PLATFORM_DATASOURCE_PASSWORD",databasePassword);
        env.put("REDIS_HOST",redis.getHost());env.put("REDIS_PORT",redis.getMappedPort(6379).toString());env.put("REDIS_DATABASE","0");
        env.put("SECRET_KEY",institutionSecret);env.put("PLATFORM_SECRET_KEY",platformSecret);
        env.put("PLATFORM_ADMIN_USERNAME",platformUser);env.put("PLATFORM_ADMIN_PASSWORD",platformPassword);
        env.put("SKILLFORGE_DEBUG","true");env.put("INSTITUTION_DB_POOL_SIZE","16");env.put("INSTITUTION_DB_POOL_MIN_IDLE","2");
        String browserOrigins=System.getenv("SKILLFORGE_GI11_BROWSER_ALLOWED_ORIGINS");
        if(browserOrigins!=null && !browserOrigins.isBlank())env.put("SKILLFORGE_WEB_ALLOWED_ORIGINS",browserOrigins);
        var host=new Host(command.start(),log);hosts.add(host);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(120);
        while(System.nanoTime()<deadline) {
            if(!host.process.isAlive())throw new IllegalStateException("gi11_host_exited_log="+log);
            var match=LISTENING.matcher(Files.readString(log));
            if(match.find()) {
                host.port=Integer.parseInt(match.group(1));
                try {
                    var response=http.send(HttpRequest.newBuilder(URI.create(host.url()+(isPlatform?"/actuator/health":"/health/ready")))
                            .timeout(Duration.ofSeconds(3)).GET().build(),HttpResponse.BodyHandlers.discarding());
                    if(response.statusCode()==200)return host;
                } catch(IOException ignored) { /* 监听事件与依赖就绪共同确认，不重启或替换宿主。 */ }
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("gi11_host_not_ready_log="+log);
    }

    private Map<String,Object> credentials(Account account) {
        return map("tenant_id",account.tenantId(),"tenant_slug",account.slug(),"username",account.username(),"password",account.password());
    }

    private void writePrivate(String name,Object value) throws IOException {
        Path path=evidence.resolve(name),temporary=evidence.resolve(name+".tmp");
        Files.writeString(temporary,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE);
    }

    private static LinkedHashMap<String,Object> map(Object... pairs) {
        var result=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;
    }
    private static String generatedPassword(){return "Gi11!"+secret();}
    private static String secret(){byte[] value=new byte[32];new SecureRandom().nextBytes(value);return HexFormat.of().formatHex(value);}
    private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException("missing_harness_environment "+name);return value;}
    private static Path repositoryRoot(){Path root=Path.of("").toAbsolutePath();while(root!=null&&!Files.isDirectory(root.resolve(".git"))&&!Files.exists(root.resolve("AGENTS.md")))root=root.getParent();if(root==null)throw new IllegalStateException("repository_root_missing");return root;}
    private record Account(boolean platform,String tenantId,String slug,String username,String password,String token){@Override public String toString(){return "GI11Account[REDACTED]";}}
    private static final class Host implements AutoCloseable {
        private final Process process;private final Path log;private int port;
        private Host(Process process,Path log){this.process=process;this.log=log;}
        String url(){return "http://127.0.0.1:"+port;}
        @Override public void close() {
            boolean interrupted=Thread.interrupted();
            try {
                process.destroy();
                try{if(process.waitFor(10,TimeUnit.SECONDS))return;}catch(InterruptedException problem){interrupted=true;}
                process.destroyForcibly();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                while(process.isAlive()&&System.nanoTime()<deadline)try{process.waitFor(250,TimeUnit.MILLISECONDS);}catch(InterruptedException problem){interrupted=true;}
                if(process.isAlive())throw new IllegalStateException("gi11_host_cleanup_failed_log="+log);
            } finally {if(interrupted)Thread.currentThread().interrupt();}
        }
    }
}
