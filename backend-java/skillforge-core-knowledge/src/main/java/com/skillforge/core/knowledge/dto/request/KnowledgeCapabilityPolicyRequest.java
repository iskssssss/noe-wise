package com.skillforge.core.knowledge.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;

/**
 * Knowledge Source 在 revision ready 边界冻结的完整调用治理策略。
 *
 * <p>每一个字段都必须由管理调用方显式提供。该 DTO 不从 Knowledge Base 的
 * retrieval/indexing 设置推导任何调用面、算子、时效或引用要求。
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record KnowledgeCapabilityPolicyRequest(
        @NotNull
        @JsonDeserialize(using = StrictBooleanDeserializer.class)
        @Schema(description = "模型决策层是否可调用")
        Boolean modelInvocable,
        @NotNull
        @JsonDeserialize(using = StrictBooleanDeserializer.class)
        @Schema(description = "用户人工指令面是否可调用")
        Boolean userInvocable,
        @NotEmpty
        @Size(max = 64)
        @JsonDeserialize(contentUsing = StrictStringDeserializer.class)
        @Schema(description = "允许的 Knowledge operator；必须显式提供且非空")
        Set<@NotBlank @Size(max = 64) String> allowedOperators,
        @NotBlank
        @Size(max = 128)
        @JsonDeserialize(using = StrictStringDeserializer.class)
        @Schema(description = "来源 freshness 治理策略，不由索引/检索配置推断")
        String freshnessPolicy,
        @NotNull
        @JsonDeserialize(using = StrictBooleanDeserializer.class)
        @Schema(description = "是否要求引用")
        Boolean citationRequired) {
}
