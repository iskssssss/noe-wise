package com.skillforge.runtime.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.media.ModelContent;
import com.skillforge.runtime.json.CodecLimits;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentExecutionCodecResourceFeedbackTest {
    private final AgentExecutionCodec codec = new AgentExecutionCodec(new CodecLimits(100_000, 10_000, 32));

    @Test
    void embeddedTextResourceKeepsIdentityAndOrderAlongsideStructuredContent() {
        var feedback = new TypedPayload(SessionRuntimePayloads.TOOL_RESULT_SCHEMA, """
                {"structuredContent":{"count":1},"content":[
                  {"type":"text","text":"前置说明"},
                  {"type":"resource","resource":{"uri":"fixture://guide/1","mimeType":"text/plain","text":"资源正文标记","_meta":{"private":"not-for-model"}}},
                  {"type":"text","text":"后置说明"}
                ],"resultType":"complete","isError":false}
                """);
        var contents = codec.toolContents(feedback);
        assertEquals(4, contents.size());
        assertEquals("{\"count\":1}", assertInstanceOf(ModelContent.Text.class, contents.get(0)).text());
        assertEquals("前置说明", assertInstanceOf(ModelContent.Text.class, contents.get(1)).text());
        String resource = assertInstanceOf(ModelContent.Text.class, contents.get(2)).text();
        assertTrue(resource.contains("fixture://guide/1"));
        assertTrue(resource.contains("text/plain"));
        assertTrue(resource.contains("资源正文标记"));
        assertFalse(resource.contains("not-for-model"));
        assertEquals("后置说明", assertInstanceOf(ModelContent.Text.class, contents.get(3)).text());
    }

    @Test
    void resourceMatchingStructuredTextStillKeepsItsSourceAndUnicodeLineBreaks() throws Exception {
        var feedback = new TypedPayload(SessionRuntimePayloads.TOOL_RESULT_SCHEMA, """
                {"structuredContent":{"text":"你好😀\\n第二行"},"content":[
                  {"type":"resource","resource":{"uri":"fixture://unicode","mimeType":"text/plain",
                    "text":"{\\"text\\":\\"你好😀\\\\n第二行\\"}"}},
                  {"type":"resource","resource":{"uri":"fixture://lines","mimeType":"text/plain",
                    "text":"你好😀\\n第二行"}}
                ]}
                """);
        var contents = codec.toolContents(feedback);
        assertEquals(3, contents.size());
        String sameText = assertInstanceOf(ModelContent.Text.class, contents.get(1)).text();
        assertTrue(sameText.contains("fixture://unicode"));
        assertTrue(sameText.contains("你好😀"));
        String multiline = assertInstanceOf(ModelContent.Text.class, contents.get(2)).text();
        assertTrue(multiline.contains("fixture://lines"));
        assertEquals("你好😀\n第二行", new com.fasterxml.jackson.databind.ObjectMapper().readTree(multiline).path("text").asText());
    }

    @Test
    void sensitiveFieldRemovalSuppressesResourceButAbsentFieldKeepsIt() {
        var limits = new CodecLimits(100_000, 10_000, 32);
        var schema = new TypedPayload("json-schema", "{}");
        var payload = new TypedPayload(SessionRuntimePayloads.TOOL_RESULT_SCHEMA, """
                {"structuredContent":{"secret":"private-value","count":1},"content":[
                  {"type":"resource","resource":{"uri":"fixture://protected","mimeType":"text/plain","text":"private-resource"}}
                ]}
                """);
        var stripped = SessionRuntimePayloads.safeToolOutput(schema, payload, Set.of("secret"), limits);
        var strippedContents = codec.toolContents(stripped);
        assertEquals(1, strippedContents.size());
        String safeText = assertInstanceOf(ModelContent.Text.class, strippedContents.getFirst()).text();
        assertTrue(safeText.contains("count"));
        assertFalse(safeText.contains("private-value"));
        assertFalse(stripped.canonicalJson().contains("private-resource"));
        var untouched = SessionRuntimePayloads.safeToolOutput(schema, payload, Set.of("not-present"), limits);
        var retainedContents = codec.toolContents(untouched);
        assertEquals(2, retainedContents.size());
        String resource = assertInstanceOf(ModelContent.Text.class, retainedContents.getLast()).text();
        assertTrue(resource.contains("fixture://protected"));
        assertTrue(resource.contains("private-resource"));
    }

    @Test
    void malformedAndBinaryResourcesCannotBePresentedAsText() {
        for (String resource : new String[] {
                "{\"uri\":\"fixture://binary\",\"mimeType\":\"application/octet-stream\",\"blob\":\"AQID\"}",
                "{\"uri\":\"fixture://mixed\",\"text\":\"text\",\"blob\":\"AQID\"}",
                "{\"uri\":\"fixture://invalid\",\"text\":7}",
                "{\"text\":\"没有资源身份\"}"
        }) {
            var feedback = new TypedPayload(SessionRuntimePayloads.TOOL_RESULT_SCHEMA,
                    "{\"content\":[{\"type\":\"resource\",\"resource\":" + resource + "}]}");
            assertThrows(IllegalArgumentException.class, () -> codec.toolContents(feedback));
        }
    }
}
