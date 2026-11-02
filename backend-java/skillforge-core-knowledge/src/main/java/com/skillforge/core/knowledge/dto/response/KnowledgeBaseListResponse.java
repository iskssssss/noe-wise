package com.skillforge.core.knowledge.dto.response;

import java.util.List;

/**
 * 知识库列表响应 DTO。
 *
 * @param total 知识库总数
 * @param items 知识库列表
 */
public record KnowledgeBaseListResponse(
        int total,
        List<KnowledgeBaseItemResponse> items) {
}
