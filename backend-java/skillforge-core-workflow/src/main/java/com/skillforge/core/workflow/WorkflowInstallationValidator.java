package com.skillforge.core.workflow;

import com.skillforge.core.capability.CapabilityCatalogScope;
import com.skillforge.core.capability.CapabilityCatalogService;
import com.skillforge.core.capability.CapabilityMountResolver;
import com.skillforge.core.capability.CapabilityRef;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.capability.KnowledgeSourceDescriptor;
import com.skillforge.core.capability.ToolVersion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.tool.ToolDeployment;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.workflow.execution.WorkflowDefinitionContract;
import com.skillforge.core.workflow.mapper.WorkflowInstallationMapper;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 管理绑定保存稳定目标；新执行解析当前发布并一次性校验精确目标。 */
@Component
public class WorkflowInstallationValidator {
    private final CapabilityCatalogService catalog;
    private final CapabilityMountResolver.Registry registry;
    private final WorkflowInstallationTargetAccess targets;
    private final WorkflowInstallationMapper installations;
    private final ObjectMapper json;

    public WorkflowInstallationValidator(CapabilityCatalogService catalog, CapabilityMountResolver.Registry registry,
            WorkflowInstallationTargetAccess targets, WorkflowInstallationMapper installations, ObjectMapper json) {
        this.catalog = catalog; this.registry = registry; this.targets = targets; this.installations = installations; this.json = json;
    }

    /** 仅在新执行准入使用；冻结之后恢复和节点执行直接使用 RuntimeBindings。 */
    public WorkflowInstallationBindings currentBindings(long tenant, WorkflowInstallationBindings configured) {
        var capabilities = new ArrayList<WorkflowInstallationBindings.CapabilityBinding>();
        for (var binding : configured.capabilities()) {
            long definitionId;
            try { definitionId = Long.parseLong(binding.capability().capabilityId()); }
            catch (NumberFormatException malformed) { throw invalid("能力绑定必须指定稳定资源身份"); }
            var published = catalog.getCurrentPublishedVersion(CapabilityCatalogScope.tenant(tenant), definitionId);
            if (published.capabilityType() != binding.capability().type()) throw invalid("能力稳定身份类型不匹配");
            var ref = new CapabilityRef(binding.capability().type(), Long.toString(definitionId), published.id().toString());
            var descriptor = registry.find(tenant, ref).orElseThrow(() -> invalid("当前发布能力不可用"));
            if (descriptor instanceof ToolVersion) {
                var deployment = registry.findToolDeployment(tenant, binding.toolDeploymentId())
                        .orElseThrow(() -> invalid("Deployment 不存在"));
                capabilities.add(new WorkflowInstallationBindings.CapabilityBinding(binding.slot(), ref,
                        deployment.deploymentId(), deployment.revision(), null));
            } else if (descriptor instanceof KnowledgeSourceDescriptor source) {
                capabilities.add(new WorkflowInstallationBindings.CapabilityBinding(binding.slot(), ref,
                        null, null, targets.currentKnowledgeRevision(tenant, source)));
            } else throw invalid("Workflow 槽不支持该能力类型");
        }
        var children = configured.workflows().stream().map(child -> {
            var published = installations.currentTarget(tenant, child.instanceId());
            if (published == null || !published.workflowId().equals(child.workflow())) throw invalid("子图当前发布目标不可用");
            return new WorkflowInstallationBindings.WorkflowBinding(child.workflow(), child.instanceId(),
                    published.definitionId(), published.installationRevision());
        }).toList();
        return new WorkflowInstallationBindings(capabilities, children, configured.agents());
    }

    /** 生产安装及 Run 冻结入口，未完成执行链的定义不能成为可调用能力。 */
    public void validateForExecution(long tenant, long instanceId, Map<String, Object> definition, WorkflowInstallationBindings bindings) {
        try { WorkflowDefinitionContract.requireExecutable(definition); }
        catch (IllegalArgumentException unavailable) { throw invalid(unavailable.getMessage()); }
        validate(tenant, instanceId, definition, bindings);
    }

    public void validate(long tenant, long instanceId, Map<String, Object> definition, WorkflowInstallationBindings bindings) {
        WorkflowDefinitionContract.validate(definition);
        var requirements = WorkflowDefinitionContract.requirements(definition);
        var selected = unique(bindings.capabilities(), WorkflowInstallationBindings.CapabilityBinding::slot);
        if (!requirements.keySet().containsAll(selected.keySet())) throw invalid("安装包含未声明能力槽");
        for (var requirement : requirements.values()) {
            var binding = selected.get(requirement.slot());
            if (binding == null) {
                if (requirement.required()) throw invalid("缺少必需能力槽: " + requirement.slot());
                continue;
            }
            var ref = binding.capability();
            if (!ref.type().name().equalsIgnoreCase(requirement.type()))
                throw invalid("能力槽类型不匹配: " + requirement.slot());
            long definitionId;
            long versionId;
            try { definitionId = Long.parseLong(ref.capabilityId()); versionId = Long.parseLong(ref.versionId()); }
            catch (NumberFormatException malformed) { throw invalid("安装绑定必须指定精确能力版本身份"); }
            var selectedDefinition = catalog.getDefinition(CapabilityCatalogScope.tenant(tenant), definitionId);
            if (!selectedDefinition.capabilityKey().equals(requirement.capabilityId())
                    || selectedDefinition.capabilityType() != ref.type())
                throw invalid("能力稳定键与声明槽不匹配: " + requirement.slot());
            var versions = catalog.publishedVersions(CapabilityCatalogScope.tenant(tenant), List.of(versionId));
            if (versions.size() != 1 || !versions.getFirst().definitionId().toString().equals(ref.capabilityId())
                    || !WorkflowDefinitionContract.matchesVersion(requirement.versionConstraint(), versions.getFirst().versionKey()))
                throw invalid("能力版本不满足发布状态或版本约束: " + requirement.slot());
            var descriptor = registry.find(tenant, ref).orElseThrow(() -> invalid("能力不可用"));
            if (descriptor instanceof ToolVersion tool) {
                if (binding.toolDeploymentId() == null || binding.toolDeploymentRevision() == null || binding.knowledgeRevision() != null)
                    throw invalid("Tool 槽必须且只能指定精确 Deployment");
                ToolDeployment deployment = registry.findToolDeployment(tenant, binding.toolDeploymentId()).orElseThrow(() -> invalid("Deployment 不存在"));
                if (!deployment.active() || !deployment.tenantId().equals(tenant) || !deployment.toolVersion().equals(ref)
                        || deployment.revision() != binding.toolDeploymentRevision()
                        || !Set.of(ToolDeployment.ProviderType.MCP, ToolDeployment.ProviderType.PLATFORM).contains(deployment.providerType())
                        || !tool.policy().providerRequirements().equals(deployment.providerType() == ToolDeployment.ProviderType.PLATFORM
                            ? Set.of("platform_handler:" + deployment.providerRef()) : Set.of()))
                    throw invalid("Deployment 不满足租户、版本或 Provider 要求");
                targets.requireProvider(tenant, deployment);
            } else if (descriptor instanceof KnowledgeSourceDescriptor source) {
                if (binding.knowledgeRevision() == null || binding.knowledgeRevision() <= 0
                        || binding.toolDeploymentId() != null || binding.toolDeploymentRevision() != null)
                    throw invalid("Knowledge 槽必须且只能指定精确知识修订");
                targets.requireKnowledge(tenant, source, binding.knowledgeRevision());
            } else throw invalid("Workflow 槽不支持该能力类型");
        }
        var nodes = WorkflowDefinitionContract.list(definition.get("nodes"), "nodes").stream()
                .map(value -> WorkflowDefinitionContract.object(value, "node")).toList();
        var childKeys = nodes.stream().filter(n -> "subgraph".equals(n.get("type"))).map(n -> WorkflowDefinitionContract.text(n, "workflow")).collect(Collectors.toSet());
        var children = unique(bindings.workflows(), WorkflowInstallationBindings.WorkflowBinding::workflow);
        if (!children.keySet().equals(childKeys)) throw invalid("子图绑定必须精确覆盖定义中的子图");
        for (var child : children.values()) requireChild(tenant, instanceId, child, new HashSet<>());
        var agentSlots = nodes.stream().filter(n -> "agentic".equals(n.get("type"))).map(n -> WorkflowDefinitionContract.text(n, "agent_slot")).collect(Collectors.toSet());
        var agents = unique(bindings.agents(), WorkflowInstallationBindings.AgentBinding::slot);
        if (!agents.keySet().equals(agentSlots)) throw invalid("Agent 槽绑定必须精确覆盖定义中的 agentic 节点");
        agents.values().forEach(agent -> targets.requireAgent(tenant, agent.agentId()));
    }

    /** 子图闭包沿不可变修订读取；禁止安装自身或任何间接回环。 */
    public com.skillforge.runtime.api.port.WorkflowPortal.FrozenWorkflowTarget target(long tenant,
            WorkflowInstallationBindings.WorkflowBinding child) {
        var resolved=installations.target(tenant,child.instanceId(),child.installationRevision(),child.definitionVersionId());
        if(resolved==null||resolved.definitionId()!=child.definitionVersionId())throw invalid("子图冻结目标不可用");
        return new com.skillforge.runtime.api.port.WorkflowPortal.FrozenWorkflowTarget(
                new com.skillforge.runtime.api.port.WorkflowPortal.WorkflowHandle("workflow:child:"+child.instanceId()+":"+child.installationRevision()),
                Long.toString(child.instanceId()),child.definitionVersionId(),new com.skillforge.runtime.api.CapabilityRef(
                        com.skillforge.runtime.api.CapabilityType.WORKFLOW,Long.toString(resolved.capabilityId()),Long.toString(resolved.capabilityVersionId())),
                java.time.Duration.ofMillis(resolved.timeoutMs()),child.installationRevision());
    }

    /** 子图闭包沿不可变修订读取；禁止安装自身或任何间接回环。 */
    private void requireChild(long tenant, long root, WorkflowInstallationBindings.WorkflowBinding child, Set<Long> path) {
        if (root == child.instanceId() || !path.add(child.instanceId())) throw invalid("子图依赖存在循环");
        var stored = installations.find(tenant, child.instanceId(), child.installationRevision());
        if (stored == null || installations.target(tenant, child.instanceId(), child.installationRevision(),
                child.definitionVersionId()) == null) throw invalid("子图安装修订不存在或定义不匹配");
        if (!installations.publishedTarget(tenant, child.instanceId(), child.installationRevision(), child.workflow()))
            throw invalid("子图身份不匹配或已停用");
        var nested = json.convertValue(stored.getBindings(), WorkflowInstallationBindings.class);
        nested.workflows().forEach(value -> requireChild(tenant, root, value, path));
        path.remove(child.instanceId());
    }

    private static <T> Map<String, T> unique(List<T> values, Function<T, String> key) {
        var result = new java.util.LinkedHashMap<String, T>();
        for (T value : values) if (result.putIfAbsent(key.apply(value), value) != null) throw invalid("安装槽重复: " + key.apply(value));
        return result;
    }
    private static ApiException invalid(String reason) { return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, reason); }
}
