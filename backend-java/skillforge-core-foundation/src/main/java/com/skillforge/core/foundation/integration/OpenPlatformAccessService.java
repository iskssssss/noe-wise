package com.skillforge.core.foundation.integration;

import com.skillforge.common.context.ServicePrincipalContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Open Platform 当前主体与 scope 授权；异步出口也复验撤销。 */
@Service
public class OpenPlatformAccessService {
    private static final Logger LOG = LoggerFactory.getLogger(OpenPlatformAccessService.class);
    private final InstitutionIntegrationService integrations;

    public OpenPlatformAccessService(InstitutionIntegrationService integrations) {
        this.integrations=integrations;
    }

    /** scope 和接入身份只取当前已认证主体；机构接入失效或凭证轮换后失败关闭。 */
    public Access require(String scope) { return require(ServicePrincipalContext.get(), scope); }

    /** SSE 持有首次认证事实，但每轮读事实前仍核对数据库中的当前凭证版本与机构接入授权。 */
    public Access require(ServicePrincipalContext.Identity identity, String scope) {
        LOG.debug("开放平台当前授权进入 scope={}", scope);
        try {
            integrations.requirePrincipal(identity,scope);
            return new Access(identity);
        } finally { LOG.debug("开放平台当前授权退出 scope={}",scope); }
    }

    public record Access(ServicePrincipalContext.Identity principal) {}
}
