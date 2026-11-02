package com.skillforge.core.capability.tool;

import com.skillforge.core.capability.CapabilityMountSnapshot;
import com.skillforge.core.capability.ToolDefinition;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 只面向当前冻结挂载的 Tool 全量目录与精确解析边界。 */
public interface ToolCatalog {

    /** 返回当前快照中仍能精确解析版本与部署的 Tool。 */
    List<Entry> list(CapabilityMountSnapshot snapshot);

    /** 只接受冻结 mount handle；名称或 qualifiedName 不构成授权回退。 */
    Optional<Entry> resolve(CapabilityMountSnapshot snapshot, String toolHandle);

    /** 可安全投影给模型的目录项；target 保留精确调用绑定。 */
    record Entry(
            String serverIdentity,
            String qualifiedName,
            ToolResolver.Resolved target) {

        public Entry {
            serverIdentity = requireText(serverIdentity, "serverIdentity");
            qualifiedName = requireText(qualifiedName, "qualifiedName");
            Objects.requireNonNull(target, "target");
            String expected = serverIdentity + "/" + target.version().definition().name();
            if (!expected.equals(qualifiedName)) {
                throw new IllegalArgumentException(
                        "qualifiedName must bind server identity and ToolDefinition name");
            }
        }

        public String handle() {
            return target.mount().handle();
        }

        public ToolDefinition definition() {
            return target.version().definition();
        }

        private static String requireText(String value, String field) {
            if (value == null || value.isBlank() || !value.equals(value.trim())) {
                throw new IllegalArgumentException(field + " must be normalized non-blank text");
            }
            return value;
        }
    }
}
