package com.skillforge.runtime.adapter.jdbc.callback;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/** numeric epoch 秒保留 API lease 的完整纳秒精度。 */
public final class CallbackInstantTypeHandler extends BaseTypeHandler<Instant> {
    @Override public void setNonNullParameter(PreparedStatement statement, int index, Instant value, JdbcType type)
            throws SQLException {
        statement.setBigDecimal(index, BigDecimal.valueOf(value.getEpochSecond()).add(BigDecimal.valueOf(value.getNano(), 9)));
    }
    @Override public Instant getNullableResult(ResultSet result, String column) throws SQLException {
        return decode(result.getBigDecimal(column));
    }
    @Override public Instant getNullableResult(ResultSet result, int column) throws SQLException {
        return decode(result.getBigDecimal(column));
    }
    @Override public Instant getNullableResult(CallableStatement result, int column) throws SQLException {
        return decode(result.getBigDecimal(column));
    }
    private static Instant decode(BigDecimal value) {
        if (value == null) return null;
        var seconds = value.setScale(0, RoundingMode.FLOOR);
        return Instant.ofEpochSecond(seconds.longValueExact(), value.subtract(seconds).movePointRight(9).intValueExact());
    }
}
