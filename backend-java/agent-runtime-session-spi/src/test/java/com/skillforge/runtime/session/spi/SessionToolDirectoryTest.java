package com.skillforge.runtime.session.spi;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ToolPortal.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 模型身份只建立一一映射，不修改或猜测原执行目标。 */
class SessionToolDirectoryTest {
    @Test
    void stableHandleDirectoryUsesExactHandlesByDefaultAndRemainsFunctional() {
        var tools = List.of(tool("tool:101"), tool("tool:102"));
        SessionToolDirectory directory = (owner, agent) -> tools;

        var identities = directory.modelIdentities(run(), tools);

        assertEquals(Map.of("tool:101", "tool:101", "tool:102", "tool:102"), identities);
        assertThrows(UnsupportedOperationException.class, () -> identities.put("tool:103", "tool:103"));
    }

    @Test
    void explicitOwnerMappingPreservesInputScopedTargetsAndRejectsIncompleteOrExtraCoverage() {
        var first = tool("tool:101:input:first");
        var second = tool("tool:102:input:first");
        var tools = List.of(first, second);

        assertEquals(Map.of(first.target().handle().value(), "tool:101", second.target().handle().value(), "tool:102"),
                SessionToolDirectory.requireModelIdentities(tools,
                        Map.of(first.target().handle().value(), "tool:101", second.target().handle().value(), "tool:102")));
        assertEquals("tool:101:input:first", first.target().handle().value());
        assertThrows(IllegalArgumentException.class, () -> SessionToolDirectory.requireModelIdentities(tools,
                Map.of(first.target().handle().value(), "tool:101")));
        assertThrows(IllegalArgumentException.class, () -> SessionToolDirectory.requireModelIdentities(tools,
                Map.of(first.target().handle().value(), "tool:101", "tool:unknown", "tool:102")));
    }

    @Test
    void rejectsDuplicateExactHandlesDuplicateVisibleIdentitiesAndNonNormalizedValues() {
        var first = tool("tool:101");
        var second = tool("tool:102");

        assertThrows(IllegalArgumentException.class, () -> SessionToolDirectory.requireModelIdentities(
                List.of(first, first), Map.of("tool:101", "tool:101")));
        assertThrows(IllegalArgumentException.class, () -> SessionToolDirectory.requireModelIdentities(
                List.of(first, second), Map.of("tool:101", "same", "tool:102", "same")));
        assertThrows(IllegalArgumentException.class, () -> SessionToolDirectory.requireModelIdentities(
                List.of(first), Map.of("tool:101", " tool:101")));
    }

    /** 固定合法调用上下文，身份映射不需要外部资源或真实时间。 */
    private static RunIdentity run() {
        var tenant = new TenantId("tenant");
        var owner = SessionOwner.user(new SessionRef(tenant, "session"), new AgentId("agent"), new EndUserId(7));
        var input = new InputId("input");
        return new RunIdentity(owner, owner.target().requireAgent(), input, new RunRef(tenant, "run"), new TraceEnvelope("trace", "span", input, Optional.empty()), new ResultRef("identity"), new com.skillforge.runtime.api.RunRequester.EndUser(owner.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(owner.sessionRef().tenantId(), input.value()));
    }

    /** 同名不同挂载保持独立完整目标，测试不从名称推导身份。 */
    private static SessionToolDirectory.Entry tool(String handle) {
        var target = new FrozenToolTarget(new ToolHandle(handle), new CapabilityRef(CapabilityType.TOOL, "search", "v1"),
                "deployment", 1, new TypedPayload("json-schema", "{}"), new TypedPayload("json-schema", "{}"),
                ToolEffect.READ_ONLY, true, Duration.ofSeconds(3), true, ToolConfirmationPolicy.NEVER,
                Set.of(), Set.of(), true);
        var binding = new ToolBinding(ToolBinding.ProviderType.MCP, "server", "search");
        return new SessionToolDirectory.Entry(target, binding,
                new SessionToolDirectory.Discovery("mcp:server", "mcp:server/search", "search", Optional.empty(), "Search documents"));
    }
}
