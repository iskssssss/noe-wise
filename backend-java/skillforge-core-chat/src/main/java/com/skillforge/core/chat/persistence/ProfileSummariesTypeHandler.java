package com.skillforge.core.chat.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.chat.persistence.ProfileSummary;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

/** 有界原始总结窗口的类型化 JSONB 映射。 */
public class ProfileSummariesTypeHandler extends BaseTypeHandler<List<ProfileSummary>> {
    private static final ObjectMapper JSON = new ObjectMapper();
    @Override
    public void setNonNullParameter(PreparedStatement statement, int index,
            List<ProfileSummary> summaries, JdbcType jdbcType) throws SQLException {
        try {
            var json = new PGobject();
            json.setType("jsonb");
            json.setValue(JSON.writeValueAsString(summaries));
            statement.setObject(index, json);
        } catch (Exception exception) { throw new SQLException("profile_window_encoding_failed", exception); }
    }
    @Override public List<ProfileSummary> getNullableResult(ResultSet rows, String column) throws SQLException {
        return parse(rows.getString(column));
    }
    @Override public List<ProfileSummary> getNullableResult(ResultSet rows, int column) throws SQLException {
        return parse(rows.getString(column));
    }
    @Override public List<ProfileSummary> getNullableResult(CallableStatement statement, int column) throws SQLException {
        return parse(statement.getString(column));
    }
    private List<ProfileSummary> parse(String raw) throws SQLException {
        if (raw == null) return List.of();
        try { return JSON.readValue(raw, new TypeReference<List<ProfileSummary>>() { }); }
        catch (Exception exception) { throw new SQLException("profile_window_decoding_failed", exception); }
    }
}
