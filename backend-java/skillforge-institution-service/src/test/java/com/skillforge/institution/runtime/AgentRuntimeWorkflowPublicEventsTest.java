package com.skillforge.institution.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.session.AgentExecutionProgram;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 公开协议的独立边界测试；脱敏不改写真实 Workflow 结局，也不公开私有结果引用。 */
class AgentRuntimeWorkflowPublicEventsTest {
    private static final String PRIVATE_VALUE = "隔离测试禁止公开值";
    private static final String REVISION = "9007199254740993";
    private final ObjectMapper json = new ObjectMapper();
    private final PublicOutputGuard guard = mock(PublicOutputGuard.class);

    @BeforeEach void publicPolicy() {
        when(guard.capture()).thenReturn(new PublicOutputGuard.Scope(List.of(PRIVATE_VALUE)));
    }

    @Test void startedEventPreservesExactPublicCallAndTraceIdentity() throws Exception {
        var body = base("DISPATCHED");
        var event = started(body);
        var safe = AgentRuntimeExecutionPublicEvents.protectWorkflow(event, guard, json);
        assertEquals(event.id(), safe.id());
        assertEquals(event.type(), safe.type());
        assertEquals(event.data().schemaId(), safe.data().schemaId());
        assertEquals(body, json.readTree(safe.data().canonicalJson()));
    }

    @Test void completedEventKeepsTypedExportAndExactRevisionWithoutAProjectionWrapper() throws Exception {
        String document = json.writeValueAsString(Map.of("业务结果", Map.of(
                "count", 0, "enabled", false, "items", List.of(Map.of("名称", "真实值")))));
        var body = result("COMPLETED", "workflow-output.v1", document);
        var event = finished(body);
        var safe = AgentRuntimeExecutionPublicEvents.protectWorkflow(event, guard, json);
        JsonNode actual = json.readTree(safe.data().canonicalJson());
        assertEquals(event.id(), safe.id());
        assertEquals(event.type(), safe.type());
        assertEquals(event.data().schemaId(), safe.data().schemaId());
        assertEquals(body, actual);
        assertEquals(REVISION, actual.path("executionRevision").textValue());
        var output = json.readTree(actual.path("output").path("canonicalJson").textValue());
        assertEquals(json.readTree(document), output);
        assertTrue(output.path("业务结果").path("count").isIntegralNumber());
        assertEquals(0, output.path("业务结果").path("count").intValue());
        assertTrue(output.path("业务结果").path("enabled").isBoolean());
        assertFalse(output.path("业务结果").path("enabled").booleanValue());
    }

    @Test void undeclaredFieldsAndPrivateResultReferencesAreRejectedAtBothEnvelopeLevels() {
        for (String field : List.of("context", "resultRef")) {
            var start = base("DISPATCHED");
            start.put(field, "private-reference");
            assertThrows(IllegalArgumentException.class,
                    () -> AgentRuntimeExecutionPublicEvents.protectWorkflow(started(start), guard, json));
            var result = result("COMPLETED", "workflow-output.v1", "{}");
            result.put(field, "private-reference");
            assertThrows(IllegalArgumentException.class,
                    () -> AgentRuntimeExecutionPublicEvents.protectWorkflow(finished(result), guard, json));
        }
        var result = result("COMPLETED", "workflow-output.v1", "{}");
        ((ObjectNode) result.get("output")).put("resultRef", "private-material-reference");
        assertThrows(IllegalArgumentException.class,
                () -> AgentRuntimeExecutionPublicEvents.protectWorkflow(finished(result), guard, json));
    }

    @Test void decodedUnicodeNestedValuesAndFieldNamesAreCheckedAcrossTheWholeExport() throws Exception {
        String escaped = unicodeEscapes(PRIVATE_VALUE);
        var documents = List.of(
                "{\"nested\":[{\"deep\":{\"value\":\"" + escaped + "\"}}]}",
                "{\"nested\":[{\"" + escaped + "\":\"business-value\"}]}",
                "{\"large\":\"" + "x".repeat(12000) + "\",\"tail\":[{\"text\":\"" + escaped + "\"}]}");
        for (String document : documents) {
            assertFalse(document.contains(PRIVATE_VALUE), "测试材料必须迫使保护器检查解码后的值，不能只命中原始全文");
            var event = finished(result("COMPLETED", "workflow-output.v1", document));
            var safe = AgentRuntimeExecutionPublicEvents.protectWorkflow(event, guard, json);
            var actual = json.readTree(safe.data().canonicalJson());
            assertIdentity(event, safe, actual);
            assertEquals("COMPLETED", actual.path("status").textValue());
            assertFalse(actual.path("outputAccepted").booleanValue());
            assertTrue(actual.path("output").isNull());
            assertEquals(PublicOutputGuard.SAFE_MESSAGE, actual.path("summary").textValue());
            assertFalse(safe.data().canonicalJson().contains(PRIVATE_VALUE));
            assertFalse(safe.data().canonicalJson().contains(escaped));
        }
    }

    @Test void rejectedSchemaAndSummaryCannotLeakWhileActualCompletionIsPreserved() throws Exception {
        var schemaEvent = finished(result("COMPLETED", "schema-" + PRIVATE_VALUE, "{\"safe\":true}"));
        var safeSchema = AgentRuntimeExecutionPublicEvents.protectWorkflow(schemaEvent, guard, json);
        var schemaBody = json.readTree(safeSchema.data().canonicalJson());
        assertEquals("COMPLETED", schemaBody.path("status").textValue());
        assertFalse(schemaBody.path("outputAccepted").booleanValue());
        assertTrue(schemaBody.path("output").isNull());
        assertFalse(safeSchema.data().canonicalJson().contains(PRIVATE_VALUE));

        var body = base("DISPATCHED");
        body.put("summary", "阶段说明 " + PRIVATE_VALUE);
        var event = started(body);
        var safe = AgentRuntimeExecutionPublicEvents.protectWorkflow(event, guard, json);
        var actual = json.readTree(safe.data().canonicalJson());
        assertIdentity(event, safe, actual);
        assertEquals("DISPATCHED", actual.path("status").textValue());
        assertEquals(PublicOutputGuard.SAFE_MESSAGE, actual.path("summary").textValue());
    }

    @Test void failedUnknownAndOtherNonCompletedOutcomesNeverPublishAnOutputBody() throws Exception {
        for (String status : List.of("FAILED", "UNKNOWN", "TERMINATED", "CANCELLED")) {
            var body = result(status, null, null);
            if (status.equals("UNKNOWN")) body.putNull("executionRevision");
            var event = finished(body);
            var safe = AgentRuntimeExecutionPublicEvents.protectWorkflow(event, guard, json);
            var actual = json.readTree(safe.data().canonicalJson());
            assertIdentity(event, safe, actual);
            assertEquals(status, actual.path("status").textValue());
            assertFalse(actual.path("outputAccepted").booleanValue());
            assertTrue(actual.path("output").isNull());

            var forgedAccepted = result(status, "workflow-output.v1", "{\"body\":\"must-not-be-published\"}");
            assertThrows(IllegalArgumentException.class,
                    () -> AgentRuntimeExecutionPublicEvents.protectWorkflow(finished(forgedAccepted), guard, json));
            var forgedUnaccepted = result(status, "workflow-output.v1", "{\"body\":\"must-not-be-published\"}");
            forgedUnaccepted.put("outputAccepted", false);
            assertThrows(IllegalArgumentException.class,
                    () -> AgentRuntimeExecutionPublicEvents.protectWorkflow(finished(forgedUnaccepted), guard, json));
        }
    }

    private ObjectNode base(String status) {
        return json.createObjectNode().put("actionId", "workflow-action-original").put("executionId", "workflow-execution-original")
                .put("status", status).put("summary", "工作流公开概要").put("inputId", "input-original")
                .put("rootInputId", "root-input-original").put("traceId", "trace-original")
                .put("spanId", "span-original").put("agentId", "9007199254740995");
    }

    private ObjectNode result(String status, String schema, String document) {
        var body = base(status).put("executionRevision", REVISION).put("outputAccepted", document != null);
        if (document == null) body.putNull("output");
        else body.putObject("output").put("schemaId", schema).put("canonicalJson", document);
        return body;
    }

    private ExecutionStore.Event started(ObjectNode body) {
        return event("workflow-started-original", AgentExecutionProgram.WORKFLOW_STARTED_EVENT,
                AgentExecutionProgram.WORKFLOW_STARTED_SCHEMA, body);
    }

    private ExecutionStore.Event finished(ObjectNode body) {
        return event("workflow-result-original", AgentExecutionProgram.WORKFLOW_RESULT_EVENT,
                AgentExecutionProgram.WORKFLOW_RESULT_SCHEMA, body);
    }

    private static ExecutionStore.Event event(String id, String type, String schema, ObjectNode body) {
        return new ExecutionStore.Event(new EventId(id), type, new TypedPayload(schema, body.toString()));
    }

    private static String unicodeEscapes(String text) {
        return text.chars().mapToObj(value -> "\\" + "u" + String.format("%04x", value)).collect(Collectors.joining());
    }

    private void assertIdentity(ExecutionStore.Event before, ExecutionStore.Event after, JsonNode actual) throws Exception {
        assertEquals(before.id(), after.id());
        assertEquals(before.type(), after.type());
        assertEquals(before.data().schemaId(), after.data().schemaId());
        var original = json.readTree(before.data().canonicalJson());
        for (String field : List.of("actionId", "executionId", "inputId", "rootInputId", "traceId", "spanId", "agentId"))
            assertEquals(original.get(field), actual.get(field), field);
    }
}
