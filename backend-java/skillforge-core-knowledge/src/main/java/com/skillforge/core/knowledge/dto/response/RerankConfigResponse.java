package com.skillforge.core.knowledge.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 机构 Rerank 服务商配置响应 DTO。
 *
 * @param enabled          是否启用
 * @param provider         服务商标识（当前固定 openai_compatible）
 * @param baseUrl          OpenAI 兼容 Base URL
 * @param model            Rerank 模型名
 * @param path             端点路径（默认 /rerank）
 * @param hasApiKey        是否已配置 API Key
 * @param apiKeyMasked     API Key 脱敏值
 * @param fallbackToSystem 是否回退（未启用或未配置时为 true，即不重排）
 */
public record RerankConfigResponse(
        @Schema(description = "是否启用") boolean enabled,
        @Schema(description = "服务商标识，当前固定 openai_compatible") String provider,
        @Schema(description = "OpenAI 兼容 Base URL") String baseUrl,
        @Schema(description = "Rerank 模型名") String model,
        @Schema(description = "端点路径，默认 /rerank") String path,
        @Schema(description = "是否已配置 API Key") boolean hasApiKey,
        @Schema(description = "API Key 脱敏值，未配置为空串") String apiKeyMasked,
        @Schema(description = "是否回退（未启用或未配置时为 true，即不重排）") boolean fallbackToSystem) {
}
