package com.skillforge.institution.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.core.shared.config.SkillForgeProperties;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.institution.security.HmacSignatureVerifier;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** GI-15 真实双宿主：平台树变更、总部直属创建、当前目录及重启后的原回执。 */
class InstitutionHierarchyHttpIntegrationTest extends AbstractIntegrationTest {
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SkillForgeProperties properties;
    @LocalServerPort private int institutionPort;
    private int platformPort;
    private String platformToken;
    private Process platform;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** 本批只操作管理域；正式迁移和认证保留，未创建需要模型推进的 Run。 */
    @DynamicPropertySource static void runtime(DynamicPropertyRegistry registry) {
        registry.add("agent-runtime.workers.auto-startup",()->"false");
    }

    /** 失败时同样释放当前测试拥有的子进程与线程身份。 */
    @AfterEach void cleanup() throws Exception {
        stopPlatform();
        com.skillforge.common.context.TenantContext.clear();
        com.skillforge.common.context.RequestContext.clear();
    }

    /** 当前动作/目标先于树变更；每次成功关系都可恢复，拒绝不产生半个机构或变更历史。 */
    @Test @Timeout(240)
    void platformAndHeadquartersManageOnlyTheirCurrentExplicitRelations() throws Exception {
        String marker="hierarchy-"+SnowflakeIds.nextId();
        Path evidence=repositoryRoot().resolve("backend-java/skillforge-institution-service/build/reports/institution-hierarchy/"+marker);
        Files.createDirectories(evidence);startPlatform(evidence,"first");
        Account headquarters=createAccount(marker,"h1"),otherHeadquarters=createAccount(marker,"h2");
        Account clinic=createAccount(marker,"c1"),otherClinic=createAccount(marker,"c2"),leaf=createAccount(marker,"c11");
        Map<String,Object> attach=change(clinic.id(),headquarters.id(),1L);
        JsonNode attached=platform("POST","/api/v1/platform/institution-relations/attach",attach,marker+":attach-c1",200);
        assertRelation(attached,clinic.id(),headquarters.id(),2L);
        assertThat(platform("POST","/api/v1/platform/institution-relations/attach",attach,marker+":attach-c1",200)).isEqualTo(attached);
        platform("POST","/api/v1/platform/institution-relations/attach",change(clinic.id(),otherHeadquarters.id(),1L),marker+":attach-c1",409);
        platform("POST","/api/v1/platform/institution-relations/attach",change(leaf.id(),clinic.id(),1L),marker+":attach-c11",200);
        var beforeCycle=facts();
        platform("POST","/api/v1/platform/institution-relations/attach",change(headquarters.id(),leaf.id(),1L),marker+":cycle",409);
        assertThat(facts()).as("拒绝成环不能修改树、历史、机构或映射").isEqualTo(beforeCycle);

        JsonNode machine=platform("POST","/api/v1/platform/management-principals",Map.of(
                "name","仅指定机构的树管理","scopes",List.of("platform.institution_relations.read","platform.institution_relations.attach"),
                "target_tenant_ids",List.of(headquarters.id(),clinic.id()),"target_definition_ids",List.of(),"target_namespaces",List.of()),marker+":machine",200);
        JsonNode visible=machine(machine,"GET","/api/v1/platform/institution-relations?page=1&page_size=100",null,null,200);
        assertThat(visible.path("total").asInt()).isEqualTo(2);
        assertThat(visible.path("items").findValuesAsText("tenant_id")).containsExactlyInAnyOrder(headquarters.id(),clinic.id());
        var beforeDenied=facts();
        machine(machine,"POST","/api/v1/platform/institution-relations/attach",change(otherClinic.id(),headquarters.id(),1L),marker+":foreign-target",403);
        assertThat(facts()).isEqualTo(beforeDenied);

        // 同时释放两次相反挂接；正式树行锁必须裁定一个成功、一个冲突，不能形成环。
        var release=new CountDownLatch(1);
        var first=CompletableFuture.supplyAsync(()->race(release,change(otherHeadquarters.id(),otherClinic.id(),1L),marker+":race-a"));
        var second=CompletableFuture.supplyAsync(()->race(release,change(otherClinic.id(),otherHeadquarters.id(),1L),marker+":race-b"));
        release.countDown();
        assertThat(List.of(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);

        String headquartersToken=tenantLogin(headquarters);
        JsonNode emptyDirectory=institution(headquartersToken,"GET","/api/v1/tenant/sub-institutions?page=1&page_size=100",null,null,200);
        assertThat(emptyDirectory.path("items").isEmpty()).as("平台挂接本身不自动授予总部目录权").isTrue();
        setChildQuota(headquarters.id(),2L);
        Map<String,Object> provision=Map.of("namespace",marker,"external_institution_ref","hq-child","name","总部显式新建直属门诊","default_plan_code","free");
        JsonNode created=institution(headquartersToken,"POST","/api/v1/tenant/sub-institutions/provision",provision,marker+":provision-child",200);
        String childId=created.path("tenant_id").asText();
        assertThat(childId).matches("[1-9][0-9]*");
        assertThat(created.path("parent_tenant_id").asText()).isEqualTo(headquarters.id());
        assertThat(institution(headquartersToken,"POST","/api/v1/tenant/sub-institutions/provision",provision,marker+":provision-child",200)).isEqualTo(created);
        JsonNode directory=institution(headquartersToken,"GET","/api/v1/tenant/sub-institutions?page=1&page_size=100",null,null,200);
        assertThat(directory.path("total").asInt()).isEqualTo(1);
        assertThat(directory.path("items").findValuesAsText("tenant_id")).containsExactly(childId);
        JsonNode mapping=platform("POST","/api/v1/platform/institutions/associate",Map.of(
                "tenant_id",headquarters.id(),"namespace",marker,"external_institution_ref","headquarters"),marker+":hq-mapping",200);
        JsonNode executionOnly=executionCredential(headquarters.id(),mapping.path("integration_id").asText(),List.of("sessions.read"),marker+":execution-only");
        institutionMachine(executionOnly,"GET","/api/v1/tenant/sub-institutions",null,null,403);
        JsonNode directoryOnly=executionCredential(headquarters.id(),mapping.path("integration_id").asText(),List.of("tenant.sub_institutions.read"),marker+":directory-only");
        assertThat(institutionMachine(directoryOnly,"GET","/api/v1/tenant/sub-institutions",null,null,200).path("items").findValuesAsText("tenant_id"))
                .containsExactly(childId);
        var beforeMachineDenied=facts();
        institutionMachine(directoryOnly,"POST","/api/v1/tenant/sub-institutions/provision",Map.of(
                "namespace",marker,"external_institution_ref","not-authorized","name","未授权创建","default_plan_code","free"),marker+":read-only-create",403);
        assertThat(facts()).isEqualTo(beforeMachineDenied);
        JsonNode headquartersMachine=executionCredential(headquarters.id(),mapping.path("integration_id").asText(),
                List.of("tenant.sub_institutions.read","tenant.sub_institutions.provision"),marker+":hq-machine");
        setChildQuota(headquarters.id(),3L);
        Map<String,Object> machineProvision=Map.of("namespace",marker,"external_institution_ref","machine-child","name","明确机构管理机器创建","default_plan_code","free");
        JsonNode machineChild=institutionMachine(headquartersMachine,"POST","/api/v1/tenant/sub-institutions/provision",machineProvision,marker+":machine-provision",200);
        assertThat(machineChild.path("parent_tenant_id").asText()).isEqualTo(headquarters.id());
        assertThat(institutionMachine(headquartersMachine,"POST","/api/v1/tenant/sub-institutions/provision",machineProvision,marker+":machine-provision",200)).isEqualTo(machineChild);
        institutionMachine(headquartersMachine,"POST","/api/v1/open-platform/end-users/ensure",Map.of("external_user_ref","not-execution-permission"),null,403);
        platform("POST","/api/v1/platform/institution-relations/detach",Map.of("child_tenant_id",machineChild.path("tenant_id").asText(),
                "expected_revision",machineChild.path("revision").asLong()),marker+":detach-machine-child",200);
        setChildQuota(headquarters.id(),2L);
        var beforeQuota=facts();
        institution(headquartersToken,"POST","/api/v1/tenant/sub-institutions/provision",Map.of(
                "namespace",marker,"external_institution_ref","over-quota","name","超额门诊","default_plan_code","free"),marker+":over-quota",409);
        assertThat(facts()).as("容量拒绝不留下机构、映射、关系历史或幂等半事实").isEqualTo(beforeQuota);

        String clinicToken=tenantLogin(clinic);
        institution(clinicToken,"POST","/api/v1/chat/end-users",Map.of("display_name",marker+":private-patient"),null,200);
        assertThat(institution(headquartersToken,"GET","/api/v1/chat/end-users",null,null,200).toString())
                .doesNotContain(marker+":private-patient");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_tenant_members WHERE tenant_id=?",Long.class,Long.parseLong(childId))).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_service_principals WHERE tenant_id=?",Long.class,Long.parseLong(childId))).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_external_institution_mappings WHERE namespace=? AND external_institution_ref=? AND tenant_id=?",
                Long.class,marker,"hq-child",Long.parseLong(childId))).isEqualTo(1L);

        JsonNode moved=platform("POST","/api/v1/platform/institution-relations/move",change(childId,otherHeadquarters.id(),created.path("revision").asLong()),marker+":move-child",200);
        assertThat(institution(headquartersToken,"GET","/api/v1/tenant/sub-institutions",null,null,200).path("items").isEmpty()).isTrue();
        assertThat(institution(headquartersToken,"POST","/api/v1/tenant/sub-institutions/provision",provision,marker+":provision-child",200))
                .as("旧创建键只读原回执，不把已迁移机构重新挂回").isEqualTo(created);
        JsonNode returned=platform("POST","/api/v1/platform/institution-relations/move",change(childId,headquarters.id(),moved.path("revision").asLong()),marker+":return-child",200);
        assertThat(institution(headquartersToken,"GET","/api/v1/tenant/sub-institutions",null,null,200).path("items").isEmpty())
                .as("重新挂回不能复活旧关系引用上的目录grant").isTrue();
        platform("POST","/api/v1/platform/institution-relations/detach",Map.of("child_tenant_id",childId,"expected_revision",returned.path("revision").asLong()),marker+":detach-child",200);
        JsonNode current=platform("GET","/api/v1/platform/institution-relations?page=1&page_size=100",null,null,200);
        stopPlatform();startPlatform(evidence,"restarted");
        assertThat(platform("GET","/api/v1/platform/institution-relations?page=1&page_size=100",null,null,200)).isEqualTo(current);
        assertThat(platform("POST","/api/v1/platform/institution-relations/attach",attach,marker+":attach-c1",200))
                .as("平台进程重启仍读原挂接回执").isEqualTo(attached);
        assertThat(institution(headquartersToken,"POST","/api/v1/tenant/sub-institutions/provision",provision,marker+":provision-child",200)).isEqualTo(created);
        var contract=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+platformPort+"/v3/api-docs")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(contract.statusCode()).isEqualTo(200);Files.writeString(evidence.resolve("platform-openapi.json"),contract.body());
    }

    /** 为本次目标保留原八项覆盖，只改变子机构额度及其精确CAS修订。 */
    private void setChildQuota(String tenantId,long limit) throws Exception {
        String path="/api/v1/admin/foundation/catalog/tenant-entitlements?tenant_id="+tenantId;
        JsonNode current=platform("GET",path,null,null,200);
        ObjectNode overrides=((ObjectNode)current.path("overrides")).deepCopy();overrides.put("sub_institutions",limit);
        platform("PUT","/api/v1/admin/foundation/catalog/tenant-quota",Map.of("tenant_id",tenantId,
                "expected_revision",current.path("revision").asLong(),"limits",overrides),null,200);
    }

    /** 两个请求共用启动屏障，异常保持原原因，不能被计为竞争冲突。 */
    private int race(CountDownLatch release,Map<String,Object> command,String key) {
        try {if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("tree_race_release_timeout");
            return exchange(platformPort,"X-Platform-Token","Bearer "+platformToken,"POST","/api/v1/platform/institution-relations/attach",command,key).status();
        }catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}
    }

    /** 当前关系和创建事实的全量分母；安全认证/拒绝审计不是非法业务写入。 */
    private Map<String,Object> facts() {
        return jdbc.queryForMap("""
                SELECT (SELECT count(*) FROM nw_tenants) AS tenants,
                    (SELECT count(*) FROM nw_external_institution_mappings) AS mappings,
                    (SELECT count(*) FROM nw_institution_relation_revisions) AS relations,
                    (SELECT coalesce(sum(relation_revision),0) FROM nw_tenants) AS revisions,
                    (SELECT count(*) FROM nw_institution_provision_requests) AS provisions
                """);
    }

    /** 平台人员创建真实机构管理员，树关系仍由正式关系API写入。 */
    private Account createAccount(String marker,String suffix) throws Exception {
        String slug=marker+"-"+suffix;
        JsonNode result=platform("POST","/api/v1/admin/tenants",Map.of("tenant_name",suffix,"tenant_slug",slug,
                "plan_code","free","admin_username","hierarchy-admin","admin_password","HierarchyFixture1234","admin_email",suffix+"@example.test"),null,201);
        return new Account(result.path("id").asText(),slug);
    }

    /** 原生认证返回固定机构JWT，不使用平台realm或正文模拟总部身份。 */
    private String tenantLogin(Account account) throws Exception {
        return institution(null,"POST","/api/v1/auth/login",Map.of("tenant_slug",account.slug(),"username","hierarchy-admin","password","HierarchyFixture1234"),null,200).path("access_token").asText();
    }

    private Map<String,Object> change(String child,String parent,long expected) {return Map.of("child_tenant_id",child,"parent_tenant_id",parent,"expected_revision",expected);}
    private void assertRelation(JsonNode value,String child,String parent,long revision) {
        assertThat(value.path("tenant_id").asText()).isEqualTo(child);assertThat(value.path("parent_tenant_id").asText()).isEqualTo(parent);
        assertThat(value.path("revision").asLong()).isEqualTo(revision);assertThat(value.path("tree_revision").asLong()).isPositive();
    }
    private JsonNode platform(String method,String path,Object value,String key,int expected) throws Exception {
        return success(exchange(platformPort,"X-Platform-Token","Bearer "+platformToken,method,path,value,key),method,path,expected);
    }
    private JsonNode institution(String token,String method,String path,Object value,String key,int expected) throws Exception {
        return success(exchange(institutionPort,token==null?null:"Authorization",token==null?null:"Bearer "+token,method,path,value,key),method,path,expected);
    }
    /** 平台单独签发固定机构和integration的明确scope，不从总部关系推导机器权限。 */
    private JsonNode executionCredential(String tenant,String integration,List<String> scopes,String key) throws Exception {
        return platform("POST","/api/v1/platform/institution-credentials/issue",Map.of("tenant_id",tenant,"integration_id",integration,"scopes",scopes),key,200);
    }
    private JsonNode institutionMachine(JsonNode credential,String method,String path,Object value,String key,int expected) throws Exception {
        return signed(credential,institutionPort,"X-Api-Key","api_key",method,path,value,key,expected);
    }
    /** 真实管理HMAC仍覆盖原方法、完整请求目标和实际正文。 */
    private JsonNode machine(JsonNode credential,String method,String path,Object value,String key,int expected) throws Exception {
        return signed(credential,platformPort,"X-Management-Key","management_key",method,path,value,key,expected);
    }
    /** 管理与执行凭据使用不同宿主/头，但签名材料始终绑定真实请求字节。 */
    private JsonNode signed(JsonNode credential,int hostPort,String keyHeader,String keyField,String method,String path,Object value,String key,int expected) throws Exception {
        String body=value==null?"":json.writeValueAsString(value),timestamp=Long.toString(Instant.now().getEpochSecond());
        String signature=HmacSignatureVerifier.sign(credential.path("signing_secret").asText(),HmacSignatureVerifier.canonical(timestamp,method,path,body));
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+hostPort+path)).timeout(Duration.ofSeconds(20))
                .header("Content-Type","application/json").header(keyHeader,credential.path(keyField).asText()).header("X-Timestamp",timestamp).header("X-Signature",signature);
        if(key!=null)builder.header("Idempotency-Key",key);
        var response=http.send(builder.method(method,body.isEmpty()?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        return success(new Reply(response.statusCode(),json.readTree(response.body())),method,path,expected);
    }
    private Reply exchange(int port,String header,String token,String method,String path,Object value,String key) throws Exception {
        String body=value==null?"":json.writeValueAsString(value);
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20)).header("Content-Type","application/json").header("Accept","application/json");
        if(header!=null)builder.header(header,token);if(key!=null)builder.header("Idempotency-Key",key);
        var response=http.send(builder.method(method,body.isEmpty()?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build(),HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(),json.readTree(response.body()));
    }
    /** 断言只输出状态/业务错误码，不打印JWT或一次管理秘密。 */
    private JsonNode success(Reply result,String method,String path,int expected) {
        assertThat(result.status()).as("%s %s code=%s message=%s",method,path,result.body().path("code").asText(),result.body().path("message").asText()).isEqualTo(expected);
        return result.body().path("data");
    }

    /** 子进程只连接本测试拥有的PG/Redis，重启仍使用同一数据库验证持久恢复。 */
    private void startPlatform(Path evidence,String label) throws Exception {
        Path root=repositoryRoot(),jar=root.resolve("backend-java/skillforge-platform-service/build/libs/skillforge-platform-service.jar");
        assertThat(jar).exists();try(var socket=new ServerSocket(0)){platformPort=socket.getLocalPort();}
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java");
        var builder=new ProcessBuilder(java.toString(),"-jar",jar.toString(),"--server.port="+platformPort,
                "--agent-runtime.workers.auto-startup=false","--spring.flyway.enabled=false","--spring.cloud.nacos.discovery.enabled=false","--spring.cloud.nacos.config.enabled=false");
        builder.directory(root.resolve("backend-java/skillforge-platform-service").toFile()).redirectErrorStream(true).redirectOutput(evidence.resolve("platform-"+label+".log").toFile());
        var env=builder.environment();env.put("SPRING_DATASOURCE_URL",POSTGRES.getJdbcUrl());env.put("SPRING_DATASOURCE_USERNAME",POSTGRES.getUsername());env.put("SPRING_DATASOURCE_PASSWORD",POSTGRES.getPassword());
        env.put("PLATFORM_DATASOURCE_USERNAME",POSTGRES.getUsername());env.put("PLATFORM_DATASOURCE_PASSWORD",POSTGRES.getPassword());
        env.put("REDIS_HOST",REDIS.getHost());env.put("REDIS_PORT",REDIS.getMappedPort(6379).toString());env.put("REDIS_DATABASE","0");
        env.put("SECRET_KEY",properties.getSecretKey());env.put("PLATFORM_SECRET_KEY",TEST_PLATFORM_JWT_SECRET);
        env.put("PLATFORM_ADMIN_USERNAME","hierarchy-platform-admin");env.put("PLATFORM_ADMIN_PASSWORD","SyntheticPlatform1234");platform=builder.start();
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500)).ignoreExceptions().untilAsserted(()->{
            assertThat(platform.isAlive()).isTrue();
            platformToken=success(exchange(platformPort,null,null,"POST","/api/v1/platform/auth/login",Map.of("username","hierarchy-platform-admin","password","SyntheticPlatform1234"),null),"POST","platform login",200).path("access_token").asText();
            assertThat(platformToken).isNotBlank();
        });
    }
    private void stopPlatform() throws Exception {if(platform!=null){platform.destroy();if(!platform.waitFor(10,TimeUnit.SECONDS)){platform.destroyForcibly();assertThat(platform.waitFor(10,TimeUnit.SECONDS)).isTrue();}platform=null;}}
    private static Path repositoryRoot() {Path p=Path.of("").toAbsolutePath();while(p!=null&&!Files.exists(p.resolve("settings.gradle.kts"))&&!Files.isDirectory(p.resolve(".git")))p=p.getParent();if(p!=null&&Files.exists(p.resolve("settings.gradle.kts")))p=p.getParent();if(p==null)throw new IllegalStateException("repository_root_missing");return p;}
    /** 本测试已实际开通的普通机构与本地账号入口。 */
    private record Account(String id,String slug) { }
    /** 仅在测试内保留的HTTP响应；调用断言不输出凭据正文。 */
    private record Reply(int status,JsonNode body) { @Override public String toString(){return "HTTP "+status;} }
}
