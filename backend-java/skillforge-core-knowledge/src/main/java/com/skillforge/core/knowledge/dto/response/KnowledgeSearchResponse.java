package com.skillforge.core.knowledge.dto.response;

import java.util.List;

/**
 * 知识库检索响应 DTO。
 *
 * @param items 检索结果列表
 */
public record KnowledgeSearchResponse(
        List<KnowledgeSearchItemResponse> items) {
}
