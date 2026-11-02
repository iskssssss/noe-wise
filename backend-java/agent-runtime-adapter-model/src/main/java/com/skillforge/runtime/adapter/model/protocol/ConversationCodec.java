package com.skillforge.runtime.adapter.model.protocol;

import com.skillforge.runtime.adapter.model.provider.ModelProvider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ModelProtocol;
import java.io.IOException;
import java.util.Optional;

/** 协议级对话编解码器；不拥有供应商端点、凭据或能力声明。 */
public interface ConversationCodec {

    ModelProtocol protocol();

    ArrayNode encode(CanonicalConversation conversation,
            ModelProvider.ChatCompletionsPolicy policy) throws IOException;

    CanonicalConversation decodeAssistant(JsonNode message,
            boolean preserveArguments, boolean includeReasoning) throws IOException;

    Optional<String> decodeTextDelta(JsonNode delta) throws IOException;

    Optional<String> decodeReasoningDelta(JsonNode delta) throws IOException;

    Iterable<JsonNode> toolCallDeltas(JsonNode delta) throws IOException;

    ToolCallFragment decodeToolCallDelta(JsonNode call, boolean preserveArguments) throws IOException;

    /** 协议分片已解析；索引关联、聚合和事件调度仍由 transport 负责。 */
    record ToolCallFragment(int index, String callId, String name,
            String argumentsDelta, boolean invalidArguments) { }
}
