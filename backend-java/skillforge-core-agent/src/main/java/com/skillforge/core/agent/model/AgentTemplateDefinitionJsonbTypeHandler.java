package com.skillforge.core.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.sql.*;
import org.apache.ibatis.type.*;

/** 模板定义的类型化 JSONB 快照，不接受旧定义字段。 */
@MappedTypes(AgentTemplateDefinition.class)
public final class AgentTemplateDefinitionJsonbTypeHandler extends BaseTypeHandler<AgentTemplateDefinition> {
    private static final ObjectMapper JSON = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @Override public void setNonNullParameter(PreparedStatement ps, int i, AgentTemplateDefinition value, JdbcType type) throws SQLException {
        try { ps.setObject(i, JSON.writeValueAsString(value), Types.OTHER); }
        catch (Exception e) { throw new SQLException("模板定义序列化失败", e); }
    }
    @Override public AgentTemplateDefinition getNullableResult(ResultSet rs, String column) throws SQLException { return parse(rs.getString(column)); }
    @Override public AgentTemplateDefinition getNullableResult(ResultSet rs, int column) throws SQLException { return parse(rs.getString(column)); }
    @Override public AgentTemplateDefinition getNullableResult(CallableStatement cs, int column) throws SQLException { return parse(cs.getString(column)); }
    private AgentTemplateDefinition parse(String value) throws SQLException {
        if (value == null) return null;
        try { return JSON.readValue(value, AgentTemplateDefinition.class); }
        catch (Exception e) { throw new SQLException("模板定义格式无效", e); }
    }
}
