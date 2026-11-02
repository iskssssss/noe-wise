package com.skillforge.execution;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 宿主的公开 snake_case 配置不能改变内部执行协议，也不能提供双格式读取。 */
class ExecutionWireContractTest {
    private final ObjectMapper host = mapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final ObjectMapper sandbox = mapper();

    @Test void manifestKeepsCamelCaseWithNestedPolicyAndExactRequestValues() throws Exception {
        var file = new ArtifactFile("scripts/calculate.py", 12, "a".repeat(64));
        var request = request(List.of(file));
        var manifest = new ExecutionManifest(request, List.of(file));
        byte[] encoded = host.writeValueAsBytes(manifest);
        var execution = sandbox.readTree(encoded).path("execution");
        for (String field : List.of("taskId", "scriptPath", "programTools", "deadlineAt", "executionPolicy",
                "outputFiles", "environmentVersion", "inputJson")) {
            assertTrue(execution.has(field), field);
            assertFalse(execution.has(PropertyNamingStrategies.SnakeCaseStrategy.INSTANCE.translate(field)));
        }
        assertEquals("test-execution", execution.path("taskId").asText());
        assertFalse(execution.has("publicResultFile"));
        assertEquals("scripts/calculate.py", execution.path("scriptPath").asText());
        assertTrue(execution.path("executionPolicy").has("executionTimeoutSeconds"));
        assertFalse(execution.path("executionPolicy").has("execution_timeout_seconds"));
        assertEquals(manifest, sandbox.readValue(encoded, ExecutionManifest.class));
        assertEquals(manifest, host.readValue(sandbox.writeValueAsBytes(manifest), ExecutionManifest.class));
    }

    @Test void responsePreservesCamelCaseAndExplicitBusinessResultWithoutRenamingBusinessFields() throws Exception {
        var businessJson = sandbox.createObjectNode().put("order_id", "business-order");
        var results = new PublicResults(List.of(new PublicResults.Text("plain", "合计 391"),
                new PublicResults.Table(List.of(new PublicResults.Column("total", "金额")),
                        List.of(Arrays.asList((Object) 391), Arrays.asList((Object) null)))));
        var response = new ExecutionResponse("test-execution", ExecutionState.COMPLETED, "finished", 0,
                true, false, null, null, List.of(new ArtifactFile("report.txt", 12, "b".repeat(64))),
                List.of(new ExecutionResponse.LogReference("stdout", 64, true)), businessJson, results);
        byte[] encoded = host.writeValueAsBytes(response);
        var body = sandbox.readTree(encoded);
        for (String field : List.of("taskId", "exitCode", "stdoutTruncated", "stderrTruncated", "deliveryError", "jsonResult", "publicResults")) {
            assertTrue(body.has(field), field);
            assertFalse(body.has(PropertyNamingStrategies.SnakeCaseStrategy.INSTANCE.translate(field)));
        }
        assertEquals("business-order", body.path("jsonResult").path("order_id").asText());
        assertEquals(response, sandbox.readValue(encoded, ExecutionResponse.class));
        assertEquals(response, host.readValue(sandbox.writeValueAsBytes(response), ExecutionResponse.class));
    }

    @Test void snakeCaseRequestIsRejectedAndAdmissionReceiptHasOneTypedMeaning() throws Exception {
        String valid = host.writeValueAsString(new ExecutionManifest(request(List.of()), List.of()));
        String invalid = valid.replace("\"taskId\"", "\"task_id\"");
        assertThrows(Exception.class, () -> host.readValue(invalid, ExecutionManifest.class));
        assertThrows(Exception.class, () -> sandbox.readValue(invalid, ExecutionManifest.class));
        var legacy = sandbox.readTree(valid);
        ((com.fasterxml.jackson.databind.node.ObjectNode)legacy.path("execution")).put("publicResultFile", "result.json");
        assertThrows(Exception.class, () -> host.treeToValue(legacy, ExecutionManifest.class));
        assertThrows(Exception.class, () -> sandbox.treeToValue(legacy, ExecutionManifest.class));
        var rejection = ExecutionAdmissionRejection.notSubmitted(ExecutionAdmissionRejection.Code.INVALID_EXECUTION_MANIFEST);
        assertEquals(rejection, host.readValue(sandbox.writeValueAsBytes(rejection), ExecutionAdmissionRejection.class));
        assertEquals(ExecutionAdmissionRejection.Submission.NOT_SUBMITTED, rejection.submission());
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule()).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    private static ExecuteRequest request(List<ArtifactFile> files) {
        var policy = new ExecutionPolicy(1, ExecutionLimits.DEFAULT_TIMEOUT_SECONDS, 30, 24, 60,
                ExecutionLimits.OUTPUT_CAPTURE_BYTES, ExecutionLimits.MAX_FILES, ExecutionLimits.CONCURRENT_EXECUTIONS,
                ExecutionLimits.MEMORY_LIMIT, new BigDecimal("2"), ExecutionLimits.PIDS_LIMIT);
        return new ExecuteRequest("test-execution", Language.PYTHON, "", List.of(new DependencySpec("requests", "2.32.3")),
                files, "scripts/calculate.py", false, Instant.parse("2026-10-01T00:05:00Z"), policy, List.of("report.txt"), ExecutionProfile.STANDARD, "test-python", "{\"order_id\":17}");
    }
}
