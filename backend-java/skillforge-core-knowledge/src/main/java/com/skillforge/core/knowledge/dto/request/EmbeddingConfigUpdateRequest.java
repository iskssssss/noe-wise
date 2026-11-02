package com.skillforge.core.knowledge.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 保存机构 Embedding 服务商配置请求 DTO。
 *
 * <p>apiKey 传空串表示保留旧值（不修改），传非空表示更新；baseUrl 与 model
 * 必填且 baseUrl 需以 http(s):// 开头。
 *
 * @param enabled 是否启用
 * @param baseUrl OpenAI 兼容 Base URL
 * @param model   Embedding 模型名
 * @param apiKey  API Key，空串保留旧值
 * @param provider 服务商标识（默认 openai_compatible）
 */
public record EmbeddingConfigUpdateRequest(
        @Schema(description = "是否启用") boolean enabled,
        @Schema(description = "OpenAI 兼容 Base URL，需以 http(s):// 开头", example = "https://api.openai.com/v1") String baseUrl,
        @Schema(description = "Embedding 模型名", example = "text-embedding-3-small") String model,
        @Schema(description = "API Key，空串保留旧值") String apiKey,
        @Schema(description = "服务商标识，默认 openai_compatible") String provider) {
}
