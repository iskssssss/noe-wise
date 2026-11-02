package com.skillforge.runtime.adapter.model;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.api.port.ModelPort.*;
import org.junit.jupiter.api.Test;

/** 原生系统Agent将业务JSON视为回答正文，不将其解析为Runtime动作。 */
class SystemAgentNativeProtocolTest {
    @Test void preservesNativeBusinessJsonAsTextWithoutTerminalToolOrActionEnvelope() throws Exception {
        var json=new ObjectMapper();String business="{\"result\":\"完成\",\"items\":[1,2]}";
        var output=OpenAiModelProtocol.output(new ResponseFormat.NativeConversation(),business);
        assertEquals(com.skillforge.runtime.api.port.ModelPort.TEXT_OUTPUT_SCHEMA,output.schemaId());
        assertTrue(json.readTree(output.canonicalJson()).isTextual());
        assertEquals(business,json.readTree(output.canonicalJson()).textValue());
        assertEquals("完成",json.readTree(business).path("result").asText());
        assertEquals(2,json.readTree(business).path("items").size());
    }
}
