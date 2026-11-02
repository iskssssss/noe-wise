package com.skillforge.platform.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.platform.dto.tenant.CreateTenantMemberRequest;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

/** 成员创建的空密码在输入边界拒绝，不依赖编码器异常。 */
class MemberPasswordValidationTest {
    @Test
    void nullPasswordAndPasswordsShorterThanTwelveFailCascadedValidation() throws Exception {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var missing = new PlatformTenantController.CreateMemberCommand(1L,
                    new CreateTenantMemberRequest("member", null, java.util.List.of("developer"), null));
            assertEquals("payload.password", validator.validate(missing).iterator().next().getPropertyPath().toString());
            var absent = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                    "{\"username\":\"member\",\"roles\":[\"developer\"]}", CreateTenantMemberRequest.class);
            assertEquals("password", validator.validate(absent).iterator().next().getPropertyPath().toString());
            assertEquals("password", validator.validate(new CreateTenantMemberRequest(
                    "member", "password123", java.util.List.of("developer"), null)).iterator().next().getPropertyPath().toString());
            var valid = new PlatformTenantController.CreateMemberCommand(1L,
                    new CreateTenantMemberRequest("member", "password12345", java.util.List.of("developer"), null));
            assertTrue(validator.validate(valid).isEmpty());
            assertEquals("password", validator.validate(new CreateTenantMemberRequest("member", "        ", java.util.List.of("developer"), null)).iterator().next().getPropertyPath().toString());
        }
    }
}
