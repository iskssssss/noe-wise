package com.skillforge.institution.runtime;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.core.knowledge.KnowledgeLinkFetcher;
import com.skillforge.core.knowledge.integration.runtime.knowledge.JdbcKnowledgePortal;
import com.skillforge.runtime.adapter.jdbc.memory.JdbcMemoryPort;
import com.skillforge.runtime.memory.MemoryAccessPolicy;
import com.skillforge.runtime.memory.MemoryRuntime;
import com.skillforge.runtime.memory.MemorySourcePolicy;
import com.skillforge.runtime.starter.AgentRuntimeDecrypt;
import com.skillforge.runtime.starter.AgentRuntimeTenantBinder;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 灵知 宿主向 Agent Runtime Starter 提供的必选生产 bean。
 *
 * <p>预算事务、解密、租户绑定和 Memory 授权由宿主显式给出；缺失时 Starter
 * 启动失败，不安装 in-memory fallback。</p>
 */
@Configuration
@Import({
        com.skillforge.core.foundation.integration.runtime.AgentRuntimeFoundationConfiguration.class,
        com.skillforge.core.capability.integration.runtime.AgentRuntimeCapabilityConfiguration.class,
        com.skillforge.core.knowledge.integration.runtime.AgentRuntimeKnowledgeConfiguration.class,
        com.skillforge.core.workflow.integration.runtime.AgentRuntimeWorkflowBindingConfiguration.class,
        com.skillforge.core.agent.integration.runtime.AgentRuntimeSessionCapabilitiesConfiguration.class
})
public class AgentRuntimeHostConfiguration {

    @Bean
    com.skillforge.runtime.api.port.ExecutionBudgetPort hostExecutionBudgets(javax.sql.DataSource source) {
        return new com.skillforge.runtime.adapter.jdbc.budget.JdbcExecutionBudgets(source,
                new HostBudgetTransactions());
    }

    @Bean
    AgentRuntimeDecrypt agentRuntimeDecrypt(CryptoService crypto) {
        return ciphertext -> crypto.decrypt(ciphertext).getBytes(StandardCharsets.UTF_8);
    }

    @Bean
    AgentRuntimeTenantBinder agentRuntimeTenantBinder() {
        return (tenant, work) -> {
            String previous = TenantContext.getTenantId();
            TenantContext.setTenantId(tenant.value());
            try {
                work.run();
            } finally {
                // 程序子调用嵌套在宿主 Handler 中，结束后恢复外层产物交付所需的租户。
                if (previous == null) TenantContext.clear();
                else TenantContext.setTenantId(previous);
            }
        };
    }

    @Bean
    MemoryAccessPolicy agentRuntimeMemoryAccessPolicy(com.skillforge.institution.group.GroupBindingService groups,
            com.skillforge.core.foundation.integration.InstitutionIntegrationService integrations,com.skillforge.core.foundation.workflowtest.WorkflowTestIsolation tests) {
        return new AgentRuntimeMemoryAccessPolicy(groups,integrations,tests);
    }

    @Bean
    AgentRuntimeMemoryTypeRegistry agentRuntimeMemoryTypeRegistry() {
        return new AgentRuntimeMemoryTypeRegistry();
    }

    @Bean
    MemorySourcePolicy agentRuntimeMemorySourcePolicy(com.skillforge.institution.group.GroupMemorySources sources,com.skillforge.core.foundation.workflowtest.WorkflowTestIsolation tests) {
        return new AgentRuntimeMemorySourcePolicy(sources,tests);
    }

    @Bean
    MemoryRuntime.CurrentFactSource agentRuntimeMemoryCurrentFacts(JdbcMemoryPort memory) {
        return memory::findCurrent;
    }

    @Bean
    JdbcKnowledgePortal.LinkReader agentRuntimeKnowledgeLinkReader(KnowledgeLinkFetcher fetcher) {
        return (url, maxCharacters, timeout) -> {
            KnowledgeLinkFetcher.FetchResult result = fetcher.fetch(url, maxCharacters, timeout);
            return new JdbcKnowledgePortal.LinkResponse(result.url(), result.statusCode(),
                    result.contentType(), result.body(), result.redirected(), result.truncated());
        };
    }
}
