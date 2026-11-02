package com.skillforge.core.agent.dto.response;

/** WORKFLOW CapabilityMount 的类型化响应配置。 */
public record WorkflowMountConfigResponse(
        String workflowInstanceId,
        long workflowInstanceRevision,
        long installationRevision) {
}
