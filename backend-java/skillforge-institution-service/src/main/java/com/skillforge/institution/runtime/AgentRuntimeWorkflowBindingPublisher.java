package com.skillforge.institution.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.CapabilityMountResolver;
import com.skillforge.core.capability.ToolVersion;
import com.skillforge.core.capability.KnowledgeSourceDescriptor;
import com.skillforge.core.workflow.WorkflowInstallationBindings;
import com.skillforge.core.workflow.WorkflowInstallationValidator;
import com.skillforge.core.workflow.runtime.WorkflowRuntimeBindings;
import com.skillforge.core.workflow.integration.runtime.workflow.JdbcWorkflowBindings;
import com.skillforge.core.capability.integration.runtime.tool.JdbcMcpBindings;
import com.skillforge.core.knowledge.integration.runtime.knowledge.JdbcKnowledgeBindings;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.ToolPortal.*;
import com.skillforge.runtime.api.port.KnowledgePortal.*;
import com.skillforge.runtime.api.port.WorkflowPortal.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** 在 Run 准入时冻结安装的全部精确目标及子图闭包，不留到节点执行时搜索资源。 */
@Service
public final class AgentRuntimeWorkflowBindingPublisher {
    private final JdbcWorkflowBindings workflows;
    private final JdbcMcpBindings tools;
    private final JdbcKnowledgeBindings knowledge;
    private final CapabilityMountResolver.Registry registry;
    private final WorkflowInstallationValidator validator;
    private final ObjectMapper json;
    private final WorkflowTestRuntimePolicy tests;

    public AgentRuntimeWorkflowBindingPublisher(JdbcWorkflowBindings workflows,JdbcMcpBindings tools,
            JdbcKnowledgeBindings knowledge,CapabilityMountResolver.Registry registry,
            WorkflowInstallationValidator validator,ObjectMapper json,WorkflowTestRuntimePolicy tests) {
        this.workflows=workflows;this.tools=tools;this.knowledge=knowledge;
        this.registry=registry;this.validator=validator;this.json=json;this.tests=tests;
    }

    public JdbcWorkflowBindings.Binding publish(TenantId tenant,FrozenWorkflowTarget target) {
        return publish(tenant,target,new HashSet<>());
    }

    private JdbcWorkflowBindings.Binding publish(TenantId tenant,FrozenWorkflowTarget target,Set<String> path) {
        if(!path.add(target.instanceId()))throw new IllegalArgumentException("workflow_subgraph_cycle");
        try {return workflows.find(tenant,target).orElseGet(()->workflows.publish(tenant,target,
                binding->resolve(tenant,binding,path),WorkflowResolvedBindingsJson::canonical));}
        finally {path.remove(target.instanceId());}
    }

    private String resolve(TenantId tenant,JdbcWorkflowBindings.Binding parent,Set<String> path) {
        try {
            long tenantId=Long.parseLong(tenant.value());
            var configured=json.readValue(parent.bindingsJson(),WorkflowInstallationBindings.class);
            var selected=validator.currentBindings(tenantId,configured);
            tests.installation(tenantId,Long.parseLong(parent.target().instanceId()),configured,selected);
            @SuppressWarnings("unchecked") Map<String,Object> definition=json.readValue(parent.definitionJson(),Map.class);
            validator.validateForExecution(tenantId,Long.parseLong(parent.target().instanceId()),definition,selected);
            var toolSlots=new ArrayList<WorkflowRuntimeBindings.ToolSlot>();
            var knowledgeSlots=new ArrayList<WorkflowRuntimeBindings.KnowledgeSlot>();
            var children=new ArrayList<WorkflowRuntimeBindings.WorkflowSlot>();
            var agents=new ArrayList<WorkflowRuntimeBindings.AgentSlot>();
            for(var slot:selected.capabilities()) {
                var descriptor=registry.find(tenantId,slot.capability()).orElseThrow();
                var ref=new CapabilityRef(CapabilityType.valueOf(slot.capability().type().name()),
                        slot.capability().capabilityId(),slot.capability().versionId());
                String identity="workflow:"+parent.target().instanceId()+":"+parent.target().installationRevision()+":"+slot.slot();
                if(descriptor instanceof ToolVersion tool) {
                    var deployment=registry.findToolDeployment(tenantId,slot.toolDeploymentId()).orElseThrow();
                    if(deployment.revision()!=slot.toolDeploymentRevision()||!deployment.active()
                            ||!deployment.toolVersion().equals(slot.capability()))throw new IllegalArgumentException("workflow_tool_deployment_drift");
                    var policy=tool.policy();var source=tool.definition();
                    var target=new FrozenToolTarget(new ToolHandle("tool:"+identity),ref,deployment.deploymentId(),deployment.revision(),
                            schema(source.inputSchema()),schema(source.outputSchema().orElse(Map.of())),ToolEffect.valueOf(policy.effect().name()),
                            policy.idempotent(),Duration.ofMillis(policy.timeoutMs()),tool.metadata().invocationPolicy().modelInvocable(),
                            ToolConfirmationPolicy.valueOf(policy.confirmation().name()),policy.sensitiveFields(),policy.providerRequirements(),source.outputSchema().isPresent(),policy.callIdentityPolicy());
                    var binding=new ToolBinding(ToolBinding.ProviderType.valueOf(deployment.providerType().name()),deployment.providerRef(),source.name());
                    if(binding.providerType()==ToolBinding.ProviderType.MCP)
                        tools.publish(tenant,target,new JdbcMcpBindings.Profile(source.name(),JdbcMcpBindings.PROTOCOL_VERSION));
                    toolSlots.add(new WorkflowRuntimeBindings.ToolSlot(slot.slot(),target,binding));
                } else if(descriptor instanceof KnowledgeSourceDescriptor source) {
                    var target=new FrozenKnowledgeTarget(new KnowledgeHandle("knowledge:"+identity),ref,source.sourceId(),slot.knowledgeRevision(),
                            source.sourceVersions().stream().map(version->new KnowledgeSourceVersion(source.sourceId(),version)).collect(Collectors.toSet()),
                            source.allowedOperators().stream().map(KnowledgeOperatorType::valueOf).collect(Collectors.toSet()),parent.target().timeout());
                    knowledge.publish(tenant,target);
                    knowledgeSlots.add(new WorkflowRuntimeBindings.KnowledgeSlot(slot.slot(),target));
                } else throw new IllegalArgumentException("workflow_capability_type_rejected");
            }
            for(var child:selected.workflows()) {
                var resolved=validator.target(tenantId,child);
                var target=new FrozenWorkflowTarget(new WorkflowHandle(parent.target().handle().value()+":child:"+child.instanceId()),
                        resolved.instanceId(),resolved.instanceRevision(),resolved.capability(),resolved.timeout(),resolved.installationRevision());
                publish(tenant,target,path);
                children.add(new WorkflowRuntimeBindings.WorkflowSlot(child.workflow(),target));
            }
            selected.agents().forEach(slot->agents.add(new WorkflowRuntimeBindings.AgentSlot(slot.slot(),slot.agentId())));
            return WorkflowResolvedBindingsJson.canonical(
                    json.writeValueAsString(new WorkflowRuntimeBindings(toolSlots,knowledgeSlots,children,agents)));
        } catch(RuntimeException rejected) {throw rejected;}
        catch(Exception rejected) {throw new IllegalArgumentException("workflow_binding_freeze_rejected",rejected);}
    }
    private TypedPayload schema(Map<String,Object> schema) throws com.fasterxml.jackson.core.JsonProcessingException {
        return new TypedPayload("json-schema",json.writeValueAsString(schema));
    }
}
