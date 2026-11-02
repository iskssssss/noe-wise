package com.skillforge.execution;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** 持久工作区的冻结保留与检查点策略，独立于任务临时目录政策。 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record WorkspacePolicy(long revision, int checkpointIntervalSeconds, int localIdleHours, int snapshotHistory) {
    public WorkspacePolicy {
        if (revision < 1 || checkpointIntervalSeconds < 1 || localIdleHours < 1 || snapshotHistory < 1 || snapshotHistory > 100)
            throw new IllegalArgumentException("workspace_policy_invalid");
    }
}
