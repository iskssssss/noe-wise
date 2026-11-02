package com.skillforge.core.shared.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;
import org.postgresql.util.PGobject;

/**
 * PostgreSQL JSONB 类型处理器。
 *
 * <p>将 Java 对象（Map / List 等）序列化为 JSON 后以 {@link PGobject} 绑定到
 * JSONB 列，读取时反序列化为通用对象，解决 MyBatis 默认 String 处理器无法
 * 直接读写 jsonb 列的问题。
 */
@MappedTypes({Map.class, List.class})
public class JsonbTypeHandler extends BaseTypeHandler<Object> {

    /** Jackson 对象映射器，用于 JSON 序列化与反序列化。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 绑定非空参数为 JSONB 对象。
     *
     * @param ps       预处理语句
     * @param i        参数下标
     * @param parameter Java 对象
     * @param jdbcType  JDBC 类型（可忽略）
     * @throws SQLException SQL 执行异常
     */
    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Object parameter, JdbcType jdbcType)
            throws SQLException {
        try {
            PGobject json = new PGobject();
            json.setType("jsonb");
            json.setValue(MAPPER.writeValueAsString(parameter));
            ps.setObject(i, json);
        } catch (Exception e) {
            throw new SQLException("JSONB 序列化失败", e);
        }
    }

    /**
     * 按列名读取 JSONB 值。
     *
     * @param rs         结果集
     * @param columnName 列名
     * @return 反序列化后的 Java 对象
     * @throws SQLException SQL 执行异常
     */
    @Override
    public Object getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    /**
     * 按列下标读取 JSONB 值。
     *
     * @param rs          结果集
     * @param columnIndex 列下标
     * @return 反序列化后的 Java 对象
     * @throws SQLException SQL 执行异常
     */
    @Override
    public Object getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    /**
     * 从存储过程结果读取 JSONB 值。
     *
     * @param cs          可调用语句
     * @param columnIndex 列下标
     * @return 反序列化后的 Java 对象
     * @throws SQLException SQL 执行异常
     */
    @Override
    public Object getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    /**
     * 将 JSON 字符串解析为 Java 对象。
     *
     * @param json JSON 字符串
     * @return 解析结果；null 输入返回 null
     * @throws SQLException 解析失败时抛出
     */
    private Object parse(String json) throws SQLException {
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (Exception e) {
            throw new SQLException("JSONB 反序列化失败", e);
        }
    }
}
