package com.skillforge.institution.dto.capability;

import com.skillforge.core.capability.CapabilityCatalogError;
import com.skillforge.core.capability.CapabilityValidationView;
import java.util.List;

/** Draft Version 实时校验响应。 */
public record CapabilityValidationResponse(
        @io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*")
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long definitionId,
        @io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*")
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long versionId,
        Long definitionRevision,
        boolean valid,
        List<CapabilityCatalogError> errors) {

    public static CapabilityValidationResponse from(CapabilityValidationView value) {
        return new CapabilityValidationResponse(value.definitionId(), value.versionId(),
                value.definitionRevision(), value.valid(), value.errors());
    }
}
