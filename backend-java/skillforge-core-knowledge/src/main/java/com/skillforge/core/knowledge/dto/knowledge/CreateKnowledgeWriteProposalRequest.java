package com.skillforge.core.knowledge.dto.knowledge;

import com.skillforge.core.knowledge.knowledge.KnowledgeWriteProposal;
import java.util.Map;

/** Knowledge Runtime 写回提案创建请求。 */
public record CreateKnowledgeWriteProposalRequest(
        KnowledgeWriteProposal.Target target,
        String proposalId,
        String sourceEventId,
        String bundleId,
        Map<String, Object> payload) {
}
