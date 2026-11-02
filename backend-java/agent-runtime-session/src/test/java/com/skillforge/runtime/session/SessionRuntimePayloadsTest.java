package com.skillforge.runtime.session;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.json.CodecLimits;
import org.junit.jupiter.api.Test;

class SessionRuntimePayloadsTest {
    private static final CodecLimits LIMITS = new CodecLimits(4_096, 128, 16);
    private final SessionRuntimePayloads payloads = new SessionRuntimePayloads();

    @Test
    void allOfRequiresEveryBranchAndSiblingForInputAndOutput() {
        var schema = new TypedPayload("json-schema", """
                {"type":"object","required":["id"],"allOf":[
                  {"properties":{"id":{"type":"string","minLength":2}}},
                  {"anyOf":[{"required":["name"]},{"required":["flag"]}]},
                  {"properties":{"flag":{"type":"boolean","nullable":true}}}]}
                """);
        for (String input : java.util.List.of("{}", "{\"id\":\"x\",\"name\":\"ok\"}", "{\"id\":\"ok\"}", "{\"id\":\"ok\",\"flag\":1}")) {
            var payload = new TypedPayload("application/json", input);
            assertThrows(IllegalArgumentException.class, () -> SessionRuntimePayloads.validateAgainstSchema(schema, payload, LIMITS));
            var output = new TypedPayload(SessionRuntimePayloads.TOOL_RESULT_SCHEMA, "{\"content\":[],\"structuredContent\":" + input + "}");
            assertThrows(IllegalArgumentException.class, () -> SessionRuntimePayloads.validateToolOutput(schema, output, LIMITS));
        }
        for (String input : java.util.List.of("{\"id\":\"ok\",\"name\":\"yes\"}", "{\"id\":\"ok\",\"flag\":null}")) {
            assertDoesNotThrow(() -> SessionRuntimePayloads.validateAgainstSchema(schema, new TypedPayload("application/json", input), LIMITS));
            assertDoesNotThrow(() -> SessionRuntimePayloads.validateToolOutput(schema,
                    new TypedPayload(SessionRuntimePayloads.TOOL_RESULT_SCHEMA, "{\"content\":[],\"structuredContent\":" + input + "}"), LIMITS));
        }
        for (String source : java.util.List.of("{\"allOf\":[{},{}]}",
                "{\"allOf\":[{\"description\":\"annotation\"},{\"title\":\"title\"}]}")) {
            var empty = new TypedPayload("json-schema", source);
            assertDoesNotThrow(() -> SessionRuntimePayloads.validateToolOutput(empty,
                    new TypedPayload(SessionRuntimePayloads.TOOL_RESULT_SCHEMA, "{\"content\":[]}"), LIMITS));
        }
        for (String source : java.util.List.of("{\"allOf\":[]}", "{\"allOf\":[true]}",
                "{\"allOf\":[{\"pattern\":\"x\"}]}", "{\"allOf\":[{\"nullable\":true}]}", "{\"allOf\":[{\"items\":[{}]}]}",
                "{\"allOf\":[{\"$ref\":\"#/$defs/missing\"}]}", "{\"allOf\":[{}],\"minItems\":2}")) {
            assertThrows(IllegalArgumentException.class, () -> payloads.validate(new TypedPayload("json-schema", source), LIMITS));
        }
    }

    @Test
    void allOfPreservesSiblingRequiredConstraint() {
        var schema = new TypedPayload("json-schema", "{\"type\":\"object\",\"allOf\":[{\"type\":\"object\"}],\"required\":[\"id\"]}");
        var invalid = new TypedPayload("application/json", "{}");
        assertThrows(IllegalArgumentException.class, () -> SessionRuntimePayloads.validateAgainstSchema(schema, invalid, LIMITS));
    }

    @Test void dynamicPayloadTreePreservesExactNumbersAndControlStrings() {
        var tree = SessionRuntimePayloadJson.parse(
                "{\"revision\":\"9223372036854775807\",\"count\":9007199254740993123456789,\"ratio\":0.12345678901234567890123456789}", LIMITS);
        assertEquals(new java.math.BigInteger("9007199254740993123456789"), tree.path("count").bigIntegerValue());
        assertEquals(new java.math.BigDecimal("0.12345678901234567890123456789"), tree.path("ratio").decimalValue());
        assertEquals("9223372036854775807", tree.path("revision").textValue());
        assertEquals("{\"revision\":\"9223372036854775807\",\"count\":9007199254740993123456789,\"ratio\":0.12345678901234567890123456789}",
                SessionRuntimePayloadJson.write(tree));
    }

    @Test void dynamicPayloadKeepsDomainTreeOrderAndStableMapOrder() {
        var tree = AgentExecutionCodec.object();
        tree.put("schemaId", "preview"); tree.put("characters", "12");
        assertEquals("{\"schemaId\":\"preview\",\"characters\":\"12\"}", SessionRuntimePayloadJson.write(tree));
        var map = new java.util.LinkedHashMap<String, Object>();
        map.put("schemaId", "preview"); map.put("characters", "12");
        assertEquals("{\"characters\":\"12\",\"schemaId\":\"preview\"}", SessionRuntimePayloadJson.write(map));
    }

    @Test void dynamicPayloadKeyBudgetAndMalformedInputsKeepDomainRejection() {
        assertDoesNotThrow(() -> SessionRuntimePayloadJson.parse("{\"a\":1}", new CodecLimits(32, 3, 2)));
        assertThrows(IllegalArgumentException.class,
                () -> SessionRuntimePayloadJson.parse("{\"a\":1}", new CodecLimits(32, 2, 2)));
        for (String invalid : new String[]{"{\"a\":1,\"a\":2}", "{} {}", "\"\\ud800\""}) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> SessionRuntimePayloadJson.parse(invalid, LIMITS));
            assertEquals("session_runtime_payload_invalid", failure.getMessage());
        }
    }

    @Test void hostInputValidatorPreservesSchemaAndPayloadRejections() {
        var inputs = new SessionRuntimePayloads.InputValidator(4_096, 128, 16);
        var schema = new TypedPayload("json-schema", "{\"type\":\"object\",\"properties\":{\"count\":{\"type\":\"integer\"}},\"required\":[\"count\"],\"additionalProperties\":false}");
        assertDoesNotThrow(() -> inputs.validate(schema, new TypedPayload("application/json", "{\"count\":1}")));
        for (String invalid : new String[]{"{}", "{\"count\":\"1\"}", "{\"count\":1,\"extra\":true}"})
            assertThrows(IllegalArgumentException.class, () -> inputs.validate(schema, new TypedPayload("application/json", invalid)));
        assertThrows(IllegalArgumentException.class, () -> inputs.validate(
                new TypedPayload("application/json", schema.canonicalJson()), new TypedPayload("application/json", "{\"count\":1}")));
    }

    @Test void hostInputValidatorKeepsConstructionAndParsingQuotas() {
        assertThrows(IllegalArgumentException.class, () -> new SessionRuntimePayloads.InputValidator(0, 128, 16));
        assertThrows(IllegalArgumentException.class, () -> new SessionRuntimePayloads.InputValidator(4_096, 128, 129));
        var inputs = new SessionRuntimePayloads.InputValidator(16, 128, 16);
        var schema = new TypedPayload("json-schema", "{}");
        assertThrows(IllegalArgumentException.class, () -> inputs.validate(schema,
                new TypedPayload("application/json", "\"this exceeds the configured input capacity\"")));
    }

    @Test void schemaDiagnosticLocatesExtraPlanFieldInFinalizeWithoutChangingValidation() {
        var schema = new TypedPayload("json-schema", """
                {"type":"object","properties":{"deliverables":{"type":"array","items":{
                  "type":"object","properties":{"key":{"type":"string"},"representations":{"type":"array"}},
                  "required":["key","representations"],"additionalProperties":false}}},"required":["deliverables"]}
                """);
        var invalid = new TypedPayload("application/json", """
                {"deliverables":[{"key":"report","representations":[],"formats":[{"format":"json"}]}]}
                """);
        assertEquals(new SessionRuntimePayloads.SchemaViolation("/deliverables/0/formats", "additionalProperties"),
                SessionRuntimePayloads.inputSchemaViolation(schema, invalid, LIMITS).orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> SessionRuntimePayloads.validateAgainstSchema(schema, invalid, LIMITS));
        var corrected = new TypedPayload("application/json", "{\"deliverables\":[{\"key\":\"report\",\"representations\":[]}]}");
        assertDoesNotThrow(() -> SessionRuntimePayloads.validateAgainstSchema(schema, corrected, LIMITS));
        assertEquals(java.util.Optional.empty(), SessionRuntimePayloads.inputSchemaViolation(schema, corrected, LIMITS));
    }

    @Test void schemaDiagnosticsCoverConstraintsWithoutReturningValuesOrEnumCandidates() {
        String[][] cases = {
                {"{\"type\":\"object\",\"required\":[\"missing\"]}", "{}", "/missing", "required"},
                {"{\"type\":\"integer\"}", "\"private-value\"", "", "type"},
                {"{\"enum\":[\"private-candidate\"]}", "\"private-value\"", "", "enum"},
                {"{\"minLength\":2}", "\"😀\"", "", "minLength"},
                {"{\"maxLength\":1}", "\"😀a\"", "", "maxLength"},
                {"{\"minimum\":3}", "2", "", "minimum"},
                {"{\"maximum\":3}", "4", "", "maximum"}
        };
        for (var item : cases) {
            var schema = new TypedPayload("json-schema", item[0]);
            var value = new TypedPayload("application/json", item[1]);
            var diagnostic = SessionRuntimePayloads.inputSchemaViolation(schema, value, LIMITS).orElseThrow();
            assertEquals(new SessionRuntimePayloads.SchemaViolation(item[2], item[3]), diagnostic);
            assertEquals(false, diagnostic.toString().contains("private"));
            assertThrows(IllegalArgumentException.class, () -> SessionRuntimePayloads.validateAgainstSchema(schema, value, LIMITS));
        }
    }

    @Test void schemaDiagnosticTraversesAdditionalSchemasAndNestedArrays() {
        var schema = new TypedPayload("json-schema", """
                {"type":"object","properties":{"groups":{"type":"array","items":{"type":"array","items":{
                  "type":"object","additionalProperties":{"type":"integer"}}}}}}
                """);
        var value = new TypedPayload("application/json", "{\"groups\":[[{\"count\":1},{\"count\":\"secret-value\"}]]}");
        assertEquals(new SessionRuntimePayloads.SchemaViolation("/groups/0/1/count", "type"),
                SessionRuntimePayloads.inputSchemaViolation(schema, value, LIMITS).orElseThrow());
    }

    @Test void schemaDiagnosticHidesUnsafeDynamicNamesAndBoundsDeepPaths() {
        var schema = new TypedPayload("json-schema", "{\"type\":\"object\",\"additionalProperties\":false}");
        for (String name : java.util.List.of("token=do-not-echo/secret", "credential".repeat(20), "line\nbreak")) {
            var value = new TypedPayload("application/json", SessionRuntimePayloadJson.write(java.util.Map.of(name, "secret-value")));
            assertEquals(new SessionRuntimePayloads.SchemaViolation("/_", "additionalProperties"),
                    SessionRuntimePayloads.inputSchemaViolation(schema, value, LIMITS).orElseThrow());
        }
        String nestedSchema = "{\"type\":\"integer\"}", nestedValue = "\"private-value\"";
        String name = "abcdefghijabcdefghijabcdefghij";
        for (int depth = 0; depth < 12; depth++) {
            nestedSchema = "{\"properties\":{\"" + name + "\":" + nestedSchema + "}}";
            nestedValue = "{\"" + name + "\":" + nestedValue + "}";
        }
        var diagnostic = SessionRuntimePayloads.inputSchemaViolation(new TypedPayload("json-schema", nestedSchema),
                new TypedPayload("application/json", nestedValue), new CodecLimits(16_384, 1_024, 64)).orElseThrow();
        assertEquals("type", diagnostic.keyword());
        assertEquals(true, diagnostic.path().length() <= 256);
        assertEquals(true, diagnostic.path().endsWith("/_"));
    }

    @Test void schemaDiagnosticFallsBackWithoutInventingAValidationFailure() {
        var schema = new TypedPayload("json-schema", "{\"type\":\"integer\"}");
        var value = new TypedPayload("application/json", "1");
        assertEquals(java.util.Optional.empty(), SessionRuntimePayloads.inputSchemaViolation(schema, value, LIMITS));
        assertEquals(java.util.Optional.empty(), SessionRuntimePayloads.inputSchemaViolation(null, value, LIMITS));
        assertEquals(java.util.Optional.empty(), SessionRuntimePayloads.inputSchemaViolation(schema, null, LIMITS));
        assertEquals(java.util.Optional.empty(), SessionRuntimePayloads.inputSchemaViolation(schema, value, null));
        assertEquals(java.util.Optional.empty(), SessionRuntimePayloads.inputSchemaViolation(new TypedPayload("json-schema", "[]"), value, LIMITS));
        assertEquals(java.util.Optional.empty(), SessionRuntimePayloads.inputSchemaViolation(schema, new TypedPayload("application/json", "{"), LIMITS));
        assertEquals(java.util.Optional.empty(), SessionRuntimePayloads.inputSchemaViolation(new TypedPayload("application/json", "{}"), value, LIMITS));
        assertEquals(java.util.Optional.empty(), SessionRuntimePayloads.inputSchemaViolation(schema, value, new CodecLimits(1, 1, 1)));
    }

    /** 注册表只放行规范的已知拒绝事实，附加私有字段或伪造未知原因仍失败。 */
    @Test void knownSingleControlRefusalIsRegisteredWithoutAcceptingArbitraryFailures() {
        assertDoesNotThrow(() -> payloads.validate(ModelDecisionProjectionPolicy.SINGLE_CONTROL_REQUIRED, LIMITS));
        assertDoesNotThrow(() -> payloads.validate(ModelDecisionProjectionPolicy.TOOL_NOT_AVAILABLE, LIMITS));
        for (String wire : java.util.List.of(
                "{\"reason\":\"unknown\",\"type\":\"decision_rejected\"}",
                "{\"reason\":\"CONTROL_ACTION_REQUIRES_SINGLE_CALL\",\"secret\":\"private\",\"type\":\"decision_rejected\"}",
                "{\"reason\":\"TOOL_NOT_AVAILABLE\",\"tool_name\":\"private\",\"type\":\"decision_rejected\"}")) {
            assertThrows(IllegalArgumentException.class, () -> payloads.validate(
                    new TypedPayload(ModelDecisionProjectionPolicy.DECISION_SCHEMA, wire), LIMITS));
        }
    }

    @Test void knowledgeChunksAcceptAdapterEscapedMultilineTextWithoutRelaxingPayloadBounds() {
        String schema = "skillforge.runtime.knowledge.chunk.v1";
        // JdbcKnowledgePortal 的 KnowledgeJson.quote 对控制字符使用 Unicode 转义。
        for (String body : java.util.List.of("{\"text\":\"# MarkItDown\\u000a\\u000aSupports PDF\\u0009and Word\"}",
                "{\"text\":\"# MarkItDown\\n\\nSupports PDF\\tand Word\"}")) {
            assertDoesNotThrow(() -> payloads.validate(new TypedPayload(schema, body), LIMITS));
        }
        for (String body : java.util.List.of("[]", "{\"text\":null}", "{\"text\":42}",
                "{\"text\":\"a\",\"text\":\"b\"}", "{\"text\":\"a\"}{}",
                "{\"text\":\"\\ud800\"}", "{\"text\":\"raw\nnewline\"}",
                "{\"text\":\"" + "x".repeat(4_096) + "\"}")) {
            assertThrows(IllegalArgumentException.class,
                    () -> payloads.validate(new TypedPayload(schema, body), LIMITS));
        }
    }

    @Test void mcpContinuationRegistersBoundedRequestObjectsWithoutAcceptingUnknownSchemas() {
        String schema = "skillforge.runtime.tool.mcp-input-request.v1";
        for (String body : java.util.List.of("{}",
                "{\"message\":\"补充数量\",\"requestedSchema\":{\"type\":\"object\",\"properties\":{\"count\":{\"type\":\"number\",\"minimum\":0.25}}}}",
                "{\"context\":{\"integer\":9007199254740993,\"fraction\":0.1234567890123456789}}")) {
            assertDoesNotThrow(() -> payloads.validate(new TypedPayload(schema, body), LIMITS));
        }
        for (String body : java.util.List.of("null", "[]", "27", "\"value\"", "true",
                "{\"n\":1,\"n\":2}", "{}{}", "{\"n\":NaN}")) {
            assertThrows(IllegalArgumentException.class,
                    () -> payloads.validate(new TypedPayload(schema, body), LIMITS));
        }
        assertThrows(IllegalArgumentException.class, () -> payloads.validate(new TypedPayload(schema,
                "{\"text\":\"" + "x".repeat(4_096) + "\"}"), LIMITS));
        assertThrows(IllegalArgumentException.class, () -> payloads.validate(new TypedPayload(schema,
                "{\"nested\":" + "[".repeat(17) + "0" + "]".repeat(17) + "}"), LIMITS));
        assertThrows(IllegalArgumentException.class, () -> payloads.validate(new TypedPayload(schema,
                "{\"nodes\":[" + "0,".repeat(128) + "0]}"), LIMITS));
        assertThrows(IllegalArgumentException.class, () -> payloads.validate(new TypedPayload(
                "skillforge.runtime.tool.mcp-input-request.v2", "{}"), LIMITS));
    }

    @Test void registersWorkflowRecoveryPayloadsWithoutAcceptingUnknownSignalFields() {
        String signal = com.skillforge.runtime.session.spi.SessionWorkflowWakeups.SIGNAL_SCHEMA;
        for (String body : java.util.List.of(
                "{\"kind\":\"async-task-timeout\",\"deadlineAt\":\"2026-09-27T00:00:00Z\"}",
                "{\"kind\":\"node-result\",\"nodeRun\":\"real:node\",\"resultRef\":\"real:result\"}",
                "{\"kind\":\"script\",\"taskId\":\"task:1\"}")) {
            assertDoesNotThrow(() -> payloads.validate(new TypedPayload(signal, body), LIMITS));
        }
        for (String body : java.util.List.of(
                "{\"kind\":\"async-task-timeout\",\"deadlineAt\":\"invalid\"}",
                "{\"kind\":\"node-result\",\"factRef\":\"fact:1\",\"result\":{}}",
                "{\"kind\":\"node-result\",\"factRef\":\"fact:1\"}",
                "{\"kind\":\"node-result\",\"nodeRun\":\"real:node\",\"resultRef\":\"real:result\",\"extra\":true}",
                "{\"kind\":\"callback\",\"factRef\":\"fact:1\"}")) {
            assertThrows(IllegalArgumentException.class, () -> payloads.validate(new TypedPayload(signal, body), LIMITS));
        }
        String callback = com.skillforge.runtime.session.spi.SessionWorkflowWakeups.ASYNC_RESPONSE_SCHEMA;
        assertDoesNotThrow(() -> payloads.validate(new TypedPayload(callback, "{\"status\":\"completed\",\"result\":{\"answer\":42}}"), LIMITS));
        assertDoesNotThrow(() -> payloads.validate(new TypedPayload(callback, "{\"status\":\"failed\",\"reason_code\":\"task_failed\"}"), LIMITS));
        assertThrows(IllegalArgumentException.class, () -> payloads.validate(new TypedPayload(callback, "{\"status\":\"completed\",\"result\":null}"), LIMITS));
    }

    @Test void planReviewContinuationRegistersBothRequestAndResponse() {
        String schema = "skillforge.runtime.plan-review-request.v1";
        String request = "{\"options\":[\"approve\",\"keep_planning\"],\"plan\":\"# 计划\\n\\n实施与验收\",\"title\":\"计划\"}";
        assertDoesNotThrow(() -> payloads.validate(new TypedPayload(schema, request), LIMITS));
        for (String malformed : java.util.List.of("{}",
                request.replace("\"plan\":\"# 计划\\n\\n实施与验收\"", "\"plan\":null"),
                request.replace("\"title\":\"计划\"", "\"title\":\" \""),
                request.replace("\"approve\",\"keep_planning\"", "\"approve\",\"execute\""),
                request.replace("\"approve\",\"keep_planning\"", "\"approve\""),
                request.replace("\"title\":\"计划\"", "\"title\":\"计划\",\"extra\":true"))) {
            assertThrows(IllegalArgumentException.class,
                    () -> payloads.validate(new TypedPayload(schema, malformed), LIMITS));
        }
    }

    @Test void planReviewContinuationUsesRegisteredClosedResponseSchema() {
        String schema = SessionRuntimePayloads.PLAN_REVIEW_RESPONSE_SCHEMA;
        for (String decision : java.util.List.of("approve", "keep_planning")) {
            assertDoesNotThrow(() -> payloads.validate(new TypedPayload(schema,
                    "{\"checkpointRevision\":\"7\",\"decision\":\"" + decision + "\",\"feedback\":\"补充验收步骤\"}"), LIMITS));
        }
        for (String response : java.util.List.of("{}", "{\"decision\":\"approve\"}",
                "{\"checkpointRevision\":\"7\",\"decision\":\"execute\",\"feedback\":\"\"}",
                "{\"checkpointRevision\":\"7\",\"decision\":\"approve\",\"feedback\":null}",
                "{\"checkpointRevision\":\"7\",\"decision\":\"approve\",\"feedback\":\"\",\"admin\":true}")) {
            assertThrows(IllegalArgumentException.class,
                    () -> payloads.validate(new TypedPayload(schema, response), LIMITS));
        }
        for (String revision : java.util.List.of("7", "7.0", "null", "\"0\"", "\"07\"", "\"+7\"",
                "\"-7\"", "\"9223372036854775808\"")) {
            assertThrows(IllegalArgumentException.class, () -> payloads.validate(new TypedPayload(schema,
                    "{\"checkpointRevision\":"+revision+",\"decision\":\"approve\",\"feedback\":\"\"}"), LIMITS));
        }
        assertThrows(IllegalArgumentException.class, () -> payloads.validate(
                new TypedPayload("skillforge.runtime.plan-review-response.v1", "{\"decision\":\"approve\",\"feedback\":\"\"}"), LIMITS));
    }

    @Test
    void acceptsExternalNumbersMcpEnvelopeAndWorkflowMessages() {
        for (var payload : java.util.List.of(
                new TypedPayload("application/json", "{\"n\":9007199254740993,\"fraction\":0.1234567890123456789}"),
                new TypedPayload("json-schema", "{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"number\",\"minimum\":0.25}}}"),
                new TypedPayload(SessionRuntimePayloads.TOOL_RESULT_SCHEMA, "{\"content\":[{\"type\":\"image\",\"data\":\"AQID\"}],\"structuredContent\":{\"answer\":42},\"isError\":false}"),
                new TypedPayload("skillforge.runtime.workflow.engine-result.v1", "{}"),
                new TypedPayload("skillforge.runtime.workflow.engine-result.v1", "{\"answer\":42,\"score\":0.75}"),
                new TypedPayload("skillforge.runtime.workflow.engine-result.v1", "{\"messages\":[{\"role\":\"assistant\",\"score\":0.75}],\"answer\":42}"))) {
            assertDoesNotThrow(() -> payloads.validate(payload, LIMITS));
        }
    }

    @Test
    void rejectsMalformedExternalPayloadsAndUnsupportedSchemas() {
        for (String schema : java.util.List.of("{\"required\":true}", "{\"type\":\"nope\"}",
                "{\"minimum\":\"zero\"}")) {
            assertThrows(IllegalArgumentException.class, () -> payloads.validate(new TypedPayload("json-schema", schema), LIMITS));
        }
        for (var payload : java.util.List.of(
                new TypedPayload("application/json", "{\"n\":1,\"n\":2}"),
                new TypedPayload("application/json", "{}{}"),
                new TypedPayload(SessionRuntimePayloads.TOOL_RESULT_SCHEMA, "{\"content\":[],\"structuredContent\":null}"),
                new TypedPayload(SessionRuntimePayloads.TOOL_RESULT_SCHEMA, "{\"content\":[{\"type\":2}]}"),
                new TypedPayload("skillforge.runtime.workflow.engine-result.v1", "{\"messages\":[\"text\"]}"),
                new TypedPayload("skillforge.runtime.workflow.engine-result.v1", "[]"))) {
            assertThrows(IllegalArgumentException.class, () -> payloads.validate(payload, LIMITS));
        }
    }

    @Test
    void projectsUnknownKeywordsAndReferencesWhilePreservingChoiceConstraints() {
        assertDoesNotThrow(() -> payloads.validate(new TypedPayload("json-schema", "{\"pattern\":\".*\"}"), LIMITS));
        assertDoesNotThrow(() -> payloads.validate(
                new TypedPayload("json-schema", "{\"properties\":{\"absent\":{\"unknownConstraint\":true}}}"), LIMITS));
        var nullable = new TypedPayload("json-schema",
                "{\"title\":\"At\",\"anyOf\":[{\"type\":\"string\",\"format\":\"date-time\"},{\"type\":\"null\"}]}");
        assertDoesNotThrow(() -> payloads.validate(nullable, LIMITS));
        assertDoesNotThrow(() -> SessionRuntimePayloads.validateAgainstSchema(nullable,
                new TypedPayload("application/json", "\"2026-09-17T12:32:54Z\""), LIMITS));
        assertDoesNotThrow(() -> SessionRuntimePayloads.validateAgainstSchema(nullable,
                new TypedPayload("application/json", "null"), LIMITS));
        assertThrows(IllegalArgumentException.class, () -> SessionRuntimePayloads.validateAgainstSchema(nullable,
                new TypedPayload("application/json", "1"), LIMITS));
        var mcpOutput = new TypedPayload("json-schema", """
                {"type":"object","$defs":{"Rec":{"type":"object","required":["id"],"properties":{"id":{"type":"string"}},"additionalProperties":false},"Err":{"type":"object","properties":{"error":{"type":"string"}},"additionalProperties":false}},"anyOf":[{"$ref":"#/$defs/Rec"},{"$ref":"#/$defs/Err"}]}
                """);
        assertDoesNotThrow(() -> payloads.validate(mcpOutput, LIMITS));
        assertThrows(IllegalArgumentException.class, () -> SessionRuntimePayloads.validateToolOutput(mcpOutput,
                new TypedPayload(SessionRuntimePayloads.TOOL_RESULT_SCHEMA, "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}"),
                LIMITS));
        for (String content : java.util.List.of("{\"id\":\"record\"}", "{\"error\":\"failed\"}")) {
            assertDoesNotThrow(() -> SessionRuntimePayloads.validateToolOutput(mcpOutput,
                    new TypedPayload(SessionRuntimePayloads.TOOL_RESULT_SCHEMA,
                            "{\"content\":[],\"structuredContent\":" + content + "}"), LIMITS));
        }
        for (String content : java.util.List.of("{\"id\":1}", "{\"extra\":true}", "null")) {
            assertThrows(IllegalArgumentException.class, () -> SessionRuntimePayloads.validateToolOutput(mcpOutput,
                    new TypedPayload(SessionRuntimePayloads.TOOL_RESULT_SCHEMA,
                            "{\"content\":[],\"structuredContent\":" + content + "}"), LIMITS));
        }
        var input = new TypedPayload("json-schema",
                "{\"type\":\"object\",\"title\":\"ListArgs\",\"properties\":{\"page\":{\"type\":\"integer\",\"minimum\":1}},\"additionalProperties\":false}");
        assertDoesNotThrow(() -> SessionRuntimePayloads.validateAgainstSchema(input,
                new TypedPayload("application/json", "{\"page\":1}"), LIMITS));
        assertThrows(IllegalArgumentException.class, () -> SessionRuntimePayloads.validateAgainstSchema(input,
                new TypedPayload("application/json", "{\"page\":1,\"extra\":true}"), LIMITS));
    }

    /** 浏览器判别联合保留命令闭合结构；参数字符串、错层级和未知操作不能绕过预校验。 */
    @Test void browserCommandChoiceRetainsProjectedFieldsAndRejectsMalformedCommands() throws Exception {
        var schema = browserCommandSchema();
        var limits = new CodecLimits(8_192, 512, 32);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var projected = SessionRuntimeJsonSchema.project(mapper.readTree(schema.canonicalJson()));
        var choices = projected.path("properties").path("command").path("oneOf");
        assertEquals(3, choices.size());
        assertEquals("navigate", choices.get(0).path("properties").path("operation").path("enum").get(0).asText());
        assertEquals(mapper.readTree("[\"operation\",\"parameters\"]"), choices.get(0).path("required"));
        assertEquals(false, choices.get(0).path("additionalProperties").asBoolean());
        assertEquals("string", choices.get(0).path("properties").path("parameters").path("properties").path("url").path("type").asText());
        assertEquals(false, choices.get(0).path("properties").path("parameters").path("additionalProperties").asBoolean());
        for (String command : java.util.List.of(
                "{\"operation\":\"navigate\",\"parameters\":{\"url\":\"https://example.invalid\",\"tab_id\":7}}",
                "{\"operation\":\"observe\",\"parameters\":{}}",
                "{\"operation\":\"screenshot\",\"parameters\":{}}")) {
            var input = new TypedPayload("application/json", "{\"task\":\"task\",\"command\":" + command + "}");
            assertDoesNotThrow(() -> SessionRuntimePayloads.validateAgainstSchema(schema, input, limits));
            assertEquals(java.util.Optional.empty(), SessionRuntimePayloads.inputSchemaViolation(schema, input, limits));
        }
        for (String command : java.util.List.of("{}", "\"private-script\"", "null",
                "{\"operation\":\"observe\"}",
                "{\"operation\":\"observe\",\"parameters\":\"{}\"}",
                "{\"operation\":\"navigate\",\"url\":\"private-address\",\"parameters\":{}}",
                "{\"operation\":\"unknown-private-value\",\"parameters\":{}}",
                "{\"operation\":\"navigate\",\"parameters\":{\"url\":5}}",
                "{\"operation\":\"navigate\",\"parameters\":{\"tab_id\":\"7\"}}",
                "{\"operation\":\"observe\",\"parameters\":{\"private-field\":true}}",
                "{\"operation\":\"screenshot\",\"parameters\":{},\"extra\":true}")) {
            var input = new TypedPayload("application/json", "{\"task\":\"task\",\"command\":" + command + "}");
            assertThrows(IllegalArgumentException.class, () -> SessionRuntimePayloads.validateAgainstSchema(schema, input, limits));
            assertEquals(new SessionRuntimePayloads.SchemaViolation("/command", "oneOf"),
                    SessionRuntimePayloads.inputSchemaViolation(schema, input, limits).orElseThrow());
        }
        for (String input : java.util.List.of("{}", "{\"task\":\"task\",\"operation\":\"observe\",\"parameters\":{}}",
                "{\"task\":\"task\",\"command\":{\"operation\":\"observe\",\"parameters\":{}},\"extra\":true}")) {
            assertThrows(IllegalArgumentException.class, () -> SessionRuntimePayloads.validateAgainstSchema(schema,
                    new TypedPayload("application/json", input), limits));
        }
    }

    /** oneOf 不能用类型并集代替；重叠分支必须拒绝，anyOf 则允许重叠命中。 */
    @Test void choicesRequireExactOrNonzeroMatchesAndKeepSiblingConstraints() {
        var one = new TypedPayload("json-schema", "{\"oneOf\":[{\"type\":\"integer\"},{\"type\":\"number\"}]}");
        var any = new TypedPayload("json-schema", "{\"anyOf\":[{\"type\":\"integer\"},{\"type\":\"number\"}]}");
        var integer = new TypedPayload("application/json", "1");
        assertThrows(IllegalArgumentException.class, () -> SessionRuntimePayloads.validateAgainstSchema(one, integer, LIMITS));
        assertEquals(new SessionRuntimePayloads.SchemaViolation("", "oneOf"),
                SessionRuntimePayloads.inputSchemaViolation(one, integer, LIMITS).orElseThrow());
        assertDoesNotThrow(() -> SessionRuntimePayloads.validateAgainstSchema(one, new TypedPayload("application/json", "1.5"), LIMITS));
        assertDoesNotThrow(() -> SessionRuntimePayloads.validateAgainstSchema(any, integer, LIMITS));
        var wrong = new TypedPayload("application/json", "\"private-value\"");
        assertThrows(IllegalArgumentException.class, () -> SessionRuntimePayloads.validateAgainstSchema(any, wrong, LIMITS));
        assertEquals(new SessionRuntimePayloads.SchemaViolation("", "anyOf"),
                SessionRuntimePayloads.inputSchemaViolation(any, wrong, LIMITS).orElseThrow());
        var sibling = new TypedPayload("json-schema", """
                {"type":"object","properties":{"left":{"type":"string"},"right":{"type":"string"}},
                "additionalProperties":false,"anyOf":[{"required":["left"]},{"required":["right"]}]}
                """);
        assertDoesNotThrow(() -> SessionRuntimePayloads.validateAgainstSchema(sibling,
                new TypedPayload("application/json", "{\"left\":\"l\",\"right\":\"r\"}"), LIMITS));
        for (String input : java.util.List.of("{}", "[]", "{\"left\":1}", "{\"left\":\"l\",\"extra\":true}")) {
            assertThrows(IllegalArgumentException.class, () -> SessionRuntimePayloads.validateAgainstSchema(sibling,
                    new TypedPayload("application/json", input), LIMITS));
        }
        var both = new TypedPayload("json-schema", """
                {"oneOf":[{"type":"integer"},{"type":"number"}],"anyOf":[{"minimum":2},{"maximum":0}]}
                """);
        assertDoesNotThrow(() -> SessionRuntimePayloads.validateAgainstSchema(both, new TypedPayload("application/json", "2.5"), LIMITS));
        assertEquals(new SessionRuntimePayloads.SchemaViolation("", "anyOf"),
                SessionRuntimePayloads.inputSchemaViolation(both, new TypedPayload("application/json", "1.5"), LIMITS).orElseThrow());
        for (String malformed : java.util.List.of("{\"oneOf\":[]}", "{\"anyOf\":{}}", "{\"oneOf\":[true]}")) {
            assertThrows(IllegalArgumentException.class, () -> payloads.validate(new TypedPayload("json-schema", malformed), LIMITS));
        }
    }

    /** choice 内的失败引用与无法保真的交集必须拒绝，不能被替换为总能匹配的空分支。 */
    @Test void choicesRejectUnresolvableReferencesAndMalformedAllOfBranches() throws Exception {
        var limits = new CodecLimits(16_384, 1_024, 64);
        for (String source : java.util.List.of(
                "{\"anyOf\":[{\"$ref\":\"#/$defs/missing\"},{\"type\":\"string\"}]}",
                "{\"anyOf\":[{\"$ref\":7},{\"type\":\"string\"}]}",
                "{\"anyOf\":[{\"$ref\":\"https://example.invalid/schema\"},{\"type\":\"string\"}]}",
                "{\"$defs\":{\"invalid\":false},\"oneOf\":[{\"$ref\":\"#/$defs/invalid\"},{\"type\":\"string\"}]}",
                "{\"$defs\":{\"loop\":{\"$ref\":\"#/$defs/loop\"}},\"anyOf\":[{\"$ref\":\"#/$defs/loop\"},{\"type\":\"string\"}]}",
                "{\"$defs\":{\"a\":{\"$ref\":\"#/$defs/b\"},\"b\":{\"$ref\":\"#/$defs/a\"}},\"oneOf\":[{\"$ref\":\"#/$defs/a\"},{\"type\":\"string\"}]}",
                "{\"anyOf\":[{\"allOf\":[]},{\"type\":\"boolean\"}]}",
                "{\"anyOf\":[{\"type\":\"object\",\"properties\":{\"child\":{\"$ref\":\"#/$defs/missing\"}}},{\"type\":\"string\"}]}",
                "{\"anyOf\":[{\"type\":\"array\",\"items\":{\"$ref\":\"#/$defs/missing\"}},{\"type\":\"string\"}]}")) {
            var schema = new TypedPayload("json-schema", source);
            assertThrows(IllegalArgumentException.class, () -> payloads.validate(schema, limits));
            assertThrows(IllegalArgumentException.class, () -> SessionRuntimePayloads.validateAgainstSchema(schema,
                    new TypedPayload("application/json", "1"), limits));
        }
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var deep = mapper.createObjectNode();
        var definitions = deep.putObject("$defs");
        for (int index = 0; index < 40; index++)
            definitions.putObject("ref" + index).put("$ref", "#/$defs/ref" + (index + 1));
        definitions.putObject("ref40").put("type", "string");
        deep.putArray("anyOf").addObject().put("$ref", "#/$defs/ref0");
        assertThrows(IllegalArgumentException.class, () -> SessionRuntimeJsonSchema.project(deep));
        assertThrows(IllegalArgumentException.class, () -> payloads.validate(new TypedPayload("json-schema", deep.toString()), limits));
    }

    /** 引用与兄弟约束需要同时成立；未实现交集时显式拒绝冲突，也不提前覆盖后继引用链的约束。 */
    @Test void choicesRejectReferenceSiblingConstraintOverridesButPreserveExplicitEmptyBranches() {
        for (String source : java.util.List.of(
                "{\"$defs\":{\"Base\":{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"number\"}]}},\"$ref\":\"#/$defs/Base\",\"anyOf\":[{\"type\":\"boolean\"}]}",
                "{\"$defs\":{\"Base\":{\"oneOf\":[{\"type\":\"string\"},{\"type\":\"number\"}]}},\"$ref\":\"#/$defs/Base\",\"oneOf\":[{\"type\":\"boolean\"}]}",
                "{\"$defs\":{\"Base\":{\"type\":\"string\"}},\"anyOf\":[{\"$ref\":\"#/$defs/Base\",\"type\":\"boolean\"}]}",
                "{\"$defs\":{\"A\":{\"$ref\":\"#/$defs/B\",\"minLength\":5},\"B\":{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"boolean\"}]}},\"$ref\":\"#/$defs/A\",\"minLength\":1}")) {
            var schema = new TypedPayload("json-schema", source);
            assertThrows(IllegalArgumentException.class, () -> payloads.validate(schema, LIMITS));
            assertThrows(IllegalArgumentException.class, () -> SessionRuntimePayloads.validateAgainstSchema(schema,
                    new TypedPayload("application/json", "true"), LIMITS));
            assertThrows(IllegalArgumentException.class, () -> SessionRuntimePayloads.validateAgainstSchema(schema,
                    new TypedPayload("application/json", "\"x\""), LIMITS));
        }
        for (String source : java.util.List.of(
                "{\"anyOf\":[{},{\"type\":\"string\"}]}",
                "{\"$defs\":{\"empty\":{}},\"anyOf\":[{\"$ref\":\"#/$defs/empty\"},{\"type\":\"string\"}]}",
                "{\"oneOf\":[{},{\"type\":\"string\"}]}")) {
            assertDoesNotThrow(() -> SessionRuntimePayloads.validateAgainstSchema(new TypedPayload("json-schema", source),
                    new TypedPayload("application/json", "1"), LIMITS));
        }
        var single = new TypedPayload("json-schema", "{\"anyOf\":[{\"allOf\":[{\"type\":\"string\",\"minLength\":2}]},{\"type\":\"boolean\"}]}");
        assertDoesNotThrow(() -> SessionRuntimePayloads.validateAgainstSchema(single,
                new TypedPayload("application/json", "\"ok\""), LIMITS));
        assertThrows(IllegalArgumentException.class, () -> SessionRuntimePayloads.validateAgainstSchema(single,
                new TypedPayload("application/json", "1"), LIMITS));
        var identical = new TypedPayload("json-schema", "{\"$defs\":{\"Base\":{\"type\":\"string\",\"minLength\":2}},\"$ref\":\"#/$defs/Base\",\"type\":\"string\",\"minLength\":2}");
        assertDoesNotThrow(() -> SessionRuntimePayloads.validateAgainstSchema(identical,
                new TypedPayload("application/json", "\"ok\""), LIMITS));
    }

    /** 与浏览器正式结构相同的代表性闭合判别联合，不引入宿主或执行契约模块依赖。 */
    private static TypedPayload browserCommandSchema() {
        return new TypedPayload("json-schema", """
                {"type":"object","properties":{"task":{"type":"string"},"command":{"oneOf":[
                {"type":"object","properties":{"operation":{"const":"navigate"},"parameters":{"type":"object",
                "properties":{"url":{"type":"string"},"tab_id":{"type":"integer"}},"additionalProperties":false}},
                "required":["operation","parameters"],"additionalProperties":false},
                {"type":"object","properties":{"operation":{"const":"observe"},"parameters":{"type":"object",
                "properties":{},"additionalProperties":false}},"required":["operation","parameters"],"additionalProperties":false},
                {"type":"object","properties":{"operation":{"const":"screenshot"},"parameters":{"type":"object",
                "properties":{},"additionalProperties":false}},"required":["operation","parameters"],"additionalProperties":false}
                ]}},"required":["task","command"],"additionalProperties":false}
                """);
    }

    @Test
    void frozenSchemaEnforcesRequiredTypesBoundsAndAdditionalProperties() {
        var schema = new TypedPayload("json-schema", "{\"description\":\"input\",\"type\":\"object\",\"required\":[\"n\"],\"properties\":{\"n\":{\"type\":\"number\",\"minimum\":0.1234567890123456789}},\"additionalProperties\":false}");
        assertDoesNotThrow(() -> SessionRuntimePayloads.validateAgainstSchema(schema,
                new TypedPayload("application/json", "{\"n\":0.1234567890123456789}"), LIMITS));
        for (String json : java.util.List.of("{}", "{\"n\":\"1\"}", "{\"n\":0.1234567890123456788}", "{\"n\":1,\"extra\":true}")) {
            assertThrows(IllegalArgumentException.class, () -> SessionRuntimePayloads.validateAgainstSchema(schema,
                    new TypedPayload("application/json", json), LIMITS));
        }
        assertThrows(IllegalArgumentException.class, () -> SessionRuntimePayloads.validateAgainstSchema(
                new TypedPayload("json-schema", "{\"additionalProperties\":false}"),
                new TypedPayload("application/json", "{\"extra\":true}"), LIMITS));
    }

    @Test
    void toolOutputChecksStructuredContentAndOnlyEmptySchemaAllowsMissingContent() {
        var schema = new TypedPayload("json-schema", "{\"type\":\"object\",\"required\":[\"answer\"],\"properties\":{\"answer\":{\"type\":\"integer\"}}}");
        assertDoesNotThrow(() -> SessionRuntimePayloads.validateToolOutput(schema,
                new TypedPayload(SessionRuntimePayloads.TOOL_RESULT_SCHEMA, "{\"content\":[],\"structuredContent\":{\"answer\":9007199254740993}}"), LIMITS));
        for (String json : java.util.List.of("{\"content\":[]}", "{\"content\":[],\"structuredContent\":{\"answer\":\"42\"}}")) {
            assertThrows(IllegalArgumentException.class, () -> SessionRuntimePayloads.validateToolOutput(schema,
                    new TypedPayload(SessionRuntimePayloads.TOOL_RESULT_SCHEMA, json), LIMITS));
        }
        assertDoesNotThrow(() -> SessionRuntimePayloads.validateToolOutput(new TypedPayload("json-schema", "{}"),
                new TypedPayload(SessionRuntimePayloads.TOOL_RESULT_SCHEMA, "{\"content\":[{\"type\":\"text\",\"text\":\"result\"}]}"), LIMITS));
    }

    @Test
    void acceptsContextTextModelTextJsonAndDecisionSchemas() {
        assertDoesNotThrow(() -> payloads.validate(
                SessionPreparationContent.encode("instruction"), LIMITS));
        assertDoesNotThrow(() -> payloads.validate(
                new TypedPayload(ModelDecisionProjectionPolicy.TEXT_SCHEMA,
                        "\"{\\\"type\\\":\\\"respond\\\",\\\"output\\\":\\\"ok\\\"}\""),
                LIMITS));
        assertDoesNotThrow(() -> payloads.validate(
                new TypedPayload(ModelDecisionProjectionPolicy.JSON_SCHEMA,
                        "{\"output\":\"ok\",\"type\":\"respond\"}"),
                LIMITS));
        assertDoesNotThrow(() -> payloads.validate(
                new TypedPayload(ModelDecisionProjectionPolicy.JSON_SCHEMA,
                        "{\"arguments\":{\"kind\":\"all\",\"page\":1},\"tool_handle\":\"tool:a\",\"type\":\"tool_call\"}"),
                LIMITS));
        assertDoesNotThrow(() -> payloads.validate(
                new TypedPayload(ModelDecisionProjectionPolicy.DECISION_SCHEMA,
                        "{\"evidence_item_ids\":[],\"output\":\"ok\",\"type\":\"respond\"}"),
                LIMITS));
        assertDoesNotThrow(() -> payloads.validate(
                new TypedPayload(ModelDecisionProjectionPolicy.DECISION_SCHEMA,
                        "{\"calls\":[{\"arguments\":{\"case_key\":\"a\"},\"tool_handle\":\"tool:query\",\"type\":\"tool_call\"},{\"arguments\":{\"case_key\":\"b\"},\"tool_handle\":\"tool:query\",\"type\":\"tool_call\"}],\"type\":\"tool_batch\"}"),
                LIMITS));
        assertDoesNotThrow(() -> payloads.validate(
                new TypedPayload(ModelDecisionProjectionPolicy.DECISION_SCHEMA, "{}"),
                LIMITS));
        assertDoesNotThrow(() -> payloads.validate(
                new TypedPayload(SessionRuntimePayloads.KNOWLEDGE_CHUNK_SCHEMA, "{\"text\":\"sentinel\"}"),
                LIMITS));
    }

    @Test
    void rejectsUnknownSchemaAndWrongWire() {
        assertThrows(IllegalArgumentException.class, () -> payloads.validate(
                new TypedPayload("unknown.v1", "\"x\""), LIMITS));
        assertThrows(IllegalArgumentException.class, () -> payloads.validate(
                new TypedPayload(ModelDecisionProjectionPolicy.TEXT_SCHEMA, "{}"), LIMITS));
        assertThrows(IllegalArgumentException.class, () -> payloads.validate(
                new TypedPayload(ModelDecisionProjectionPolicy.JSON_SCHEMA, "\"x\""), LIMITS));
        assertThrows(IllegalArgumentException.class, () -> payloads.validate(
                new TypedPayload(ModelDecisionProjectionPolicy.JSON_SCHEMA, "[]"), LIMITS));
        assertThrows(IllegalArgumentException.class, () -> payloads.validate(
                new TypedPayload(ModelDecisionProjectionPolicy.DECISION_SCHEMA, "{\"type\":\"nope\"}"),
                LIMITS));
        assertThrows(IllegalArgumentException.class, () -> payloads.validate(
                new TypedPayload(ModelDecisionProjectionPolicy.DECISION_SCHEMA,
                        "{\"calls\":[{\"arguments\":{},\"tool_handle\":\"tool:query\",\"type\":\"tool_call\"}],\"type\":\"tool_batch\"}"),
                LIMITS));
        assertThrows(IllegalArgumentException.class, () -> payloads.validate(
                new TypedPayload("skillforge.runtime.agent.model-result.v3", "{}"), LIMITS));
        assertThrows(IllegalArgumentException.class, () -> payloads.validate(
                new TypedPayload(SessionRuntimePayloads.KNOWLEDGE_CHUNK_SCHEMA, "{}"), LIMITS));
        assertThrows(IllegalArgumentException.class, () -> payloads.validate(
                new TypedPayload(SessionRuntimePayloads.KNOWLEDGE_CHUNK_SCHEMA, "\"x\""), LIMITS));
    }

}
