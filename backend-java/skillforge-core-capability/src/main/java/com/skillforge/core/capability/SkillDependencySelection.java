package com.skillforge.core.capability;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 编辑时选择能力身份；具体发布版本由保存事务解析。 */
public record SkillDependencySelection(@NotNull CapabilityType type,
        @JsonProperty("capability_id") @NotBlank String capabilityId) { }
