package com.skillforge.institution.artifact;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 实际 SQL 验证产物渠道权限，不依赖前端隐藏或仅检查租户。 */
@Testcontainers(disabledWithoutDocker=true)
class ArtifactChannelQueryTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine");
    @Test void channelSessionTenantPublicationAndBindingMustAllMatch()throws Exception{
        var source=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        var jdbc=new JdbcTemplate(source);
        // 查询同时解析 Agent 与 Team 分支，最小 SQL 夹具必须具备两种目标的直接依赖。
        jdbc.execute("CREATE TABLE nw_chat_sessions(session_id text,tenant_id bigint,agent_id bigint,channel_id bigint,team_id text,end_user_id bigint)");
        jdbc.execute("CREATE TABLE nw_channels(id bigint,tenant_id bigint,status text)");
        jdbc.execute("CREATE TABLE nw_agents(id bigint,tenant_id bigint,status text)");
        jdbc.execute("CREATE TABLE nw_channel_agent_bindings(channel_id bigint,tenant_id bigint,agent_id bigint)");
        jdbc.execute("CREATE TABLE nw_agent_teams(tenant_id bigint,id text,status text,published_revision bigint)");
        jdbc.execute("CREATE TABLE nw_agent_team_versions(tenant_id bigint,team_id text,revision bigint,definition jsonb)");
        jdbc.execute("CREATE TABLE nw_chat_user_teams(tenant_id bigint,team_id text,channel_id bigint,client_ref uuid)");
        jdbc.execute("CREATE TABLE nw_chat_client_profiles(tenant_id bigint,channel_id bigint,client_ref uuid,end_user_id bigint)");
        jdbc.execute("CREATE TABLE nw_end_users(tenant_id bigint,id bigint,status text)");
        jdbc.update("INSERT INTO nw_chat_sessions(session_id,tenant_id,agent_id,channel_id) VALUES('s',7,9,8)");
        jdbc.update("INSERT INTO nw_channels VALUES(8,7,'active'),(18,7,'active')");
        jdbc.update("INSERT INTO nw_agents VALUES(9,7,'published')");
        jdbc.update("INSERT INTO nw_channel_agent_bindings VALUES(8,7,9),(18,7,9)");
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mapper/ArtifactMapper.xml"));
        var mapper=new SqlSessionTemplate(factory.getObject()).getMapper(ArtifactMapper.class);
        assertTrue(mapper.canChannelAccess("7","8","s"));
        assertFalse(mapper.canChannelAccess("17","8","s"));
        assertFalse(mapper.canChannelAccess("7","18","s"));
        jdbc.update("UPDATE nw_channels SET status='disabled' WHERE id=8");assertFalse(mapper.canChannelAccess("7","8","s"));
        jdbc.update("UPDATE nw_channels SET status='active' WHERE id=8");
        jdbc.update("UPDATE nw_agents SET status='draft'");assertFalse(mapper.canChannelAccess("7","8","s"));
        jdbc.update("UPDATE nw_agents SET status='published'");
        jdbc.update("DELETE FROM nw_channel_agent_bindings WHERE channel_id=8");assertFalse(mapper.canChannelAccess("7","8","s"));
    }
}
