package com.skillforge.runtime.starter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.SessionRuntimePayloads;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 验证宿主 schema 扩展不能绕过通用 JSON 限额，且默认 registry 保持原行为。 */
class AgentRuntimePayloadSchemaTest {
    private static final CodecLimits LIMITS = new CodecLimits(4096, 128, 16);
    private final AgentRuntimeAutoConfiguration configuration = new AgentRuntimeAutoConfiguration();

    @Test
    void routesExactHostSchemaAfterBoundedParsingAndPreservesPayload() {
        var host = mock(AgentRuntimePayloadSchema.class);
        when(host.schemaIds()).thenReturn(Set.of("host.connection.v1"));
        var validator = configuration.agentRuntimePayloadValidator(List.of(host));
        var payload = new TypedPayload("host.connection.v1", "{\"ready\":true}");
        validator.validate(payload, LIMITS);
        verify(host).validate(same(payload));
        clearInvocations(host);
        assertThrows(IllegalArgumentException.class, () -> validator.validate(payload, new CodecLimits(5, 128, 16)));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(payload, new CodecLimits(4096, 1, 16)));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(
                new TypedPayload("host.connection.v1", "{\"value\":{\"nested\":true}}"), new CodecLimits(4096, 128, 2)));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(
                new TypedPayload("host.connection.v1", "{\"ready\":true,\"ready\":false}"), LIMITS));
        verifyNoInteractions(host);
    }

    @Test
    void keepsDefaultsAndUnknownSchemaRejectionWithAndWithoutHost() {
        var defaults = configuration.agentRuntimePayloadValidator(List.of());
        assertInstanceOf(SessionRuntimePayloads.class, defaults);
        var host = mock(AgentRuntimePayloadSchema.class);
        when(host.schemaIds()).thenReturn(Set.of("host.connection.v1"));
        var extended = configuration.agentRuntimePayloadValidator(List.of(host));
        for (var validator : List.of(defaults, extended)) {
            assertDoesNotThrow(() -> validator.validate(new TypedPayload(
                    SessionRuntimePayloads.TOOL_RESULT_SCHEMA, "{\"content\":[]}"), LIMITS));
            assertThrows(IllegalArgumentException.class, () -> validator.validate(new TypedPayload(
                    SessionRuntimePayloads.TOOL_RESULT_SCHEMA, "{}"), LIMITS));
            assertThrows(IllegalArgumentException.class, () -> validator.validate(new TypedPayload(
                    "unknown.schema", "{}"), LIMITS));
        }
        verify(host, never()).validate(any());
    }

    @Test
    void rejectsDuplicateSchemaRegistrationDuringAssembly() {
        var first = mock(AgentRuntimePayloadSchema.class);
        var second = mock(AgentRuntimePayloadSchema.class);
        when(first.schemaIds()).thenReturn(Set.of("host.connection.v1"));
        when(second.schemaIds()).thenReturn(Set.of("host.connection.v1"));
        assertThrows(IllegalArgumentException.class,
                () -> configuration.agentRuntimePayloadValidator(List.of(first, second)));
    }
}
