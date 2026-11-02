package com.skillforge.institution.dto.capability;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 租户创建 Capability Definition 请求；归属由认证租户上下文决定。 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CreateCapabilityDefinitionRequest(
        @NotBlank(message = "capability_type 不能为空") String capabilityType,
        @NotBlank @Size(max = 128) String capabilityKey,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 4000) String description) {
}
