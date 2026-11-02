package com.skillforge.institution.dto.capability;

import com.skillforge.core.capability.SkillVersionSummary;

/** 技能历史摘要；选择精确版本后再按需读取内容。 */
public record SkillVersionSummaryResponse(
        @io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*")
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long id,
        String versionKey,
        String status) {
    public static SkillVersionSummaryResponse from(SkillVersionSummary value) {
        return new SkillVersionSummaryResponse(value.id(), value.versionKey(), value.status());
    }
}
