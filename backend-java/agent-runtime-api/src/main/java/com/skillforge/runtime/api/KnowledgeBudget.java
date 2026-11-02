package com.skillforge.runtime.api;

/** 冻结一次知识请求可消费的查询、结果、token、字符和时间预算。 */
public record KnowledgeBudget(
        int maxQueries,
        int maxItems,
        int maxTokens,
        int maxCharacters,
        long timeoutMs) {

    /** 拒绝无法执行或可绕过治理的非正预算。 */
    public KnowledgeBudget {
        maxQueries = ApiContracts.requirePositive(maxQueries, "maxQueries");
        maxItems = ApiContracts.requirePositive(maxItems, "maxItems");
        maxTokens = ApiContracts.requirePositive(maxTokens, "maxTokens");
        maxCharacters = ApiContracts.requirePositive(maxCharacters, "maxCharacters");
        timeoutMs = ApiContracts.requirePositive(timeoutMs, "timeoutMs");
    }
}
