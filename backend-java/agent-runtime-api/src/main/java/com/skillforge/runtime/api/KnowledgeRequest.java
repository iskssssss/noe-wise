package com.skillforge.runtime.api;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/** 冻结一次知识检索的身份、来源、算子、预算与时态边界。 */
public record KnowledgeRequest(
        String knowledgeRequestId,
        TenantId tenantId,
        RunRequester requester,
        AgentId agentId,
        SessionRef sessionRef,
        TraceEnvelope trace,
        KnowledgeMode mode,
        String intent,
        KnowledgeOperation operation,
        Set<String> allowedSourceIds,
        Set<KnowledgeSourceVersion> allowedSourceVersions,
        Set<KnowledgeOperatorType> allowedOperators,
        KnowledgeBudget budget,
        Instant asOf,
        Optional<Instant> freshnessAfter,
        boolean citationRequired) {

    /** 拒绝缺失身份、空白意图、可变集合、空 Optional 与跨租户 Session。 */
    public KnowledgeRequest {
        knowledgeRequestId = ApiContracts.requireText(knowledgeRequestId, "knowledgeRequestId");
        tenantId = ApiContracts.requireNonNull(tenantId, "tenantId");
        requester = ApiContracts.requireNonNull(requester, "requester");
        if (requester instanceof RunRequester.System system && !system.principal().tenantId().equals(tenantId))
            throw new IllegalArgumentException("knowledge system requester tenant mismatch");
        agentId = ApiContracts.requireNonNull(agentId, "agentId");
        sessionRef = ApiContracts.requireNonNull(sessionRef, "sessionRef");
        trace = ApiContracts.requireNonNull(trace, "trace");
        mode = ApiContracts.requireNonNull(mode, "mode");
        intent = ApiContracts.requireText(intent, "intent");
        operation = ApiContracts.requireNonNull(operation, "operation");
        allowedSourceIds = ApiContracts.immutableTextSet(allowedSourceIds, "allowedSourceIds");
        allowedSourceVersions = ApiContracts.immutableSet(allowedSourceVersions, "allowedSourceVersions");
        allowedOperators = ApiContracts.immutableSet(allowedOperators, "allowedOperators");
        budget = ApiContracts.requireNonNull(budget, "budget");
        asOf = ApiContracts.requireNonNull(asOf, "asOf");
        freshnessAfter = ApiContracts.requireOptional(freshnessAfter, "freshnessAfter");
        ApiContracts.requireSameTenant(tenantId, sessionRef.tenantId(), "sessionRef.tenantId");

        // 来源版本是精确 pair 集合，可以保留同一来源的多个获准版本，但不能扩大来源范围。
        for (KnowledgeSourceVersion sourceVersion : allowedSourceVersions) {
            if (!allowedSourceIds.contains(sourceVersion.sourceId())) {
                throw new IllegalArgumentException("allowedSourceVersions must belong to allowedSourceIds");
            }
        }
        if (operation instanceof KnowledgeOperation.ListDocuments && !allowedOperators.contains(KnowledgeOperatorType.DOCUMENT_LIST)
                || operation instanceof KnowledgeOperation.ReadDocument && !allowedOperators.contains(KnowledgeOperatorType.DOCUMENT_READ))
            throw new IllegalArgumentException("knowledge_document_operator_rejected");
        // 文档selector是用户参数，不能改写以上授权集合；是否命中由Portal在SQL前确定拒绝并反馈。
    }
}
