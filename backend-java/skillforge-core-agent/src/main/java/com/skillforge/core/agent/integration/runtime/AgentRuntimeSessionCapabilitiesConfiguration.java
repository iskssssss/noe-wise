package com.skillforge.core.agent.integration.runtime;

import com.skillforge.core.agent.integration.runtime.session.JdbcSessionCapabilities;
import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 产品会话能力装配；成员关系与当前身份仍由持久事实校验。 */
@Configuration(proxyBeanMethods = false)
public class AgentRuntimeSessionCapabilitiesConfiguration {
    @Bean
    JdbcSessionCapabilities agentRuntimeSessionCapabilities(DataSource source, JdbcIdentityPolicyPort identities) {
        return new JdbcSessionCapabilities(source, identities, Set.of("MCP", "PLATFORM"));
    }
}
