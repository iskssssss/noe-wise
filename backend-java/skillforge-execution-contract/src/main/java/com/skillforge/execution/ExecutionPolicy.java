package com.skillforge.execution;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.math.BigDecimal;

/** 受信宿主随执行请求传入的冻结运营策略；参与请求摘要与任务持久化。 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record ExecutionPolicy(long revision, int executionTimeoutSeconds, int dockerControlTimeoutSeconds,
        int workspaceRetentionHours, int workspaceCleanupIntervalMinutes, int outputCaptureBytes,
        int maxArtifactFiles, int maxConcurrentExecutions, String memoryLimit, BigDecimal cpuLimit, int pidsLimit) {
    public ExecutionPolicy {
        if(revision<1 || executionTimeoutSeconds<1 || executionTimeoutSeconds>ExecutionLimits.MAX_TIMEOUT_SECONDS || dockerControlTimeoutSeconds<1 || dockerControlTimeoutSeconds>3600
                || workspaceRetentionHours<1 || workspaceRetentionHours>8760 || workspaceCleanupIntervalMinutes<1 || workspaceCleanupIntervalMinutes>43200 || outputCaptureBytes<1024
                || outputCaptureBytes>100_000_000 || maxArtifactFiles<1 || maxArtifactFiles>100_000
                || maxConcurrentExecutions<1 || maxConcurrentExecutions>10_000 || pidsLimit<16 || pidsLimit>1_000_000
                || memoryLimit==null || memoryLimit.length()>32 || !memoryLimit.matches("[1-9][0-9]*[kKmMgG]") || cpuLimit==null
                || cpuLimit.compareTo(new BigDecimal("0.1"))<0 || cpuLimit.compareTo(new BigDecimal("256"))>0)
            throw new IllegalArgumentException("sandbox_execution_policy_invalid");
    }
}
