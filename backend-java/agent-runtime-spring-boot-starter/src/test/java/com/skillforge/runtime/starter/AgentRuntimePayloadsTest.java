package com.skillforge.runtime.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.when;

import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ToolPortal.FrozenToolTarget;
import com.skillforge.runtime.api.port.ToolPortal.ToolCall;
import com.skillforge.runtime.api.port.ToolPortal.ToolBinding;
import com.skillforge.runtime.api.port.ToolPortal.ToolContinuationCall;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.SessionRuntimePayloads;
import com.skillforge.runtime.session.spi.SessionToolDirectory;
import org.junit.jupiter.api.Test;
import java.util.Set;

class AgentRuntimePayloadsTest {
    private final AgentRuntimePayloads guards = new AgentRuntimePayloads(
            new SessionRuntimePayloads(), new CodecLimits(4096, 256, 32));

    @Test
    void realRegistryValidatesArgumentsAgainstFrozenInputSchema() {
        var call = mock(ToolCall.class);
        var target = mock(FrozenToolTarget.class);
        when(call.target()).thenReturn(target);
        when(target.inputSchema()).thenReturn(new TypedPayload("json-schema",
                "{\"type\":\"object\",\"required\":[\"query\"],\"properties\":{\"query\":{\"type\":\"string\"}},\"additionalProperties\":false}"));
        when(call.arguments()).thenReturn(new TypedPayload("application/json", "{\"query\":\"docs\"}"));
        assertThat(guards.toolInputs().test(call)).isTrue();
        for (String invalid : java.util.List.of("{}", "{\"query\":42}", "{\"query\":\"docs\",\"extra\":true}")) {
            when(call.arguments()).thenReturn(new TypedPayload("application/json", invalid));
            assertThat(guards.toolInputs().test(call)).isFalse();
        }
        when(call.arguments()).thenReturn(new TypedPayload("json-schema", "{\"query\":\"docs\"}"));
        assertThat(guards.toolInputs().test(call)).isFalse();
    }

    @Test
    void realRegistryChecksFrozenOutputAgainstStructuredContent() {
        var call = mock(ToolCall.class);
        var target = mock(FrozenToolTarget.class);
        when(call.target()).thenReturn(target);
        when(target.outputSchema()).thenReturn(new TypedPayload("json-schema",
                "{\"type\":\"object\",\"required\":[\"answer\"],\"properties\":{\"answer\":{\"type\":\"integer\"}}}"));
        assertThat(guards.toolOutputs().test(call, output("{\"content\":[],\"structuredContent\":{\"answer\":42}}"))).isTrue();
        assertThat(guards.toolOutputs().test(call, output("{\"content\":[],\"structuredContent\":{\"answer\":\"42\"}}"))).isFalse();
        assertThat(guards.toolOutputs().test(call, output("{\"content\":[]}"))).isFalse();
        when(target.outputSchema()).thenReturn(new TypedPayload("json-schema", "{}"));
        assertThat(guards.toolOutputs().test(call, output("{\"content\":[{\"type\":\"text\",\"text\":\"docs\"}]}"))).isTrue();
    }

    @Test
    void safeOutputValidatesRawStructuredContentBeforeRedactingSensitiveFields() {
        var call = mock(ToolCall.class);
        var target = mock(FrozenToolTarget.class);
        when(call.target()).thenReturn(target);
        when(target.outputSchema()).thenReturn(new TypedPayload("json-schema",
                "{\"type\":\"object\",\"required\":[\"answer\",\"token\"],\"properties\":{\"answer\":{\"type\":\"integer\"},\"token\":{\"type\":\"string\"}},\"additionalProperties\":false}"));
        when(target.sensitiveFields()).thenReturn(Set.of("token"));

        var safe = guards.safeToolOutput(call, output("{\"content\":[{\"type\":\"text\",\"text\":\"secret-value\"}],"
                + "\"structuredContent\":{\"answer\":42,\"token\":\"secret-value\"},\"resultType\":\"complete\"}"));

        assertThat(safe.schemaId()).isEqualTo("skillforge.runtime.tool.result.v1");
        assertThat(safe.canonicalJson()).isEqualTo(
                "{\"content\":[],\"resultType\":\"complete\",\"structuredContent\":{\"answer\":42}}");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> guards.safeToolOutput(call,
                output("{\"content\":[],\"structuredContent\":{\"answer\":42,\"token\":7}}")))
                .isInstanceOf(IllegalArgumentException.class);

        when(target.outputSchema()).thenReturn(new TypedPayload("json-schema",
                "{\"type\":\"object\",\"required\":[\"success\",\"body\"],\"properties\":{"
                        + "\"success\":{\"type\":\"boolean\"},\"body\":{\"type\":\"string\"},"
                        + "\"headers\":{\"type\":\"object\"}},\"additionalProperties\":false}"));
        when(target.sensitiveFields()).thenReturn(Set.of("headers"));
        var http = guards.safeToolOutput(call, output(
                "{\"content\":[{\"text\":\"{\\\"ok\\\":true}\",\"type\":\"text\"}],"
                        + "\"resultType\":\"complete\",\"structuredContent\":{"
                        + "\"body\":\"{\\\"ok\\\":true}\",\"success\":true}}"));
        assertThat(http.canonicalJson()).isEqualTo(
                "{\"content\":[{\"text\":\"{\\\"ok\\\":true}\",\"type\":\"text\"}],"
                        + "\"resultType\":\"complete\",\"structuredContent\":{"
                        + "\"body\":\"{\\\"ok\\\":true}\",\"success\":true}}");
    }

    @Test
    void currentMembershipRequiresTheExactCurrentBindingForInvokeAndContinuation() {
        var directory = mock(SessionToolDirectory.class);
        var target = mock(FrozenToolTarget.class);
        var run = mock(com.skillforge.runtime.api.port.RunIdentity.class);
        var current = new ToolBinding(ToolBinding.ProviderType.TEST_MOCK, "fixture", "lookup");
        var changed = new ToolBinding(ToolBinding.ProviderType.TEST_MOCK, "other", "lookup");
        when(directory.requireCurrent(run, target)).thenReturn(new SessionToolDirectory.Entry(
                target, current, new SessionToolDirectory.Discovery(
                        "test_mock:fixture", "test_mock:fixture/lookup", "lookup",
                        java.util.Optional.empty(), "lookup fixture")));

        var invoke = mock(ToolCall.class, RETURNS_DEEP_STUBS);
        when(invoke.context().callContext().runIdentity()).thenReturn(run);
        when(invoke.target()).thenReturn(target);
        when(invoke.binding()).thenReturn(current, changed);
        assertThat(AgentRuntimePayloads.toolMembership(directory).test(invoke)).isTrue();
        assertThat(AgentRuntimePayloads.toolMembership(directory).test(invoke)).isFalse();

        var continuation = mock(ToolContinuationCall.class, RETURNS_DEEP_STUBS);
        when(continuation.context().callContext().runIdentity()).thenReturn(run);
        when(continuation.target()).thenReturn(target);
        when(continuation.binding()).thenReturn(current, changed);
        assertThat(AgentRuntimePayloads.toolContinuationMembership(directory).test(continuation)).isTrue();
        assertThat(AgentRuntimePayloads.toolContinuationMembership(directory).test(continuation)).isFalse();
    }

    private TypedPayload output(String json) { return new TypedPayload("skillforge.runtime.tool.result.v1", json); }
}
