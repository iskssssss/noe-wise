package com.skillforge.core.chat.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.chat.contract.ChatReferenceView;
import java.sql.*;
import java.util.List;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

/** 保留引用 selector 的多态类型，不能将数据库 Map 冒充 typed DTO。 */
public final class ChatReferencesTypeHandler extends BaseTypeHandler<List<ChatReferenceView>> {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final TypeReference<List<ChatReferenceView>> TYPE=new TypeReference<>(){};
    @Override public void setNonNullParameter(PreparedStatement statement,int index,List<ChatReferenceView> value,JdbcType type)throws SQLException {
        try { var json=new PGobject(); json.setType("jsonb"); json.setValue(JSON.writerFor(TYPE).writeValueAsString(value)); statement.setObject(index,json); }
        catch(Exception error){throw new SQLException("公开引用编码失败",error);}
    }
    @Override public List<ChatReferenceView> getNullableResult(ResultSet result,String column)throws SQLException{return read(result.getString(column));}
    @Override public List<ChatReferenceView> getNullableResult(ResultSet result,int column)throws SQLException{return read(result.getString(column));}
    @Override public List<ChatReferenceView> getNullableResult(CallableStatement result,int column)throws SQLException{return read(result.getString(column));}
    private static List<ChatReferenceView> read(String value)throws SQLException {
        try{return value==null?List.of():JSON.readValue(value,TYPE);}catch(Exception error){throw new SQLException("公开引用解码失败",error);}
    }
}
