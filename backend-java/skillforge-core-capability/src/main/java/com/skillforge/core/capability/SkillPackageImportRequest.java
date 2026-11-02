package com.skillforge.core.capability;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/** Skill 标准包导入时的宿主治理字段；包内容不携带这些授权。注入预算由 SKILL.md 估算。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SkillPackageImportRequest(
        @NotBlank @Size(max = 128) String versionKey,
        boolean modelInvocable,
        boolean userInvocable,
        @NotNull SkillActivationMode activationMode,
        @Size(max = 128) String conflictGroup,
        @NotNull List<@NotNull CapabilityRef> requiredCapabilities,
        Map<@NotBlank String, @NotBlank String> files) {
}
