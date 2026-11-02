package com.skillforge.migration;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;

/** 真实PostgreSQL执行导航增量，保留自定义树和既有节点修订。 */
@Testcontainers(disabledWithoutDocker=false)
class TrajectoryNavigationMigrationTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeEach void seed() throws Exception {
        try(var connection=connect();var statement=connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS nw_foundation_navigation_nodes CASCADE");
            statement.execute("""
                CREATE TABLE nw_foundation_navigation_nodes(owner_key text NOT NULL,tenant_id bigint,code text NOT NULL,parent_code text,
                    title text NOT NULL,route_key text,sort_order integer NOT NULL,enabled boolean NOT NULL,revision bigint NOT NULL DEFAULT 1,
                    created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),created_by text NOT NULL,updated_by text NOT NULL,
                    PRIMARY KEY(owner_key,code),FOREIGN KEY(owner_key,parent_code) REFERENCES nw_foundation_navigation_nodes(owner_key,code) DEFERRABLE INITIALLY DEFERRED);
                INSERT INTO nw_foundation_navigation_nodes(owner_key,code,parent_code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES
                    ('platform','nav_runtime',NULL,'运行与用量',NULL,30,true,'seed','seed'),
                    ('platform','sessions','nav_runtime','会话观察','sessions',110,true,'seed','seed'),
                    ('tenant_template','nav_agents',NULL,'智能体与协作',NULL,10,true,'seed','seed'),
                    ('tenant_template','sessions','nav_agents','会话记录','sessions',120,true,'seed','seed');
                """);
        }
    }

    @Test void defaultsGainOneTrajectoryEntryUnderTheirExistingSessionGroup() throws Exception {
        try(var connection=connect();var statement=connection.createStatement()) {
            migrate(connection);
            try(var rows=statement.executeQuery("SELECT owner_key,parent_code,route_key,sort_order FROM nw_foundation_navigation_nodes WHERE code='trajectories' ORDER BY owner_key")) {
                assertTrue(rows.next());assertEquals("platform",rows.getString(1));assertEquals("nav_runtime",rows.getString(2));assertEquals("trajectories",rows.getString(3));assertEquals(111,rows.getInt(4));
                assertTrue(rows.next());assertEquals("tenant_template",rows.getString(1));assertEquals("nav_agents",rows.getString(2));assertEquals("trajectories",rows.getString(3));assertEquals(121,rows.getInt(4));
                assertFalse(rows.next());
            }
            // 重复执行不覆盖同身份或使既有节点的CAS修订变化。
            migrate(connection);
            try(var rows=statement.executeQuery("SELECT count(*),max(revision) FROM nw_foundation_navigation_nodes")) {
                assertTrue(rows.next());assertEquals(6,rows.getInt(1));assertEquals(1,rows.getLong(2));
            }
        }
    }

    @Test void customPlatformTreeAndInitializedTenantTreeRemainUntouched() throws Exception {
        try(var connection=connect();var statement=connection.createStatement()) {
            statement.execute("UPDATE nw_foundation_navigation_nodes SET title='我的运行区',revision=4,updated_by='admin' WHERE owner_key='platform' AND code='nav_runtime'");
            statement.execute("""
                INSERT INTO nw_foundation_navigation_nodes(owner_key,tenant_id,code,title,route_key,sort_order,enabled,created_by,updated_by)
                VALUES('tenant:11',11,'sessions','我的会话','sessions',321,true,'admin','admin')
                """);
            migrate(connection);
            try(var rows=statement.executeQuery("SELECT count(*) FROM nw_foundation_navigation_nodes WHERE code='trajectories' AND owner_key<>'tenant_template'")) {
                assertTrue(rows.next());assertEquals(0,rows.getInt(1));
            }
            try(var rows=statement.executeQuery("SELECT title,revision FROM nw_foundation_navigation_nodes WHERE owner_key='platform' AND code='nav_runtime'")) {
                assertTrue(rows.next());assertEquals("我的运行区",rows.getString(1));assertEquals(4,rows.getLong(2));
            }
        }
    }

    private static void migrate(Connection connection) throws Exception {
        try(var input=TrajectoryNavigationMigrationTest.class.getClassLoader().getResourceAsStream("db/migration/V333__agent_trajectory_navigation.sql")) {
            assertNotNull(input);connection.setAutoCommit(false);
            try(var statement=connection.createStatement()){statement.execute(new String(input.readAllBytes(),StandardCharsets.UTF_8));}
            connection.commit();connection.setAutoCommit(true);
        }
    }
    private static Connection connect() throws Exception{return DriverManager.getConnection(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());}
}
