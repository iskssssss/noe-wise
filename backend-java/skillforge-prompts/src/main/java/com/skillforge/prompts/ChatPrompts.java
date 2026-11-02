package com.skillforge.prompts;

import com.skillforge.runtime.session.spi.SessionPrompt;
import java.util.ArrayList;
import java.util.Objects;

/** 来源分层的聊天指令；平台发布策略与数据库角色由宿主传入，固定协议按可用能力装配。 */
public final class ChatPrompts {
    private static final SessionPrompt.Module CORE = module("host-core", SessionPrompt.Type.HOST, "core");
    private static final SessionPrompt.Module RUNTIME = module("chat-runtime-policy", SessionPrompt.Type.RUNTIME, "runtime-policy");
    private static final java.util.List<SessionPrompt.Module> SPECIALIZED = java.util.List.of(
            module("chat-runtime-actions", SessionPrompt.Type.RUNTIME_ACTIONS, "runtime-actions"),
            module("chat-runtime-tools", SessionPrompt.Type.RUNTIME_TOOLS, "runtime-tools"),
            module("chat-runtime-workflow", SessionPrompt.Type.RUNTIME_WORKFLOW, "runtime-workflow"),
            module("chat-runtime-skill", SessionPrompt.Type.RUNTIME_SKILL, "runtime-skill"),
            module("chat-runtime-memory", SessionPrompt.Type.RUNTIME_MEMORY, "runtime-memory"),
            module("chat-runtime-knowledge", SessionPrompt.Type.RUNTIME_KNOWLEDGE, "runtime-knowledge"),
            module("chat-runtime-task", SessionPrompt.Type.RUNTIME_TASK, "runtime-task"),
            module("chat-runtime-goal", SessionPrompt.Type.RUNTIME_GOAL, "runtime-goal"),
            module("chat-runtime-discovery", SessionPrompt.Type.RUNTIME_DISCOVERY, "runtime-discovery"),
            module("chat-runtime-program", SessionPrompt.Type.RUNTIME_PROGRAM, "runtime-program"),
            module("chat-runtime-code", SessionPrompt.Type.RUNTIME_CODE, "runtime-code"),
            module("chat-runtime-code-workspace", SessionPrompt.Type.RUNTIME_CODE_WORKSPACE, "runtime-code-workspace"),
            module("chat-runtime-code-artifacts", SessionPrompt.Type.RUNTIME_CODE_ARTIFACTS, "runtime-code-artifacts"));
    private static final SessionPrompt.Module PLAN = module("plan-policy", SessionPrompt.Type.PLAN_POLICY, "plan-policy");

    private ChatPrompts() { }

    /** 数据库正文按字面组合，固定协作政策不得混入业务角色来源。 */
    public static String roleInstructions(String systemPrompt, String roleDescription) {
        return "\nAgent 角色与业务指令：\n" + Objects.requireNonNull(systemPrompt, "systemPrompt")
                + "\n" + Objects.requireNonNullElse(roleDescription, "");
    }

    public static SessionPrompt prompt(String policy, long platformRevision, String agentPublicationRef, String agentInstructions) {
        if (platformRevision <= 0) throw new IllegalArgumentException("prompt_platform_revision_invalid");
        var modules = new ArrayList<SessionPrompt.Module>();
        modules.add(CORE);
        modules.add(new SessionPrompt.Module("platform-task-policy", SessionPrompt.Type.PLATFORM,
                Long.toString(platformRevision), policy));
        if (agentInstructions != null && !agentInstructions.isBlank()) {
            modules.add(new SessionPrompt.Module("agent-role", SessionPrompt.Type.AGENT,
                    Objects.requireNonNull(agentPublicationRef, "agentPublicationRef"), agentInstructions));
        }
        modules.add(RUNTIME);
        modules.addAll(SPECIALIZED);
        modules.add(PLAN);
        return new SessionPrompt(modules);
    }

    public static String sourceVersion(String content) { return PromptResources.digest(content); }

    public static String goalRoundInstructions() { return PromptResources.read("chat/goal-round"); }

    private static SessionPrompt.Module module(String name, SessionPrompt.Type type, String resource) {
        String content = PromptResources.read("chat/" + resource);
        return new SessionPrompt.Module(name, type, sourceVersion(content), content);
    }
}
