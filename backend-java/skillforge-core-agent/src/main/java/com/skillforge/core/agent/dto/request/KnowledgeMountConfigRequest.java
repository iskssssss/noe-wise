package com.skillforge.core.agent.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.skillforge.core.capability.CapabilityMount.KnowledgeMountConfig.BindingMode;

/** KNOWLEDGE_SOURCE CapabilityMount 的版本绑定策略。 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record KnowledgeMountConfigRequest(
        BindingMode mode,
        String pinnedRevisionId) {
}
