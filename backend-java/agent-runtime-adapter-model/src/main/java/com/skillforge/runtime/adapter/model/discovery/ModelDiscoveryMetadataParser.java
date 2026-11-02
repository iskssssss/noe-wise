package com.skillforge.runtime.adapter.model.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 仅映射供应商明确返回的字段，不从模型名称或服务默认值生成声明。 */
public final class ModelDiscoveryMetadataParser {
    private ModelDiscoveryMetadataParser() { }

    public static Optional<ModelDiscoveryMetadata> openAi(JsonNode row) {
        String name = string(row, "name");
        var context = context(row);
        var inputs = strings(row, "input_modalities"); var outputs = strings(row, "output_modalities");
        var effort = object(row, "effort");
        var reasoning = effort == null ? null : new ModelDiscoveryMetadata.Reasoning(strings(effort, "supported_levels"), string(effort, "default_level"));
        var apis = object(row, "api_capabilities");
        var messages = apis == null ? null : object(apis, "anthropic_messages");
        var capability = messages == null ? null : new ModelDiscoveryMetadata.Messages(string(messages, "system_prompt_update"), string(messages, "tool_update"));
        var flags = strings(row, "capabilities"); var features = strings(row, "features");
        if (name == null && context == null && inputs == null && outputs == null && reasoning == null && capability == null && flags == null && features == null)
            return Optional.empty();
        return Optional.of(new ModelDiscoveryMetadata(name, context, inputs, outputs, reasoning, flags, features, capability));
    }

    public static ModelDiscoveryMetadata aliyun(JsonNode row) {
        var info = object(row, "model_info"); var modalities = object(row, "inference_metadata");
        return new ModelDiscoveryMetadata(string(row, "name"), info == null ? null : context(info),
                modalities == null ? null : strings(modalities, "request_modality"),
                modalities == null ? null : strings(modalities, "response_modality"), null,
                strings(row, "capabilities"), strings(row, "features"), null);
    }

    private static ModelDiscoveryMetadata.Context context(JsonNode row) {
        Long window = capacity(row, "context_window"), input = capacity(row, "max_input_tokens"), output = capacity(row, "max_output_tokens"),
                reasoning = capacity(row, "max_reasoning_tokens"), reasoningInput = capacity(row, "reasoning_max_input_tokens"),
                reasoningOutput = capacity(row, "reasoning_max_output_tokens");
        if (window == null && input == null && output == null && reasoning == null && reasoningInput == null && reasoningOutput == null) return null;
        return new ModelDiscoveryMetadata.Context(window, input, output, reasoning, reasoningInput, reasoningOutput);
    }

    private static Long capacity(JsonNode row, String field) {
        var value = row.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) throw invalid();
        return value.longValue();
    }
    public static String string(JsonNode row, String field) {
        var value = row.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual() || value.textValue().chars().anyMatch(c -> Character.isISOControl((char) c))) throw invalid();
        return value.textValue();
    }
    private static JsonNode object(JsonNode row, String field) {
        var value = row.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isObject()) throw invalid();
        return value;
    }
    private static List<String> strings(JsonNode row, String field) {
        var value = row.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isArray()) throw invalid();
        var result = new ArrayList<String>();
        for (var item : value) {
            if (!item.isTextual() || item.textValue().isBlank()) throw invalid();
            if (!result.contains(item.textValue())) result.add(item.textValue());
        }
        return List.copyOf(result);
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("model_discovery_response_invalid"); }
}
