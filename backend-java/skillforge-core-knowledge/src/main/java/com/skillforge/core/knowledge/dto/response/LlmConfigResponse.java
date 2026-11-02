package com.skillforge.core.knowledge.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 机构 LLM 服务商配置响应 DTO。
 *
 * @param enabled              是否启用（未启用时机构 AI 功能不可用）
 * @param providerId           SDK 供应商品牌标识
 * @param serviceId            SDK 接入服务标识
 * @param profileVersion       SDK ServiceProfile 版本
 * @param protocol             SDK 模型协议标识
 * @param authenticationScheme SDK 认证方案标识
 * @param dialectVersion       SDK 协议方言版本
 * @param verificationStatus   该服务协议适配的验证状态
 * @param baseUrl              解析后的 Base URL
 * @param model                LLM 模型名
 * @param hasApiKey            是否已配置 API Key
 * @param apiKeyMasked         API Key 脱敏值
 */
public record LlmConfigResponse(
        @Schema(description = "是否启用（未启用时机构 AI 功能不可用）") boolean enabled,
        @Schema(description = "SDK 供应商品牌标识") String providerId,
        @Schema(description = "SDK 接入服务标识") String serviceId,
        @Schema(description = "SDK ServiceProfile 版本") String profileVersion,
        @Schema(description = "SDK 模型协议标识") String protocol,
        @Schema(description = "SDK 认证方案标识") String authenticationScheme,
        @Schema(description = "SDK 协议方言版本") String dialectVersion,
        @Schema(description = "组合验证状态：VERIFIED / UNVERIFIED") String verificationStatus,
        @Schema(description = "解析后的 Base URL") String baseUrl,
        @Schema(description = "LLM 模型名") String model,
        @Schema(description = "是否已配置 API Key") boolean hasApiKey,
        @Schema(description = "API Key 脱敏值，未配置为空串") String apiKeyMasked) {
}
