package com.skillforge.core.knowledge.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 显式获取供应商模型候选的请求；不保存配置，也不发送推理请求。
 *
 * @param providerId           SDK 供应商品牌标识
 * @param serviceId            SDK 接入服务标识
 * @param profileVersion       SDK ServiceProfile 版本
 * @param authenticationScheme SDK 模型发现认证方案
 * @param baseUrl              自定义服务 Base URL；官方服务可空
 * @param apiKey               API Key；凭据范围未变化时可空
 */
public record LlmModelDiscoveryRequest(
        @Schema(description = "SDK 供应商品牌标识", example = "deepseek")
        String providerId,
        @Schema(description = "SDK 接入服务标识", example = "deepseek-api")
        String serviceId,
        @Schema(description = "SDK ServiceProfile 版本", example = "2026-09-13")
        String profileVersion,
        @Schema(description = "SDK 模型发现认证方案", example = "bearer-api-key")
        String authenticationScheme,
        @Schema(description = "自定义服务 Base URL；官方服务可空")
        String baseUrl,
        @Schema(description = "API Key；凭据范围未变化时可空")
        String apiKey) {
}
