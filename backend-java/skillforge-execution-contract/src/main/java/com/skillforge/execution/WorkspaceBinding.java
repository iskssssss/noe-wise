package com.skillforge.execution;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.Objects;

/** 宿主从可信 Run 生成的工作区身份；模型不能指定或覆盖归属。 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record WorkspaceBinding(String workspaceId, String tenantId, String sessionId, long endUserId,
                               String productSessionId, WorkspacePolicy policy, boolean initializeAllowed) {
    /** 首次初始化使用明确创建许可；已确认初始化的宿主请求必须采用完整签名关闭许可。 */
    public WorkspaceBinding(String workspaceId, String tenantId, String sessionId, long endUserId,
                            String productSessionId, WorkspacePolicy policy) {
        this(workspaceId, tenantId, sessionId, endUserId, productSessionId, policy, true);
    }
    public WorkspaceBinding {
        if (workspaceId == null || !workspaceId.matches("[0-9a-f]{64}") || tenantId == null || tenantId.isBlank()
                || sessionId == null || sessionId.isBlank() || productSessionId == null || productSessionId.isBlank() || endUserId < 1)
            throw new IllegalArgumentException("workspace_binding_invalid");
        Objects.requireNonNull(policy, "policy");
    }
}
