package com.skillforge.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 安装配置在写入不可变修订前完成 schema 校验和默认值物化。 */
class WorkflowInstallationConfigurationTest {

    @Test
    void rejectsMissingRequiredAndUnknownKeys() {
        Map<String, Object> definition = definition(List.of(Map.of(
                "key", "name", "type", "string", "required", true)));

        assertThrows(IllegalArgumentException.class,
                () -> WorkflowInstallationConfiguration.resolve(definition, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowInstallationConfiguration.resolve(definition, Map.of("name", "ok", "unknown", "x")));
    }

    @Test
    void rejectsValuesWithTheWrongTypeOrOutsideConfiguredRange() {
        Map<String, Object> definition = definition(List.of(
                Map.of("key", "enabled", "type", "boolean", "default", true),
                Map.of("key", "retries", "type", "integer", "min", 1, "max", 3, "default", 2)));

        assertThrows(IllegalArgumentException.class,
                () -> WorkflowInstallationConfiguration.resolve(definition, Map.of("enabled", "true")));
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowInstallationConfiguration.resolve(definition, Map.of("retries", 4)));
    }

    @Test
    void materializesEveryDeclaredDefaultInTheStoredConfiguration() {
        Map<String, Object> definition = definition(List.of(
                Map.of("key", "title", "type", "string", "default", "Default title"),
                Map.of("key", "enabled", "type", "boolean", "default", true),
                Map.of("key", "retries", "type", "integer", "min", 1, "max", 3, "default", 2),
                Map.of("key", "mode", "type", "enum", "enum_values", List.of("fast", "safe"), "default", "safe")));

        assertEquals(Map.of("title", "Default title", "enabled", true, "retries", 2, "mode", "safe"),
                WorkflowInstallationConfiguration.resolve(definition, Map.of()));
    }

    @Test
    void explicitOptionalEmptyTextFalseAndZeroOverrideNonEmptyDefaults() {
        var definition = definition(List.of(
                Map.of("key", "title", "type", "string", "required", false, "default", "Default title"),
                Map.of("key", "notes", "type", "textarea", "required", false, "default", "Default notes"),
                Map.of("key", "enabled", "type", "boolean", "default", true),
                Map.of("key", "retries", "type", "integer", "min", 0, "default", 2)));
        var supplied = Map.<String, Object>of("title", "", "notes", "", "enabled", false, "retries", 0);

        assertEquals(supplied, WorkflowInstallationConfiguration.resolve(definition, supplied));
    }

    @Test
    void missingOptionalStringAndTextareaUseTheirDeclaredDefaults() {
        var definition = definition(List.of(
                Map.of("key", "title", "type", "string", "required", false, "default", "Default title"),
                Map.of("key", "notes", "type", "textarea", "required", false, "default", "Default notes")));

        assertEquals(Map.of("title", "Default title", "notes", "Default notes"),
                WorkflowInstallationConfiguration.resolve(definition, Map.of()));
    }

    @Test
    void explicitRequiredEmptyOrWhitespaceTextIsRejectedInsteadOfFallingBackToDefault() {
        for (String type : List.of("string", "textarea")) {
            var definition = definition(List.of(
                    Map.of("key", "value", "type", type, "required", true, "default", "Default text")));
            for (String value : List.of("", " ", "\t\n")) {
                assertThrows(IllegalArgumentException.class,
                        () -> WorkflowInstallationConfiguration.resolve(definition, Map.of("value", value)),
                        type + " must reject explicitly supplied blank text");
            }
        }
    }

    private static Map<String, Object> definition(List<Map<String, Object>> schema) {
        return Map.of("nodes", List.of(), "meta", Map.of("config_schema", schema));
    }
}
