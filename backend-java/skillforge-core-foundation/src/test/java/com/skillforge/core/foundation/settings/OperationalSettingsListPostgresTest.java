package com.skillforge.core.foundation.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.core.shared.config.SkillForgeProperties;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 私有最小配置表、正式XML与受限角色；不代替完整迁移或HTTP权限验收。 */
@Testcontainers(disabledWithoutDocker=false)
class OperationalSettingsListPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("pgvector/pgvector:pg16");
    private static SqlSessionFactory factory;
    private static final Reads READS=new Reads();
    private static CryptoService crypto;
    @BeforeAll static void database() throws Exception {
        var properties=new SkillForgeProperties();properties.setSecretKey("synthetic-settings-list-key-32-bytes");crypto=new CryptoService(properties);
        try(var connection=DriverManager.getConnection(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());var sql=connection.createStatement()) {
            sql.execute("CREATE TABLE nw_platform_operational_setting_groups(group_key text PRIMARY KEY,current_revision bigint NOT NULL CHECK(current_revision>0),updated_at timestamptz,updated_by bigint)");
            sql.execute("CREATE TABLE nw_platform_operational_setting_versions(group_key text,revision bigint,values jsonb NOT NULL CHECK(jsonb_typeof(values)='object'),created_at timestamptz,actor_id bigint,PRIMARY KEY(group_key,revision))");
            sql.execute("INSERT INTO nw_platform_operational_setting_groups VALUES('gamma',1,now(),NULL),('beta',1,now(),NULL),('alpha',1,now(),NULL)");
            sql.execute("""
                    INSERT INTO nw_platform_operational_setting_versions VALUES
                    ('alpha',1,jsonb_build_object('value','A'),now(),NULL),
                    ('alpha',2,jsonb_build_object('value','NEW'),now(),7),
                    ('gamma',1,jsonb_build_object('value','C'),now(),NULL)
                    """);
            try(var insert=connection.prepareStatement("INSERT INTO nw_platform_operational_setting_versions VALUES('beta',1,?::jsonb,now(),NULL)")) {
                insert.setString(1,new ObjectMapper().writeValueAsString(Map.of("token",Map.of("$encrypted",crypto.encrypt("secret")))));insert.executeUpdate();
            }
            sql.execute("CREATE ROLE settings_list_reader LOGIN PASSWORD 'isolated-only' NOSUPERUSER NOBYPASSRLS");
            sql.execute("GRANT USAGE ON SCHEMA public TO settings_list_reader; GRANT SELECT ON nw_platform_operational_setting_groups,nw_platform_operational_setting_versions TO settings_list_reader");
        }
        var source=new PGSimpleDataSource();source.setURL(PG.getJdbcUrl());source.setUser("settings_list_reader");source.setPassword("isolated-only");
        var configuration=new MybatisConfiguration(new Environment("settings-list",new JdbcTransactionFactory(),source));
        configuration.setMapUnderscoreToCamelCase(true);configuration.addInterceptor(READS);
        try(var xml=OperationalSettingsListPostgresTest.class.getClassLoader().getResourceAsStream("mapper/settings/OperationalSettingsMapper.xml")) {
            assertNotNull(xml);new XMLMapperBuilder(xml,configuration,"mapper/settings/OperationalSettingsMapper.xml",configuration.getSqlFragments()).parse();
        }
        factory=new MybatisSqlSessionFactoryBuilder().build(configuration);
    }
    @Test void sixCurrentReadsBecomeTwoAndTheSecondBatchUsesCapturedPairs() throws Exception {
        try(var session=factory.openSession()) {
            var mapper=session.getMapper(OperationalSettingsMapper.class);
            var registry=OperationalSettingsListBatchTest.registry();
            var service=new OperationalSettingsService(mapper,crypto,registry);
            READS.queries.clear();
            var before=registry.groups().stream().map(group->service.get(group.key())).toList();
            assertEquals(6,READS.queries.size());session.clearCache();READS.queries.clear();
            READS.advanceBeforeVersions=true;
            var after=service.list();assertEquals(before,after);assertEquals(2,READS.queries.size());
            assertEquals(List.of("alpha","beta","gamma"),after.stream().map(OperationalSettingsService.GroupView::key).toList());
            assertTrue(after.get(1).values().isEmpty(),"secret values remain excluded from public projection");
            assertTrue(READS.queries.get(0).contains("group_key IN"));
            assertTrue(READS.queries.get(1).contains("revision = ?"));
            assertTrue(!READS.queries.get(1).contains("JOIN")&&!READS.queries.get(1).contains("MAX("));
            try(var owner=DriverManager.getConnection(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());var sql=owner.createStatement();var pointer=sql.executeQuery("SELECT current_revision FROM nw_platform_operational_setting_groups WHERE group_key='alpha'")) {
                assertTrue(pointer.next());assertEquals(2,pointer.getLong(1));
            }
            System.out.println("SETTINGS_LIST_READ groups=3 baseline_prepared_selects=6 candidate_prepared_selects=2 boundary=business-query-count-not-latency-or-disk");
            try(var sql=session.getConnection().createStatement();var role=sql.executeQuery("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname=current_user")) {
                assertTrue(role.next());assertTrue(role.getBoolean(1));
            }
        }
    }
    @Intercepts(@Signature(type=StatementHandler.class,method="prepare",args={Connection.class,Integer.class}))
    public static final class Reads implements Interceptor {
        private final List<String> queries=new ArrayList<>();
        private boolean advanceBeforeVersions;
        @Override public Object intercept(Invocation invocation) throws Throwable {
            var sql=((StatementHandler)invocation.getTarget()).getBoundSql().getSql();
            if(sql.startsWith("SELECT")&&sql.contains("nw_platform_operational_setting_")) {
                queries.add(sql);
                if(advanceBeforeVersions&&sql.contains("FROM nw_platform_operational_setting_versions")) {
                    advanceBeforeVersions=false;
                    try(var owner=DriverManager.getConnection(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());var update=owner.createStatement()) {
                        update.executeUpdate("UPDATE nw_platform_operational_setting_groups SET current_revision=2 WHERE group_key='alpha'");
                    }
                }
            }
            return invocation.proceed();
        }
    }
}
