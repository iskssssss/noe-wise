package com.skillforge.core.shared.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;

/** 字节上限用于具有编码边界的字段，字符数约束仍由 Size 维护。 */
@Constraint(validatedBy = Utf8BytesValidator.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
public @interface Utf8Bytes {
    int max();
    String message() default "UTF-8 编码超出字节上限";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
