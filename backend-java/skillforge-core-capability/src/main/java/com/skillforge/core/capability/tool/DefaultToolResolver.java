package com.skillforge.core.capability.tool;

import com.skillforge.core.capability.CapabilityMetadata;
import com.skillforge.core.capability.CapabilityMount;
import com.skillforge.core.capability.CapabilityMount.ToolMountConfig;
import com.skillforge.core.capability.CapabilityMountSnapshot;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.capability.ToolVersion;
import java.util.Objects;
import java.util.Optional;

/** 默认 ToolResolver；名称查找、活动版本回退和未挂载 Deployment 一律失败关闭。 */
public final class DefaultToolResolver implements ToolResolver {

    private final ToolRegistry tools;
    private final ToolDeploymentRegistry deployments;

    public DefaultToolResolver(ToolRegistry tools, ToolDeploymentRegistry deployments) {
        this.tools = Objects.requireNonNull(tools, "tools");
        this.deployments = Objects.requireNonNull(deployments, "deployments");
    }

    @Override
    public Resolution resolve(String toolHandle, CapabilityMountSnapshot snapshot) {
        if (toolHandle == null || toolHandle.isBlank() || snapshot == null) {
            return rejected(Reason.CAPABILITY_NOT_MOUNTED);
        }
        CapabilityMount mount = snapshot.findActive(toolHandle).orElse(null);
        if (mount == null || mount.capability().type() != CapabilityType.TOOL
                || !(mount.config() instanceof ToolMountConfig config)) {
            return rejected(Reason.CAPABILITY_NOT_MOUNTED);
        }
        Optional<ToolVersion> versionResult = tools.findVersion(
                snapshot.tenantId(), mount.capability());
        ToolVersion version = versionResult == null ? null : versionResult.orElse(null);
        if (version == null
                || version.metadata().status() != CapabilityMetadata.Status.PUBLISHED
                || !version.metadata().ref().equals(mount.capability())
                || version.metadata().trust() == CapabilityMetadata.Trust.TENANT
                    && !Objects.equals(version.metadata().tenantId(), snapshot.tenantId())
                || version.metadata().trust() == CapabilityMetadata.Trust.PLATFORM
                    && version.metadata().tenantId() != null) {
            return rejected(Reason.TOOL_VERSION_INACTIVE);
        }
        Optional<ToolDeployment> deploymentResult = deployments.findDeployment(
                snapshot.tenantId(), config.toolDeploymentId());
        ToolDeployment deployment = deploymentResult == null
                ? null : deploymentResult.orElse(null);
        if (deployment == null || !deployment.active()
                || deployment.revision() < config.toolDeploymentRevision()
                || !Objects.equals(deployment.tenantId(), snapshot.tenantId())
                || !deployment.toolVersion().capabilityId().equals(mount.capability().capabilityId())) {
            return rejected(Reason.TOOL_DEPLOYMENT_UNAVAILABLE);
        }
        // Deployment 身份与 Provider 不随能力发布改变；实时启停仍须检查，执行内容取原快照。
        return new Resolved(mount, version, new ToolDeployment(deployment.deploymentId(), deployment.tenantId(),
                mount.capability(), deployment.providerType(), deployment.providerRef(), config.toolDeploymentRevision(),
                deployment.environment(), deployment.active()));
    }

    private static Rejected rejected(Reason reason) {
        return new Rejected(reason);
    }
}
