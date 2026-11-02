package com.skillforge.runtime.starter;

import com.skillforge.runtime.api.port.KnowledgePortal.FrozenKnowledgeTarget;
import com.skillforge.runtime.api.port.WorkflowPortal.FrozenWorkflowTarget;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** 已发布模型预算与能力候选的值契约；不拥有受理、执行、恢复或旧Session准入行为。 */
public final class AgentRuntimeAdmissionConfiguration {
    private AgentRuntimeAdmissionConfiguration() { }

    /** 宿主发布配置给出的封闭预算；实际消耗由唯一typed执行Store登记。 */
    public record Budget(int maxBusinessModelCalls, int maxToolCalls, int maxKnowledgeCalls,
            long maxInputTokens, long maxOutputTokens, long maxTotalTokens, Duration maxWallTime) {
        public Budget {
            Objects.requireNonNull(maxWallTime);
            if (maxBusinessModelCalls < 0 || maxToolCalls < 0 || maxKnowledgeCalls < 0 || maxInputTokens <= 0
                    || maxOutputTokens <= 0 || maxTotalTokens <= 0 || maxWallTime.isZero() || maxWallTime.isNegative())
                throw new IllegalArgumentException("runtime_admission_budget_invalid");
        }
    }

    /** 已由宿主从发布资源解析的不可变Workflow与Knowledge候选。 */
    public record CapabilityTargets(long generation, List<FrozenWorkflowTarget> workflows, List<FrozenKnowledgeTarget> knowledge) {
        public CapabilityTargets {
            if (generation <= 0) throw new IllegalArgumentException("runtime_capability_generation_invalid");
            workflows = List.copyOf(workflows); knowledge = List.copyOf(knowledge);
        }
    }
}
