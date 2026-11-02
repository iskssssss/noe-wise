package com.skillforge.core.foundation.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.core.foundation.contract.ControlOperationItem;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

/** 控制操作逐项事实的唯一 JSONB 编码；读取时直接恢复受契约约束的类型。 */
public final class ControlOperationItemsTypeHandler extends BaseTypeHandler<List<ControlOperationItem>> {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private static final ObjectReader READER = MAPPER.readerFor(new TypeReference<List<ControlOperationItem>>() {});

    @Override
    public void setNonNullParameter(PreparedStatement statement, int index,
            List<ControlOperationItem> items, JdbcType jdbcType) throws SQLException {
        try {
            PGobject value = new PGobject();
            value.setType("jsonb");
            value.setValue(MAPPER.writeValueAsString(items));
            statement.setObject(index, value);
        } catch (Exception failure) {
            throw new SQLException("控制操作逐项事实 JSONB 编码失败", failure);
        }
    }

    @Override
    public List<ControlOperationItem> getNullableResult(ResultSet rows, String column) throws SQLException {
        return read(rows.getString(column));
    }

    @Override
    public List<ControlOperationItem> getNullableResult(ResultSet rows, int column) throws SQLException {
        return read(rows.getString(column));
    }

    @Override
    public List<ControlOperationItem> getNullableResult(CallableStatement statement, int column) throws SQLException {
        return read(statement.getString(column));
    }

    private static List<ControlOperationItem> read(String json) throws SQLException {
        if (json == null) return null;
        try {
            List<ControlOperationItem> items = READER.readValue(json);
            return List.copyOf(items);
        } catch (Exception failure) {
            throw new SQLException("控制操作逐项事实 JSONB 恢复失败", failure);
        }
    }
}
