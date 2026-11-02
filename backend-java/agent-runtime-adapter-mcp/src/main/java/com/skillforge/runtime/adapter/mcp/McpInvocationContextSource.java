package com.skillforge.runtime.adapter.mcp;

import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ProviderCallContext;
import java.util.Optional;

/** 从已受理输入事实读取 MCP 扩展上下文；返回值不得来自模型 arguments。 */
@FunctionalInterface
public interface McpInvocationContextSource {
    Optional<TypedPayload> extraParameters(ProviderCallContext context);

    static McpInvocationContextSource empty() {
        return ignored -> Optional.empty();
    }
}
