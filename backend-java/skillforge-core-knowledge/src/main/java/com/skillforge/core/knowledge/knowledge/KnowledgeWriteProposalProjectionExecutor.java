package com.skillforge.core.knowledge.knowledge;

import java.util.Map;

/**
 * 审核通过后的 Knowledge 写回下游投影 SPI。
 *
 * <p>治理队列只负责审核 CAS 与投影状态；具体事实源由拥有该事实源的模块实现，
 * 这样不会让 core-shared 依赖机构知识库的具体存储实现。
 */
public interface KnowledgeWriteProposalProjectionExecutor {

    /** 是否能处理该提案及其 payload。 */
    boolean supports(KnowledgeWriteProposal proposal);

    /** 执行一次下游投影；实现必须以 proposalId 作为幂等边界。 */
    ProjectionResult project(String tenantId, KnowledgeWriteProposal proposal);

    /** 下游投影结果。 */
    record ProjectionResult(String status, Long reference, Map<String, Object> details, String error) {

        public ProjectionResult {
            if (!"applied".equals(status) && !"failed".equals(status)) {
                throw new IllegalArgumentException("projection status must be applied or failed");
            }
            details = details == null ? Map.of() : Map.copyOf(details);
        }

        public static ProjectionResult applied(Long reference, Map<String, Object> details) {
            return new ProjectionResult("applied", reference, details, null);
        }

        public static ProjectionResult failed(String error) {
            return new ProjectionResult("failed", null, Map.of(),
                    error == null || error.isBlank() ? "projection failed" : error);
        }
    }
}
