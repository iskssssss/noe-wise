package com.skillforge.core.capability;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SkillPrivilegeBoundaryTest {

    private static final List<String> FORBIDDEN_FIELDS = List.of(
            "script", "scripts", "credential", "credentials", "provider", "provider_config",
            "network", "network_access", "executable", "command", "workflow");

    private final CapabilityDescriptorCodec codec = new CapabilityDescriptorCodec(
            new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE));

    @Test
    void rejectsEveryExecutionOrCredentialField() {
        for (String forbiddenField : FORBIDDEN_FIELDS) {
            Map<String, Object> descriptor = validDescriptor();
            descriptor.put(forbiddenField, "forbidden");

            assertThrows(IllegalArgumentException.class,
                    () -> decode(descriptor), forbiddenField);
        }
    }

    @Test
    void acceptsScriptAsInertPackageResource() {
        SkillPackage skillPackage = new SkillPackageValidator().fromDirectory(Map.of(
                "SKILL.md", bytes("---\nname: sample-skill\ndescription: Sample skill\n---\n仅在需要时阅读脚本。"),
                "scripts/example.sh", bytes("curl https://example.test")));
        Map<String, Object> descriptor = new LinkedHashMap<>(SkillPackageDescriptorMapper.descriptor(
                skillPackage, true, false, SkillActivationMode.ON_DEMAND, null, List.of(), 20));

        SkillDescriptor decoded = assertInstanceOf(SkillDescriptor.class, decode(descriptor));
        org.junit.jupiter.api.Assertions.assertEquals("scripts/example.sh",
                decoded.skillPackage().files().get(1).path());
    }

    private Map<String, Object> validDescriptor() {
        SkillPackage skillPackage = new SkillPackageValidator().fromDirectory(Map.of(
                "SKILL.md", bytes("---\nname: sample-skill\ndescription: Sample skill\n---\n遵循此指令")));
        return new LinkedHashMap<>(SkillPackageDescriptorMapper.descriptor(
                skillPackage, true, false, SkillActivationMode.ON_DEMAND, null, List.of(), 20));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private SkillDescriptor decode(Map<String, Object> descriptor) {
        return (SkillDescriptor) codec.decode(
                new CapabilityRef(CapabilityType.SKILL, "skill", "v1"),
                10L,
                "Skill",
                "",
                "TENANT",
                "PUBLISHED",
                "sha256:test",
                descriptor);
    }
}
