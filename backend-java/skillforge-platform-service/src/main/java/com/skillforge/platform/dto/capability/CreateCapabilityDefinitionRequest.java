package com.skillforge.platform.dto.capability;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 平台创建公共 Capability Definition 请求；tenant_id 始终由服务端置空。 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CreateCapabilityDefinitionRequest(
        @NotBlank(message = "capability_type 不能为空") String capabilityType,
        @NotBlank @Size(max = 128) String capabilityKey,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 4000) String description) {
}
