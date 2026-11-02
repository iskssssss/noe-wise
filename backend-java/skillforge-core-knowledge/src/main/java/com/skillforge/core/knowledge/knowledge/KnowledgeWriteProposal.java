package com.skillforge.core.knowledge.knowledge;

import java.util.Map;

/** Knowledge Runtime 产生的写回提案；不会直接激活记忆或修改知识库。 */
public record KnowledgeWriteProposal(
        String proposalId,
        Target target,
        String sourceEventId,
        String bundleId,
        Map<String, Object> payload,
        Status status) {

    public KnowledgeWriteProposal {
        requireText(proposalId, "proposalId");
        if (target == null || status == null) {
            throw new NullPointerException("target/status");
        }
        requireText(sourceEventId, "sourceEventId");
        requireText(bundleId, "bundleId");
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    public enum Target { MEMORY, INSTITUTIONAL_KNOWLEDGE }

    public enum Status { PENDING_CONFIRM, APPROVED, REJECTED }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
