package com.skillforge.core.foundation.integration.runtime;

import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;
import com.skillforge.runtime.api.AccessEnvelope;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.AuthenticationRef;
import com.skillforge.runtime.api.RequestId;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.port.SessionOwner;
import java.time.Duration;
import java.util.Objects;

/**
 * 把宿主已经完成凭据校验的服务主体登记为短期 Runtime 认证事实。
 * 本边界不接收原始凭据，也不从请求正文推断 owner 或权限。
 */
public final class AgentRuntimeTrustedAccess {
    private final JdbcIdentityPolicyPort identities;
    private final Duration validity;

    AgentRuntimeTrustedAccess(JdbcIdentityPolicyPort identities, Duration validity) {
        this.identities = Objects.requireNonNull(identities);
        this.validity = Objects.requireNonNull(validity);
    }

    /** 为一次只读或控制面调用创建完整 AccessEnvelope。 */
    public AccessEnvelope serviceAccess(
            PrincipalRef.ServicePrincipal principal,
            SessionOwner owner,
            AgentId executor,
            String channel,
            String source,
            RequestId requestId,
            String traceId,
            String spanId) {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(executor, "executor");
        if (!principal.tenantId().equals(owner.sessionRef().tenantId())) {
            throw new IllegalArgumentException("trusted principal and owner tenant must match");
        }
        AuthenticationRef authentication = identities.registerTrusted(
                principal, owner, channel, source, validity);
        return new AccessEnvelope(authentication, owner.sessionRef().tenantId(), owner.target(), java.util.Optional.of(executor),
                owner.subject(), requestId, channel, source, traceId, spanId);
    }

    /** 直连 Agent 的快捷入口；团队调用必须使用带 executor 的重载。 */
    public AccessEnvelope serviceAccess(
            PrincipalRef.ServicePrincipal principal,
            SessionOwner owner,
            String channel,
            String source,
            RequestId requestId,
            String traceId,
            String spanId) {
        return serviceAccess(principal, owner, owner.target().requireAgent(), channel, source,
                requestId, traceId, spanId);
    }
}
