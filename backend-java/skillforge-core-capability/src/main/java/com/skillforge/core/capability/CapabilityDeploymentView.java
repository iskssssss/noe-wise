package com.skillforge.core.capability;

import com.skillforge.core.capability.tool.ToolDeployment;
import java.time.Instant;

/** ToolDeployment 只读投影；名称来自精确可见版本，定义关联仅提供已发布版本，不含凭证。 */
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
        Instant updatedAt,
        Long definitionId) {
}
