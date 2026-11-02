package com.skillforge.core.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CapabilityDescriptorCodecTest {

    private final CapabilityDescriptorCodec codec = new CapabilityDescriptorCodec(
            new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE));

    @Test
    void decodesEveryClosedCapabilityDescriptor() {
        SkillDescriptor skill = assertInstanceOf(SkillDescriptor.class,
                decode(CapabilityType.SKILL, skillDescriptor()));
        assertEquals(SkillActivationMode.ALWAYS, skill.activationMode());
        assertEquals("policy", skill.conflictGroup());
        assertEquals("sample-skill", skill.skillPackage().metadata().name());
        ToolVersion tool = assertInstanceOf(ToolVersion.class,
                decode(CapabilityType.TOOL, Map.ofEntries(
                Map.entry("model_invocable", true),
                Map.entry("user_invocable", false),
                Map.entry("tool_name", "customer.search"),
                Map.entry("title", "Search customers"),
                Map.entry("input_schema", Map.of()),
                Map.entry("output_schema", Map.of()),
                Map.entry("icons", List.of(Map.of(
                        "src", "https://example.test/customer.svg",
                        "mimeType", "image/svg+xml",
                        "sizes", List.of("any"),
                        "theme", "light"))),
                Map.entry("annotations", Map.of("destructiveHint", true)),
                Map.entry("effect", "READ_ONLY"),
                Map.entry("idempotent", true),
                Map.entry("confirmation_policy", "NEVER"),
                Map.entry("timeout_ms", 1_000),
                Map.entry("sensitive_fields", Set.of()),
                Map.entry("provider_requirements", Set.of()))));
        assertEquals("customer.search", tool.definition().name());
        assertEquals("Search customers", tool.definition().title().orElseThrow());
        assertEquals(true, tool.definition().annotations().orElseThrow()
                .value().get("destructiveHint"));
        assertEquals(ToolExecutionPolicy.Effect.READ_ONLY, tool.policy().effect());
        assertInstanceOf(WorkflowDescriptor.class, decode(CapabilityType.WORKFLOW, Map.ofEntries(
                Map.entry("model_invocable", true),
                Map.entry("user_invocable", true),
                Map.entry("input_schema", Map.of()),
                Map.entry("output_schema", Map.of()),
                Map.entry("supports_hitl", false),
                Map.entry("export_fields", Set.of()),
                Map.entry("timeout_ms", 1_000),
                Map.entry("concurrency_safe", true))));
        assertInstanceOf(KnowledgeSourceDescriptor.class,
                decode(CapabilityType.KNOWLEDGE_SOURCE, Map.of(
                        "model_invocable", true,
                        "user_invocable", false,
                        "source_id", "knowledge-1",
                        "source_versions", List.of("source-v1", "source-v2"),
                        "allowed_operators", Set.of("VECTOR"),
                        "freshness_policy", "PINNED",
                        "citation_required", true)));
    }

    @Test
    void defaultsOmittedAndExplicitNullToolPolicySetsToEmpty() {
        var value = toolDescriptor();
        ToolVersion omitted = assertInstanceOf(ToolVersion.class, decode(CapabilityType.TOOL, value));
        assertEquals(Set.of(), omitted.policy().sensitiveFields());
        assertEquals(Set.of(), omitted.policy().providerRequirements());

        value.put("sensitive_fields", null);
        value.put("provider_requirements", null);
        ToolVersion explicitNull = assertInstanceOf(ToolVersion.class, decode(CapabilityType.TOOL, value));
        assertEquals(omitted.policy(), explicitNull.policy());
    }

    @Test
    void acceptsToolPolicyArraysFromListsAndSets() {
        for (var values : List.of(List.of(), Set.of(),
                List.of("credential", "authorization", "credential"), Set.of("credential", "authorization"))) {
            var value = toolDescriptor();
            value.put("sensitive_fields", values);
            value.put("provider_requirements", values);
            ToolVersion tool = assertInstanceOf(ToolVersion.class, decode(CapabilityType.TOOL, value));
            var expected = values.isEmpty() ? Set.of() : Set.of("credential", "authorization");
            assertEquals(expected, tool.policy().sensitiveFields());
            assertEquals(expected, tool.policy().providerRequirements());
        }
    }

    @Test
    void rejectsToolPolicyWrongArrayShapesAndNonNormalizedStringElementsBeforeCoercion() {
        List<Object> invalid = List.of("scalar", 42, true, Map.of("value", "credential"),
                List.of(42), List.of(true), java.util.Arrays.asList((Object) null),
                List.of(Map.of("value", "credential")), List.of(List.of("credential")),
                List.of(""), List.of(" \t\n "), List.of(" credential"), List.of("credential "),
                List.of("credential", 42));
        for (String field : List.of("sensitive_fields", "provider_requirements")) {
            for (Object raw : invalid) {
                var value = toolDescriptor();
                value.put(field, raw);
                var error = assertThrows(IllegalArgumentException.class,
                        () -> decode(CapabilityType.TOOL, value), field + ": " + raw);
                assertTrue(error.getMessage().contains(field), error.getMessage());
            }
        }
    }

    @Test
    void rejectsSkillDescriptorMissingField() {
        Map<String, Object> value = skillDescriptor();
        value.remove("activation_mode");

        assertThrows(IllegalArgumentException.class,
                () -> decode(CapabilityType.SKILL, value));
    }

    @Test
    void rejectsSkillDescriptorWithExtraField() {
        Map<String, Object> value = skillDescriptor();
        value.put("provider", "forbidden");

        assertThrows(IllegalArgumentException.class,
                () -> decode(CapabilityType.SKILL, value));
    }

    @Test
    void rejectsCompleteCamelCaseSkillDescriptor() {
        Map<String, Object> value = skillDescriptor();
        value.remove("model_invocable");
        value.put("modelInvocable", true);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> decode(CapabilityType.SKILL, value));

        String message = exceptionChainMessage(exception);
        assertTrue(message.contains("modelInvocable"), message);
    }

    @Test
    void rejectsWrongSkillScalarAndListTypes() {
        Map<String, Object> scalar = skillDescriptor();
        scalar.put("package", "not-an-object");
        assertThrows(IllegalArgumentException.class,
                () -> decode(CapabilityType.SKILL, scalar));

        Map<String, Object> blockList = skillDescriptor();
        @SuppressWarnings("unchecked")
        Map<String, Object> packageValue = new LinkedHashMap<>((Map<String, Object>) blockList.get("package"));
        packageValue.put("files", List.of(List.of("not-an-object")));
        blockList.put("package", packageValue);
        assertThrows(IllegalArgumentException.class,
                () -> decode(CapabilityType.SKILL, blockList));

        Map<String, Object> intentScalar = skillDescriptor();
        intentScalar.put("intents", "assist");
        assertThrows(IllegalArgumentException.class,
                () -> decode(CapabilityType.SKILL, intentScalar));

        Map<String, Object> tokenScalar = skillDescriptor();
        tokenScalar.put("estimated_tokens", "20");
        assertThrows(IllegalArgumentException.class,
                () -> decode(CapabilityType.SKILL, tokenScalar));

        Map<String, Object> tokenDecimal = skillDescriptor();
        tokenDecimal.put("estimated_tokens", 20.0);
        assertThrows(IllegalArgumentException.class,
                () -> decode(CapabilityType.SKILL, tokenDecimal));
    }

    @Test
    void rejectsSkillPackageDigestMismatch() {
        Map<String, Object> value = skillDescriptor();
        @SuppressWarnings("unchecked")
        Map<String, Object> packageValue = new LinkedHashMap<>((Map<String, Object>) value.get("package"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files = (List<Map<String, Object>>) packageValue.get("files");
        Map<String, Object> changed = new LinkedHashMap<>(files.getFirst());
        changed.put("sha256", "0".repeat(64));
        packageValue.put("files", List.of(changed));
        value.put("package", packageValue);

        assertThrows(IllegalArgumentException.class,
                () -> decode(CapabilityType.SKILL, value));
    }

    @Test
    void rejectsInvalidSkillCollectionsAndReferences() {
        Map<String, Object> emptyBlocks = skillDescriptor();
        @SuppressWarnings("unchecked")
        Map<String, Object> emptyPackage = new LinkedHashMap<>((Map<String, Object>) emptyBlocks.get("package"));
        emptyPackage.put("files", List.of());
        emptyBlocks.put("package", emptyPackage);
        assertThrows(IllegalArgumentException.class,
                () -> decode(CapabilityType.SKILL, emptyBlocks));

        Map<String, Object> emptyIntents = skillDescriptor();
        emptyIntents.put("intents", List.of());
        assertThrows(IllegalArgumentException.class,
                () -> decode(CapabilityType.SKILL, emptyIntents));

        Map<String, Object> selfReference = skillDescriptor();
        selfReference.put("required_capabilities", List.of(
                capabilityRef("SKILL", "1", "2")));
        assertThrows(IllegalArgumentException.class,
                () -> decode(CapabilityType.SKILL, selfReference));

        Map<String, Object> duplicateReference = skillDescriptor();
        Map<String, Object> required = capabilityRef("TOOL", "lookup", "1");
        duplicateReference.put("required_capabilities", List.of(required, required));
        assertThrows(IllegalArgumentException.class,
                () -> decode(CapabilityType.SKILL, duplicateReference));
    }

    @Test
    void rejectsUnknownDescriptorFieldsForOtherCapabilityTypes() {
        assertThrows(IllegalArgumentException.class, () -> decode(
                CapabilityType.KNOWLEDGE_SOURCE, Map.of(
                        "model_invocable", true,
                        "user_invocable", false,
                        "source_id", "knowledge-1",
                        "source_versions", List.of("source-v1"),
                        "allowed_operators", Set.of("VECTOR"),
                        "freshness_policy", "PINNED",
                        "citation_required", true,
                        "provider_credential", "forbidden")));
    }

    @Test
    void knowledgeDescriptorKeepsManifestOrderAndRejectsInvalidIdentityOrVersions() {
        KnowledgeSourceDescriptor descriptor = assertInstanceOf(KnowledgeSourceDescriptor.class,
                decode(CapabilityType.KNOWLEDGE_SOURCE, Map.of(
                        "model_invocable", true,
                        "user_invocable", false,
                        "source_id", "knowledge-1",
                        "source_versions", List.of("source-v2", "source-v1"),
                        "allowed_operators", Set.of("VECTOR"),
                        "freshness_policy", "PINNED",
                        "citation_required", true)));
        assertEquals(List.of("source-v2", "source-v1"), descriptor.sourceVersions().stream().toList());

        Map<String, Object> missingSourceId = new LinkedHashMap<>();
        missingSourceId.put("model_invocable", true);
        missingSourceId.put("user_invocable", false);
        missingSourceId.put("source_versions", List.of("source-v1"));
        missingSourceId.put("allowed_operators", Set.of("VECTOR"));
        missingSourceId.put("freshness_policy", "PINNED");
        missingSourceId.put("citation_required", true);
        assertThrows(IllegalArgumentException.class,
                () -> decode(CapabilityType.KNOWLEDGE_SOURCE, missingSourceId));

        Map<String, Object> duplicateVersions = new LinkedHashMap<>(missingSourceId);
        duplicateVersions.put("source_id", "knowledge-1");
        duplicateVersions.put("source_versions", List.of("source-v1", "source-v1"));
        assertThrows(IllegalArgumentException.class,
                () -> decode(CapabilityType.KNOWLEDGE_SOURCE, duplicateVersions));

        Map<String, Object> nonNormalizedVersion = new LinkedHashMap<>(duplicateVersions);
        nonNormalizedVersion.put("source_versions", List.of(" source-v1"));
        assertThrows(IllegalArgumentException.class,
                () -> decode(CapabilityType.KNOWLEDGE_SOURCE, nonNormalizedVersion));
    }

    @Test
    void rejectsCompleteCamelCaseDescriptorUnderSnakeCasePersistenceContract() {
        Map<String, Object> camelCase = new LinkedHashMap<>();
        camelCase.put("modelInvocable", true);
        camelCase.put("userInvocable", false);
        camelCase.put("toolName", "customer.search");
        camelCase.put("inputSchema", Map.of());
        camelCase.put("outputSchema", Map.of());
        camelCase.put("effect", "READ_ONLY");
        camelCase.put("idempotent", true);
        camelCase.put("confirmationPolicy", "NEVER");
        camelCase.put("timeoutMs", 1_000);
        camelCase.put("sensitiveFields", Set.of());
        camelCase.put("providerRequirements", Set.of());
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> decode(CapabilityType.TOOL, camelCase));

        String message = exceptionChainMessage(exception);
        assertTrue(message.contains("modelInvocable"), message);
        assertTrue(message.toLowerCase(Locale.ROOT).contains("unrecognized field"), message);
    }

    private Map<String, Object> toolDescriptor() {
        return new LinkedHashMap<>(Map.of(
                "model_invocable", true, "user_invocable", false,
                "tool_name", "customer.search", "input_schema", Map.of(),
                "effect", "READ_ONLY", "idempotent", true,
                "confirmation_policy", "NEVER", "timeout_ms", 1_000));
    }

    private Map<String, Object> skillDescriptor() {
        SkillPackage skillPackage = new SkillPackageValidator().fromDirectory(Map.of(
                "SKILL.md", "---\nname: sample-skill\ndescription: Sample skill\n---\nFollow policy"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return new LinkedHashMap<>(SkillPackageDescriptorMapper.descriptor(
                skillPackage, true, false, SkillActivationMode.ALWAYS, "policy", List.of(), 20));
    }

    private Map<String, Object> capabilityRef(String type, String capabilityId, String versionId) {
        return Map.of(
                "type", type,
                "capability_id", capabilityId,
                "version_id", versionId);
    }

    private String exceptionChainMessage(Throwable exception) {
        StringBuilder message = new StringBuilder();
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current.getMessage() != null) {
                message.append(current.getMessage()).append('\n');
            }
        }
        return message.toString();
    }

    private CapabilityDescriptor decode(CapabilityType type, Map<String, Object> value) {
        return codec.decode(new CapabilityRef(type, "1", "2"), 10L,
                "name", "description", "TENANT", "PUBLISHED", "sha256:test", value);
    }
}
