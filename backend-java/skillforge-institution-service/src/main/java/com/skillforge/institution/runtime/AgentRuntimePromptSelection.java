package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.port.ToolPortal.ToolBinding.ProviderType;
import com.skillforge.runtime.session.spi.SessionPrompt;
import com.skillforge.runtime.session.spi.SessionPrompt.Type;
import java.util.EnumSet;
import java.util.Set;

/** 仅从本次受理的冻结目录选择专项规则；选择结果随 Profile 保存，恢复直接读取原正文。 */
final class AgentRuntimePromptSelection {
    private AgentRuntimePromptSelection() { }

    static SessionPrompt select(SessionPrompt prompt, AgentRuntimeExecutionCatalog.FrozenCatalog catalog) {
        var modules = EnumSet.of(Type.RUNTIME_ACTIONS);
        if (!catalog.tools().isEmpty()) modules.add(Type.RUNTIME_TOOLS);
        if (!catalog.knowledge().isEmpty()) modules.add(Type.RUNTIME_KNOWLEDGE);
        if (!catalog.skills().isEmpty()) modules.add(Type.RUNTIME_SKILL);
        if (!catalog.workflows().isEmpty()) modules.add(Type.RUNTIME_WORKFLOW);
        var platform = catalog.tools().stream()
                .filter(entry -> entry.target().modelInvocable() && entry.binding().providerType() == ProviderType.PLATFORM)
                .filter(entry -> entry.binding().providerRef().equals(entry.binding().toolName()))
                .map(entry -> entry.binding().toolName()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (platform.contains("search_memory")) modules.add(Type.RUNTIME_MEMORY);
        if (platform.stream().anyMatch(Set.of("search_tasks", "read_task", "update_task")::contains)) modules.add(Type.RUNTIME_TASK);
        if (platform.stream().anyMatch(Set.of("get_goal", "update_goal")::contains)) modules.add(Type.RUNTIME_GOAL);
        if (platform.contains("search_tools")) modules.add(Type.RUNTIME_DISCOVERY);
        if (platform.contains("run_code")) modules.add(Type.RUNTIME_PROGRAM);
        if (platform.contains("code_execute")) {
            modules.add(Type.RUNTIME_CODE);
            modules.add(platform.contains("workspace_files") ? Type.RUNTIME_CODE_WORKSPACE : Type.RUNTIME_CODE_ARTIFACTS);
        }
        // 规划正文随来源冻结；实际模型轮按当前计划与已激活 Skill 决定是否展示。
        return new SessionPrompt(prompt.selectedModules(true, modules));
    }
}
