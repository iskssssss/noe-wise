package com.skillforge.core.knowledge.dto.knowledge;

/** Knowledge Runtime 写回提案审核请求。 */
public record ReviewKnowledgeWriteProposalRequest(String reviewerId, String note) {
}
