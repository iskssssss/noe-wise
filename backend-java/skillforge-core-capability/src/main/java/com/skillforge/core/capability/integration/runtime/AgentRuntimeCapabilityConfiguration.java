package com.skillforge.core.capability.integration.runtime;

import com.skillforge.core.capability.integration.runtime.tool.JdbcMcpBindings;
import com.skillforge.runtime.adapter.mcp.McpToolPortal;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ToolPortal;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.SessionRuntimePayloads;
import com.skillforge.runtime.starter.AgentRuntimeDecrypt;
import com.skillforge.runtime.starter.AgentRuntimePayloadValidation;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import java.net.http.HttpClient;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 平台 MCP 冻结绑定与传输装配；发送授权由当前执行调用显式提供。 */
@Configuration(proxyBeanMethods = false)
public class AgentRuntimeCapabilityConfiguration {
    @Bean
    JdbcMcpBindings agentRuntimeMcpBindings(DataSource source) {
        return new JdbcMcpBindings(source);
    }

    @Bean
    AgentRuntimeMcpToolProvider agentRuntimeMcpToolProvider(JdbcMcpBindings bindings,
            AgentRuntimeDecrypt decrypt, AgentRuntimeProperties properties, HttpClient http, Clock clock,
            AgentRuntimePayloadValidation payloads) {
        var limits = new CodecLimits(properties.getMaxPayloadCodeUnits(),
                properties.getMaxPayloadNodes(), properties.getMaxPayloadDepth());
        var portal = new McpToolPortal((tenant, target) -> bindings.find(tenant, target).map(value ->
                new McpToolPortal.Binding(value.target(), new ToolPortal.ToolBinding(
                        ToolPortal.ToolBinding.ProviderType.MCP, value.providerRef(), value.toolName()),
                        value.endpoint(), value.encryptedCredential(), value.toolName(), value.protocolVersion())),
                decrypt::decrypt, call -> false, call -> false,
                call -> acceptsInput(call, payloads, limits),
                call -> call.inputResponses().values().stream().allMatch(response -> payloads.accepts(response.result())),
                (call, output) -> acceptsOutput(call, output, payloads, limits),
                http, clock, properties.getHttpMaximumResponseBytes(), context -> {
                    // 只有已登记派发视图携带原输入上下文；基础门户不能另取上下文后绕开该边界。
                    throw new IllegalStateException("mcp_registered_dispatch_context_required");
                });
        return new AgentRuntimeMcpToolProvider(portal);
    }

    private static boolean acceptsInput(ToolPortal.ToolCall call,
            AgentRuntimePayloadValidation payloads, CodecLimits limits) {
        if (!payloads.accepts(call.arguments()) || !payloads.accepts(call.target().inputSchema())) return false;
        try {
            SessionRuntimePayloads.validateAgainstSchema(call.target().inputSchema(), call.arguments(), limits);
            return true;
        } catch (RuntimeException rejected) {
            return false;
        }
    }

    private static boolean acceptsOutput(ToolPortal.ToolCall call, TypedPayload output,
            AgentRuntimePayloadValidation payloads, CodecLimits limits) {
        if (!payloads.accepts(output) || !payloads.accepts(call.target().outputSchema())) return false;
        try {
            SessionRuntimePayloads.validateToolOutput(call.target().outputSchema(), output, limits);
            return true;
        } catch (RuntimeException rejected) {
            return false;
        }
    }
}
