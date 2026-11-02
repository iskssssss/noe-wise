package com.skillforge.runtime.session;

/** 原生模型函数名的唯一纯映射；稳定显示身份不代替执行端的 opaque handle。 */
public final class SessionToolNames {
    private SessionToolNames() { }

    public static String wireName(String identity, String logicalName) {
        if (identity == null || identity.isBlank() || logicalName == null || logicalName.isBlank())
            throw new IllegalArgumentException("session_tool_identity_invalid");
        String prefix = logicalName.chars().anyMatch(character -> character > 127) ? "tool"
                : logicalName.replaceAll("[^A-Za-z0-9_-]+", "_").replaceAll("^[_-]+|[_-]+$", "");
        if (prefix.isEmpty()) prefix = "tool";
        if (prefix.length() > 28) prefix = prefix.substring(0, 28);
        return "sf_" + prefix + "_" + SessionEventValues.hash("skillforge.runtime.tool-wire-name.v1", identity).substring(0, 32);
    }
}
