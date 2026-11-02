package com.skillforge.core.agent.dto.response;

import com.skillforge.core.capability.SkillActivationMode;

/** SKILL CapabilityMount 的类型化响应配置。 */
public record SkillMountConfigResponse(
        SkillActivationMode activationMode,
        long tokenBudget) {
}
