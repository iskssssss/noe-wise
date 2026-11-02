package com.skillforge.core.agent.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.skillforge.core.capability.SkillActivationMode;

/** SKILL CapabilityMount 的激活配置。 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record SkillMountConfigRequest(
        SkillActivationMode activationMode,
        Long tokenBudget) {
}
