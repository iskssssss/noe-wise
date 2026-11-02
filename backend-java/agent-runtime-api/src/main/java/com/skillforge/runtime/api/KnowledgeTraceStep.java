package com.skillforge.runtime.api;

import java.util.List;

/** 记录知识步骤中候选、选择、token 与耗时的可回放轨迹。 */
public record KnowledgeTraceStep(
        String stepId,
        String phase,
        List<String> candidateItemIds,
        List<String> selectedItemIds,
        int tokenCost,
        long elapsedMs) {

    /** 拒绝空白步骤、重复候选、越界选择或负用量。 */
    public KnowledgeTraceStep {
        stepId = ApiContracts.requireText(stepId, "stepId");
        phase = ApiContracts.requireText(phase, "phase");
        candidateItemIds = ApiContracts.immutableDistinctTextList(candidateItemIds, "candidateItemIds");
        selectedItemIds = ApiContracts.immutableDistinctTextList(selectedItemIds, "selectedItemIds");
        tokenCost = ApiContracts.requireNonNegative(tokenCost, "tokenCost");
        elapsedMs = ApiContracts.requireNonNegative(elapsedMs, "elapsedMs");
        // 选择阶段只能缩减本步骤已经记录的候选集合。
        if (!candidateItemIds.containsAll(selectedItemIds)) {
            throw new IllegalArgumentException("selectedItemIds must be a subset of candidateItemIds");
        }
    }
}
