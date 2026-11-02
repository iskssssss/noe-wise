package com.skillforge.core.chat.access;

import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.skillforge.core.foundation.integration.OpenPlatformAccessService;
import com.skillforge.core.chat.session.SessionView;
import java.util.Objects;
import org.springframework.http.HttpStatus;

/** 开放平台个人会话归属与冻结业务范围的唯一校验。 */
final class OpenPlatformSessionOwnership {
    private OpenPlatformSessionOwnership() { }

    static void require(SessionView session, OpenPlatformAccessService.Access access,
            InstitutionIntegrationService integrations) {
        var principal = access.principal();
        if (session.getGroupBindingId() != null
                || !Objects.equals(session.getTenantId().toString(), principal.tenantId())
                || !Objects.equals(session.getIntegrationId(), principal.integrationId())
                || !("open-platform:" + principal.integrationId()).equals(session.getScopeType())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "会话不属于当前 integration");
        }
        if (session.getBusinessScopeId() != null) {
            integrations.requireBusinessScopeById(principal.tenantId(), principal.integrationId(),
                    session.getBusinessScopeId());
        }
    }
}
