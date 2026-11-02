package com.skillforge.core.agent.systemagent;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.time.Instant;
import java.util.List;

/** 平台智能体的配置、版本与执行公开值；不暴露数据库实体或模型凭据。 */
public final class SystemAgentTypes {
    private SystemAgentTypes() {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Mount(String capabilityType, String capabilityDefinitionId, int priority,
                        Long tokenBudget, String activationMode, String toolDeploymentId) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Config(String name, String description, String systemPrompt, String modelConfigId,
                         int timeoutSeconds, int maxBusinessModelCalls, List<Mount> mounts) {
        public Config {
            if (maxBusinessModelCalls < 1 || maxBusinessModelCalls > 100) throw new IllegalArgumentException("system_agent_business_model_calls_invalid");
            mounts = mounts == null ? List.of() : List.copyOf(mounts);
        }
        @JsonAnySetter public void rejectUnknown(String field, Object value) {
            throw new IllegalArgumentException("system_agent_config_field_unknown");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record View(String id, String agentKey, String purpose, String executionMode, String status,
                       long revision, Long publishedVersion, Config draft, Config published, Instant updatedAt) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Version(long version, Config config, Instant createdAt) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RunView(String id, String agentId, Long version, boolean debug, String status,
                          String output, String errorCode,
                          @JsonSerialize(using = ToStringSerializer.class) Long inputTokens,
                          @JsonSerialize(using = ToStringSerializer.class) Long outputTokens,
                          long durationMs, Instant createdAt) {}

    /** 固定模型任务和通用 Runtime 执行共同使用的受信冻结输入。 */
    public record FrozenRun(String id, String agentId, String tenantId, String purpose,
                            String executionMode, Long version, boolean debug, Config config,
                            long modelRevision, String input, List<PlatformAgentResources.PublishedMount> mounts, RunView view) {}
}
