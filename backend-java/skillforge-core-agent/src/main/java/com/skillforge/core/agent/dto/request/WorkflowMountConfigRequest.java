package com.skillforge.core.agent.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** WORKFLOW CapabilityMount 的已安装实例配置。 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record WorkflowMountConfigRequest(
        String workflowInstanceId,
        Long workflowInstanceRevision) {
}
