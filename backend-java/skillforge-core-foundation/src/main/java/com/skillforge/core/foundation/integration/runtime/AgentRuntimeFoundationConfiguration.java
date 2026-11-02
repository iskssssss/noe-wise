package com.skillforge.core.foundation.integration.runtime;

import com.skillforge.core.foundation.integration.runtime.callback.JdbcCallbackIngress;
import com.skillforge.core.foundation.integration.runtime.callback.JdbcCallbackSigningKeys;
import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;
import com.skillforge.core.foundation.integration.runtime.session.JdbcCallbackPolicy;
import com.skillforge.runtime.adapter.jdbc.callback.JdbcCallbackRegistrationPort;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionLedgerPort;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.starter.AgentRuntimeDecrypt;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 平台身份与回调的显式装配；不依赖 Agent、知识、能力或 Workflow 领域。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentRuntimePlatformProperties.class)
public class AgentRuntimeFoundationConfiguration {
    @Bean
    AgentRuntimeSystemGrants agentRuntimeSystemGrants(AgentRuntimePlatformProperties properties) {
        properties.validate();
        return new AgentRuntimeSystemGrants(properties);
    }

    @Bean
    JdbcIdentityPolicyPort agentRuntimeIdentityPolicy(DataSource source, AgentRuntimeSystemGrants grants) {
        return new JdbcIdentityPolicyPort(source, grants.view());
    }

    @Bean
    AgentRuntimeTrustedAccess agentRuntimeTrustedAccess(
            JdbcIdentityPolicyPort identities, AgentRuntimePlatformProperties properties) {
        return new AgentRuntimeTrustedAccess(identities, properties.getTrustedAuthenticationValidity());
    }

    @Bean
    JdbcCallbackSigningKeys agentRuntimeCallbackSigningKeys(DataSource source, AgentRuntimeDecrypt decrypt) {
        return new JdbcCallbackSigningKeys(source, decrypt::decrypt);
    }

    @Bean
    JdbcCallbackIngress agentRuntimeCallbackIngress(DataSource source, JdbcCallbackSigningKeys keys,
            JdbcIdentityPolicyPort identities, AgentRuntimePlatformProperties properties,
            AgentRuntimeSystemGrants grants) {
        Set<String> schemaIds = Set.copyOf(properties.getCallbackPayloadSchemaIds());
        return new JdbcCallbackIngress(source, keys, identities, tenant -> systemPrincipal(grants, tenant),
                payload -> schemaIds.contains(payload.schemaId()), properties.getCallbackSignatureWindow(),
                properties.getCallbackRecoveryWindow(), properties.getCallbackMaximumBodyBytes());
    }

    @Bean
    JdbcCallbackRegistrationPort agentRuntimeCallbacks(DataSource source, JdbcIdentityPolicyPort identities,
            JdbcExecutionLedgerPort executions, JdbcCallbackIngress ingress) {
        var policy = new JdbcCallbackPolicy(source, identities, executions);
        return new JdbcCallbackRegistrationPort(source, policy::authorizeRegister,
                policy::authorizeStatus, ingress::verifyProof);
    }

    private static PrincipalRef.SystemPrincipal systemPrincipal(AgentRuntimeSystemGrants grants, TenantId tenant) {
        var matches = grants.view().keySet().stream()
                .filter(principal -> principal.tenantId().equals(tenant)).toList();
        if (matches.size() != 1) throw new IllegalStateException("callback_system_principal_unresolved");
        return matches.getFirst();
    }
}
