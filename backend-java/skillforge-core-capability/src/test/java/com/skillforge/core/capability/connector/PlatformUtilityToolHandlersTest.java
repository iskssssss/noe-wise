package com.skillforge.core.capability.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 平台无外部依赖工具的契约测试。 */
class PlatformUtilityToolHandlersTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void currentTimeUsesRequestedTimezone() {
        Map<String, Object> result = new GetCurrentTimeToolHandler().execute(
                Map.of("timezone", "Asia/Shanghai"), PlatformToolTestContext.create());

        assertTrue((Boolean) result.get("success"));
        assertEquals("Asia/Shanghai", result.get("timezone"));
        assertTrue(result.containsKey("epoch_millis"));
    }

    @Test
    void timeToolsDefaultToBeijingAndPreserveAbsoluteInstant() {
        var context = PlatformToolTestContext.create();
        var current = new GetCurrentTimeToolHandler().execute(Map.of(), context);
        assertEquals("Asia/Shanghai", current.get("timezone"));
        assertTrue(String.valueOf(current.get("iso_8601")).endsWith("+08:00"));
        var converter = new ConvertDatetimeToolHandler();
        assertEquals("2027-01-01T00:00:00+08:00", converter.execute(
                Map.of("datetime", "2026-12-31T16:00:00Z"), context).get("value"));
        assertEquals("2027-01-01T00:00:00+08:00", converter.execute(
                Map.of("datetime", "2027-01-01T00:00:00"), context).get("value"));
    }

    @Test
    void calculatorHonorsPrecedenceAndRejectsDivisionByZero() {
        CalculateExpressionToolHandler handler = new CalculateExpressionToolHandler();

        assertEquals("7", handler.execute(Map.of("expression", "1 + 2 * 3"),
                PlatformToolTestContext.create()).get("value"));
        assertFalse((Boolean) handler.execute(Map.of("expression", "1 / 0"),
                PlatformToolTestContext.create()).get("success"));
    }

    @Test
    void datetimeConversionUsesOnlyDeclaredFormats() {
        ConvertDatetimeToolHandler handler = new ConvertDatetimeToolHandler();
        Map<String, Object> result = handler.execute(Map.of(
                "datetime", "2026-01-01T00:00:00Z", "target_timezone", "Asia/Shanghai",
                "output_format", "ISO_LOCAL_DATE_TIME"), PlatformToolTestContext.create());

        assertTrue((Boolean) result.get("success"));
        assertEquals("2026-01-01T08:00:00", result.get("value"));
        assertFalse(handler.isError(result));
        assertFalse((Boolean) handler.execute(Map.of(
                "datetime", "2026-01-01T00:00:00Z", "output_format", "yyyy-MM-dd"),
                PlatformToolTestContext.create()).get("success"));
    }

    @ParameterizedTest
    @CsvSource({
        "2026-01-01T00:00:00Z,Asia/Shanghai,Asia/Beijing,ISO_OFFSET_DATE_TIME,target_timezone 必须是有效的 IANA 时区",
        "2026-01-01T00:00:00,Asia/Beijing,Asia/Shanghai,ISO_OFFSET_DATE_TIME,datetime 必须是 ISO 8601 时间，source_timezone 必须是有效的 IANA 时区",
        "not-a-date,Asia/Shanghai,Asia/Shanghai,ISO_OFFSET_DATE_TIME,datetime 必须是 ISO 8601 时间，source_timezone 必须是有效的 IANA 时区",
        "2026-01-01T00:00:00Z,Asia/Shanghai,Asia/Shanghai,yyyy-MM-dd,output_format 必须是 ISO_OFFSET_DATE_TIME、ISO_LOCAL_DATE_TIME、ISO_LOCAL_DATE 或 EPOCH_MILLIS"
    })
    void datetimeInputRejectionsRemainKnownToolErrors(String datetime, String source, String target,
            String format, String error) {
        var handler = new ConvertDatetimeToolHandler();
        var result = handler.execute(Map.of("datetime", datetime, "source_timezone", source,
                "target_timezone", target, "output_format", format), PlatformToolTestContext.create());

        assertEquals(Map.of("success", false, "error", error), result);
        assertTrue(handler.isError(result));
    }

    @ParameterizedTest
    @CsvSource({
        "2026-01-01T00:00:00Z,2026-01-01T08:00:00+08:00",
        "2026-01-01T03:00:00+03:00,2026-01-01T08:00:00+08:00"
    })
    void datetimeAbsoluteInputDoesNotRequireSourceTimezone(String datetime, String expected) {
        var handler = new ConvertDatetimeToolHandler();
        var result = handler.execute(Map.of("datetime", datetime, "source_timezone", "Asia/Beijing",
                "target_timezone", "Asia/Shanghai"), PlatformToolTestContext.create());

        assertEquals(Map.of("success", true, "timezone", "Asia/Shanghai", "value", expected), result);
        assertFalse(handler.isError(result));
    }

    @Test
    void jsonQueryReadsPointerAndReportsMissingValue() {
        JsonQueryToolHandler handler = new JsonQueryToolHandler(objectMapper);

        Map<String, Object> found = handler.execute(Map.of(
                "value", Map.of("subject", Map.of("name", "Ada")), "pointer", "/subject/name"),
                PlatformToolTestContext.create());
        Map<String, Object> missing = handler.execute(Map.of("value", Map.of(), "pointer", "/missing"),
                PlatformToolTestContext.create());

        assertEquals("Ada", found.get("value"));
        assertEquals(true, found.get("success"));
        assertEquals(true, found.get("found"));
        assertEquals(true, missing.get("success"));
        assertFalse((Boolean) missing.get("found"));
        assertTrue(missing.containsKey("value"));
        assertEquals(null, missing.get("value"));
        assertFalse(handler.isError(missing));
    }

    @Test
    void jsonQueryPreservesNullNodesIncludingTheRoot() throws Exception {
        var handler = new JsonQueryToolHandler(objectMapper);
        Map<String, Object> nullRoot = new LinkedHashMap<>();
        nullRoot.put("value", null);
        nullRoot.put("pointer", "");
        for (var args : List.of(nullRoot,
                Map.<String, Object>of("value", "null", "pointer", ""),
                Map.<String, Object>of("value", "{\"contact\":{\"phone\":null}}", "pointer", "/contact/phone"),
                Map.<String, Object>of("value", "[null]", "pointer", "/0"))) {
            var result = handler.execute(args, PlatformToolTestContext.create());
            assertEquals(true, result.get("success"));
            assertEquals(true, result.get("found"));
            assertTrue(result.containsKey("value"));
            assertEquals(null, result.get("value"));
            assertFalse(handler.isError(result));
            var wire = objectMapper.readTree(objectMapper.writeValueAsString(result));
            assertTrue(wire.has("value"));
            assertTrue(wire.get("value").isNull());
        }
    }

    @Test
    void jsonQueryMarksInvalidInputAsBusinessFailure() {
        var handler = new JsonQueryToolHandler(objectMapper);
        for (var args : List.of(Map.<String, Object>of(),
                Map.<String, Object>of("value", "{", "pointer", ""),
                Map.<String, Object>of("value", Map.of(), "pointer", "missing-slash"))) {
            var result = handler.execute(args, PlatformToolTestContext.create());
            assertEquals(false, result.get("success"));
            assertTrue(handler.isError(result));
            assertTrue(result.containsKey("error"));
            assertFalse(result.containsKey("found"));
        }
    }

    @Test
    void schemaValidatorReturnsAllKnownValidationErrors() {
        JsonSchemaValidateToolHandler handler = new JsonSchemaValidateToolHandler(objectMapper);
        Map<String, Object> schema = Map.of(
                "type", "object",
                "required", List.of("name", "age"),
                "properties", Map.of(
                        "name", Map.of("type", "string", "minLength", 2),
                        "age", Map.of("type", "integer", "minimum", 0)),
                "additionalProperties", false);

        Map<String, Object> valid = handler.execute(
                Map.of("value", Map.of("name", "Ada", "age", 20), "schema", schema),
                PlatformToolTestContext.create());
        Map<String, Object> invalid = handler.execute(
                Map.of("value", Map.of("name", "A", "extra", true), "schema", schema),
                PlatformToolTestContext.create());

        assertTrue((Boolean) valid.get("valid"));
        assertFalse((Boolean) invalid.get("valid"));
        assertTrue(((List<?>) invalid.get("errors")).size() >= 3);
    }

    @Test
    void schemaValidatorRejectsExtraFieldsWithoutDeclaredProperties() {
        var handler = new JsonSchemaValidateToolHandler(objectMapper);
        for (var schema : List.of(
                Map.of("type", "object", "additionalProperties", false),
                Map.of("type", "object", "properties", Map.of(), "additionalProperties", false))) {
            assertEquals(Map.of("success", true, "valid", true, "errors", List.of()),
                    handler.validateValue(Map.of(), schema));
            assertEquals(Map.of("success", true, "valid", false,
                            "errors", List.of("$: 不允许额外字段 unexpected")),
                    handler.validateValue(Map.of("unexpected", 1), schema));
        }
    }

    @Test
    void schemaValidatorAppliesAdditionalSchemaOnlyToUndeclaredProperties() {
        var handler = new JsonSchemaValidateToolHandler(objectMapper);
        for (var schema : List.of(
                Map.of("type", "object", "additionalProperties", Map.of("type", "string")),
                Map.of("type", "object", "properties", Map.of("id", Map.of("type", "integer")),
                        "additionalProperties", Map.of("type", "string")))) {
            assertEquals(Map.of("success", true, "valid", false,
                            "errors", List.of("$.label: 类型不符合 schema.type")),
                    handler.validateValue(Map.of("label", 7), schema));
            assertTrue((Boolean) handler.validateValue(Map.of("label", "ok"), schema).get("valid"));
        }
        var schema = Map.of("properties", Map.of("id", Map.of("type", "integer")),
                "additionalProperties", Map.of("type", "string"));
        assertTrue((Boolean) handler.validateValue(Map.of("id", 7, "label", "ok"), schema).get("valid"));
        assertEquals(List.of("$.id: 类型不符合 schema.type"),
                handler.validateValue(Map.of("id", "wrong", "label", "ok"), schema).get("errors"));
    }

    @Test
    void schemaValidatorRecursesIntoAdditionalPropertySchemas() {
        var handler = new JsonSchemaValidateToolHandler(objectMapper);
        var schema = Map.of("additionalProperties", Map.of("type", "object",
                "additionalProperties", Map.of("type", "string", "minLength", 2)));
        assertEquals(List.of("$.labels.name: 长度小于 schema.minLength"),
                handler.validateValue(Map.of("labels", Map.of("name", "A")), schema).get("errors"));
        assertTrue((Boolean) handler.validateValue(Map.of("labels", Map.of("name", "Ada")), schema).get("valid"));
    }

    @Test
    void schemaValidatorAllowsAdditionalFieldsByDefaultOrExplicitly() {
        var handler = new JsonSchemaValidateToolHandler(objectMapper);
        for (var schema : List.of(Map.of(), Map.of("additionalProperties", true),
                Map.of("additionalProperties", Map.of()))) {
            assertTrue((Boolean) handler.validateValue(Map.of("extra", List.of(1, "ok")), schema).get("valid"));
        }
    }

    @Test
    void schemaValidatorRejectsInvalidAdditionalPropertiesConstraint() {
        var handler = new JsonSchemaValidateToolHandler(objectMapper);
        for (var constraint : List.of(7, "false", List.of())) {
            assertEquals(List.of("$: schema.additionalProperties 必须是布尔值或对象"),
                    handler.validateValue(Map.of(), Map.of("additionalProperties", constraint)).get("errors"));
        }
    }

    @Test
    void schemaValidatorEnforcesArrayBoundsEvenWithoutItemSchema() {
        var handler = new JsonSchemaValidateToolHandler(objectMapper);
        var schema = Map.of("type", "array", "minItems", 1, "maxItems", 2);
        assertTrue((Boolean) handler.validateValue(List.of("业务编号"), schema).get("valid"));
        assertFalse((Boolean) handler.validateValue(List.of(), schema).get("valid"));
        assertFalse((Boolean) handler.validateValue(List.of(1, 2, 3), schema).get("valid"));
        assertFalse((Boolean) handler.validateValue(List.of(1), Map.of("type", "array", "minItems", -1)).get("valid"));
    }

    @Test
    void templateRendererOnlyResolvesDataPaths() {
        TemplateRenderToolHandler handler = new TemplateRenderToolHandler();
        Map<String, Object> result = handler.execute(Map.of(
                "template", "您好，{{user.name}}；第一个项目：{{items.0}}；{{unknown}}",
                "data", Map.of("user", Map.of("name", "Ada"), "items", List.of("申请"))),
                PlatformToolTestContext.create());

        assertEquals("您好，Ada；第一个项目：申请；", result.get("rendered"));
    }
}
