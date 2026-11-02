package com.skillforge.runtime.api;

import java.util.List;
import java.util.Set;

/** 表示知识计划中受来源、算子与局部预算约束的确定性步骤。 */
public record KnowledgeStep(
        String stepId,
        String query,
        Set<String> sourceIds,
        KnowledgeOperatorType operator,
        List<String> dependsOn,
        KnowledgeBudget budget) {

    /** 拒绝空白步骤、重复依赖、可变来源集合或缺失算子与预算。 */
    public KnowledgeStep {
        stepId = ApiContracts.requireText(stepId, "stepId");
        query = ApiContracts.requireText(query, "query");
        sourceIds = ApiContracts.immutableTextSet(sourceIds, "sourceIds");
        operator = ApiContracts.requireNonNull(operator, "operator");
        dependsOn = ApiContracts.immutableDistinctTextList(dependsOn, "dependsOn");
        budget = ApiContracts.requireNonNull(budget, "budget");
        // 步骤不能依赖自身，否则计划无法形成确定性的有向无环执行顺序。
        if (dependsOn.contains(stepId)) {
            throw new IllegalArgumentException("dependsOn must not contain stepId");
        }
    }
}
