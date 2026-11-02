package com.skillforge.core.agent.dto.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.skillforge.core.shared.web.DecimalStringIdDeserializer;
import com.skillforge.core.shared.web.StrictLongDeserializer;
import com.skillforge.core.shared.web.StrictStringDeserializer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/** 平台与机构共享源生命周期的唯一命令协议，源所有者和身份不能从请求接管。 */
public final class AgentDefinitionCommands {
    private AgentDefinitionCommands() { }
    /** @param definitionId 稳定源身份 @param expectedRevision 精确工作草稿修订 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DefinitionCommand(@JsonDeserialize(using=DecimalStringIdDeserializer.class) @NotNull @Positive Long definitionId,
            @JsonDeserialize(using=StrictLongDeserializer.class) @Min(1)long expectedRevision) { }
    /** 未保存内容只读预览；不允许调用方提交所有权或授权范围。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PreviewContent(@JsonDeserialize(using=DecimalStringIdDeserializer.class) @NotNull @Positive Long definitionId,
            @JsonDeserialize(using=StrictLongDeserializer.class) @Min(1) long expectedRevision,
            @NotNull com.skillforge.core.agent.model.AgentTemplateDefinition definition) { }
    /** @param definitionId 稳定源身份 @param expectedRevision 精确草稿修订 @param payload 内容修改，不包含所有权 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record UpdateDefinition(@JsonDeserialize(using=DecimalStringIdDeserializer.class) @NotNull @Positive Long definitionId,
            @JsonDeserialize(using=StrictLongDeserializer.class) @Min(1)long expectedRevision,@NotNull @Valid UpdateAgentTemplateRequest payload) { }
    /** @param definitionId 源身份 @param expectedRevision 精确草稿修订 @param publishedRevision 已存在的不可变历史版本 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RestoreDefinition(@JsonDeserialize(using=DecimalStringIdDeserializer.class) @NotNull @Positive Long definitionId,
            @JsonDeserialize(using=StrictLongDeserializer.class) @Min(1)long expectedRevision,
            @JsonDeserialize(using=StrictStringDeserializer.class) @NotBlank String publishedRevision) { }
}
