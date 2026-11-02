package com.skillforge.core.knowledge.knowledge;

/** 验证写回提案引用的是已提交 EvidenceRef 或允许的 Session Observation。 */
public interface KnowledgeWriteProposalSourceVerifier {
    void verify(String tenantId, KnowledgeWriteProposal proposal);
}
