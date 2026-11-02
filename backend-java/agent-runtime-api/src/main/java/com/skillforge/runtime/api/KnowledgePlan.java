package com.skillforge.runtime.api;

import java.util.HashMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 冻结知识请求的有序步骤、停止条件与规划器版本。 */
public record KnowledgePlan(
        String planId,
        String knowledgeRequestId,
        List<KnowledgeStep> steps,
        String stopCondition,
        String plannerVersion) {

    /** 拒绝空白身份、重复步骤身份、可变步骤或缺失规划事实。 */
    public KnowledgePlan {
        planId = ApiContracts.requireText(planId, "planId");
        knowledgeRequestId = ApiContracts.requireText(knowledgeRequestId, "knowledgeRequestId");
        steps = ApiContracts.immutableList(steps, "steps");
        stopCondition = ApiContracts.requireText(stopCondition, "stopCondition");
        plannerVersion = ApiContracts.requireText(plannerVersion, "plannerVersion");

        Map<String, KnowledgeStep> stepsById = new HashMap<>();
        // 逻辑步骤身份必须唯一，避免同一依赖引用解析到多个步骤。
        for (KnowledgeStep step : steps) {
            if (stepsById.putIfAbsent(step.stepId(), step) != null) {
                throw new IllegalArgumentException("steps must not contain duplicate stepId");
            }
        }
        // 每条依赖必须在同一冻结计划中存在，禁止留下运行时才能发现的悬空边。
        for (KnowledgeStep step : steps) {
            if (!stepsById.keySet().containsAll(step.dependsOn())) {
                throw new IllegalArgumentException("step dependencies must reference plan steps");
            }
        }
        ensureAcyclic(stepsById);
    }

    /** 迭代消减入度，避免扁平载荷中的长依赖路径耗尽 JVM 栈；不重排原 steps。 */
    private static void ensureAcyclic(Map<String, KnowledgeStep> stepsById) {
        Map<String, Integer> remaining = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        var ready = new ArrayDeque<String>();
        for (KnowledgeStep step : stepsById.values()) {
            remaining.put(step.stepId(), step.dependsOn().size());
            if (step.dependsOn().isEmpty()) { ready.addLast(step.stepId()); }
            for (String dependency : step.dependsOn()) {
                dependents.computeIfAbsent(dependency, ignored -> new ArrayList<>()).add(step.stepId());
            }
        }
        int processed = 0;
        while (!ready.isEmpty()) {
            String resolved = ready.removeFirst();
            processed++;
            for (String dependent : dependents.getOrDefault(resolved, List.of())) {
                int count = remaining.get(dependent) - 1;
                remaining.put(dependent, count);
                if (count == 0) { ready.addLast(dependent); }
            }
        }
        // 未处理节点必有环或依赖环；所有连通分量都必须消减完毕。
        if (processed != stepsById.size()) {
            throw new IllegalArgumentException("steps must not contain dependency cycles");
        }
    }
}
