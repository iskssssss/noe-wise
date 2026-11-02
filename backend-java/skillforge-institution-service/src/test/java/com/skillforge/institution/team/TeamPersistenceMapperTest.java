package com.skillforge.institution.team;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

/** 验证迁移 SQL 的真实 MyBatis 装配与动态条件；数据库事务行为由集成测试覆盖。 */
class TeamPersistenceMapperTest {
    /** XML 必须能够独立解析，且条件查询不能遗漏租户、工作和可选任务范围。 */
    @Test
    void xmlMappingsLoadAndRetainWorkScope() throws Exception {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        for (String name : new String[]{"TeamDefinitionMapper", "TeamWorkInputMapper", "TeamResultMapper",
                "TeamRecoveryMapper", "ChatTeamMapper", "HealthMapper", "AgentRunBindingsMapper"}) {
            String resource = "mapper/" + name + ".xml";
            try (var stream = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(stream, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        var workQuery = configuration.getMappedStatement("com.skillforge.institution.team.TeamResultMapper.rows");
        String sql = workQuery.getBoundSql(Map.of("tenant", "7", "session", "s", "root", "r",
                "task", "task", "run", "run")).getSql();
        assertTrue(sql.contains("tenant_id=?"));
        assertTrue(sql.contains("root_input_id=?"));
        assertTrue(sql.contains("task_id=?"));
        assertTrue(sql.contains("run_id=?"));
        var definition = configuration.getMappedStatement("com.skillforge.institution.team.TeamDefinitionMapper.definition");
        assertTrue(definition.getBoundSql(Map.of("tenant",7L,"id","team","lock",true)).getSql().contains("FOR UPDATE"));
        assertFalse(definition.getBoundSql(Map.of("tenant",7L,"id","team","lock",false)).getSql().contains("FOR UPDATE"));
        assertFalse(configuration.hasStatement("com.skillforge.institution.runtime.AgentRunBindingsMapper.bindTenant"));
    }
}
