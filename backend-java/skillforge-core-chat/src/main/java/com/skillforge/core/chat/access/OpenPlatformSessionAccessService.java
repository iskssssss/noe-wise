package com.skillforge.core.chat.access;

import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.skillforge.core.foundation.integration.OpenPlatformAccessService;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.core.chat.session.SessionView;
import org.springframework.stereotype.Service;

/** 开放平台会话入口：身份授权归 foundation，会话归属及业务范围复验归 chat。 */
@Service
public final class OpenPlatformSessionAccessService {
    private final OpenPlatformAccessService identities;
    private final InstitutionIntegrationService integrations;
    private final SessionService sessions;

    public OpenPlatformSessionAccessService(OpenPlatformAccessService identities,
            InstitutionIntegrationService integrations, SessionService sessions) {
        this.identities = identities;
        this.integrations = integrations;
        this.sessions = sessions;
    }

    public OpenPlatformAccessService.Access require(String scope) {
        return identities.require(scope);
    }

    public OpenPlatformAccessService.Access require(ServicePrincipalContext.Identity identity, String scope) {
        return identities.require(identity, scope);
    }

    /** 所有公开面复用冻结归属；当前凭证由每次调用前的 require 复验。 */
    public SessionView session(OpenPlatformAccessService.Access access, String sessionId) {
        var session = sessions.requireOpenPlatform(access.principal().tenantId(), sessionId,
                access.principal().integrationId());
        OpenPlatformSessionOwnership.require(session, access, integrations);
        return session;
    }
}
