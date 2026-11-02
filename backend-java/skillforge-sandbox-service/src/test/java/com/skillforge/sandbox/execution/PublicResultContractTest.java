package com.skillforge.sandbox.execution;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.execution.PublicResults;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 显式业务结果必须是封闭文字/表格协议，不接受脚本伪造执行或平台身份。 */
class PublicResultContractTest {
    private final ObjectMapper json=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    @Test void acceptsBusinessTextAndScalarTableIncludingNull()throws Exception {
        var result=json.readValue("{\"results\":[{\"kind\":\"text\",\"format\":\"plain\",\"text\":\"business_id: 7\"},"
                +"{\"kind\":\"table\",\"columns\":[{\"key\":\"code\",\"label\":\"业务编号\"}],\"rows\":[[\"12345678-1234-1234-1234-123456789abc\"],[null]]}]}",PublicResults.class);
        assertEquals(2,result.results().size());
        assertNull(((PublicResults.Table)result.results().get(1)).rows().get(1).getFirst());
    }
    @Test void rejectsExtraAuthorityFieldsUnknownTypesDuplicatesAndNonScalarCells() {
        for(String body:java.util.List.of("{\"results\":[],\"status\":\"success\"}",
                "{\"results\":[{\"kind\":\"text\",\"format\":\"plain\",\"text\":\"ok\",\"owner\":\"7\"}]}",
                "{\"results\":[],\"Run\":\"x\"}","{\"results\":[],\"references\":[]}",
                "{\"results\":[{\"kind\":\"script\",\"text\":\"x\"}]}","{\"results\":[],\"results\":[]} trailing",
                "{\"results\":[{\"kind\":\"table\",\"columns\":[{\"key\":\"x\",\"label\":\"X\"}],\"rows\":[[{}]]}]}"))
            assertThrows(Exception.class,()->json.readValue(body,PublicResults.class));
    }
}
