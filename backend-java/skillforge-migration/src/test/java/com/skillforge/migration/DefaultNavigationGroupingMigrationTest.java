package com.skillforge.migration;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;

/** 执行真实旧导航种子及V331，验证完整默认分组与管理员自定义保护。 */
@Testcontainers(disabledWithoutDocker=false)
class DefaultNavigationGroupingMigrationTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    private static final String MIGRATION="db/migration/V331__group_default_navigation_by_business.sql";

    @BeforeEach void seed() throws Exception {
        try(var connection=connect();var statement=connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS nw_foundation_navigation_nodes CASCADE");
            statement.execute("""
                CREATE TABLE nw_foundation_navigation_nodes(owner_key text NOT NULL,tenant_id bigint,code text NOT NULL,parent_code text,
                    title text NOT NULL,route_key text,sort_order integer NOT NULL,enabled boolean NOT NULL,revision bigint NOT NULL DEFAULT 1,
                    created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),created_by text NOT NULL,updated_by text NOT NULL,
                    PRIMARY KEY(owner_key,code),FOREIGN KEY(owner_key,parent_code) REFERENCES nw_foundation_navigation_nodes(owner_key,code) DEFERRABLE INITIALLY DEFERRED)
                """);
            // 只执行所属导航表的真实种子语句，其他领域的销毁迁移不进入此隔离夹具。
            Pattern navigation=Pattern.compile("(?s)(?:INSERT INTO|UPDATE|DELETE FROM) public\\.foundation_navigation_nodes\\b[^;]*;");
            for(String path:List.of("db/migration/V276__foundation_catalog.sql","db/migration/V300__remove_application_governance.sql","db/migration/V320__institution_management_navigation.sql")) {
                var statements=navigation.matcher(resource(path));
                while(statements.find()) statement.execute(statements.group());
            }
        }
    }

    @Test void completeDefaultTreesGainBusinessGroupsWithoutRewritingLeafTitlesOrSort() throws Exception {
        try(var connection=connect();var statement=connection.createStatement()) {
            var platformBefore=leaves(connection,"platform");var tenantBefore=leaves(connection,"tenant_template");
            connection.setAutoCommit(false);statement.execute(resource(MIGRATION));connection.commit();
            assertEquals(platformBefore,leaves(connection,"platform"));assertEquals(tenantBefore,leaves(connection,"tenant_template"));
            assertEquals(10,count(connection,"route_key IS NULL"));
            assertEquals(60,count(connection,"route_key IS NOT NULL AND code<>'home' AND parent_code IS NOT NULL AND revision=2"));
            assertEquals(2,count(connection,"code='home' AND parent_code IS NULL AND sort_order=0 AND revision=1"));
            assertEquals(1,count(connection,"owner_key='platform' AND code='tenants' AND parent_code='nav_institutions'"));
            assertEquals(1,count(connection,"owner_key='tenant_template' AND code='sub_institutions' AND parent_code='nav_headquarters'"));
        }
    }

    @Test void customizedPlatformAndAnyTenantTreeKeepTheirWholeStructureTitleAndOrder() throws Exception {
        try(var connection=connect();var statement=connection.createStatement()) {
            statement.execute("UPDATE nw_foundation_navigation_nodes SET title='自定义机构入口',sort_order=777,revision=2,updated_by='admin' WHERE owner_key='platform' AND code='tenants'");
            statement.execute("INSERT INTO nw_foundation_navigation_nodes(owner_key,tenant_id,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('tenant:11',11,'custom','我自己的入口','agents',321,true,'admin','admin')");
            var platformBefore=allNodes(connection,"platform");var customBefore=allNodes(connection,"tenant:11");
            connection.setAutoCommit(false);statement.execute(resource(MIGRATION));connection.commit();
            assertEquals(platformBefore,allNodes(connection,"platform"));assertEquals(customBefore,allNodes(connection,"tenant:11"));
            assertEquals(0,count(connection,"owner_key='platform' AND route_key IS NULL"));
            assertEquals(6,count(connection,"owner_key='tenant_template' AND route_key IS NULL"));
        }
    }

    @Test void deletedOrAlreadyGroupedDefaultsAreRecognizedAsCustomAndNotPartiallyReorganized() throws Exception {
        try(var connection=connect();var statement=connection.createStatement()) {
            statement.execute("DELETE FROM nw_foundation_navigation_nodes WHERE owner_key='platform' AND code='dashboard'");
            statement.execute("UPDATE nw_foundation_navigation_nodes SET parent_code='agents',revision=2,updated_by='admin' WHERE owner_key='tenant_template' AND code='teams'");
            var platformBefore=allNodes(connection,"platform");var templateBefore=allNodes(connection,"tenant_template");
            connection.setAutoCommit(false);statement.execute(resource(MIGRATION));connection.commit();
            assertEquals(platformBefore,allNodes(connection,"platform"));assertEquals(templateBefore,allNodes(connection,"tenant_template"));
        }
    }

    private record Leaf(String code,String title,String route,int sort,boolean enabled) { }
    private record Node(String code,String parent,String title,String route,int sort,boolean enabled,long revision,String createdBy,String updatedBy) { }
    private static List<Leaf> leaves(Connection connection,String owner) throws Exception {
        try(var statement=connection.prepareStatement("SELECT code,title,route_key,sort_order,enabled FROM nw_foundation_navigation_nodes WHERE owner_key=? AND route_key IS NOT NULL ORDER BY code")) {
            statement.setString(1,owner);var result=new ArrayList<Leaf>();
            try(var rows=statement.executeQuery()){while(rows.next())result.add(new Leaf(rows.getString(1),rows.getString(2),rows.getString(3),rows.getInt(4),rows.getBoolean(5)));}return result;
        }
    }
    private static List<Node> allNodes(Connection connection,String owner) throws Exception {
        try(var statement=connection.prepareStatement("SELECT code,parent_code,title,route_key,sort_order,enabled,revision,created_by,updated_by FROM nw_foundation_navigation_nodes WHERE owner_key=? ORDER BY code")) {
            statement.setString(1,owner);var result=new ArrayList<Node>();
            try(var rows=statement.executeQuery()){while(rows.next())result.add(new Node(rows.getString(1),rows.getString(2),rows.getString(3),rows.getString(4),rows.getInt(5),rows.getBoolean(6),rows.getLong(7),rows.getString(8),rows.getString(9)));}return result;
        }
    }
    private static long count(Connection connection,String predicate) throws Exception {
        // 谓词全部为本测试固定常量，不接受产品请求输入。
        try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT count(*) FROM nw_foundation_navigation_nodes WHERE "+predicate)){assertTrue(rows.next());return rows.getLong(1);}
    }
    private static Connection connect() throws Exception{return DriverManager.getConnection(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());}
    private static String resource(String path) throws Exception {
        try(var input=DefaultNavigationGroupingMigrationTest.class.getClassLoader().getResourceAsStream(path)){assertNotNull(input,path);return new String(input.readAllBytes(),StandardCharsets.UTF_8);}
    }
}
