package com.skillforge.core.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;
import java.sql.Types;

/** 将 Agent 委派配置以固定对象契约读写为 JSONB。 */
@MappedTypes(AgentDelegationConfig.class)
public class AgentDelegationConfigJsonbTypeHandler extends BaseTypeHandler<AgentDelegationConfig> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, AgentDelegationConfig parameter,
            JdbcType jdbcType) throws SQLException {
        try {
            ps.setObject(i, MAPPER.writeValueAsString(parameter), Types.OTHER);
        } catch (Exception exception) {
            throw new SQLException("Agent 委派配置 JSONB 序列化失败", exception);
        }
    }

    @Override
    public AgentDelegationConfig getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public AgentDelegationConfig getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public AgentDelegationConfig getNullableResult(CallableStatement cs, int columnIndex)
            throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private AgentDelegationConfig parse(String json) throws SQLException {
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readValue(json, AgentDelegationConfig.class);
        } catch (Exception exception) {
            throw new SQLException("Agent 委派配置 JSONB 契约无效", exception);
        }
    }
}
