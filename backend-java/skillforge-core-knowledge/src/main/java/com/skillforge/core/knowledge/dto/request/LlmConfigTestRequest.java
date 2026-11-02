package com.skillforge.core.knowledge.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 按 SDK 供应商目录测试 LLM 连接的请求；只执行显式测试，不保存配置。
 *
 * @param providerId           SDK 供应商品牌标识
 * @param serviceId            SDK 接入服务标识
 * @param profileVersion       SDK ServiceProfile 版本
 * @param protocol             SDK 模型协议标识
 * @param authenticationScheme SDK 认证方案标识
 * @param baseUrl              自定义服务 Base URL；官方服务可空
 * @param model                LLM 模型名
 * @param apiKey               API Key；原凭据范围未变化时可空
 */
public record LlmConfigTestRequest(
        @Schema(description = "SDK 供应商品牌标识", example = "custom") String providerId,
        @Schema(description = "SDK 接入服务标识", example = "custom-openai-compatible") String serviceId,
        @Schema(description = "SDK ServiceProfile 版本", example = "custom-v1") String profileVersion,
        @Schema(description = "SDK 模型协议标识", example = "openai-chat-completions") String protocol,
        @Schema(description = "SDK 认证方案标识", example = "bearer-api-key") String authenticationScheme,
        @Schema(description = "自定义服务 Base URL；官方服务可空") String baseUrl,
        @Schema(description = "LLM 模型名", example = "configured-model") String model,
        @Schema(description = "API Key；原凭据范围未变化时可空") String apiKey) {
}
