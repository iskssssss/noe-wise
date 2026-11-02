package com.skillforge.runtime.api;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Optional;

/** 冻结一次知识请求的计划、证据、冲突、充分性与完整执行轨迹。 */
public record EvidenceBundle(
        String bundleId,
        String knowledgeRequestId,
        KnowledgePlan plan,
        List<EvidenceItem> items,
        List<List<String>> conflictItemIds,
        EvidenceSufficiency sufficiency,
        List<KnowledgeTraceStep> trace,
        long version,
        Instant createdAt,
        Optional<KnowledgeDocumentResult> documentResult) {

    /** 拒绝请求错配、重复证据、悬空冲突引用、可变集合或无效版本。 */
    public EvidenceBundle {
        bundleId = ApiContracts.requireText(bundleId, "bundleId");
        knowledgeRequestId = ApiContracts.requireText(knowledgeRequestId, "knowledgeRequestId");
        plan = ApiContracts.requireNonNull(plan, "plan");
        items = ApiContracts.immutableList(items, "items");
        conflictItemIds = immutableConflictGroups(conflictItemIds);
        sufficiency = ApiContracts.requireNonNull(sufficiency, "sufficiency");
        trace = ApiContracts.immutableDistinctList(trace, "trace");
        version = ApiContracts.requirePositive(version, "version");
        createdAt = ApiContracts.requireNonNull(createdAt, "createdAt");
        documentResult = ApiContracts.requireOptional(documentResult, "documentResult");
        if (documentResult.orElse(null) instanceof KnowledgeDocumentResult.CatalogPage && !items.isEmpty())
            throw new IllegalArgumentException("knowledge catalog cannot carry evidence items");

        // Bundle 与计划必须属于同一个不可混用的知识请求。
        if (!knowledgeRequestId.equals(plan.knowledgeRequestId())) {
            throw new IllegalArgumentException("plan knowledgeRequestId must match bundle");
        }

        Set<String> itemIds = new HashSet<>();
        // itemId 是冲突与引用闭包的稳定键，Bundle 内必须唯一。
        for (EvidenceItem item : items) {
            if (!itemIds.add(item.itemId())) {
                throw new IllegalArgumentException("items must not contain duplicate itemId");
            }
        }
        // 冲突组只能引用本 Bundle 中真实存在的证据项。
        for (List<String> group : conflictItemIds) {
            if (!itemIds.containsAll(group)) {
                throw new IllegalArgumentException("conflictItemIds must reference bundle items");
            }
        }

        Set<String> planStepIds = new HashSet<>();
        // 执行轨迹只能引用本 Bundle 冻结计划中的步骤和证据项。
        for (KnowledgeStep step : plan.steps()) {
            planStepIds.add(step.stepId());
        }
        for (KnowledgeTraceStep traceStep : trace) {
            if (!planStepIds.contains(traceStep.stepId())) {
                throw new IllegalArgumentException("trace stepId must reference plan step");
            }
            if (!itemIds.containsAll(traceStep.candidateItemIds())) {
                throw new IllegalArgumentException("trace candidates must reference bundle items");
            }
        }
    }

    /** 深度复制冲突分组，并拒绝空组、空白身份和组内重复。 */
    private static List<List<String>> immutableConflictGroups(List<List<String>> groups) {
        List<List<String>> outer = ApiContracts.immutableDistinctList(groups, "conflictItemIds");
        return outer.stream()
                .map(group -> {
                    List<String> copy = ApiContracts.immutableDistinctTextList(group, "conflictItemIds group");
                    // 空冲突组不携带任何可解释事实。
                    if (copy.isEmpty()) {
                        throw new IllegalArgumentException("conflictItemIds group must not be empty");
                    }
                    return copy;
                })
                .toList();
    }
}
