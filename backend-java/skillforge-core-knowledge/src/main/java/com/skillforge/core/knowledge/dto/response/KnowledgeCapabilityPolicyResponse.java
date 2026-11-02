package com.skillforge.core.knowledge.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 已冻结并随 Knowledge revision 返回的治理策略快照。 */
public record KnowledgeCapabilityPolicyResponse(
        @Schema(description = "模型决策层是否可调用") Boolean modelInvocable,
        @Schema(description = "用户人工指令面是否可调用") Boolean userInvocable,
        @Schema(description = "允许的 Knowledge operator") List<String> allowedOperators,
        @Schema(description = "来源 freshness 治理策略") String freshnessPolicy,
        @Schema(description = "是否要求引用") Boolean citationRequired) {
}
