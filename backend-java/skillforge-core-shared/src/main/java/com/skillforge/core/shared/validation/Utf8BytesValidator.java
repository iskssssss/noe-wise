package com.skillforge.core.shared.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.nio.charset.StandardCharsets;

/** null 留给 NotBlank/NotNull 处理，避免返回重复的字段错误。 */
public final class Utf8BytesValidator implements ConstraintValidator<Utf8Bytes, String> {
    private int max;
    @Override public void initialize(Utf8Bytes annotation) { max = annotation.max(); }
    @Override public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || value.getBytes(StandardCharsets.UTF_8).length <= max;
    }
}
