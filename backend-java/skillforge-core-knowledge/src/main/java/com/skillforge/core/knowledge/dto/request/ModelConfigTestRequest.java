package com.skillforge.core.knowledge.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 模型服务商连接测试请求 DTO（不落库）。
 *
 * @param baseUrl Base URL
 * @param apiKey  API Key（明文传入，仅测试用；留空使用已保存的 Key）
 * @param model   模型名
 * @param path    端点路径（Rerank 测试用，可选，默认 /rerank）
 */
public record ModelConfigTestRequest(
        @Schema(description = "OpenAI 兼容 Base URL") String baseUrl,
        @Schema(description = "API Key（明文传入，仅测试不落库；留空使用已保存的 Key 测试）") String apiKey,
        @Schema(description = "模型名") String model,
        @Schema(description = "端点路径（Rerank 测试用，可选，默认 /rerank）") String path) {
}
