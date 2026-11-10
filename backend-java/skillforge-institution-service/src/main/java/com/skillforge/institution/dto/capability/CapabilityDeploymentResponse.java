package com.skillforge.institution.dto.capability;

import com.skillforge.core.capability.CapabilityDeploymentView;
import com.skillforge.core.capability.tool.ToolDeployment;
import java.time.Instant;

/** ToolDeployment API 响应；永远不含 Connector/MCP 凭证。 */
public record CapabilityDeploymentResponse(
        @io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*")
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long id,
        @io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*")
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long tenantId,
        String deploymentKey,
        @io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*")
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long toolVersionId,
        @io.swagger.v3.oas.annotations.media.Schema(nullable=true, description="精确绑定版本的名称，版本不可见时为空") String toolName,
        @io.swagger.v3.oas.annotations.media.Schema(nullable=true, description="精确绑定版本的版本键，版本不可见时为空") String toolVersionKey,
        ToolDeployment.ProviderType providerType,
        String providerRef,
        ToolDeployment.Environment environment,
        String status,
        Long revision,
        Instant createdAt,
        Instant updatedAt) {

    public static CapabilityDeploymentResponse from(CapabilityDeploymentView value) {
        return new CapabilityDeploymentResponse(value.id(), value.tenantId(), value.deploymentKey(),
                value.toolVersionId(), value.toolName(), value.toolVersionKey(),
                value.providerType(), value.providerRef(), value.environment(),
                value.status(), value.revision(), value.createdAt(), value.updatedAt());
    }
}
