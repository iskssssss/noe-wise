package com.skillforge.core.foundation.integration.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.AuthenticationRef;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.RequestId;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.port.SessionOwner;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AgentRuntimeTrustedAccessTest {
    @Test
    void convertsOnlyAnAlreadyVerifiedServicePrincipalIntoShortLivedAccess() {
        var identities = mock(JdbcIdentityPolicyPort.class);
        var tenant = new TenantId("7");
        var principal = new PrincipalRef.ServicePrincipal(tenant, "sp-1", "crm", 3);
        var owner = SessionOwner.user(new SessionRef(tenant, "session-1"),
                new AgentId("11"), new EndUserId(13));
        when(identities.registerTrusted(principal, owner, "open-platform", "request",
                Duration.ofMinutes(5))).thenReturn(new AuthenticationRef("auth-1"));

        var access = new AgentRuntimeTrustedAccess(identities, Duration.ofMinutes(5))
                .serviceAccess(principal, owner, "open-platform", "request",
                        new RequestId("request-1"), "trace-1", "span-1");

        assertThat(access.authenticationRef()).isEqualTo(new AuthenticationRef("auth-1"));
        assertThat(access.agentId()).contains(owner.target().requireAgent());
        assertThat(access.subject()).isEqualTo(owner.subject());
        verify(identities).registerTrusted(
                principal, owner, "open-platform", "request", Duration.ofMinutes(5));
    }

    @Test
    void rejectsCrossTenantOwnerBeforeCreatingAuthenticationFacts() {
        var identities = mock(JdbcIdentityPolicyPort.class);
        var principal = new PrincipalRef.ServicePrincipal(
                new TenantId("7"), "sp-1", "crm", 3);
        var owner = SessionOwner.user(new SessionRef(new TenantId("8"), "session-1"),
                new AgentId("11"), new EndUserId(13));

        assertThatThrownBy(() -> new AgentRuntimeTrustedAccess(
                identities, Duration.ofMinutes(5)).serviceAccess(
                        principal, owner, "open-platform", "request",
                        new RequestId("request-1"), "trace-1", "span-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("trusted principal and owner tenant must match");
    }
}
