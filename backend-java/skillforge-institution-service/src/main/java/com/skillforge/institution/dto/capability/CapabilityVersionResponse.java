package com.skillforge.institution.dto.capability;

import com.skillforge.core.capability.CapabilityVersionView;
import com.skillforge.core.capability.CapabilityType;
import java.time.Instant;
import java.util.Map;

/** Capability Version API 响应；Descriptor 只包含严格 Codec 接受的结构。 */
public record CapabilityVersionResponse(
        @io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*")
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long id,
        @io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*")
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long definitionId,
        @io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*")
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long tenantId,
        CapabilityType capabilityType,
        String versionKey,
        String name,
        String description,
        String trustLevel,
        Map<String, Object> descriptor,
        String contentHash,
        String status,
        Instant createdAt,
        Instant publishedAt) {

    public static CapabilityVersionResponse from(CapabilityVersionView value) {
        return new CapabilityVersionResponse(value.id(), value.definitionId(), value.tenantId(),
                value.capabilityType(), value.versionKey(), value.name(), value.description(),
                value.trustLevel(), value.descriptor(), value.contentHash(), value.status(),
                value.createdAt(), value.publishedAt());
    }
}
