package com.skillforge.platform.dto.capability;

import com.skillforge.core.capability.CapabilityRequirementOption;

/** 依赖候选的精确发布身份摘要；不含版本正文。 */
public record CapabilityRequirementOptionResponse(
        @io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*")
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long definitionId,
        String name,
        @io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*")
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long versionId,
        String versionKey,
        Long skillEstimatedTokens) {

    public static CapabilityRequirementOptionResponse from(CapabilityRequirementOption value) {
        return new CapabilityRequirementOptionResponse(value.definitionId(), value.name(),
                value.versionId(), value.versionKey(), value.skillEstimatedTokens());
    }
}
