package com.skillforge.core.foundation.integration;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/** 将凭证回执的固定scope集合映射为PostgreSQL text[]，不将固定契约存为JSON。 */
public final class ExecutionScopesTypeHandler extends BaseTypeHandler<List<String>> {
    /** 保存scope字符串数组。 */
    @Override public void setNonNullParameter(PreparedStatement ps, int i, List<String> values, JdbcType type) throws SQLException {
        ps.setArray(i, ps.getConnection().createArrayOf("text", values.toArray(String[]::new)));
    }
    /** 按列名恢复原始scope集合。 */
    @Override public List<String> getNullableResult(ResultSet rs, String column) throws SQLException { return read(rs.getArray(column)); }
    /** 按列序号恢复原始scope集合。 */
    @Override public List<String> getNullableResult(ResultSet rs, int column) throws SQLException { return read(rs.getArray(column)); }
    /** 从存储过程结果恢复scope集合。 */
    @Override public List<String> getNullableResult(CallableStatement cs, int column) throws SQLException { return read(cs.getArray(column)); }
    /** NULL 表示此安全回执不是凭证；非NULL保留精确集合。 */
    private List<String> read(java.sql.Array array) throws SQLException {
        if (array == null) return null;
        try { return List.copyOf(Arrays.asList((String[]) array.getArray())); }
        finally { array.free(); }
    }
}
