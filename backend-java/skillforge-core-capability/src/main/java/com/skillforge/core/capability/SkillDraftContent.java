package com.skillforge.core.capability;

import java.util.List;
import java.util.Map;

/** 编辑态文件采用 Base64 保存原始字节；不承担 Runtime descriptor 的语义。 */
public record SkillDraftContent(
        String versionKey, Map<String, String> files, boolean modelInvocable,
        boolean userInvocable, SkillActivationMode activationMode,
        String conflictGroup, List<CapabilityRef> requiredCapabilities) {
    public SkillDraftContent {
        versionKey = versionKey == null ? "" : versionKey;
        files = files == null ? Map.of() : Map.copyOf(files);
        requiredCapabilities = requiredCapabilities == null ? List.of() : List.copyOf(requiredCapabilities);
    }
}
