package com.skillforge.prompts;

/** 可发布的默认正文；不读取数据库，也不自动替换运营配置或已发布系统 Agent。 */
public final class PromptDefaults {
    public static final String AGENT_DECISION_POLICY = PromptResources.read("defaults/agent-decision-policy");
    public static final String CHAT_SUMMARY_PROMPT = PromptResources.read("defaults/chat-summary");
    public static final String PROFILE_MERGE_PROMPT = PromptResources.read("defaults/profile-merge");

    private PromptDefaults() { }

    /** 供系统用途配置的显式初始化/发布使用；未知用途拒绝，不暗中回退其他用途。 */
    public static String systemPurpose(String purpose) {
        return switch (purpose) {
            case "memory-extraction", "task-context", "context-summary" -> PromptResources.read("defaults/" + purpose);
            default -> throw new IllegalArgumentException("prompt_purpose_unknown");
        };
    }
}
