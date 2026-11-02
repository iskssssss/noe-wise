package com.skillforge.core.capability;

import java.util.Objects;

/**
 * Catalog 写入/读取边界。
 *
 * <p>平台资源与租户资源使用不同的作用域，不能靠请求体中的 tenant_id
 * 推断。平台作用域只允许写 {@code tenant_id IS NULL} 的公共制品；租户作用域
 * 必须绑定一个正数租户 ID。
 */
public record CapabilityCatalogScope(Long tenantId, boolean platformScope) {

    public CapabilityCatalogScope {
        if (platformScope && tenantId != null) {
            throw new IllegalArgumentException("platform catalog scope must not contain tenantId");
        }
        if (!platformScope && (tenantId == null || tenantId <= 0)) {
            throw new IllegalArgumentException("tenant catalog scope requires a positive tenantId");
        }
    }

    public static CapabilityCatalogScope platform() {
        return new CapabilityCatalogScope(null, true);
    }

    public static CapabilityCatalogScope tenant(Long tenantId) {
        return new CapabilityCatalogScope(Objects.requireNonNull(tenantId, "tenantId"), false);
    }

    public boolean isPlatform() {
        return platformScope;
    }
}
