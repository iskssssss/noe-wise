package com.skillforge.runtime.adapter.sqlite;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/** SQLite 以固定九位小数 UTC 文本无损保存 Instant；同一表示支持时间顺序比较。 */
public final class SqliteInstantTypeHandler extends BaseTypeHandler<Instant> {
    private static final DateTimeFormatter FORMAT = new DateTimeFormatterBuilder().appendInstant(9).toFormatter();
    public static String format(Instant value) { return FORMAT.format(value); }
    @Override public void setNonNullParameter(PreparedStatement statement, int index, Instant value, JdbcType type) throws SQLException {
        statement.setString(index, format(value));
    }
    @Override public Instant getNullableResult(ResultSet result, String column) throws SQLException { return parse(result.getString(column)); }
    @Override public Instant getNullableResult(ResultSet result, int column) throws SQLException { return parse(result.getString(column)); }
    @Override public Instant getNullableResult(CallableStatement result, int column) throws SQLException { return parse(result.getString(column)); }
    private static Instant parse(String value) { return value == null ? null : Instant.parse(value); }
}
