package com.skillforge.core.agent.dto.response;
import java.util.List;
/**
 * Agent 模板列表响应 DTO。
 *
 * @param total 模板总数
 * @param items 模板列表
 */
public record AgentTemplateListResponse(
        int total,
        List<AgentTemplateItemResponse> items) {
}
