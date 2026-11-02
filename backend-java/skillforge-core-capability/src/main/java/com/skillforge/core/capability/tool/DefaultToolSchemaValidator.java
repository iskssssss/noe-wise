package com.skillforge.core.capability.tool;

import com.skillforge.core.capability.connector.JsonSchemaValidateToolHandler;
import java.util.Map;
import java.util.Objects;

/** 复用平台 JSON Schema 校验 Handler 的 Tool schema 适配器。 */
public final class DefaultToolSchemaValidator implements ToolSchemaValidator {

    private final JsonSchemaValidateToolHandler handler;

    public DefaultToolSchemaValidator(JsonSchemaValidateToolHandler handler) {
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    @Override
    public ValidationResult validate(Map<String, Object> schema, Map<String, Object> value) {
        if (schema == null) {
            return ValidationResult.invalid("SCHEMA_INVALID");
        }
        Map<String, Object> result = handler.validateValue(value, schema);
        if (result == null || !Boolean.TRUE.equals(result.get("success"))) {
            return ValidationResult.invalid("SCHEMA_INVALID");
        }
        return Boolean.TRUE.equals(result.get("valid"))
                ? ValidationResult.ok()
                : ValidationResult.invalid("SCHEMA_VALUE_INVALID");
    }
}
