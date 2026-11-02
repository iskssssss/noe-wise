package com.skillforge.core.knowledge.dto.response;

import java.util.List;

/** Knowledge Base revision 列表响应。 */
public record KnowledgeBaseRevisionListResponse(long total, List<KnowledgeBaseRevisionResponse> items) {
}
