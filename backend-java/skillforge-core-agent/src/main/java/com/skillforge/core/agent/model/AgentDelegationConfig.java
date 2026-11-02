package com.skillforge.core.agent.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;

/** Agent 可委派目标的持久化配置。 */
public record AgentDelegationConfig(
        boolean enabled,
        @JsonProperty("target_agent_ids") List<String> targetAgentIds) {

    public AgentDelegationConfig {
        targetAgentIds = List.copyOf(Objects.requireNonNull(targetAgentIds, "targetAgentIds"));
    }

    /** 默认关闭且不允许任何目标。 */
    public static AgentDelegationConfig disabled() {
        return new AgentDelegationConfig(false, List.of());
    }
}
