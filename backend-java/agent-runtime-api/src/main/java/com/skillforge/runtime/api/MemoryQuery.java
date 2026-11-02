package com.skillforge.runtime.api;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/** 描述受身份、作用域、时态、相关度和预算约束的 Memory 查询。 */
public record MemoryQuery(
        String queryId,
        MemoryOwner owner,
        Optional<EndUserId> subjectEndUserId,
        String intent,
        Optional<MemoryTaskConstraint> taskConstraint,
        Set<MemoryScope> scopes,
        Set<MemoryTypeRef> types,
        MemoryQueryProfile profile,
        Instant asOf,
        MemoryRetrievalPolicy retrievalPolicy,
        int maxItems,
        int tokenBudget,
        int characterBudget,
        boolean requireProvenance) {

    /** 拒绝空 Optional、跨租户 Session、空作用域、无效评分或非正预算。 */
    public MemoryQuery {
        queryId = ApiContracts.requireText(queryId, "queryId");
        owner = ApiContracts.requireNonNull(owner, "owner");
        subjectEndUserId = ApiContracts.requireOptional(subjectEndUserId, "subjectEndUserId");
        intent = ApiContracts.requireText(intent, "intent");
        taskConstraint = ApiContracts.requireOptional(taskConstraint, "taskConstraint");
        scopes = ApiContracts.immutableSet(scopes, "scopes");
        types = ApiContracts.immutableSet(types, "types");
        profile = ApiContracts.requireNonNull(profile, "profile");
        asOf = ApiContracts.requireNonNull(asOf, "asOf");
        retrievalPolicy = ApiContracts.requireNonNull(retrievalPolicy, "retrievalPolicy");
        maxItems = ApiContracts.requirePositive(maxItems, "maxItems");
        tokenBudget = ApiContracts.requirePositive(tokenBudget, "tokenBudget");
        characterBudget = ApiContracts.requirePositive(characterBudget, "characterBudget");

        // 查询必须声明至少一个真实持久记忆作用域。
        if (scopes.isEmpty()) {
            throw new IllegalArgumentException("scopes must not be empty");
        }
        Set<MemoryScope> allowed = switch (owner) {
            case MemoryOwner.Session ignored -> Set.of(MemoryScope.SESSION, MemoryScope.USER_AGENT, MemoryScope.USER);
            case MemoryOwner.UserAgent ignored -> Set.of(MemoryScope.USER_AGENT, MemoryScope.USER);
            case MemoryOwner.User ignored -> Set.of(MemoryScope.USER);
            case MemoryOwner.Group ignored -> Set.of(MemoryScope.GROUP, MemoryScope.GROUP_MEMBER);
            case MemoryOwner.GroupMember member -> {
                if (subjectEndUserId.filter(subject -> !subject.equals(member.subjectEndUserId())).isPresent()) {
                    throw new IllegalArgumentException("query subject must match member owner");
                }
                yield Set.of(MemoryScope.GROUP_MEMBER);
            }
        };
        if (!allowed.containsAll(scopes)) {
            throw new IllegalArgumentException("query scopes must match owner branch");
        }
        if (!(owner instanceof MemoryOwner.Group) && !(owner instanceof MemoryOwner.GroupMember)
                && subjectEndUserId.isPresent()) {
            throw new IllegalArgumentException("personal query must not specify group subject");
        }
    }

    /** 查询租户来自归属，不接受独立的可漂移租户字段。 */
    public TenantId tenantId() { return owner.tenantId(); }
}
