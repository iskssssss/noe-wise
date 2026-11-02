package com.skillforge.core.knowledge.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 保存机构 LLM 服务商配置请求 DTO。
 *
 * <p>供应商、服务、协议、认证与 Profile 版本必须是 SDK 目录声明的有效组合；模型名
 * 来自显式发现或用户输入，不受 SDK 静态清单限制。apiKey 传空串只在服务、认证与
 * endpoint 均未变化时保留旧值。
 *
 * @param enabled              是否启用
 * @param providerId           SDK 供应商品牌标识
 * @param serviceId            SDK 接入服务标识
 * @param profileVersion       SDK ServiceProfile 版本
 * @param protocol             SDK 模型协议标识
 * @param authenticationScheme SDK 认证方案标识
 * @param baseUrl              自定义服务 Base URL；官方服务可空
 * @param model                LLM 模型名
 * @param apiKey               API Key，原凭据范围未变化时空串保留旧值
 */
public record LlmConfigUpdateRequest(
        @Schema(description = "是否启用") boolean enabled,
        @Schema(description = "SDK 供应商品牌标识", example = "custom") String providerId,
        @Schema(description = "SDK 接入服务标识", example = "custom-openai-compatible") String serviceId,
        @Schema(description = "SDK ServiceProfile 版本", example = "custom-v1") String profileVersion,
        @Schema(description = "SDK 模型协议标识", example = "openai-chat-completions") String protocol,
        @Schema(description = "SDK 认证方案标识", example = "bearer-api-key") String authenticationScheme,
        @Schema(description = "自定义服务 Base URL；官方服务可空", example = "https://models.example/v1") String baseUrl,
        @Schema(description = "LLM 模型名", example = "configured-model") String model,
        @Schema(description = "API Key；原凭据范围未变化时空串保留旧值") String apiKey) {
}
