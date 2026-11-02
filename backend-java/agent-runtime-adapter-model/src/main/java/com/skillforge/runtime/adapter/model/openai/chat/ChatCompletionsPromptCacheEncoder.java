package com.skillforge.runtime.adapter.model.openai.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.InvocationRequest;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.PromptCacheEncoding;

/** 只把 Provider 冻结的缓存策略编码为协议字段，不重排消息或修改业务历史。 */
final class ChatCompletionsPromptCacheEncoder {

    private ChatCompletionsPromptCacheEncoder() { }

    static void encode(ObjectNode root, InvocationRequest request, PromptCacheEncoding encoding) {
        switch (encoding) {
            case NONE -> { }
            case OPENAI_KEY -> key(root, request);
            case EPHEMERAL_MESSAGES -> ephemeralMessages((ArrayNode) root.path("messages"));
            case UNSUPPORTED_MODEL -> throw new UnsupportedModel();
            case INVALID_PROFILE -> throw new InvalidProfile();
        }
    }

    private static void key(ObjectNode root, InvocationRequest request) {
        request.promptCacheScope().ifPresent(scope -> root.put("prompt_cache_key", scope.key()));
    }

    private static void ephemeralMessages(ArrayNode messages) {
        // 首条稳定系统指令和最新 user/tool 各一个边界；末尾时间 system 不进入新缓存边界。
        // 始终低于协议允许的四个标记。
        // 标记只改变本次编码产物，不写回 ModelPort 的冻结消息或 Session 历史。
        // Qwen3.5+ 可能合并多条 system；未标记尾部不证明服务端把它排除在缓存外。
        if (!messages.isEmpty() && "system".equals(messages.get(0).path("role").asText())) {
            markLastText((ObjectNode) messages.get(0));
        }
        for (int index = messages.size() - 1; index > 0; index--) {
            String role = messages.get(index).path("role").asText();
            if (!"user".equals(role) && !"tool".equals(role)) continue;
            if (markLastText((ObjectNode) messages.get(index))) return;
        }
    }

    private static boolean markLastText(ObjectNode message) {
        JsonNode content = message.get("content");
        if (content == null || content.isNull()) return false;
        if (content.isTextual()) {
            if (content.textValue().isEmpty()) return false;
            ArrayNode parts = message.putArray("content");
            mark(parts.addObject().put("type", "text").put("text", content.textValue()));
            return true;
        }
        if (content.isArray()) {
            for (int index = content.size() - 1; index >= 0; index--) {
                JsonNode part = content.get(index);
                if (part.isObject() && "text".equals(part.path("type").asText())
                        && part.path("text").isTextual() && !part.path("text").textValue().isEmpty()) {
                    mark((ObjectNode) part);
                    return true;
                }
            }
        }
        return false;
    }

    private static void mark(ObjectNode part) {
        part.putObject("cache_control").put("type", "ephemeral");
    }

    static final class UnsupportedModel extends IllegalArgumentException {
        UnsupportedModel() { super("model_prompt_cache_model_unsupported"); }
    }

    static final class InvalidProfile extends IllegalArgumentException {
        InvalidProfile() { super("model_provider_profile_invalid"); }
    }
}
