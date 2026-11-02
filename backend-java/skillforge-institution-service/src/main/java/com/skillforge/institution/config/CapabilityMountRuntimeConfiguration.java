package com.skillforge.institution.config;

import com.skillforge.core.workflow.WorkflowReferenceService;
import com.skillforge.core.knowledge.KnowledgeReferenceService;
import com.skillforge.core.capability.CapabilityDescriptorCodec;
import com.skillforge.core.capability.CapabilityMountResolver;
import com.skillforge.core.capability.PersistentCapabilityRegistry;
import com.skillforge.core.capability.tool.DefaultToolCatalog;
import com.skillforge.core.capability.tool.DefaultToolResolver;
import com.skillforge.core.capability.tool.DefaultToolSchemaValidator;
import com.skillforge.core.capability.tool.PersistentToolDeploymentRegistry;
import com.skillforge.core.capability.tool.PersistentToolRegistry;
import com.skillforge.core.capability.tool.ToolDeploymentRegistry;
import com.skillforge.core.capability.tool.ToolCatalog;
import com.skillforge.core.capability.tool.ToolRegistry;
import com.skillforge.core.capability.tool.ToolResolver;
import com.skillforge.core.capability.tool.ToolSchemaValidator;
import com.skillforge.core.billing.invocation.ExternalInvocationGuard;
import com.skillforge.core.capability.connector.JsonSchemaValidateToolHandler;
import com.skillforge.core.capability.connector.PlatformToolHandler;
import com.skillforge.core.capability.connector.PlatformToolCatalog;
import com.skillforge.core.capability.persistence.CapabilityDefinitionMapper;
import com.skillforge.core.capability.persistence.CapabilityVersionMapper;
import com.skillforge.core.capability.persistence.ToolDeploymentRecordMapper;
import com.skillforge.core.shared.sandbox.SandboxGuard;
import com.skillforge.institution.service.CapabilityMountReferenceResolvers;
import com.skillforge.institution.runtime.AgentRuntimePlatformToolProvider;
import com.skillforge.runtime.adapter.mcp.McpDiscoveryClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 为 input 级 CapabilityMount 快照装配生产 Registry、守卫与 ToolPortal。 */
@Configuration
public class CapabilityMountRuntimeConfiguration {

    @Bean
    public McpDiscoveryClient mcpDiscoveryClient(
            HttpClient agentRuntimeHttpClient, SandboxGuard sandboxGuard) {
        return new McpDiscoveryClient(agentRuntimeHttpClient,
                endpoint -> URI.create(sandboxGuard.assertSafe(endpoint.toString())));
    }

    @Bean
    public AgentRuntimePlatformToolProvider agentRuntimePlatformToolProvider(
            ObjectProvider<PlatformToolHandler> handlers,ExternalInvocationGuard externalInvocationGuard,
            PlatformToolCatalog platformTools){
        return new AgentRuntimePlatformToolProvider(handlers,externalInvocationGuard,platformTools);
    }

    @Bean
    public Clock capabilityMountRuntimeClock() {
        return Clock.systemUTC();
    }

    @Bean
    public CapabilityMountReferenceResolvers capabilityMountReferenceResolvers(
            WorkflowReferenceService workflows, KnowledgeReferenceService knowledge,
            CapabilityVersionMapper capabilityVersionMapper) {
        return new CapabilityMountReferenceResolvers(capabilityVersionMapper, workflows, knowledge);
    }

    @Bean
    public PersistentCapabilityRegistry persistentCapabilityRegistry(
            CapabilityDefinitionMapper capabilityDefinitionMapper,
            CapabilityVersionMapper capabilityVersionMapper,
            ToolDeploymentRecordMapper toolDeploymentRecordMapper,
            CapabilityDescriptorCodec capabilityDescriptorCodec,
            CapabilityMountReferenceResolvers referenceResolvers) {
        return new PersistentCapabilityRegistry(
                capabilityDefinitionMapper, capabilityVersionMapper, toolDeploymentRecordMapper,
                capabilityDescriptorCodec, referenceResolvers::workflowInstanceMatches,
                referenceResolvers::resolvePublishedMount);
    }

    @Bean
    public CapabilityMountResolver capabilityMountResolver(
            PersistentCapabilityRegistry persistentCapabilityRegistry,
            Clock capabilityMountRuntimeClock) {
        return new CapabilityMountResolver(
                persistentCapabilityRegistry, capabilityMountRuntimeClock);
    }

    @Bean
    public ToolRegistry toolRegistry(
            PersistentCapabilityRegistry persistentCapabilityRegistry) {
        return new PersistentToolRegistry(persistentCapabilityRegistry);
    }

    @Bean
    public ToolDeploymentRegistry toolDeploymentRegistry(
            PersistentCapabilityRegistry persistentCapabilityRegistry) {
        return new PersistentToolDeploymentRegistry(persistentCapabilityRegistry);
    }

    @Bean
    public ToolResolver toolResolver(
            ToolRegistry toolRegistry,
            ToolDeploymentRegistry toolDeploymentRegistry) {
        return new DefaultToolResolver(toolRegistry, toolDeploymentRegistry);
    }

    @Bean
    public ToolCatalog toolCatalog(ToolResolver toolResolver) {
        return new DefaultToolCatalog(toolResolver);
    }

    @Bean
    public ToolSchemaValidator toolSchemaValidator(
            JsonSchemaValidateToolHandler jsonSchemaValidateToolHandler) {
        return new DefaultToolSchemaValidator(jsonSchemaValidateToolHandler);
    }

}
