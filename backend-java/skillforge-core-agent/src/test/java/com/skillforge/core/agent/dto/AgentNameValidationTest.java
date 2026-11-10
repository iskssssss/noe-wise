package com.skillforge.core.agent.dto;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.core.agent.dto.request.CreateAgentRequest;
import com.skillforge.core.agent.dto.request.CreateAgentTemplateRequest;
import com.skillforge.core.agent.dto.request.InstallAgentTemplateRequest;
import com.skillforge.core.agent.dto.request.UpdateAgentRequest;
import com.skillforge.core.agent.dto.request.UpdateAgentTemplateRequest;
import com.skillforge.core.agent.model.AgentDistributionPolicy;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class AgentNameValidationTest {
    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll static void setup() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll static void close() { factory.close(); }

    @ParameterizedTest(name = "{0}: 名称按 Unicode 码点限制为 128 字符")
    @MethodSource("requests")
    void accepts128CodePointsAndRejects129(String type, Function<String, Object> request) {
        for (String unit : List.of("a", "😀")) {
            String accepted = unit.repeat(128);
            assertTrue(validator.validate(request.apply(accepted)).isEmpty(), type + " 应允许 128 个码点");

            String rejected = unit.repeat(129);
            var violations = validator.validate(request.apply(rejected));
            assertEquals(1, violations.size(), type + " 应拒绝 129 个码点");
            var violation = violations.iterator().next();
            assertEquals("name", violation.getPropertyPath().toString());
            assertEquals(rejected, violation.getInvalidValue());
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void optionalUpdateAndInstallNamesKeepTheirExistingAbsentAndBlankSemantics(String name) {
        var update = new UpdateAgentRequest(name, null, null, null, null, null, null, null, null);
        var template = new UpdateAgentTemplateRequest(name, null, null, null, null, null, null, null, null);
        var install = new InstallAgentTemplateRequest(1L, name, "name-validation", null, null);

        assertTrue(validator.validate(update).isEmpty());
        assertTrue(validator.validate(template).isEmpty());
        assertTrue(validator.validate(install).isEmpty());
        assertEquals(name, update.name());
        assertEquals(name, template.name());
        assertEquals(name, install.name());
    }

    private static Stream<Arguments> requests() {
        return Stream.of(
                Arguments.of("CreateAgentRequest", (Function<String, Object>) name ->
                        new CreateAgentRequest(null, name, null, null, null)),
                Arguments.of("UpdateAgentRequest", (Function<String, Object>) name ->
                        new UpdateAgentRequest(name, null, null, null, null, null, null, null, null)),
                Arguments.of("CreateAgentTemplateRequest", (Function<String, Object>) name ->
                        new CreateAgentTemplateRequest(name, null, null, null, null, null, null, null,
                                AgentDistributionPolicy.OPEN_INSTALL)),
                Arguments.of("UpdateAgentTemplateRequest", (Function<String, Object>) name ->
                        new UpdateAgentTemplateRequest(name, null, null, null, null, null, null, null, null)),
                Arguments.of("InstallAgentTemplateRequest", (Function<String, Object>) name ->
                        new InstallAgentTemplateRequest(1L, name, "name-validation", null, null)));
    }
}
