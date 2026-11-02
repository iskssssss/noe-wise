package com.skillforge.institution.dto.capability;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 租户创建 ToolDeployment 请求；仅提交 Provider 引用，不接受凭证字段。 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CreateCapabilityDeploymentRequest(
        @NotBlank @Size(max = 128) String deploymentKey,
        @NotNull @Positive Long toolVersionId,
        @NotBlank String providerType,
        @NotBlank @Size(max = 255) String providerRef,
        @NotBlank String environment,
        String status) {
}
