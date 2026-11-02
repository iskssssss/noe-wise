package com.skillforge.platform.controller;

import com.skillforge.core.workflow.mapper.WorkflowReviewMapper;
import java.io.InputStream;
import java.sql.DriverManager;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;

/** 在隔离 PostgreSQL 执行正式审核详情 SQL，核对队列范围、资源归属与共享锁。 */
@Testcontainers(disabledWithoutDocker = false)
class WorkflowReviewDetailMapperIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static SqlSessionFactory sessions;

    @BeforeAll static void schema() throws Exception {
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE nw_workflow_templates(id bigint PRIMARY KEY,workflow_id text,tenant_id bigint,name text,version text,status text,updated_at timestamptz,description text,author text,config_schema jsonb,required_permissions jsonb,tools jsonb,connector_requirements jsonb)");
            sql.execute("CREATE TABLE nw_workflow_review_decisions(template_id bigint)");
            sql.execute("CREATE TABLE nw_workflow_assets(workflow_id text,tenant_id bigint,asset_name text,content bytea)");
            sql.execute("INSERT INTO nw_workflow_templates VALUES(1,'review-wf',7,'待审','1.0.0','review','2026-10-24T01:02:03.123456Z','说明','作者','[{\"name\":\"topic\"}]','[\"knowledge.read\"]','[]','[]'),(2,'private-wf',8,'普通草稿','1.0.0','draft',now(),null,null,'[]','[]','[]','[]')");
            sql.execute("INSERT INTO nw_workflow_assets VALUES('review-wf',7,'workflow.yaml',convert_to('nodes: []','UTF8')),('review-wf',8,'foreign.yaml',convert_to('private','UTF8'))");
        }
        var source = new PGSimpleDataSource(); source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername()); source.setPassword(POSTGRES.getPassword());
        var configuration = new Configuration(new Environment("review-detail", new JdbcTransactionFactory(), source));
        configuration.setMapUnderscoreToCamelCase(true);
        String resource = "mapper/WorkflowReviewMapper.xml";
        try (InputStream input = WorkflowReviewMapper.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        sessions = new SqlSessionFactoryBuilder().build(configuration);
    }

    @Test void detailsOnlySeeReviewQueueAndResourcesOfTheSameOwner() {
        try (var session = sessions.openSession()) {
            var mapper = session.getMapper(WorkflowReviewMapper.class);
            var detail = mapper.detailTemplate("review-wf");
            assertEquals("待审", detail.getName());
            assertEquals("2026-10-24T01:02:03.123456Z", detail.getUpdatedAt().toString());
            assertEquals("topic", detail.getConfigSchema().getFirst().get("name"));
            assertEquals("knowledge.read", detail.getRequiredPermissions().getFirst());
            assertNull(mapper.detailTemplate("private-wf"));
            var files = mapper.detailAssets("review-wf", detail.getTenantId());
            assertEquals(1, files.size());
            assertEquals("workflow.yaml", files.getFirst().name());
            assertArrayEquals("nodes: []".getBytes(java.nio.charset.StandardCharsets.UTF_8), files.getFirst().content());
        }
    }

    @Test void detailSnapshotLocksOutTemplateRevisionChangesUntilReadCompletes() throws Exception {
        try (var session = sessions.openSession();
                var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var sql = connection.createStatement()) {
            assertNotNull(session.getMapper(WorkflowReviewMapper.class).detailTemplate("review-wf"));
            sql.execute("SET lock_timeout='100ms'");
            var failure = assertThrows(java.sql.SQLException.class, () -> sql.execute("UPDATE nw_workflow_templates SET updated_at=now() WHERE workflow_id='review-wf'"));
            assertEquals("55P03", failure.getSQLState());
        }
    }
}
