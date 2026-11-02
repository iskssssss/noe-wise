package com.skillforge.core.knowledge.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 保存机构 Rerank 服务商配置请求 DTO。
 *
 * <p>apiKey 传空串表示保留旧值；path 为空串时使用默认 /rerank。
 *
 * @param enabled  是否启用
 * @param baseUrl  OpenAI 兼容 Base URL，需以 http(s):// 开头
 * @param model    Rerank 模型名
 * @param apiKey   API Key，空串保留旧值
 * @param path     端点路径，默认 /rerank
 * @param provider 服务商标识（默认 openai_compatible）
 */
public record RerankConfigUpdateRequest(
        @Schema(description = "是否启用") boolean enabled,
        @Schema(description = "OpenAI 兼容 Base URL，需以 http(s):// 开头") String baseUrl,
        @Schema(description = "Rerank 模型名", example = "BAAI/bge-reranker-v2-m3") String model,
        @Schema(description = "API Key，空串保留旧值") String apiKey,
        @Schema(description = "端点路径，默认 /rerank") String path,
        @Schema(description = "服务商标识，默认 openai_compatible") String provider) {
}
