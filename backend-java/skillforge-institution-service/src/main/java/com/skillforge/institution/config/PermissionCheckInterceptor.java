package com.skillforge.institution.config;

import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.access.FoundationPermissionInterceptor;
import org.springframework.stereotype.Component;

/** 使用当前数据库动作权限，并仅信任宿主认证链已验证的外部身份。 */
@Component
public class PermissionCheckInterceptor extends FoundationPermissionInterceptor {
    public PermissionCheckInterceptor(FoundationAccess access) {
        super(access, request -> com.skillforge.core.foundation.integration.ManagementAuthenticationInterceptor.verified(request)
                || ExternalAuthPaths.SOURCE_CHANNEL.equals(request.getAttribute(ExternalAuthPaths.ATTR_AUTH_SOURCE))
                || ExternalAuthPaths.SOURCE_OPENAPI.equals(request.getAttribute(ExternalAuthPaths.ATTR_AUTH_SOURCE))
                || ExternalAuthPaths.SOURCE_SERVICE_PRINCIPAL.equals(request.getAttribute(ExternalAuthPaths.ATTR_AUTH_SOURCE)));
    }
}
