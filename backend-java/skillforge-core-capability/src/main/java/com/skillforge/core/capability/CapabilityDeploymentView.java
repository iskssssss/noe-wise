package com.skillforge.core.capability;

import com.skillforge.core.capability.tool.ToolDeployment;
import java.time.Instant;

/** Controller 可安全使用的 ToolDeployment 只读投影；不含凭证。 */
public record CapabilityDeploymentView(
        Long id,
        Long tenantId,
        String deploymentKey,
        Long toolVersionId,
        ToolDeployment.ProviderType providerType,
        String providerRef,
        ToolDeployment.Environment environment,
        String status,
        Long revision,
        Instant createdAt,
        Instant updatedAt) {
}
