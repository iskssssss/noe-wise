package com.skillforge.core.agent.dto.response;

import com.skillforge.core.capability.CapabilityMount.KnowledgeMountConfig.BindingMode;

/** KNOWLEDGE_SOURCE CapabilityMount 的类型化响应配置。 */
public record KnowledgeMountConfigResponse(
        BindingMode mode,
        String pinnedRevisionId) {
}
