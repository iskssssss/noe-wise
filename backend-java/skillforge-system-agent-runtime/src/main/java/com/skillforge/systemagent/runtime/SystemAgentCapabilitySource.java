package com.skillforge.systemagent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.core.agent.systemagent.PlatformAgentResources.PublishedMount;
import com.skillforge.core.agent.systemagent.SystemAgentTypes.Config;
import com.skillforge.core.agent.systemagent.SystemAgentTypes.FrozenRun;
import com.skillforge.core.capability.connector.PlatformToolCatalog;
import com.skillforge.core.capability.connector.PlatformToolHandler;
import com.skillforge.runtime.api.CapabilityRef;
import com.skillforge.runtime.api.CapabilityType;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.ToolPortal.FrozenToolTarget;
import com.skillforge.runtime.api.port.ToolPortal.ToolBinding;
import com.skillforge.runtime.api.port.ToolPortal.ToolHandle;
import com.skillforge.runtime.core.SkillActivation;
import com.skillforge.runtime.session.spi.SessionToolDirectory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;

/** 受理时持久化的无凭据来源，以及它到 Runtime 冻结契约的严格映射。 */
final class SystemAgentCapabilitySource {
    private static final String DESCRIPTOR_SCHEMA = "skillforge.runtime.capability.descriptor.v1";
    private final ObjectMapper json;
    private final ObjectProvider<PlatformToolHandler> handlers;
    private final PlatformToolCatalog catalog;

    SystemAgentCapabilitySource(ObjectMapper objectMapper, ObjectProvider<PlatformToolHandler> handlers, PlatformToolCatalog catalog) {
        this.json = Objects.requireNonNull(objectMapper).copy()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.handlers = Objects.requireNonNull(handlers);
        this.catalog = Objects.requireNonNull(catalog);
    }

    SourceSnapshot from(FrozenRun run) {
        long generation = run.version() == null ? 1L : run.version();
        String promptSourceRef = run.version() == null
                ? "system-agent:" + run.agentId() + ":debug-run:" + run.id()
                : "system-agent:" + run.agentId() + ":published:" + run.version();
        if (run.version() == null && !run.debug()) throw invalid("system_agent_publication_missing");
        return new SourceSnapshot(run.id(), promptSourceRef, generation, run.config(), run.modelRevision(), run.mounts(), inputFingerprint(run.input()));
    }

    String encode(SourceSnapshot source) {
        try { return json.writeValueAsString(source); }
        catch (Exception failure) { throw new IllegalStateException("system_agent_runtime_source_invalid", failure); }
    }

    SourceSnapshot decode(String value) {
        try { return json.readValue(value, SourceSnapshot.class); }
        catch (Exception failure) { throw new IllegalStateException("system_agent_runtime_source_invalid", failure); }
    }

    List<SkillActivation.Mount> skillMounts(SourceSnapshot source) {
        List<SkillActivation.Mount> result = new ArrayList<>();
        for (PublishedMount published : ordered(source)) {
            var mount = published.mount();
            if (!"SKILL".equals(mount.capabilityType())) continue;
            if (published.skillInstructions() == null || published.skillInstructions().isBlank()
                    || mount.tokenBudget() == null || mount.tokenBudget() <= 0) {
                throw invalid("system_agent_skill_snapshot_invalid");
            }
            CapabilityRef capability = new CapabilityRef(CapabilityType.SKILL,
                    text(published.capabilityId()), positive(published.capabilityVersionId()));
            var frozen = SkillActivation.decode(descriptor(published.descriptor()));
            if (!frozen.entrypoint().content().equals(published.skillInstructions())) throw invalid("system_agent_skill_entrypoint_changed");
            result.add(new SkillActivation.Mount("skill:" + positive(published.capabilityVersionId()), capability,
                    SkillActivation.Mode.valueOf(text(mount.activationMode())), mount.tokenBudget(), frozen));
        }
        return List.copyOf(result);
    }

    List<SessionToolDirectory.Entry> tools(SourceSnapshot source, SessionOwner owner) {
        List<SessionToolDirectory.Entry> result = new ArrayList<>();
        for (PublishedMount published : ordered(source)) {
            var mount = published.mount();
            if (!"TOOL".equals(mount.capabilityType())) continue;
            var deployment = Objects.requireNonNull(published.deployment(), "deployment");
            if (!"PLATFORM".equals(deployment.providerType())
                    || !deployment.id().equals(mount.toolDeploymentId())
                    || !deployment.providerRef().equals(textField(published.descriptor(), "tool_name"))) {
                throw invalid("system_agent_tool_binding_invalid");
            }
            PlatformToolCatalog.Entry catalog = platformTool(deployment.providerRef());
            if (!json.valueToTree(catalog.descriptor()).toString().equals(json.valueToTree(published.descriptor()).toString())) {
                throw invalid("system_agent_tool_descriptor_drift");
            }
            requireHandler(catalog.name());
            CapabilityRef capability = new CapabilityRef(CapabilityType.TOOL,
                    text(published.capabilityId()), positive(published.capabilityVersionId()));
            FrozenToolTarget target = new FrozenToolTarget(new ToolHandle("tool:" + published.capabilityVersionId()),
                    capability, deployment.id(), deployment.revision(), schema(catalog.parametersSchema()),
                    schema(catalog.outputSchema()), catalog.effect(), catalog.idempotent(),
                    Duration.ofMillis(catalog.timeoutMs()), catalog.modelInvocable(),
                    catalog.confirmationPolicy(), catalog.sensitiveFields(),
                    Set.of("platform_handler:" + catalog.name()), true, catalog.callIdentityPolicy());
            ToolBinding binding = new ToolBinding(ToolBinding.ProviderType.PLATFORM,
                    deployment.providerRef(), catalog.name());
            var discovery = new SessionToolDirectory.Discovery(binding.serverIdentity(),
                    binding.serverIdentity() + "/" + catalog.name(), catalog.name(), Optional.empty(),
                    catalog.description());
            result.add(new SessionToolDirectory.Entry(target, binding, discovery, catalog.parallelSafe()));
        }
        return List.copyOf(result);
    }

    private List<PublishedMount> ordered(SourceSnapshot source) {
        return source.mounts().stream().sorted(Comparator.comparingInt(
                (PublishedMount value) -> value.mount().priority()).reversed()).toList();
    }

    private PlatformToolCatalog.Entry platformTool(String name) {
        var matches = catalog.entries().stream().filter(value -> value.name().equals(name)).toList();
        if (matches.size() != 1) throw invalid("system_agent_platform_tool_unavailable");
        return matches.getFirst();
    }

    private void requireHandler(String name) {
        var matches = handlers.stream().filter(Objects::nonNull)
                .filter(value -> name.equals(value.toolName())).limit(2).toList();
        if (matches.size() != 1) throw invalid("system_agent_platform_tool_handler_unavailable");
    }

    private TypedPayload descriptor(Map<String, Object> value) {
        try { return new TypedPayload(DESCRIPTOR_SCHEMA, json.writeValueAsString(value)); }
        catch (Exception failure) { throw invalid("system_agent_capability_descriptor_invalid"); }
    }

    private TypedPayload schema(Map<String, Object> value) {
        try { return new TypedPayload("json-schema", json.writeValueAsString(value)); }
        catch (Exception failure) { throw invalid("system_agent_tool_schema_invalid"); }
    }

    private static String textField(Map<String, Object> value, String field) {
        Object raw = value == null ? null : value.get(field);
        return raw instanceof String text ? text(text) : "";
    }
    private static String positive(String value) {
        try { if (Long.parseLong(value) <= 0) throw new NumberFormatException(); return value; }
        catch (Exception failure) { throw invalid("system_agent_capability_id_invalid"); }
    }
    private static String text(String value) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) throw invalid("system_agent_source_text_invalid");
        return value;
    }
    private static IllegalStateException invalid(String reason) { return new IllegalStateException(reason); }

    static String inputFingerprint(String input) {
        if (input == null || input.isBlank()) throw invalid("system_agent_input_invalid");
        return com.skillforge.runtime.session.AgentExecutionCodec.digest("system-agent-input.v1\0" + input);
    }

    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    record SourceSnapshot(String runId, String promptSourceRef, long generation, Config config, long modelRevision,
                          List<PublishedMount> mounts, String inputFingerprint) {
        SourceSnapshot {
            runId = text(runId);
            promptSourceRef = text(promptSourceRef);
            if (generation <= 0 || modelRevision <= 0) throw invalid("system_agent_source_revision_invalid");
            Objects.requireNonNull(config, "config");
            mounts = mounts == null ? List.of() : List.copyOf(mounts);
            if (inputFingerprint == null || !inputFingerprint.matches("[a-f0-9]{64}")) throw invalid("system_agent_input_fingerprint_invalid");
        }
    }
}
