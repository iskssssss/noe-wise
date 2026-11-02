package com.skillforge.core.capability.tool;

import java.util.Map;

/** Tool JSON Schema 校验边界。 */
public interface ToolSchemaValidator {

    ValidationResult validate(Map<String, Object> schema, Map<String, Object> value);

    record ValidationResult(boolean valid, String reason) {
        public static ValidationResult ok() {
            return new ValidationResult(true, null);
        }
        public static ValidationResult invalid(String reason) {
            return new ValidationResult(false, reason);
        }
    }
}
