package com.skillforge.core.capability;

import com.skillforge.core.capability.tool.ToolDeployment;

/** 可调用工具目录所需的当前发布摘要，不加载 Descriptor 或连接凭据。 */
public record AvailableToolDeployment(
        Long definitionId, Long versionId, String name, String description, String toolName,
        String deploymentKey, ToolDeployment.ProviderType providerType, String providerRef,
        ToolDeployment.Environment environment) {}
