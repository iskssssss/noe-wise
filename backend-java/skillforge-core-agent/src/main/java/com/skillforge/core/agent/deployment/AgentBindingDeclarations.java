package com.skillforge.core.agent.deployment;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.skillforge.core.agent.deployment.AgentDeploymentContracts.Issue;
import com.skillforge.core.agent.deployment.AgentDeploymentContracts.LocalBindings;
import com.skillforge.core.agent.model.AgentTemplateDefinition;
import com.skillforge.core.capability.CapabilityType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** 部署与发布预览共用的纯声明匹配；不解析资源、读取私有值或准备执行。 */
public final class AgentBindingDeclarations {
    public static final Pattern PARAMETER = Pattern.compile("\\{\\{parameters\\.([a-z][a-z0-9_]{0,63})\\}\\}");
    private AgentBindingDeclarations() { }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ResourceSelection(String slotKey, CapabilityType capabilityType) {
        public ResourceSelection { Objects.requireNonNull(slotKey); }
    }

    /** 仅保留结构键和类型；缺失结构是技术错误，不能当作空绑定。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record BindingShape(List<ResourceSelection> resources, List<String> delegations, Set<String> parameterKeys) {
        public BindingShape {
            resources = List.copyOf(resources);
            delegations = List.copyOf(delegations);
            parameterKeys = Set.copyOf(parameterKeys);
        }
        public static BindingShape from(LocalBindings local) {
            return new BindingShape(local.resourceBindings().stream().map(binding -> new ResourceSelection(
                    binding.slotKey(), binding.mount() == null ? null : binding.mount().capabilityType())).toList(),
                    local.delegationBindings().stream().map(binding -> binding.slotKey()).toList(), local.parameters().keySet());
        }
    }

    public static Set<String> requiredParameters(AgentTemplateDefinition definition) {
        Set<String> keys = new LinkedHashSet<>();
        var matcher = PARAMETER.matcher(definition.systemPrompt() == null ? "" : definition.systemPrompt());
        while (matcher.find()) keys.add(matcher.group(1));
        return java.util.Collections.unmodifiableSet(keys);
    }

    public static boolean declarationsMatch(AgentTemplateDefinition definition, BindingShape local) {
        Map<String, CapabilityType> declared = new HashMap<>();
        definition.resourceSlots().forEach(slot -> declared.put(slot.key(), slot.capabilityType()));
        Set<String> seen = new HashSet<>();
        for (var resource : local.resources()) {
            if (!seen.add(resource.slotKey()) || resource.capabilityType() == null
                    || declared.get(resource.slotKey()) != resource.capabilityType()) return false;
        }
        Set<String> roles = new HashSet<>();
        definition.delegationSlots().forEach(slot -> roles.add(slot.key()));
        seen.clear();
        for (var key : local.delegations()) if (!seen.add(key) || !roles.contains(key)) return false;
        return local.parameterKeys().size() <= 64 && local.parameterKeys().stream().allMatch(key -> key.matches("[a-z][a-z0-9_]{0,63}"));
    }

    public static List<Issue> missingRequirements(AgentTemplateDefinition definition, BindingShape local) {
        List<Issue> issues = new ArrayList<>();
        Set<String> resources = new HashSet<>();
        local.resources().forEach(resource -> resources.add(resource.slotKey()));
        for (var slot : definition.resourceSlots()) if (!resources.contains(slot.key()))
            issues.add(new Issue("resource_bindings." + slot.key(), "binding_required", "请绑定资源：" + slot.label()));
        Set<String> targets = Set.copyOf(local.delegations());
        for (var slot : definition.delegationSlots()) if (!targets.contains(slot.key()))
            issues.add(new Issue("delegation_bindings." + slot.key(), "binding_required", "请绑定协作角色：" + slot.label()));
        for (var key : requiredParameters(definition)) if (!local.parameterKeys().contains(key))
            issues.add(new Issue("parameters." + key, "parameter_required", "请配置提示词参数：" + key));
        return List.copyOf(issues);
    }

    public static List<Issue> assess(AgentTemplateDefinition definition, BindingShape local) {
        List<Issue> issues = new ArrayList<>();
        if (!declarationsMatch(definition, local)) issues.add(new Issue("bindings", "binding_definition_changed",
                "本地绑定与当前公共定义不匹配，请重新保存"));
        issues.addAll(missingRequirements(definition, local));
        return List.copyOf(issues);
    }
}
