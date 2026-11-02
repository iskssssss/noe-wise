package com.skillforge.runtime.desktop;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.spi.ExecutionStore.Material;

/** 桌面内部协议和不可变宿主材料的 JSON 边界，不展开异常正文。 */
final class DesktopJson {
    static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(128).maxStringLength(8_000_000).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private DesktopJson() { }

    static JsonNode parse(String value) {
        try {
            var parsed = JSON.readTree(value);
            if (parsed == null || parsed.isMissingNode()) throw new IllegalArgumentException("desktop_json_invalid");
            return parsed;
        }
        catch (JsonProcessingException invalid) { throw new IllegalArgumentException("desktop_json_invalid", invalid); }
    }

    static String write(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (JsonProcessingException invalid) { throw new IllegalStateException("desktop_json_encoding_failed", invalid); }
    }

    static ObjectNode object() { return JSON.createObjectNode(); }

    static Material material(String schema, JsonNode value) {
        var payload = new TypedPayload(schema, write(value));
        return new Material(new ResultRef("desktop:" + AgentExecutionCodec.digest(schema + "\0" + payload.canonicalJson())), payload);
    }
}
