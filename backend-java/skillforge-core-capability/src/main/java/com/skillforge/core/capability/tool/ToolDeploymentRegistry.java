package com.skillforge.core.capability.tool;

import java.util.Optional;

/** Tool 部署的独立读取边界；执行 Provider 统一由 Agent Runtime 装配。 */
public interface ToolDeploymentRegistry {

    Optional<ToolDeployment> findDeployment(Long tenantId, String deploymentId);
}
