package com.skillforge.core.agent.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** TOOL CapabilityMount 的租户 Deployment 配置。 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ToolMountConfigRequest(
        String toolDeploymentId,
        Long toolDeploymentRevision) {
}
