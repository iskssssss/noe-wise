package com.skillforge.runtime.api;

import java.util.List;

/** 记录 Memory 候选、选择、注入与实际引用四阶段的确定性集合。 */
public record MemoryRetrievalTrace(
        String queryId,
        List<String> candidateIds,
        List<String> selectedIds,
        List<String> injectedIds,
        List<String> referencedIds) {

    /** 拒绝空白、重复身份或违反阶段收敛关系的轨迹。 */
    public MemoryRetrievalTrace {
        queryId = ApiContracts.requireText(queryId, "queryId");
        candidateIds = ApiContracts.immutableDistinctTextList(candidateIds, "candidateIds");
        selectedIds = ApiContracts.immutableDistinctTextList(selectedIds, "selectedIds");
        injectedIds = ApiContracts.immutableDistinctTextList(injectedIds, "injectedIds");
        referencedIds = ApiContracts.immutableDistinctTextList(referencedIds, "referencedIds");

        // 四阶段只能逐步收窄，不能凭空引入未经过上一阶段的事实。
        if (!candidateIds.containsAll(selectedIds)
                || !selectedIds.containsAll(injectedIds)
                || !injectedIds.containsAll(referencedIds)) {
            throw new IllegalArgumentException("memory retrieval stages must form nested subsets");
        }
    }
}
