package com.skillforge.core.capability.tool;

import com.skillforge.core.capability.CapabilityMount;
import com.skillforge.core.capability.CapabilityMetadata;
import com.skillforge.core.capability.CapabilityMountSnapshot;
import com.skillforge.core.capability.ToolVersion;
import java.util.Objects;

/** 只从当前冻结挂载精确解析 ToolVersion 与 Deployment；不触碰 Provider。 */
@FunctionalInterface
public interface ToolResolver {

    Resolution resolve(String toolHandle, CapabilityMountSnapshot snapshot);

    sealed interface Resolution permits Resolved, Rejected {
    }

    record Resolved(
            CapabilityMount mount,
            ToolVersion version,
            ToolDeployment deployment) implements Resolution {

        public Resolved {
            Objects.requireNonNull(mount, "mount");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(deployment, "deployment");
            if (!mount.active()
                    || !(mount.config() instanceof CapabilityMount.ToolMountConfig config)
                    || version.metadata().status() != CapabilityMetadata.Status.PUBLISHED
                    || version.metadata().trust() == CapabilityMetadata.Trust.TENANT
                        && !Objects.equals(version.metadata().tenantId(), mount.tenantId())
                    || version.metadata().trust() == CapabilityMetadata.Trust.PLATFORM
                        && version.metadata().tenantId() != null
                    || !mount.capability().equals(version.metadata().ref())
                    || !deployment.toolVersion().equals(mount.capability())
                    || !deployment.active()
                    || !Objects.equals(deployment.tenantId(), mount.tenantId())
                    || !deployment.deploymentId().equals(config.toolDeploymentId())
                    || deployment.revision() != config.toolDeploymentRevision()) {
                throw new IllegalArgumentException(
                        "resolved ToolVersion and Deployment must exactly match the active frozen mount");
            }
        }
    }

    record Rejected(Reason reason) implements Resolution {
        public Rejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    enum Reason {
        CAPABILITY_NOT_MOUNTED,
        TOOL_VERSION_INACTIVE,
        TOOL_DEPLOYMENT_UNAVAILABLE
    }
}
