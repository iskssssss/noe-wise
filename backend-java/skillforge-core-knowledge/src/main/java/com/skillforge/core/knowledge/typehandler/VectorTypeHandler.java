package com.skillforge.core.knowledge.typehandler;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

/**
 * PostgreSQL pgvector 向量类型处理器。
 *
 * <p>将 Java 的向量字符串（形如 {@code [0.1,0.2,...]}）绑定为 pgvector 的
 * {@code vector} 类型，读取时将数据库返回的向量文本原样返回，用于写入知识库分块
 * embedding 以及向量检索。
 *
 * <p>该处理器位于独立的 {@code typehandler} 包，不在 MyBatis
 * {@code type-handlers-package} 扫描范围内，仅通过实体字段级
 * {@code @TableField(typeHandler=...)} 显式使用，避免覆盖全局 String 类型处理器。
 */
public class VectorTypeHandler extends BaseTypeHandler<String> {

    /**
     * 绑定非空参数为 pgvector 对象。
     *
     * @param ps        预处理语句
     * @param i         参数下标
     * @param parameter 向量文本，格式为 {@code [0.1,0.2,...]}
     * @param jdbcType  JDBC 类型（可忽略）
     * @throws SQLException 绑定失败时抛出
     */
    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        PGobject vector = new PGobject();
        vector.setType("vector");
        vector.setValue(parameter);
        ps.setObject(i, vector);
    }

    /**
     * 按列名读取向量文本。
     *
     * @param rs         结果集
     * @param columnName 列名
     * @return 向量文本
     * @throws SQLException SQL 执行异常
     */
    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return rs.getString(columnName);
    }

    /**
     * 按列下标读取向量文本。
     *
     * @param rs          结果集
     * @param columnIndex 列下标
     * @return 向量文本
     * @throws SQLException SQL 执行异常
     */
    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getString(columnIndex);
    }

    /**
     * 从存储过程结果读取向量文本。
     *
     * @param cs          可调用语句
     * @param columnIndex 列下标
     * @return 向量文本
     * @throws SQLException SQL 执行异常
     */
    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return cs.getString(columnIndex);
    }
}
