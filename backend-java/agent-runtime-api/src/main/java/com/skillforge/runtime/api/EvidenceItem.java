package com.skillforge.runtime.api;

import java.util.List;

/** 表示带来源版本、算子、内容、路径与相关性的不可变证据项。 */
public record EvidenceItem(
        String itemId,
        String sourceId,
        String sourceVersion,
        KnowledgeOperatorType operator,
        TypedPayload content,
        List<String> provenanceRefs,
        List<String> graphPath,
        double relevance,
        String trustLevel) {

    /** 拒绝空白身份、缺失内容、重复引用或非有限相关度。 */
    public EvidenceItem {
        itemId = ApiContracts.requireText(itemId, "itemId");
        sourceId = ApiContracts.requireText(sourceId, "sourceId");
        sourceVersion = ApiContracts.requireText(sourceVersion, "sourceVersion");
        operator = ApiContracts.requireNonNull(operator, "operator");
        content = ApiContracts.requireNonNull(content, "content");
        provenanceRefs = ApiContracts.immutableDistinctTextList(provenanceRefs, "provenanceRefs");
        graphPath = ApiContracts.immutableDistinctTextList(graphPath, "graphPath");
        relevance = ApiContracts.requireUnitInterval(relevance, "relevance");
        trustLevel = ApiContracts.requireText(trustLevel, "trustLevel");
    }
}
