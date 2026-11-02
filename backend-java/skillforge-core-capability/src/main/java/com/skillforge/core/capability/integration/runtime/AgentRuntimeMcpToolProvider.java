package com.skillforge.core.capability.integration.runtime;

import com.skillforge.core.capability.integration.runtime.tool.JdbcMcpBindings;
import com.skillforge.runtime.adapter.mcp.McpToolPortal;
import com.skillforge.runtime.api.port.ToolPortal.ToolCall;
import com.skillforge.runtime.api.port.ToolPortal.ToolContinuationCall;
import com.skillforge.runtime.api.port.ToolPortal.ToolBinding;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ToolPortal;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** MCP transport 适配器；冻结 connector/providerRef、toolName 与 target 必须同时匹配。 */
public final class AgentRuntimeMcpToolProvider {
    private final McpToolPortal portal;

    AgentRuntimeMcpToolProvider(McpToolPortal portal){
        this.portal=Objects.requireNonNull(portal);
    }

    /** 新主链已加载精确binding；只建立本次调用视图，不再经supports重复查询冻结路由。 */
    public ToolPortal forDispatch(ToolCall call, JdbcMcpBindings.Binding frozen,
            BooleanSupplier authorizeSend, McpToolPortal.BusinessSendAudit businessSendAudit, Predicate<TypedPayload> observationGuard, Optional<TypedPayload> extraParameters) {
        return portal.forDispatch(call, transport(call.binding(), call.target(), frozen), authorizeSend, businessSendAudit, observationGuard, extraParameters);
    }

    /** 原逻辑调用的新Attempt沿真实MCP continuation协议发送，不回到旧授权或来源。 */
    public ToolPortal forContinuation(ToolContinuationCall call, JdbcMcpBindings.Binding frozen,
            BooleanSupplier authorizeSend, McpToolPortal.BusinessSendAudit businessSendAudit, Predicate<TypedPayload> observationGuard, Optional<TypedPayload> extraParameters) {
        return portal.forContinuation(call, transport(call.binding(), call.target(), frozen), authorizeSend, businessSendAudit, observationGuard, extraParameters);
    }

    /** 同时核对冻结provider、toolName和完整target，不能用同名工具的其他部署替代。 */
    private static McpToolPortal.Binding transport(ToolBinding binding, ToolPortal.FrozenToolTarget target,
            JdbcMcpBindings.Binding frozen) {
        Objects.requireNonNull(frozen);
        if (binding.providerType() != ToolBinding.ProviderType.MCP || !binding.providerRef().equals(frozen.providerRef())
                || !binding.toolName().equals(frozen.toolName()) || !target.equals(frozen.target()))
            throw new IllegalArgumentException("mcp_registered_binding_mismatch");
        return new McpToolPortal.Binding(frozen.target(), binding, frozen.endpoint(), frozen.encryptedCredential(),
                frozen.toolName(), frozen.protocolVersion());
    }
}
