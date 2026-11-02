package com.skillforge.core.knowledge.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 将 Knowledge Base 草稿 revision 标记为 ready 的请求。 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record MarkKnowledgeBaseRevisionReadyRequest(
        @NotEmpty
        @Size(max = 2000)
        @Schema(description = "不可变 source_version manifest；每项必须已存在于当前知识库")
        @JsonDeserialize(contentUsing = StrictStringDeserializer.class)
        List<@Size(min = 1, max = 128) String> sourceVersions,
        @NotNull
        @Valid
        @Schema(description = "ready 时冻结的完整 Knowledge Source 治理策略")
        KnowledgeCapabilityPolicyRequest policy) {
}
