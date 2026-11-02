package com.skillforge.core.capability.contract;

/** Open Platform 可映射资源类型。 */
public enum ResourceType {
    AGENT("agent"),
    WORKFLOW("workflow"),
    SKILL("skill"),
    PERSONA("persona"),
    POLICY("policy"),
    TOOL("tool"),
    CONNECTOR("connector"),
    CHANNEL("channel"),
    KNOWLEDGE_BASE("knowledge_base");

    private final String value;

    ResourceType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
