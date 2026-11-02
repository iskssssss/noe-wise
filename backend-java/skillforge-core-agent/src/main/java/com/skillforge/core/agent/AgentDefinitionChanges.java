package com.skillforge.core.agent;
import com.skillforge.core.agent.dto.response.AgentDefinitionImpactResponse.Changes;
import com.skillforge.core.agent.model.AgentTemplateDefinition;
import com.skillforge.core.agent.deployment.AgentBindingDeclarations;
import java.util.Map;
import java.util.stream.Collectors;
/** 纯声明差量计算，平台和机构预览复用；不读取目标实例或执行权限。 */
public final class AgentDefinitionChanges {
    private AgentDefinitionChanges() { }
    /** 比较当前不可变发布和精确工作草稿，不准备Run或复制私有绑定。 */
    public static Changes between(AgentTemplateDefinition current,AgentTemplateDefinition draft) {
        var baseline=current==null?AgentTemplateDefinition.empty():current;
        Map<String,AgentTemplateDefinition.ResourceSlot> previous=baseline.resourceSlots().stream()
                .collect(Collectors.toMap(slot->slot.key(),slot->slot));
        var currentKeys=draft.resourceSlots().stream().map(slot->slot.key()).collect(Collectors.toSet());
        var previousRoles=baseline.delegationSlots().stream().map(slot->slot.key()).collect(Collectors.toSet());
        var nextRoles=draft.delegationSlots().stream().map(slot->slot.key()).collect(Collectors.toSet());
        var beforeParameters=AgentBindingDeclarations.requiredParameters(baseline);
        var nextParameters=AgentBindingDeclarations.requiredParameters(draft);
        return new Changes(draft.resourceSlots().stream().filter(slot->!previous.containsKey(slot.key())).toList(),
                draft.resourceSlots().stream().filter(slot->previous.containsKey(slot.key())
                        && previous.get(slot.key()).capabilityType()!=slot.capabilityType()).toList(),
                baseline.resourceSlots().stream().map(slot->slot.key()).filter(key->!currentKeys.contains(key)).toList(),
                draft.delegationSlots().stream().filter(slot->!previousRoles.contains(slot.key())).toList(),
                baseline.delegationSlots().stream().map(slot->slot.key()).filter(key->!nextRoles.contains(key)).toList(),
                nextParameters.stream().filter(key->!beforeParameters.contains(key)).toList(),
                beforeParameters.stream().filter(key->!nextParameters.contains(key)).toList());
    }

}
