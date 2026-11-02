package com.skillforge.core.capability.tool;

import com.skillforge.core.capability.CapabilityRef;
import com.skillforge.core.capability.ToolVersion;
import java.util.Optional;

/** 不可变 ToolVersion 的精确版本读取边界；可执行资格由 ToolResolver 判定。 */
@FunctionalInterface
public interface ToolRegistry {

    Optional<ToolVersion> findVersion(Long tenantId, CapabilityRef version);
}
