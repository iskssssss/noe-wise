package com.skillforge.core.capability;

import com.skillforge.core.capability.tool.ToolDeployment;
import java.time.Instant;

/** ToolDeployment 只读投影；名称来自精确绑定版本，不可见时为空，不含凭证。 */
public record CapabilityDeploymentView(
        Long id,
        Long tenantId,
        String deploymentKey,
        Long toolVersionId,
        String toolName,
        String toolVersionKey,
        ToolDeployment.ProviderType providerType,
        String providerRef,
        ToolDeployment.Environment environment,
        String status,
        Long revision,
        Instant createdAt,
        Instant updatedAt) {
}
