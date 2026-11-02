package com.skillforge.core.agent.integration.runtime.session;


import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;
import java.sql.DriverManager;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 真实 PG 非 Tool 挂载映射使用实际选中的发布版本展示字段。 */
@Testcontainers(disabledWithoutDocker = false)
class JdbcSessionToolCatalogTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("tool_catalog").withUsername("catalog_admin").withPassword("catalog_password");
    private JdbcSessionCapabilities capabilities;

    @BeforeAll
    static void schema() throws Exception {
        execute("""
                CREATE TABLE nw_agents(id bigint,tenant_id bigint,PRIMARY KEY(tenant_id,id));
                CREATE TABLE nw_agent_capability_mount_sets(tenant_id bigint,agent_id bigint,generation bigint,PRIMARY KEY(tenant_id,agent_id));
                CREATE TABLE nw_capability_definitions(id bigint PRIMARY KEY,tenant_id bigint,capability_type text,status text);
                CREATE TABLE nw_capability_versions(id bigint PRIMARY KEY,definition_id bigint,tenant_id bigint,capability_type text,status text,descriptor jsonb,name text,description text);
                CREATE TABLE nw_tool_deployments(id bigint PRIMARY KEY,tenant_id bigint,deployment_key text,tool_version_id bigint,revision bigint,provider_type text,provider_ref text,status text);
                CREATE TABLE nw_connector_instances(instance_id text,tenant_id bigint,capabilities jsonb);
                CREATE TABLE nw_agent_capability_mounts(id bigint PRIMARY KEY,tenant_id bigint,agent_id bigint,revision bigint,priority int,
                    capability_type text,capability_definition_id bigint,capability_version_id bigint,activation_mode text,token_budget bigint,
                    tool_deployment_id bigint,tool_deployment_revision bigint,workflow_instance_id bigint,workflow_instance_revision bigint,
                    knowledge_binding_mode text,knowledge_pinned_revision_id bigint,status text,created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now());
                CREATE TABLE nw_workflow_instances(id bigint,tenant_id bigint,status text,definition_version_id bigint,PRIMARY KEY(tenant_id,id));
                CREATE TABLE nw_workflow_containers(id bigint PRIMARY KEY,tenant_id bigint,status text,active_definition_version_id bigint);
                CREATE TABLE nw_workflow_definition_versions(id bigint PRIMARY KEY,tenant_id bigint,capability_version_id bigint,container_id bigint REFERENCES nw_workflow_containers(id));
                CREATE TABLE nw_knowledge_bases(id bigint,tenant_id bigint,status text,active_revision_id bigint);
                CREATE TABLE nw_knowledge_base_revisions(id bigint,tenant_id bigint,kb_id bigint,revision bigint,status text,
                    published_at timestamptz,capability_version_id bigint,source_versions jsonb);
                """);
        execute(migration("V143__skill_default_version.sql"));
        String publications = migration("V264__capability_current_publications.sql");
        executeSection(publications, "ALTER TABLE public.nw_agent_capability_mounts ADD COLUMN",
                "ALTER TABLE public.nw_capability_versions DROP CONSTRAINT");
        executeSection(publications, "CREATE OR REPLACE FUNCTION public.validate_skill_default_version()",
                "COMMENT ON COLUMN public.nw_capability_definitions.default_version_id");
        execute(migration("V232__workflow_installation_revisions.sql"));
    }

    @BeforeEach
    void fixture() throws Exception {
        execute("TRUNCATE nw_agents,nw_agent_capability_mount_sets,nw_agent_capability_mounts,nw_capability_definitions,nw_capability_versions,nw_tool_deployments,nw_connector_instances,nw_workflow_installation_revisions,nw_workflow_instances,nw_workflow_definition_versions,nw_workflow_containers,nw_knowledge_bases,nw_knowledge_base_revisions");
        execute("INSERT INTO nw_agents VALUES(40,1)");
        DataSource source = dataSource();
        capabilities = new JdbcSessionCapabilities(source, new JdbcIdentityPolicyPort(source, Map.of()));
    }

    @Test
    void nonToolCatalogNamesComeFromTheSelectedPublishedVersion() throws Exception {
        execute("TRUNCATE nw_workflow_installation_revisions,nw_workflow_instances,nw_workflow_definition_versions,nw_workflow_containers,nw_knowledge_bases,nw_knowledge_base_revisions");
        for (int i=1;i<=4;i++) {
            String type=i<=2?"KNOWLEDGE_SOURCE":"WORKFLOW";
            execute("INSERT INTO nw_capability_definitions(id,tenant_id,capability_type,status) VALUES("+(400+i)+",1,'"+type+"','PUBLISHED')");
            execute("INSERT INTO nw_capability_versions VALUES("+(500+i)+","+(400+i)+",1,'"+type+"','PUBLISHED',"
                    +"'{\"source_id\":\""+(600+i)+"\",\"allowed_operators\":[\"KEYWORD\"]}',"
                    +"'source-"+i+"','purpose-"+i+"')");
            execute("UPDATE nw_capability_definitions SET default_version_id="+(500+i)+" WHERE id="+(400+i));
            if (i<=2) {
                execute("INSERT INTO nw_knowledge_bases VALUES("+(600+i)+",1,'active',"+(700+i)+")");
                execute("INSERT INTO nw_knowledge_base_revisions VALUES("+(700+i)+",1,"+(600+i)+",1,'published',now(),"+(500+i)+",'[\"v1\"]')");
                execute("INSERT INTO nw_agent_capability_mounts(id,tenant_id,agent_id,revision,priority,capability_type,capability_definition_id,capability_version_id,knowledge_binding_mode,status) VALUES("
                        +(800+i)+",1,40,1,0,'KNOWLEDGE_SOURCE',"+(400+i)+","+(500+i)+",'FOLLOW_ACTIVE','ACTIVE')");
            } else {
                execute("INSERT INTO nw_workflow_containers VALUES("+(600+i)+",1,'published',"+(700+i)+")");
                execute("INSERT INTO nw_workflow_definition_versions VALUES("+(700+i)+",1,"+(500+i)+","+(600+i)+")");
                execute("INSERT INTO nw_workflow_instances(id,tenant_id,status,definition_version_id) VALUES("+(600+i)+",1,'draft',"+(700+i)+")");
                execute("INSERT INTO nw_workflow_installation_revisions VALUES(1,"+(600+i)+",1,"+(700+i)+",'{}','', '{}',now())");
                execute("UPDATE nw_workflow_instances SET status='published',installation_revision=1 WHERE id="+(600+i)+" AND tenant_id=1");
                execute("INSERT INTO nw_agent_capability_mounts(id,tenant_id,agent_id,revision,priority,capability_type,capability_definition_id,capability_version_id,workflow_instance_id,workflow_instance_revision,status) VALUES("
                        +(800+i)+",1,40,1,0,'WORKFLOW',"+(400+i)+","+(500+i)+","+(600+i)+","+(700+i)+",'ACTIVE')");
            }
        }
        // FOLLOW_ACTIVE 必须使用实际选中版本的展示字段，不能回读挂载的旧版本。
        execute("INSERT INTO nw_capability_versions VALUES(599,401,1,'KNOWLEDGE_SOURCE','PUBLISHED','{\"source_id\":\"601\"}','obsolete','obsolete')");
        execute("UPDATE nw_agent_capability_mounts SET capability_version_id=599 WHERE id=801");
        var field=JdbcSessionCapabilities.class.getDeclaredField("sessions"); field.setAccessible(true);
        var factory=(org.apache.ibatis.session.SqlSessionFactory)field.get(capabilities);
        try(var session=factory.openSession()) {
            var rows=session.getMapper(SessionCapabilitiesMapper.class).mounts("1","40",Set.of("MCP"),
                    Set.of("KNOWLEDGE_SOURCE","WORKFLOW"),null);
            assertEquals(4,rows.size());
            var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
            for(int i=1;i<=4;i++) {
                var row=rows.get(i-1);
                assertTrue(row.valid);
                assertEquals(Integer.toString(500+i),row.versionId);
                var descriptor=mapper.readTree(row.descriptor);
                assertEquals("source-"+i,descriptor.path("name").asText());
                assertEquals("purpose-"+i,descriptor.path("description").asText());
            }
        }
    }

    @Test
    void skillCatalogKeepsExactMountVersionWhenDefaultMovesUntilExplicitAdoption() throws Exception {
        execute("INSERT INTO nw_capability_definitions(id,tenant_id,capability_type,status) VALUES(901,1,'SKILL','PUBLISHED')");
        execute("INSERT INTO nw_capability_versions VALUES(902,901,1,'SKILL','PUBLISHED','{\"activation_mode\":\"ON_DEMAND\",\"marker\":\"V1\"}','skill-v1','version-one')");
        execute("INSERT INTO nw_capability_versions VALUES(903,901,1,'SKILL','PUBLISHED','{\"activation_mode\":\"ON_DEMAND\",\"marker\":\"V2\"}','skill-v2','version-two')");
        execute("UPDATE nw_capability_definitions SET default_version_id=903 WHERE id=901");
        execute("INSERT INTO nw_agent_capability_mounts(id,tenant_id,agent_id,revision,priority,capability_type,capability_definition_id,capability_version_id,activation_mode,token_budget,status) VALUES(904,1,40,1,0,'SKILL',901,902,'ON_DEMAND',1000,'ACTIVE')");
        var field = JdbcSessionCapabilities.class.getDeclaredField("sessions"); field.setAccessible(true);
        var factory = (org.apache.ibatis.session.SqlSessionFactory) field.get(capabilities);
        try (var session = factory.openSession()) {
            var mapper = session.getMapper(SessionCapabilitiesMapper.class);
            var pinned = mapper.mounts("1", "40", Set.of("MCP"), Set.of("SKILL"), null);
            assertEquals(1, pinned.size());
            assertTrue(pinned.getFirst().valid);
            assertEquals("902", pinned.getFirst().versionId);
            assertTrue(pinned.getFirst().descriptor.contains("V1"));
        }
        execute("UPDATE nw_agent_capability_mounts SET capability_version_id=903,revision=2 WHERE id=904");
        try (var session = factory.openSession()) {
            var adopted = session.getMapper(SessionCapabilitiesMapper.class).mounts("1", "40", Set.of("MCP"), Set.of("SKILL"), null);
            assertEquals("903", adopted.getFirst().versionId);
            assertTrue(adopted.getFirst().descriptor.contains("V2"));
        }
    }

    private static DataSource dataSource() {
        var source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl()); source.setUser(POSTGRES.getUsername()); source.setPassword(POSTGRES.getPassword());
        return source;
    }

    private static String migration(String name) throws Exception {
        try (var resource = JdbcSessionToolCatalogTest.class.getClassLoader().getResourceAsStream("db/migration/" + name)) {
            assertNotNull(resource, name);
            return new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    private static void executeSection(String sql, String start, String end) throws Exception {
        int from = sql.indexOf(start), to = sql.indexOf(end, from);
        assertTrue(from >= 0 && to > from, "migration section missing: " + start);
        execute(sql.substring(from, to));
    }

    private static void execute(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
