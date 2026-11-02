package com.skillforge.core.agent.dto.response;

/** TOOL CapabilityMount 的类型化响应配置。 */
public record ToolMountConfigResponse(
        String toolDeploymentId,
        long toolDeploymentRevision) {
}
