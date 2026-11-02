package com.skillforge.core.workflow.integration.runtime.workflow;


import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.WorkflowPortal.*;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = false)
class JdbcWorkflowBindingsTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("skillforge").withUsername("admin").withPassword("admin_password");
    static final TenantId TENANT = new TenantId("1");
    JdbcWorkflowBindings bindings;
    @BeforeAll static void schema() throws Exception {
        sql("CREATE TABLE nw_workflow_instances(id bigint PRIMARY KEY,tenant_id bigint,instance_id text,definition_version_id bigint,status text,UNIQUE(tenant_id,id))");
        sql("CREATE TABLE nw_workflow_definition_versions(id bigint PRIMARY KEY,tenant_id bigint,container_id bigint,capability_version_id bigint,definition jsonb)");
        sql("CREATE TABLE nw_workflow_containers(id bigint PRIMARY KEY,tenant_id bigint,workflow_id text)");
        sql("CREATE TABLE nw_capability_versions(id bigint PRIMARY KEY,tenant_id bigint,definition_id bigint,capability_type text,status text,descriptor jsonb)");
        for (String migration : List.of("V109__agent_runtime_workflow_bindings.sql", "V232__workflow_installation_revisions.sql")) {
            try (var in = JdbcWorkflowBindingsTest.class.getClassLoader().getResourceAsStream("db/migration/" + migration)) {
                assertNotNull(in); sql(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        sql("CREATE ROLE workflow_binding_reader LOGIN PASSWORD 'fixture_password' NOSUPERUSER NOBYPASSRLS");
        sql("GRANT USAGE ON SCHEMA public TO workflow_binding_reader");
        sql("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO workflow_binding_reader");
    }
    @BeforeEach void setup() throws Exception {
        sql("TRUNCATE nw_runtime_workflow_bindings,nw_workflow_instances,nw_workflow_definition_versions,nw_workflow_containers,nw_capability_versions CASCADE");
        sql("INSERT INTO nw_capability_versions VALUES(30,1,20,'WORKFLOW','PUBLISHED','{}')");
        sql("INSERT INTO nw_workflow_containers VALUES(40,1,'frozen-workflow')");
        sql("INSERT INTO nw_workflow_definition_versions VALUES(50,1,40,30,'{\"entry\":\"start\",\"nodes\":[],\"edges\":[]}')");
        sql("INSERT INTO nw_workflow_instances(id,tenant_id,instance_id,definition_version_id,status) VALUES(10,1,'business-instance',50,'draft')");
        sql("INSERT INTO nw_workflow_installation_revisions VALUES(1,10,1,50,'{\"greeting\":\"original\"}','','{}',clock_timestamp())");
        sql("UPDATE nw_workflow_instances SET installation_revision=1,status='published' WHERE id=10");
        bindings = port(false);
    }
    @Test void freezesExactPrimaryInstanceAndDefinitionIdentityAcrossRebuildAndSourceChanges() throws Exception {
        var original = bindings.publish(TENANT,target());
        assertEquals("frozen-workflow", original.workflowKey());
        assertTrue(original.configJson().contains("original"));
        sql("UPDATE nw_workflow_instances SET status='disabled'");
        assertEquals(original,port(false).find(TENANT,target()).orElseThrow());
        assertThrows(IllegalArgumentException.class,()->bindings.publish(TENANT,target()));
        assertTrue(bindings.find(new TenantId("2"),target()).isEmpty());
    }
    @Test void exactReplayDoesNotCreateSecondRowAndInstallationUpgradeCannotOverwrite() throws Exception {
        var original = bindings.publish(TENANT,target());
        assertEquals(original,bindings.publish(TENANT,target()));
        sql("INSERT INTO nw_workflow_installation_revisions VALUES(1,10,2,50,'{\"greeting\":\"changed\"}','new prompt','{}',clock_timestamp())");
        sql("UPDATE nw_workflow_instances SET installation_revision=2 WHERE id=10");
        assertEquals(original, bindings.publish(TENANT,target()));
        assertEquals(Optional.of(""), bindings.find(TENANT,target()).orElseThrow().customPrompt());
        assertEquals(1,count());
    }
    @Test void cannotUseBusinessInstanceIdWrongVersionCapabilityOrMutableDefinition() throws Exception {
        var t=target();
        for(var candidate:List.of(new FrozenWorkflowTarget(t.handle(),"business-instance",50,t.capability(),t.timeout(), 1L),
                new FrozenWorkflowTarget(t.handle(),"10",51,t.capability(),t.timeout(), 1L),
                new FrozenWorkflowTarget(t.handle(),"10",50,new CapabilityRef(CapabilityType.WORKFLOW,"21","30"),t.timeout(), 1L)))
            assertThrows(IllegalArgumentException.class,()->bindings.publish(TENANT,candidate));
        sql("UPDATE nw_workflow_definition_versions SET capability_version_id=31");
        assertThrows(IllegalArgumentException.class,()->bindings.publish(TENANT,target()));
        assertEquals(0,count());
    }
    @Test void historicalLookupChecksFullTargetAndSupportsMultipleMounts() {
        bindings.publish(TENANT,target()); var t=target();
        assertTrue(bindings.find(TENANT,new FrozenWorkflowTarget(t.handle(),t.instanceId(),t.instanceRevision(),t.capability(),Duration.ofSeconds(9), 1L)).isEmpty());
        var other = new FrozenWorkflowTarget(new WorkflowHandle("workflow:other"),t.instanceId(),t.instanceRevision(),t.capability(),t.timeout(), 1L);
        assertEquals(other,bindings.publish(TENANT,other).target());
    }
    @Test void concurrentPublicationHasOneImmutableWinner() throws Exception {
        try(var workers=Executors.newFixedThreadPool(2)) {
            var a=workers.submit(()->port(false).publish(TENANT,target()));
            var b=workers.submit(()->port(false).publish(TENANT,target()));
            assertEquals(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS)); assertEquals(1,count());
        }
    }
    @Test void hostCanonicalizationPreservesExistingSnapshotAndRejectsRealChanges() throws Exception {
        var original = bindings.publish(TENANT, target(), ignored -> " {}\n");
        assertEquals(original, port(false).publish(TENANT, target(), ignored -> "{}", String::strip));
        assertEquals(" {}\n", original.resolvedBindingsJson());
        assertEquals(1, count());
        var rejected = assertThrows(IllegalArgumentException.class,
                () -> port(false).publish(TENANT, target(), ignored -> "{\"knowledge\":[]}", String::strip));
        assertEquals("workflow_binding_conflict", rejected.getMessage());
        assertEquals(original, bindings.find(TENANT, target()).orElseThrow());
    }
    @Test void restrictedRoleCannotReadForeignOrMutateFrozenRows() throws Exception {
        bindings.publish(TENANT,target());
        assertTrue(port(true).find(new TenantId("2"),target()).isEmpty());
        assertEquals(target(),port(true).find(TENANT,target()).orElseThrow().target());
        try(var connection=DriverManager.getConnection(PG.getJdbcUrl(),"workflow_binding_reader","fixture_password")) {
            connection.setAutoCommit(false);
            try(var statement=connection.createStatement()) {
                statement.execute("SELECT set_config('app.tenant_id','1',true)");
                assertThrows(java.sql.SQLException.class,()->statement.execute("UPDATE nw_runtime_workflow_bindings SET workflow_key='overwritten'"));
                connection.rollback();
            }
        }
        assertEquals("frozen-workflow",bindings.find(TENANT,target()).orElseThrow().workflowKey());
    }
    static FrozenWorkflowTarget target() { return new FrozenWorkflowTarget(new WorkflowHandle("workflow:mount"),"10",50,
            new CapabilityRef(CapabilityType.WORKFLOW,"20","30"),Duration.ofSeconds(5), 1L); }
    static JdbcWorkflowBindings port(boolean restricted) {
        var ds=new PGSimpleDataSource();ds.setURL(PG.getJdbcUrl());
        ds.setUser(restricted?"workflow_binding_reader":PG.getUsername());ds.setPassword(restricted?"fixture_password":PG.getPassword());
        return new JdbcWorkflowBindings(ds);
    }
    static long count() throws Exception {
        try(var c=DriverManager.getConnection(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());var s=c.createStatement();
            var rows=s.executeQuery("SELECT count(*) FROM nw_runtime_workflow_bindings")) { rows.next();return rows.getLong(1); }
    }
    static void sql(String text) throws Exception {
        try(var c=DriverManager.getConnection(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());var s=c.createStatement()) { s.execute(text); }
    }
}
