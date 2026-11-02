package com.skillforge.runtime.session.spi;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** 具名指令模块冻结来源与程序顺序；模块身份不提升内容权限。 */
public record SessionPrompt(List<Module> modules) {
    public enum Type { HOST, PLATFORM, AGENT, RUNTIME, RUNTIME_ACTIONS, RUNTIME_TOOLS, RUNTIME_WORKFLOW, RUNTIME_SKILL,
        RUNTIME_MEMORY, RUNTIME_KNOWLEDGE, RUNTIME_TASK, RUNTIME_GOAL, RUNTIME_DISCOVERY,
        RUNTIME_PROGRAM, RUNTIME_CODE, RUNTIME_CODE_WORKSPACE, RUNTIME_CODE_ARTIFACTS, WORKFLOW, SYSTEM_PURPOSE, PLAN_POLICY,
        DELEGATION_POLICY, DELEGATION_TARGETS }
    public record Module(String name, Type type, String sourceVersion, String content) {
        public Module {
            text(name); Objects.requireNonNull(type); text(sourceVersion); text(content);
        }
        @Override public String toString() { return "PromptModule[" + name + "]"; }
    }
    public SessionPrompt {
        modules = List.copyOf(modules);
        if (modules.isEmpty()) throw new IllegalArgumentException("prompt_modules_empty");
        var names = new HashSet<String>();
        int previous = -1;
        for (var module : modules) {
            if (!names.add(module.name()) || module.type().ordinal() < previous)
                throw new IllegalArgumentException("prompt_modules_order_invalid");
            previous = module.type().ordinal();
        }
    }
    public static SessionPrompt single(String name, Type type, String version, String content) {
        return new SessionPrompt(List.of(new Module(name, type, version, content)));
    }
    public String render() { return render(false); }
    /** 部署冻结的 Plan 正文只在本步骤选择开启时加入；默认正文不包含规划要求。 */
    public String render(boolean planActive) {
        return selectedModules(planActive, java.util.EnumSet.allOf(Type.class)).stream()
                .map(Module::content).collect(Collectors.joining("\n\n"));
    }

    /** 能力专项规则只按受信装配选择，业务与宿主模块不受此集合裁剪。 */
    public List<Module> selectedModules(boolean planActive, java.util.Set<Type> runtimeModules) {
        return modules.stream().filter(module -> module.type() != Type.PLAN_POLICY || planActive)
                .filter(module -> !module.type().name().startsWith("RUNTIME_") || runtimeModules.contains(module.type()))
                .toList();
    }
    private static void text(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("prompt_module_invalid");
    }
    @Override public String toString() { return "SessionPrompt[REDACTED]"; }
}
