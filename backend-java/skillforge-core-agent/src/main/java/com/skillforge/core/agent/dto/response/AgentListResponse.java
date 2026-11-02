package com.skillforge.core.agent.dto.response;
import java.util.List;
/**
 * Agent 列表响应 DTO。
 *
 * @param total Agent 总数
 * @param items Agent 列表
 */
public record AgentListResponse(
        int total,
        List<AgentItemResponse> items) {
}
