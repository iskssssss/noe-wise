package com.skillforge.core.capability.integration.runtime.tool;


import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.ToolPortal;
import com.skillforge.runtime.api.port.ToolPortal.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 真实PG原部署/descriptor/Connector闭包、不可变路由与受限角色RLS验收。 */
@Testcontainers(disabledWithoutDocker=false)
final class JdbcMcpBindingsTest {
    private static final TenantId TENANT=new TenantId("1");
    private static final JdbcMcpBindings.Profile PROFILE=new JdbcMcpBindings.Profile("lookup",JdbcMcpBindings.PROTOCOL_VERSION);
    private static final FrozenToolTarget TARGET=target("tool:mount-1",new TypedPayload("json-schema","{\"type\":\"object\"}"));
    @Container private static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("skillforge").withUsername("mcp_admin").withPassword("mcp_admin_password");
    private JdbcMcpBindings bindings;
    @BeforeAll static void initialize() throws Exception {
        // 保留本测试独有容器的实际身份，便于验证执行归属与退出后精确收尾核对。
        var container = POSTGRES.getContainerInfo();
        System.out.println("MCP_FIXTURE_RESOURCE id=" + POSTGRES.getContainerId()
                + " created=" + container.getCreated() + " image=" + container.getImageId()
                + " session=" + container.getConfig().getLabels().get("org.testcontainers.sessionId"));
        execute("CREATE TABLE nw_capability_versions(id bigint PRIMARY KEY,definition_id bigint,tenant_id bigint,capability_type text,status text,descriptor jsonb,content_hash text)");
        execute("CREATE TABLE nw_tool_deployments(id bigint PRIMARY KEY,tenant_id bigint,deployment_key text,tool_version_id bigint,revision bigint,provider_type text,provider_ref text,status text)");
        execute("CREATE TABLE nw_connector_instances(instance_id text PRIMARY KEY,tenant_id bigint,base_url text,auth_type text,auth_credential_encrypted text,vault_secret_path text,headers jsonb,capabilities jsonb,status text,executor_type text)");
        try(var in=JdbcMcpBindingsTest.class.getClassLoader().getResourceAsStream("db/migration/V107__agent_runtime_mcp_bindings.sql")) {
            assertNotNull(in); execute(new String(in.readAllBytes(),StandardCharsets.UTF_8));
        }
        execute("ALTER TABLE nw_runtime_mcp_bindings DROP CONSTRAINT nw_runtime_mcp_bindings_protocol_version_check");
        try(var in=JdbcMcpBindingsTest.class.getClassLoader().getResourceAsStream("db/migration/V152__allow_readonly_mcp_idempotence.sql")) {
            assertNotNull(in); execute(new String(in.readAllBytes(),StandardCharsets.UTF_8));
        }
        execute("ALTER TABLE nw_runtime_mcp_bindings ADD CONSTRAINT skillforge_runtime_mcp_bindings_protocol_version_check CHECK (protocol_version IN ('2026-07-28','2025-11-25'))");
        for(String table:List.of("nw_capability_versions","nw_tool_deployments","nw_connector_instances")) {
            execute("ALTER TABLE "+table+" ENABLE ROW LEVEL SECURITY"); execute("ALTER TABLE "+table+" FORCE ROW LEVEL SECURITY");
            execute("CREATE POLICY tenant_isolation ON "+table+" USING (tenant_id::text=current_setting('app.tenant_id',true)"
                    +(table.equals("nw_capability_versions")?" OR tenant_id IS NULL":"")+")");
        }
        execute("CREATE ROLE mcp_reader LOGIN PASSWORD 'mcp_reader_password' NOSUPERUSER NOBYPASSRLS");
        execute("GRANT USAGE ON SCHEMA public TO mcp_reader");
        execute("GRANT SELECT ON nw_capability_versions,nw_tool_deployments,nw_connector_instances TO mcp_reader");
        execute("GRANT SELECT,INSERT,UPDATE,DELETE ON nw_runtime_mcp_bindings TO mcp_reader");
    }
    @BeforeEach void setup() {
        execute("TRUNCATE nw_capability_versions,nw_tool_deployments,nw_connector_instances,nw_runtime_mcp_bindings");
        execute("INSERT INTO nw_capability_versions VALUES(101,10,1,'TOOL','PUBLISHED','{\"model_invocable\":true,\"user_invocable\":false,\"tool_name\":\"lookup\",\"input_schema\":{\"type\":\"object\"},\"output_schema\":{\"type\":\"object\"},\"effect\":\"READ_ONLY\",\"idempotent\":false,\"confirmation_policy\":\"NEVER\",\"timeout_ms\":1000,\"sensitive_fields\":[],\"provider_requirements\":[]}','original-hash')");
        execute("INSERT INTO nw_tool_deployments VALUES(201,1,'lookup-deployment',101,3,'MCP','connector-1','ACTIVE')");
        execute("INSERT INTO nw_connector_instances VALUES('connector-1',1,'https://mcp.test/mcp','bearer','encrypted-original',NULL,'{}','[\"lookup\"]','active','mcp')");
        bindings=new JdbcMcpBindings(dataSource());
    }
    @Test void newlyDiscoveredToolRequiresItsBareCallableNameRatherThanTheQualifiedDisplayIdentity() {
        execute("UPDATE nw_connector_instances SET capabilities='[\"existing\",\"connector-1/lookup\"]'");
        assertThrows(IllegalArgumentException.class, () -> bindings.publish(TENANT, TARGET, PROFILE));
        assertEquals(0, count());
        execute("UPDATE nw_connector_instances SET capabilities='[\"existing\",\"lookup\"]'");
        var published = bindings.publish(TENANT, TARGET, PROFILE);
        assertEquals("lookup", published.toolName());
        assertEquals("connector-1", published.providerRef());
        assertEquals(published, new JdbcMcpBindings(dataSource()).find(TENANT, TARGET).orElseThrow());
    }

    @Test void freezesExplicitConnectorProtocolAndRejectsChangedRoute() {
        execute("UPDATE nw_connector_instances SET headers='{\"MCP-Protocol-Version\":\"2025-11-25\"}'");
        var first = bindings.publish(TENANT, TARGET, PROFILE);
        assertEquals("2025-11-25", first.protocolVersion());
        execute("UPDATE nw_connector_instances SET headers='{}'");
        assertEquals(first, bindings.find(TENANT, TARGET).orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> bindings.publish(TENANT, TARGET, PROFILE));
    }

    @Test void freezesOriginalRouteAndReplaysAcrossAdapterRebuild() {
        var first=bindings.publish(TENANT,TARGET,PROFILE);
        assertEquals(TARGET,first.target()); assertEquals("lookup",first.toolName()); assertEquals("https://mcp.test/mcp",first.endpoint());
        assertEquals(Optional.of("encrypted-original"),first.encryptedCredential());
        assertEquals(first,new JdbcMcpBindings(dataSource()).publish(TENANT,TARGET,PROFILE));
        assertFalse(first.toString().contains("encrypted-original")); assertFalse(first.toString().contains("https://"));
        execute("UPDATE nw_connector_instances SET base_url='https://changed.test/mcp',auth_credential_encrypted='changed'");
        assertEquals(first,new JdbcMcpBindings(dataSource()).find(TENANT,TARGET).orElseThrow());
        assertThrows(IllegalArgumentException.class,()->bindings.publish(TENANT,TARGET,PROFILE)); assertEquals(1,count());
    }
    /** 真实descriptor可为只读工具明确选择句柄身份；恢复保持原政策，当前声明不能覆写冻结binding。 */
    @Test void explicitReadOnlyHandlePolicyPublishesRestoresAndRejectsChangedPolicy() {
        String field=ToolPortal.ToolCallIdentityPolicy.DESCRIPTOR_FIELD;
        execute("UPDATE nw_capability_versions SET descriptor=jsonb_set(descriptor,'{"+field+"}','\"HANDLE\"'::jsonb)");
        var exact=new FrozenToolTarget(TARGET.handle(),TARGET.capability(),TARGET.deploymentId(),TARGET.deploymentRevision(),
                TARGET.inputSchema(),TARGET.outputSchema(),TARGET.effect(),TARGET.idempotent(),TARGET.timeout(),
                TARGET.modelInvocable(),TARGET.confirmation(),TARGET.sensitiveFields(),TARGET.providerRequirements(),
                TARGET.outputSchemaDeclared(),ToolCallIdentityPolicy.HANDLE);
        assertEquals(ToolEffect.READ_ONLY,exact.effect());
        assertThrows(IllegalArgumentException.class,()->bindings.publish(TENANT,TARGET,PROFILE));
        var original=bindings.publish(TENANT,exact,PROFILE);
        assertEquals(ToolCallIdentityPolicy.HANDLE,original.target().callIdentityPolicy());
        assertEquals(original,new JdbcMcpBindings(dataSource()).publish(TENANT,exact,PROFILE));
        assertEquals(original,new JdbcMcpBindings(dataSource()).find(TENANT,exact).orElseThrow());
        assertTrue(bindings.find(TENANT,TARGET).isEmpty());

        execute("UPDATE nw_capability_versions SET descriptor=jsonb_set(descriptor,'{"+field+"}','\"ARGUMENTS\"'::jsonb)");
        var rebuilt=new JdbcMcpBindings(dataSource());
        assertEquals(original,rebuilt.find(TENANT,exact).orElseThrow());
        assertTrue(rebuilt.find(TENANT,TARGET).isEmpty());
        assertThrows(IllegalArgumentException.class,()->rebuilt.publish(TENANT,exact,PROFILE));
        assertThrows(IllegalArgumentException.class,()->rebuilt.publish(TENANT,TARGET,PROFILE));
        assertEquals(1,count());
    }
    @Test void absentNullAndEmptyPolicySetsPublishAndRestoreExactly() {
        for (String[] policy:List.of(new String[]{null,null},new String[]{null,"[]"},
                new String[]{"[]",null},new String[]{"[]","[]"},new String[]{"null","null"},
                new String[]{"null","[]"},new String[]{"[]","null"})) {
            execute("TRUNCATE nw_runtime_mcp_bindings");
            policySets(policy[0],policy[1]);
            var published=bindings.publish(TENANT,TARGET,PROFILE);
            assertEquals(Set.of(),published.target().sensitiveFields());
            assertEquals(Set.of(),published.target().providerRequirements());
            assertEquals(published,new JdbcMcpBindings(dataSource()).find(TENANT,TARGET).orElseThrow());
            assertEquals(1,count());
        }
    }
    @Test void nonEmptyPolicySetsMustMatchExactFrozenTarget() {
        policySets("[\"api_key\",\"秘密\",\"api_key\"]","[\"oauth\",\"scope:read\",\"oauth\"]");
        var exact=new FrozenToolTarget(TARGET.handle(),TARGET.capability(),TARGET.deploymentId(),TARGET.deploymentRevision(),
                TARGET.inputSchema(),TARGET.outputSchema(),TARGET.effect(),TARGET.idempotent(),TARGET.timeout(),
                TARGET.modelInvocable(),TARGET.confirmation(),Set.of("api_key","秘密"),Set.of("oauth","scope:read"),TARGET.outputSchemaDeclared());
        assertThrows(IllegalArgumentException.class,()->bindings.publish(TENANT,TARGET,PROFILE));
        var published=bindings.publish(TENANT,exact,PROFILE);
        assertEquals(exact,published.target());
        assertEquals(published,new JdbcMcpBindings(dataSource()).find(TENANT,exact).orElseThrow());
        assertEquals(1,count());
    }
    @Test void malformedPolicySetsNeverPublishFrozenRoutes() {
        for(String malformed:List.of("{}","1","true","\"secret\"","[null]","[1]","[true]",
                "[{}]","[[]]","[\"\"]","[\" \"]","[\" secret\"]","[\"secret \"]",
                "[\"\\tsecret\"]","[\"secret\\t\"]","[\"\\u2003\"]","[\"valid\",1]")) {
            policySets(malformed,"[]");
            assertThrows(IllegalArgumentException.class,()->bindings.publish(TENANT,TARGET,PROFILE),"sensitive_fields="+malformed);
            policySets("[]",malformed);
            assertThrows(IllegalArgumentException.class,()->bindings.publish(TENANT,TARGET,PROFILE),"provider_requirements="+malformed);
            assertEquals(0,count());
        }
    }
    private static void policySets(String sensitive,String requirements) {
        // 每次从无集合字段的原 descriptor 重建，区分 JSON null 与字段不存在。
        execute("UPDATE nw_capability_versions SET descriptor=descriptor-'sensitive_fields'-'provider_requirements'");
        if(sensitive!=null)execute("UPDATE nw_capability_versions SET descriptor=jsonb_set(descriptor,'{sensitive_fields}','"+sensitive+"'::jsonb)");
        if(requirements!=null)execute("UPDATE nw_capability_versions SET descriptor=jsonb_set(descriptor,'{provider_requirements}','"+requirements+"'::jsonb)");
    }
    @Test void differentMountsCanUseSameDeploymentButFindChecksCompleteTarget() {
        bindings.publish(TENANT,TARGET,PROFILE);
        var second=target("tool:mount-2",TARGET.inputSchema()); bindings.publish(TENANT,second,PROFILE); assertEquals(2,count());
        var changed=target(TARGET.handle().value(),new TypedPayload("different-schema-id",TARGET.inputSchema().canonicalJson()));
        assertTrue(bindings.find(TENANT,changed).isEmpty());
        assertThrows(IllegalArgumentException.class,()->bindings.publish(TENANT,changed,PROFILE));
        assertTrue(bindings.find(new TenantId("2"),TARGET).isEmpty());
    }
    @Test void initialPublicationRequiresExactDeploymentRevisionAndHistoricalReadsStayFrozen() {
        execute("UPDATE nw_tool_deployments SET revision=4");
        assertEquals("mcp_binding_rejected",assertThrows(IllegalArgumentException.class,
                ()->bindings.publish(TENANT,TARGET,PROFILE)).getMessage());
        assertEquals(0,count());
        assertTrue(bindings.find(TENANT,TARGET).isEmpty());

        execute("UPDATE nw_tool_deployments SET revision=3");
        var published=bindings.publish(TENANT,TARGET,PROFILE);
        assertEquals(TARGET,published.target());
        assertEquals(1,count());
        assertEquals(published,bindings.find(TENANT,TARGET).orElseThrow());

        execute("UPDATE nw_tool_deployments SET revision=4");
        assertEquals(published,new JdbcMcpBindings(dataSource()).find(TENANT,TARGET).orElseThrow());
        assertEquals(1,count());
    }
    @Test void realDescriptorAndToolNameMustMatchAndUnknownSourceCannotPublish() {
        assertThrows(IllegalArgumentException.class,()->bindings.publish(TENANT,TARGET,new JdbcMcpBindings.Profile("invented",JdbcMcpBindings.PROTOCOL_VERSION)));
        var mismatch=target(TARGET.handle().value(),new TypedPayload("json-schema","{\"type\":\"array\"}"));
        assertThrows(IllegalArgumentException.class,()->bindings.publish(TENANT,mismatch,PROFILE));
        for(var wrong:List.of(
                new FrozenToolTarget(TARGET.handle(),TARGET.capability(),TARGET.deploymentId(),3,TARGET.inputSchema(),
                        new TypedPayload("json-schema","{\"type\":\"array\"}"),TARGET.effect(),false,TARGET.timeout(),
                        TARGET.modelInvocable(),TARGET.confirmation(),TARGET.sensitiveFields(),TARGET.providerRequirements(),TARGET.outputSchemaDeclared()),
                new FrozenToolTarget(TARGET.handle(),TARGET.capability(),TARGET.deploymentId(),3,TARGET.inputSchema(),TARGET.outputSchema(),
                        ToolEffect.DESTRUCTIVE,false,TARGET.timeout(),TARGET.modelInvocable(),TARGET.confirmation(),
                        TARGET.sensitiveFields(),TARGET.providerRequirements(),TARGET.outputSchemaDeclared()),
                new FrozenToolTarget(TARGET.handle(),TARGET.capability(),TARGET.deploymentId(),3,TARGET.inputSchema(),TARGET.outputSchema(),
                        TARGET.effect(),true,TARGET.timeout(),TARGET.modelInvocable(),TARGET.confirmation(),
                        TARGET.sensitiveFields(),TARGET.providerRequirements(),TARGET.outputSchemaDeclared()),
                new FrozenToolTarget(TARGET.handle(),TARGET.capability(),TARGET.deploymentId(),3,TARGET.inputSchema(),TARGET.outputSchema(),
                        TARGET.effect(),false,Duration.ofSeconds(2),TARGET.modelInvocable(),TARGET.confirmation(),
                        TARGET.sensitiveFields(),TARGET.providerRequirements(),TARGET.outputSchemaDeclared()),
                new FrozenToolTarget(TARGET.handle(),TARGET.capability(),TARGET.deploymentId(),3,TARGET.inputSchema(),TARGET.outputSchema(),
                        TARGET.effect(),false,TARGET.timeout(),false,TARGET.confirmation(),TARGET.sensitiveFields(),
                        TARGET.providerRequirements(),TARGET.outputSchemaDeclared()),
                new FrozenToolTarget(TARGET.handle(),TARGET.capability(),TARGET.deploymentId(),3,TARGET.inputSchema(),TARGET.outputSchema(),
                        TARGET.effect(),false,TARGET.timeout(),TARGET.modelInvocable(),ToolConfirmationPolicy.POLICY,
                        TARGET.sensitiveFields(),TARGET.providerRequirements(),TARGET.outputSchemaDeclared()),
                new FrozenToolTarget(TARGET.handle(),TARGET.capability(),TARGET.deploymentId(),3,TARGET.inputSchema(),TARGET.outputSchema(),
                        TARGET.effect(),false,TARGET.timeout(),TARGET.modelInvocable(),TARGET.confirmation(),Set.of("secret"),
                        TARGET.providerRequirements(),TARGET.outputSchemaDeclared()),
                new FrozenToolTarget(TARGET.handle(),TARGET.capability(),TARGET.deploymentId(),3,TARGET.inputSchema(),TARGET.outputSchema(),
                        TARGET.effect(),false,TARGET.timeout(),TARGET.modelInvocable(),TARGET.confirmation(),TARGET.sensitiveFields(),
                        Set.of("oauth"),TARGET.outputSchemaDeclared()))) {
            assertThrows(IllegalArgumentException.class,()->bindings.publish(TENANT,wrong,PROFILE));
        }
        assertThrows(IllegalArgumentException.class,()->new JdbcMcpBindings.Profile("lookup","unsupported"));
        execute("UPDATE nw_tool_deployments SET revision=4"); assertThrows(IllegalArgumentException.class,()->bindings.publish(TENANT,TARGET,PROFILE));
        execute("UPDATE nw_tool_deployments SET revision=3"); execute("UPDATE nw_connector_instances SET tenant_id=2");
        assertThrows(IllegalArgumentException.class,()->bindings.publish(TENANT,TARGET,PROFILE)); assertEquals(0,count());
    }
    @Test void activeRequirementOnlyAtPublicationAndPlatformVersionIsAccessible() {
        execute("UPDATE nw_capability_versions SET tenant_id=NULL"); var original=bindings.publish(TENANT,TARGET,PROFILE);
        execute("UPDATE nw_tool_deployments SET status='DISABLED'"); execute("UPDATE nw_connector_instances SET status='disabled'");
        execute("UPDATE nw_capability_versions SET status='ARCHIVED'");
        assertEquals(original,bindings.find(TENANT,TARGET).orElseThrow());
        assertThrows(IllegalArgumentException.class,()->bindings.publish(TENANT,TARGET,PROFILE));
    }
    @Test void acceptsReadOnlyIdempotenceButRejectsWriteReplayClaims() {
        execute("UPDATE nw_capability_versions SET descriptor=jsonb_set(descriptor,'{idempotent}','true')");
        var claimed=new FrozenToolTarget(TARGET.handle(),TARGET.capability(),TARGET.deploymentId(),TARGET.deploymentRevision(),
                TARGET.inputSchema(),TARGET.outputSchema(),TARGET.effect(),true,TARGET.timeout(),TARGET.modelInvocable(),
                TARGET.confirmation(),TARGET.sensitiveFields(),TARGET.providerRequirements(),TARGET.outputSchemaDeclared());
        assertNotNull(bindings.publish(TENANT,claimed,PROFILE));
        var write=new FrozenToolTarget(TARGET.handle(),TARGET.capability(),TARGET.deploymentId(),TARGET.deploymentRevision(),
                TARGET.inputSchema(),TARGET.outputSchema(),ToolEffect.IDEMPOTENT_WRITE,true,TARGET.timeout(),TARGET.modelInvocable(),
                TARGET.confirmation(),TARGET.sensitiveFields(),TARGET.providerRequirements(),TARGET.outputSchemaDeclared());
        assertEquals("mcp_write_idempotence_unsupported",assertThrows(IllegalArgumentException.class,
                ()->bindings.publish(TENANT,write,PROFILE)).getMessage());
        assertEquals(1,count());
    }
    @Test void noneAuthIsExplicitAndUnsupportedHeadersVaultAndAuthAreRejected() {
        for(String change:List.of("auth_type='basic'","headers='{\"Authorization\":\"plaintext\"}'","vault_secret_path='unversioned/path'")) {
            execute("UPDATE nw_connector_instances SET "+change); assertThrows(IllegalArgumentException.class,()->bindings.publish(TENANT,TARGET,PROFILE));
            execute("UPDATE nw_connector_instances SET auth_type='bearer',headers='{}',vault_secret_path=NULL");
        }
        execute("UPDATE nw_connector_instances SET auth_type='none',auth_credential_encrypted=NULL");
        assertTrue(bindings.publish(TENANT,TARGET,PROFILE).encryptedCredential().isEmpty());
    }
    @Test void concurrentPublicationIsSingleImmutableFact() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            Callable<JdbcMcpBindings.Binding> task=()->{start.await();return new JdbcMcpBindings(dataSource()).publish(TENANT,TARGET,PROFILE);};
            var a=pool.submit(task);var b=pool.submit(task);start.countDown();
            assertEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS)); assertEquals(1,count());
        }
    }
    @Test void restrictedRoleCannotReadForeignOrOverwriteHistoricalRoute() throws Exception {
        bindings.publish(TENANT,TARGET,PROFILE);
        try(var c=dataSource().getConnection();var s=c.createStatement()) {
            try(var r=s.executeQuery("SELECT rolsuper,rolbypassrls FROM pg_roles WHERE rolname=current_user")){assertTrue(r.next());assertFalse(r.getBoolean(1));assertFalse(r.getBoolean(2));}
            s.execute("SELECT set_config('app.tenant_id','2',false)");
            try(var r=s.executeQuery("SELECT count(*) FROM nw_runtime_mcp_bindings")){assertTrue(r.next());assertEquals(0,r.getInt(1));}
            s.execute("SELECT set_config('app.tenant_id','1',false)");
            assertThrows(SQLException.class,()->s.execute("UPDATE nw_runtime_mcp_bindings SET endpoint='https://replace.test'"));
            assertThrows(SQLException.class,()->s.execute("DELETE FROM nw_runtime_mcp_bindings"));
        }
    }
    @Test void failedInsertRollsBackAndDoesNotExposeSourceCredential() {
        execute("CREATE FUNCTION mcp_test_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'encrypted-original'; END; $$");
        execute("CREATE TRIGGER mcp_test_fail BEFORE INSERT ON nw_runtime_mcp_bindings FOR EACH ROW EXECUTE FUNCTION mcp_test_fail()");
        try {
            var failure=assertThrows(IllegalStateException.class,()->bindings.publish(TENANT,TARGET,PROFILE));
            assertEquals("mcp_binding_storage_failure",failure.getMessage());assertNull(failure.getCause());assertEquals(0,count());
        } finally {execute("DROP TRIGGER mcp_test_fail ON nw_runtime_mcp_bindings");execute("DROP FUNCTION mcp_test_fail()");}
    }
    private static FrozenToolTarget target(String handle,TypedPayload input) {
        return new FrozenToolTarget(new ToolHandle(handle),new CapabilityRef(CapabilityType.TOOL,"10","101"),"lookup-deployment",3,
                input,new TypedPayload("json-schema","{\"type\":\"object\"}"),ToolEffect.READ_ONLY,false,Duration.ofSeconds(1),
                true,ToolConfirmationPolicy.NEVER,Set.of(),Set.of(),true);
    }
    private static int count(){try(var c=admin();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM nw_runtime_mcp_bindings")){r.next();return r.getInt(1);}catch(SQLException e){throw new IllegalStateException(e);}}
    private static DataSource dataSource(){var ds=new PGSimpleDataSource();ds.setURL(POSTGRES.getJdbcUrl());ds.setUser("mcp_reader");ds.setPassword("mcp_reader_password");return ds;}
    private static Connection admin()throws SQLException{return DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());}
    private static void execute(String sql){try(var c=admin();var s=c.createStatement()){s.execute(sql);}catch(SQLException e){throw new IllegalStateException(e);}}
}
