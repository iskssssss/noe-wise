package com.skillforge.core.agent.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

/** 模板安装命令；同租户同 requestId 同参数重放返回原 Agent。 */
public record InstallAgentTemplateRequest(
        @NotNull @Positive Long templateId,
        @Size(max = 128) String name,
        @NotBlank @Pattern(regexp = "[a-zA-Z0-9_-]{1,128}") String requestId,
        @Valid List<ResourceBinding> resourceBindings,
        @Valid List<DelegationBinding> delegationBindings) {
    public InstallAgentTemplateRequest {
        resourceBindings = resourceBindings == null ? List.of() : List.copyOf(resourceBindings);
        delegationBindings = delegationBindings == null ? List.of() : List.copyOf(delegationBindings);
    }
    public record ResourceBinding(@NotBlank String slotKey, @NotNull @Valid CapabilityMountRequest mount) { }
    public record DelegationBinding(@NotBlank String slotKey, @NotBlank String targetAgentId) { }
}
