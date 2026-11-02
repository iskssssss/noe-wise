package com.skillforge.platform.dto.capability;

import com.skillforge.core.capability.CapabilityVersionSummary;

/** 能力版本列表专用五字段响应，不含描述符或文件正文。 */
public record CapabilityVersionSummaryResponse(
        @io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*")
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long id,
        String versionKey,
        String name,
        String description,
        String status) {
    public static CapabilityVersionSummaryResponse from(CapabilityVersionSummary value) {
        return new CapabilityVersionSummaryResponse(value.id(), value.versionKey(), value.name(),
                value.description(), value.status());
    }
}
