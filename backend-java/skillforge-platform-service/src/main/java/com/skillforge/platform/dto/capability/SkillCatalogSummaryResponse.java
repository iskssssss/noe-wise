package com.skillforge.platform.dto.capability;

import com.skillforge.core.capability.SkillCatalogSummary;

/** 平台技能目录摘要；大整数身份保持字符串精度。 */
public record SkillCatalogSummaryResponse(
        @io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*")
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long id,
        String capabilityKey,
        String name,
        String description,
        String status,
        @io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*")
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long defaultVersionId,
        String defaultVersionKey) {

    public static SkillCatalogSummaryResponse from(SkillCatalogSummary value) {
        return new SkillCatalogSummaryResponse(value.id(), value.capabilityKey(), value.name(), value.description(),
                value.status(), value.defaultVersionId(), value.defaultVersionKey());
    }
}
