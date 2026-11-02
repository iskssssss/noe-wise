package com.skillforge.systemagent.runtime;

import com.skillforge.core.billing.invocation.ExternalInvocationGuard;
import com.skillforge.core.billing.invocation.ExternalInvocationLedger;
import com.skillforge.core.capability.connector.CalculateExpressionToolHandler;
import com.skillforge.core.capability.connector.ConvertDatetimeToolHandler;
import com.skillforge.core.capability.connector.GetCurrentTimeToolHandler;
import com.skillforge.core.capability.connector.HttpRequestToolHandler;
import com.skillforge.core.capability.connector.JsonQueryToolHandler;
import com.skillforge.core.capability.connector.JsonSchemaValidateToolHandler;
import com.skillforge.core.capability.connector.PlatformToolHandler;
import com.skillforge.core.capability.connector.TemplateRenderToolHandler;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.core.foundation.application.ServicePrincipalService;
import com.skillforge.core.foundation.runtimeconfig.RunSettingsMapper;
import com.skillforge.core.foundation.runtimeconfig.RunSettingsService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.metering.CostRecorder;
import com.skillforge.core.shared.config.SkillForgeProperties;
import com.skillforge.core.foundation.persistence.ServicePrincipalMapper;
import com.skillforge.core.shared.sandbox.SandboxGuard;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.runtime.adapter.jdbc.budget.JdbcExecutionBudgets;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore;
import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;
import com.skillforge.runtime.adapter.jdbc.memory.JdbcMemoryPort;
import com.skillforge.runtime.adapter.jdbc.memory.MemorySearchEmbedding;
import com.skillforge.runtime.api.MemoryRetrievalPolicy;
import com.skillforge.runtime.adapter.jdbc.model.JdbcModelBindings;
import com.skillforge.runtime.adapter.model.OpenAiModelPort;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.core.CoreRuntime;
import com.skillforge.runtime.memory.MemoryRuntime;
import com.skillforge.runtime.session.spi.SessionTokenCounter;
import com.skillforge.runtime.starter.*;
import com.skillforge.core.foundation.integration.runtime.AgentRuntimeTrustedAccess;
import com.skillforge.core.foundation.integration.runtime.AgentRuntimeFoundationConfiguration;
import com.skillforge.core.foundation.integration.runtime.AgentRuntimePlatformProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** 平台系统智能体宿主；执行只装配同一typed Store/Program/Coordinator和共享五十槽Worker。 */
@AutoConfiguration
@Import(AgentRuntimeFoundationConfiguration.class)
@org.mybatis.spring.annotation.MapperScan(basePackageClasses=RunSettingsMapper.class, annotationClass=org.apache.ibatis.annotations.Mapper.class)
@AutoConfigureBefore(AgentRuntimeAutoConfiguration.class)
public class SystemAgentRuntimeAutoConfiguration {
    @Bean @ConditionalOnMissingBean
    ServicePrincipalService systemAgentServicePrincipalService(ServicePrincipalMapper principals, CryptoService crypto) { return new ServicePrincipalService(principals, crypto); }

    @Bean
    @ConditionalOnMissingBean
    RunSettingsService systemAgentRunSettingsService(RunSettingsMapper mapper,
            OperationalSettingsService settings, ObjectMapper json) {
        return new RunSettingsService(mapper, settings, json);
    }

    @Bean
    @ConditionalOnMissingBean
    SandboxGuard systemAgentSandboxGuard(SkillForgeProperties properties) {
        return new SandboxGuard(properties);
    }

    @Bean @ConditionalOnMissingBean
    GetCurrentTimeToolHandler systemAgentCurrentTimeTool() { return new GetCurrentTimeToolHandler(); }
    @Bean @ConditionalOnMissingBean
    CalculateExpressionToolHandler systemAgentCalculateTool() { return new CalculateExpressionToolHandler(); }
    @Bean @ConditionalOnMissingBean
    ConvertDatetimeToolHandler systemAgentConvertDatetimeTool() { return new ConvertDatetimeToolHandler(); }
    @Bean @ConditionalOnMissingBean
    JsonQueryToolHandler systemAgentJsonQueryTool(ObjectMapper json) { return new JsonQueryToolHandler(json); }
    @Bean @ConditionalOnMissingBean
    JsonSchemaValidateToolHandler systemAgentJsonSchemaTool(ObjectMapper json) {
        return new JsonSchemaValidateToolHandler(json);
    }
    @Bean @ConditionalOnMissingBean
    TemplateRenderToolHandler systemAgentTemplateTool() { return new TemplateRenderToolHandler(); }
    @Bean @ConditionalOnMissingBean
    HttpRequestToolHandler systemAgentHttpTool(SandboxGuard sandbox, ObjectMapper json,
            RunSettingsService settings) {
        return new HttpRequestToolHandler(sandbox, json, settings);
    }

    @Bean SystemAgentRuntimeStore systemAgentRuntimeStore(DataSource source) {
        return new SystemAgentRuntimeStore(source);
    }

    @Bean SystemAgentCapabilitySource systemAgentCapabilitySource(ObjectMapper json,
            ObjectProvider<PlatformToolHandler> handlers, com.skillforge.core.capability.connector.PlatformToolCatalog catalog) {
        return new SystemAgentCapabilitySource(json, handlers, catalog);
    }

    @Bean SystemAgentPlatformToolProvider systemAgentPlatformToolProvider(ObjectProvider<PlatformToolHandler> handlers,ExternalInvocationGuard external){return new SystemAgentPlatformToolProvider(handlers,external);}
    @Bean AgentRuntimeDecrypt systemAgentRuntimeDecrypt(CryptoService crypto){return ciphertext->crypto.decrypt(ciphertext).getBytes(StandardCharsets.UTF_8);}
    @Bean AgentRuntimeTenantBinder systemAgentRuntimeTenantBinder(){return (tenant,work)->{String previous=TenantContext.getTenantId();TenantContext.setTenantId(tenant.value());try{work.run();}finally{if(previous==null)TenantContext.clear();else TenantContext.setTenantId(previous);}};}
    @Bean SystemAgentModelProvisioner systemAgentModelProvisioner(AgentRuntimeModelConfiguration models,com.skillforge.core.model.PlatformModelCatalogService catalog,SystemAgentRuntimeStore store,SystemAgentCapabilitySource sources){return new SystemAgentModelProvisioner(models,catalog,store,sources);}
    @Bean SystemAgentModelInvocationAudit systemAgentModelInvocationAudit(ExternalInvocationLedger ledger,CostRecorder costs,org.springframework.transaction.PlatformTransactionManager transactions){return new SystemAgentModelInvocationAudit(ledger,costs,transactions);}
    /** 私有Store只承接本Host写权；共享Worker的发现/路由读取同一typed表。 */
    @Bean(destroyMethod="close") SystemAgentExecutions systemAgentExecutions(DataSource dataSource,ExecutionBudgetPort budgets,JdbcIdentityPolicyPort identities,AgentRuntimeModelConfiguration profiles,JdbcModelBindings bindings,SessionTokenCounter tokens,SystemAgentCapabilitySource sources,SystemAgentRuntimeStore business,OpenAiModelPort models,SystemAgentModelInvocationAudit audit,SystemAgentPlatformToolProvider tools,AgentRuntimeProperties properties,ObjectMapper json,CoreRuntime core,ObjectProvider<ExecutionHostWorker> workers,ExecutionHostScheduler scheduler,Clock clock){
        if(!(budgets instanceof JdbcExecutionBudgets jdbc))throw new IllegalStateException("system_agent_transactional_budget_required");
        var holder=new AtomicReference<SystemAgentExecutions>();
        var store=new JdbcExecutionStore(dataSource,identities,jdbc,(session,run,actor,prepared)->holder.get().allowed(session,run,actor,prepared),configuration->{},(session,run)->holder.get().confirm(session,run),(session,run,actor,operation,input)->holder.get().allowedControl(session,run,actor,operation,input),session->{});
        var host=new SystemAgentExecutions(store,jdbc,identities,profiles,bindings,tokens,sources,business,models,audit,tools,properties,json,core,workers,scheduler,clock);holder.set(host);return host;
    }
    @Bean @ConditionalOnMissingBean(ExecutionTenantDiscovery.class) ExecutionTenantDiscovery systemAgentTenantDiscovery(){return (after,limit)->after.compareTo(SystemAgentRuntimeContracts.TENANT_ID)<0&&limit>0?List.of(SystemAgentRuntimeContracts.TENANT_ID):List.of();}
    @Bean com.skillforge.core.agent.systemagent.SystemAgentRunner systemAgentRuntimeRunner(SystemAgentExecutions executions,AgentRuntimeTrustedAccess access,JdbcIdentityPolicyPort identities,AgentRuntimeProperties properties,AgentRuntimePlatformProperties platformProperties,ServicePrincipalService principals,SystemAgentRuntimeStore store,SystemAgentCapabilitySource sources,SystemAgentModelProvisioner models,ObjectMapper json,Clock clock){return new SystemAgentRuntimeRunner(executions,access,identities,properties,platformProperties,principals,store,sources,models,json,clock);}
    /** 独立系统宿主仅查询既有正文投影；写入仍由原 MemoryPolicy 拒绝，不补造机构正文或模型配置。 */
    @Bean
    @ConditionalOnMissingBean(AgentRuntimeMemorySearchBindings.class)
    AgentRuntimeMemorySearchBindings systemAgentMemorySearchBindings() {
        return new AgentRuntimeMemorySearchBindings(
                fact -> { throw new IllegalStateException("system_agent_memory_write_denied"); },
                tenant -> MemoryRetrievalPolicy.lexicalV1(),
                request -> new MemorySearchEmbedding.Outcome.Rejected("system_agent_memory_embedding_unconfigured"));
    }

    @Bean SystemAgentMemoryPolicies systemAgentMemoryPolicies(){return new SystemAgentMemoryPolicies();}
    /** 系统宿主只读长期记忆，连同维护迁移一起关闭写权。 */
    @Bean com.skillforge.runtime.adapter.jdbc.memory.MemoryTransitionGuard systemAgentMemoryTransitionGuard(){return (session,command,current)->false;}
    @Bean MemoryRuntime.CurrentFactSource systemAgentMemoryCurrentFacts(JdbcMemoryPort memory){return memory::findCurrent;}
}
