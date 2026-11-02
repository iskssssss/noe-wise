package com.skillforge.core.foundation.integration.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.PrincipalRef;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentRuntimeSystemGrantsTest {

    @Test
    void registersSameGrantIdempotentlyAndRejectsDrift() {
        var grants = new AgentRuntimeSystemGrants();
        var tenant = new TenantId("12");
        grants.register(tenant, "agent-runtime-worker", Set.of("sessions.write"));
        grants.register(tenant, "agent-runtime-worker", Set.of("sessions.write"));
        assertThat(grants.tenantIds()).containsExactly(tenant);
        assertThat(grants.view().get(new PrincipalRef.SystemPrincipal(tenant, "agent-runtime-worker")))
                .containsExactly("sessions.write");
        assertThatThrownBy(() -> grants.register(tenant, "agent-runtime-worker",
                Set.of("sessions.read")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("agent_runtime_system_grant_mismatch");
    }

    @Test
    void seedsConfiguredGrantsFromProperties() {
        var properties = new AgentRuntimePlatformProperties();
        var grant = new AgentRuntimePlatformProperties.SystemGrant();
        grant.setTenantId("3");
        grant.setPrincipalId("worker");
        grant.setActions(Set.of("sessions.write", "sessions.read"));
        properties.setSystemGrants(List.of(grant));
        var grants = new AgentRuntimeSystemGrants(properties);
        assertThat(grants.tenantIds()).containsExactly(new TenantId("3"));
    }
}
