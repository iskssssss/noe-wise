package com.skillforge.prompts;

import com.skillforge.runtime.session.spi.SessionPrompt;
import java.util.List;
import java.util.Objects;

/** 通用系统 Agent 的原生动作协议与已发布用途正文分开冻结，只声明实际挂载能力。 */
public final class SystemAgentPrompts {
    private static final String ACTION_PROTOCOL = PromptResources.read("system-agent/action-protocol");
    private static final SessionPrompt.Module PROTOCOL = new SessionPrompt.Module("system-agent-protocol",
            SessionPrompt.Type.HOST, PromptResources.digest(ACTION_PROTOCOL), ACTION_PROTOCOL);

    private SystemAgentPrompts() { }

    public static SessionPrompt prompt(String purposeSourceRef, String purposeInstructions) {
        Objects.requireNonNull(purposeSourceRef, "purposeSourceRef");
        Objects.requireNonNull(purposeInstructions, "purposeInstructions");
        String purpose = "用途指令：\n" + purposeInstructions;
        return new SessionPrompt(List.of(PROTOCOL, new SessionPrompt.Module("system-purpose",
                SessionPrompt.Type.SYSTEM_PURPOSE, purposeSourceRef, purpose)));
    }
}
