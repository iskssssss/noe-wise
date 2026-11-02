package com.skillforge.institution.dto.capability;

import com.skillforge.core.capability.CapabilityDefinitionView;
import com.skillforge.core.capability.CapabilityType;
import java.time.Instant;

/** Capability Definition API 响应，不暴露持久化 Entity。 */
public record CapabilityDefinitionResponse(
        @io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*")
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long id,
        @io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*")
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long tenantId,
        CapabilityType capabilityType,
        String capabilityKey,
        String name,
        String description,
        String trustLevel,
        String status,
        @io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*")
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long defaultVersionId,
        Long revision,
        Instant createdAt,
        Instant updatedAt) {

    public static CapabilityDefinitionResponse from(CapabilityDefinitionView value) {
        return new CapabilityDefinitionResponse(value.id(), value.tenantId(), value.capabilityType(),
                value.capabilityKey(), value.name(), value.description(), value.trustLevel(),
                value.status(), value.defaultVersionId(), value.revision(), value.createdAt(), value.updatedAt());
    }
}
