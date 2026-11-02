package com.skillforge.core.chat.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.skillforge.core.chat.contract.ChatInputReference;
import com.skillforge.core.chat.contract.ChatReferenceView;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.postgresql.util.PGobject;

/** PostgreSQL JSONB 边界必须保留多态引用，不能将原始 Map 当作已授权材料。 */
class ChatReferencesTypeHandlerTest {
    @Test void writesAndReadsTypedSelectorsWithoutLosingVersionOrTaskSource() throws Exception {
        var value=List.of(new ChatReferenceView("成员分析",new ChatInputReference.Process(
                new ChatInputReference.WorkTaskSource("work-1",ChatInputReference.DIRECT_TASK_KIND,"task-1"),"process-1",9)));
        var handler=new ChatReferencesTypeHandler();
        var statement=mock(PreparedStatement.class);
        handler.setNonNullParameter(statement,2,value,JdbcType.OTHER);
        var parameter=ArgumentCaptor.forClass(PGobject.class);
        verify(statement).setObject(eq(2),parameter.capture());
        assertEquals("jsonb",parameter.getValue().getType());
        assertTrue(parameter.getValue().getValue().contains("\"kind\":\"work_task\""));
        var row=mock(ResultSet.class);
        when(row.getString("input_references")).thenReturn(parameter.getValue().getValue());
        assertEquals(value,handler.getNullableResult(row,"input_references"));
    }
}
