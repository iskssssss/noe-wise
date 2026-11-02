package com.skillforge.systemagent.runtime;

import com.skillforge.runtime.api.MemoryScope;
import com.skillforge.runtime.api.MemoryTypeRef;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RetentionClassRef;
import com.skillforge.runtime.memory.MemoryAccessPolicy;
import com.skillforge.runtime.memory.MemorySourcePolicy;
import com.skillforge.runtime.memory.MemoryTypeRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 平台调试空间只读召回自身账本，禁止系统 Agent 写长期记忆。 */
final class SystemAgentMemoryPolicies implements MemoryAccessPolicy, MemorySourcePolicy, MemoryTypeRegistry {
    private static final ResultRef ACCESS = new ResultRef("system-agent-memory-access.v1");
    private static final ResultRef SOURCE = new ResultRef("system-agent-memory-source.v1");
    private final Map<MemoryTypeRef, Registration> registrations = Map.of(
            new MemoryTypeRef("preference"), registration("preference",
                    Set.of(MemoryScope.SESSION, MemoryScope.USER_AGENT, MemoryScope.USER),
                    "skillforge.memory.preference.v1", false),
            new MemoryTypeRef("episode"), registration("episode",
                    Set.of(MemoryScope.USER_AGENT), "skillforge.memory.episode.v1", true));

    @Override public MemoryAccessPolicy.Decision authorize(MemoryAccessPolicy.Request request) {
        if (!request.context().identity().owner().equals(request.context().runIdentity().owner())) {
            return new MemoryAccessPolicy.Decision.Denied("system_agent_memory_owner_mismatch", ACCESS);
        }
        if (request.operation() != MemoryAccessPolicy.Operation.QUERY) {
            return new MemoryAccessPolicy.Decision.Denied("system_agent_memory_write_denied", ACCESS);
        }
        if (request.types().stream().anyMatch(type -> !registrations.containsKey(type))) {
            return new MemoryAccessPolicy.Decision.Denied("system_agent_memory_type_rejected", ACCESS);
        }
        return new MemoryAccessPolicy.Decision.Allowed(ACCESS);
    }

    @Override public MemorySourcePolicy.Decision authorize(MemorySourcePolicy.Request request) {
        return new MemorySourcePolicy.Decision.Denied("system_agent_memory_source_denied", SOURCE);
    }

    @Override public Optional<Registration> find(MemoryTypeRef type) {
        return Optional.ofNullable(registrations.get(type));
    }

    private static Registration registration(String name, Set<MemoryScope> scopes,
            String schema, boolean episodic) {
        return new Registration(new MemoryTypeRef(name), scopes, List.of(new Schema("v1", schema)),
                new RetentionClassRef("standard"), episodic, false,
                (version, payload) -> "v1".equals(version) && payload != null
                        && schema.equals(payload.schemaId())
                        ? new Validation.Allowed() : new Validation.Rejected("system_agent_memory_payload_rejected"));
    }
}
