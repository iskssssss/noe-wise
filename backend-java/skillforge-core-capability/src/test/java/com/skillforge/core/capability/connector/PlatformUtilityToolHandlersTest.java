package com.skillforge.core.capability.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

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
        assertFalse((Boolean) handler.execute(Map.of(
                "datetime", "2026-01-01T00:00:00Z", "output_format", "yyyy-MM-dd"),
                PlatformToolTestContext.create()).get("success"));
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
