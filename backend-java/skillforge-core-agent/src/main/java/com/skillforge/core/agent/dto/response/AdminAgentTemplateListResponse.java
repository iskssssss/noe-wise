package com.skillforge.core.agent.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 平台运营后台 Agent 模板分页列表响应 DTO。
 *
 * @param total 匹配筛选条件的模板总数
 * @param items 当前页模板列表项
 */
public record AdminAgentTemplateListResponse(
        @Schema(description = "匹配筛选条件的模板总数") long total,
        @Schema(description = "当前页模板列表项") List<AdminAgentTemplateItemResponse> items) {
}
