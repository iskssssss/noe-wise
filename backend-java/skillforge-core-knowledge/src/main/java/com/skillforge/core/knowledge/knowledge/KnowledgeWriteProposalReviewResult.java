package com.skillforge.core.knowledge.knowledge;

/**
 * 写回提案审核结果；审批状态与事实源投影状态分开表达。
 *
 * @param proposal         审核后的提案
 * @param projectionStatus 投影状态：pending / applied / failed
 * @param projectionRef    已投影事实的稳定引用，可空
 * @param projectionError  投影失败原因，可空
 */
public record KnowledgeWriteProposalReviewResult(
        KnowledgeWriteProposal proposal,
        String projectionStatus,
        Long projectionRef,
        String projectionError) {

    public boolean isApplied() {
        return "applied".equals(projectionStatus);
    }
}
