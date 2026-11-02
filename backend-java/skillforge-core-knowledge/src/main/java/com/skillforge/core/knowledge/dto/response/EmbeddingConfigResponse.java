package com.skillforge.core.knowledge.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 机构 Embedding 服务商配置响应 DTO。
 *
 * @param enabled          是否启用
 * @param provider         服务商标识（当前固定 openai_compatible）
 * @param baseUrl          OpenAI 兼容 Base URL
 * @param model            Embedding 模型名
 * @param dimensions       向量维度（测试连接自动回填，展示用）
 * @param hasApiKey        是否已配置 API Key
 * @param apiKeyMasked     API Key 脱敏值（如 sk-***abc），未配置为空
 * @param fallbackToSystem 是否回退平台全局 Embedding 模型（未启用或未配置时为 true）
 */
public record EmbeddingConfigResponse(
        @Schema(description = "是否启用") boolean enabled,
        @Schema(description = "服务商标识，当前固定 openai_compatible") String provider,
        @Schema(description = "OpenAI 兼容 Base URL") String baseUrl,
        @Schema(description = "Embedding 模型名") String model,
        @Schema(description = "向量维度（测试连接自动回填，展示用）") Integer dimensions,
        @Schema(description = "是否已配置 API Key") boolean hasApiKey,
        @Schema(description = "API Key 脱敏值，未配置为空串") String apiKeyMasked,
        @Schema(description = "是否回退平台全局 Embedding 模型（未启用或未配置时为 true）") boolean fallbackToSystem) {
}
