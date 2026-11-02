package com.skillforge.core.foundation.dto.identity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** 创建 Tenant Open Platform 服务身份的请求。 */
public record CreateServicePrincipalRequest(
        @NotBlank String integrationId,
        @NotEmpty List<String> scopes) {
}
