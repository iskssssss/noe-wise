package com.skillforge.institution.common;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * 租户上下文工具。
 *
 * <p>提供请求级租户 ID 读取的公共入口：上下文缺失时抛出 401，
 * 供各控制器复用（替代原先逐控制器复制的私有方法）。
 */
public final class TenantIds {

    private TenantIds() {
    }

    /**
     * 获取当前请求租户 ID。
     *
     * @return 租户 ID
     * @throws ApiException 缺少租户上下文时抛出（401）
     */
    public static String requireTenantId() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
        }
        return tenantId;
    }
}
