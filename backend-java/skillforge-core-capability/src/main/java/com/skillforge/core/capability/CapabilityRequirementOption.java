package com.skillforge.core.capability;

/** 依赖选择目录的精确当前发布身份，不包含能力正文。 */
public record CapabilityRequirementOption(
        Long definitionId, String name, Long versionId, String versionKey) {}
