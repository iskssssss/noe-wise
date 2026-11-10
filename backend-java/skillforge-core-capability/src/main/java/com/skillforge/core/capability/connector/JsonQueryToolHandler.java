package com.skillforge.core.capability.connector;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 以 RFC 6901 JSON Pointer 读取 JSON 节点。 */
@Component
public class JsonQueryToolHandler implements PlatformToolHandler {

    private final ObjectMapper objectMapper;

    public JsonQueryToolHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String toolName() {
        return "json_query";
    }

    @Override
    public boolean isError(Map<String, Object> result) {
        return Boolean.FALSE.equals(result.get("success"));
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> args, PlatformToolExecutionContext context) {
        if (args == null || !args.containsKey("value")) {
            return Map.of("success", false, "error", "value 不能为空");
        }
        String pointer = args.get("pointer") == null ? "" : String.valueOf(args.get("pointer"));
        try {
            JsonNode source = args.get("value") instanceof String text
                    ? objectMapper.readTree(text) : objectMapper.valueToTree(args.get("value"));
            JsonNode result = source.at(JsonPointer.compile(pointer));
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("found", !result.isMissingNode());
            response.put("value", result.isMissingNode() ? null : objectMapper.convertValue(result, Object.class));
            return response;
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return Map.of("success", false, "error", "pointer 必须是有效的 RFC 6901 JSON Pointer，value 必须是 JSON 值");
        }
    }
}
