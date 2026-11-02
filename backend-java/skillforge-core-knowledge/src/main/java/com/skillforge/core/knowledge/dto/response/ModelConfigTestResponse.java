package com.skillforge.core.knowledge.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 模型服务商连接测试响应 DTO。
 *
 * @param ok         是否成功
 * @param dimensions 向量维度（Embedding 测试成功时返回）
 * @param latencyMs  耗时（毫秒）
 * @param error      错误信息（失败时）
 */
public record ModelConfigTestResponse(
        @Schema(description = "是否成功") boolean ok,
        @Schema(description = "向量维度（Embedding 测试成功时返回）") Integer dimensions,
        @Schema(description = "耗时（毫秒）") long latencyMs,
        @Schema(description = "错误信息（失败时）") String error) {
}
