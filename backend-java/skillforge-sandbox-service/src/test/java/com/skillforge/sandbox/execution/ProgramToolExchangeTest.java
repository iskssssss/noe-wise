package com.skillforge.sandbox.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.execution.ExecuteRequest;
import com.skillforge.execution.ExecutionLimits;
import com.skillforge.execution.Language;
import com.skillforge.sandbox.api.SandboxConflictException;
import com.skillforge.sandbox.api.SandboxRequestException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgramToolExchangeTest {

    @TempDir
    Path temporary;

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> preparedTasks = new ArrayList<>();

    @AfterEach
    void cleanReadOnlyWorkspaces() {
        TaskWorkspace workspace = new TaskWorkspace(TestSandboxProperties.create(temporary));
        for (String taskId : preparedTasks) {
            workspace.delete(taskId);
        }
    }

    @Test
    void createsSdkAndExposesOnlyTheNextSerialCall() throws Exception {
        TaskWorkspace workspace = preparedWorkspace("ordered");
        ProgramToolExchange exchange = new ProgramToolExchange(json, workspace);
        Path program = workspace.program("ordered");

        writeRequest(program, 1, "first", json.createObjectNode().put("value", 1));
        exchange.complete("ordered", 1, json.createObjectNode().put("success", true));
        writeRequest(program, 2, "later", json.createObjectNode().put("value", 2));

        assertEquals(List.of(2), exchange.pending("ordered").stream()
                .map(ProgramToolExchange.ToolCall::ordinal).toList());
        String sdk = Files.readString(temporary.resolve("workspaces/ordered/input/__skillforge_tools.mjs"));
        assertTrue(sdk.contains("export const tools"));
        assertTrue(sdk.contains("queue.then"));
        assertTrue(sdk.contains("const maximumCalls = " + ExecutionLimits.PROGRAM_CALLS + ";"));
    }

    @Test
    void completesTheLastAllowedSerialCallAndRejectsTheNextOrdinal() throws Exception {
        TaskWorkspace workspace = preparedWorkspace("boundary");
        ProgramToolExchange exchange = new ProgramToolExchange(json, workspace);
        Path program = workspace.program("boundary");
        ObjectNode completed = json.createObjectNode().put("success", true);
        for (int ordinal = 1; ordinal <= ExecutionLimits.PROGRAM_CALLS; ordinal++) {
            writeRequest(program, ordinal, "lookup", json.createObjectNode());
            if (ordinal < ExecutionLimits.PROGRAM_CALLS) {
                Files.writeString(program.resolve("response-" + ordinal + ".json"),
                        json.writeValueAsString(completed), StandardCharsets.UTF_8);
            }
        }

        assertEquals(List.of(ExecutionLimits.PROGRAM_CALLS), exchange.pending("boundary").stream()
                .map(ProgramToolExchange.ToolCall::ordinal).toList());
        exchange.complete("boundary", ExecutionLimits.PROGRAM_CALLS, completed);
        assertEquals(List.of(), exchange.pending("boundary"));
        var rejected = assertThrows(SandboxRequestException.class,
                () -> exchange.complete("boundary", ExecutionLimits.PROGRAM_CALLS + 1, completed));
        assertEquals("INVALID_TOOL_ORDINAL", rejected.code());
        assertTrue(rejected.getMessage().contains(Integer.toString(ExecutionLimits.PROGRAM_CALLS)));
    }

    @Test
    void freezesResultIdempotentlyAndRejectsDifferentValue() throws Exception {
        TaskWorkspace workspace = preparedWorkspace("result");
        ProgramToolExchange exchange = new ProgramToolExchange(json, workspace);
        Path program = workspace.program("result");
        writeRequest(program, 1, "lookup", json.createObjectNode());
        ObjectNode observation = json.createObjectNode().put("success", false).put("error", "denied");

        exchange.complete("result", 1, observation);
        exchange.complete("result", 1, observation.deepCopy());

        assertEquals(List.of(), exchange.pending("result"));
        assertEquals(observation, json.readTree(program.resolve("response-1.json").toFile()));
        ObjectNode different = json.createObjectNode().put("success", true).put("answer", 43);
        assertEquals("TOOL_RESULT_ALREADY_SET",
                assertThrows(SandboxConflictException.class,
                        () -> exchange.complete("result", 1, different)).code());
    }

    @Test
    void rejectsTooManyCallsOversizedMessagesAndInvalidHandle() throws Exception {
        TaskWorkspace limitWorkspace = preparedWorkspace("limit");
        ProgramToolExchange limitExchange = new ProgramToolExchange(json, limitWorkspace);
        writeRequest(limitWorkspace.program("limit"), ExecutionLimits.PROGRAM_CALLS + 1, "overflow", json.createObjectNode());
        assertEquals("INVALID_TOOL_MESSAGE",
                assertThrows(SandboxConflictException.class, () -> limitExchange.pending("limit")).code());

        TaskWorkspace serialWorkspace = preparedWorkspace("serial");
        ProgramToolExchange serialExchange = new ProgramToolExchange(json, serialWorkspace);
        writeRequest(serialWorkspace.program("serial"), 1, "first", json.createObjectNode());
        writeRequest(serialWorkspace.program("serial"), 2, "concurrent", json.createObjectNode());
        assertEquals("INVALID_TOOL_MESSAGE",
                assertThrows(SandboxConflictException.class, () -> serialExchange.pending("serial")).code());

        TaskWorkspace sizeWorkspace = preparedWorkspace("size");
        ProgramToolExchange sizeExchange = new ProgramToolExchange(json, sizeWorkspace);
        Files.writeString(sizeWorkspace.program("size").resolve("request-1.json"),
                "x".repeat(ProgramToolExchange.MAXIMUM_MESSAGE_BYTES + 1));
        assertEquals("INVALID_TOOL_MESSAGE",
                assertThrows(SandboxConflictException.class, () -> sizeExchange.pending("size")).code());

        TaskWorkspace handleWorkspace = preparedWorkspace("handle");
        ProgramToolExchange handleExchange = new ProgramToolExchange(json, handleWorkspace);
        writeRequest(handleWorkspace.program("handle"), 1, "x".repeat(129), json.createObjectNode());
        assertEquals("INVALID_TOOL_MESSAGE",
                assertThrows(SandboxConflictException.class, () -> handleExchange.pending("handle")).code());

        TaskWorkspace resultWorkspace = preparedWorkspace("large-result");
        ProgramToolExchange resultExchange = new ProgramToolExchange(json, resultWorkspace);
        writeRequest(resultWorkspace.program("large-result"), 1, "large", json.createObjectNode());
        ObjectNode oversized = json.createObjectNode().put("value",
                "x".repeat(ProgramToolExchange.MAXIMUM_MESSAGE_BYTES));
        assertEquals("TOOL_MESSAGE_TOO_LARGE",
                assertThrows(SandboxRequestException.class,
                        () -> resultExchange.complete("large-result", 1, oversized)).code());
    }

    @Test
    void rejectsSymbolicLinkMessagesWithoutFollowingThem() throws Exception {
        TaskWorkspace workspace = preparedWorkspace("link");
        ProgramToolExchange exchange = new ProgramToolExchange(json, workspace);
        Path outside = temporary.resolve("outside.json");
        Files.writeString(outside, "{\"ordinal\":1,\"handle\":\"x\",\"arguments\":{}}");
        try {
            Files.createSymbolicLink(workspace.program("link").resolve("request-1.json"), outside);
        } catch (UnsupportedOperationException | IOException unavailable) {
            Assumptions.assumeTrue(false, "当前文件系统不允许创建符号链接");
        }

        assertEquals("INVALID_TOOL_MESSAGE",
                assertThrows(SandboxConflictException.class, () -> exchange.pending("link")).code());
        assertTrue(Files.exists(outside));
    }

    private TaskWorkspace preparedWorkspace(String taskId) {
        TaskWorkspace workspace = new TaskWorkspace(TestSandboxProperties.create(temporary));
        ExecuteRequest request = new ExecuteRequest(taskId, Language.NODE, "console.log('ok')",
                List.of(), List.of(), null, true, Instant.now().plusSeconds(60),
                TestSandboxProperties.policy(), List.of(),
                com.skillforge.execution.ExecutionProfile.STANDARD, null, null);
        workspace.prepare(new RequestPolicy(json).validate(request));
        preparedTasks.add(taskId);
        return workspace;
    }

    private void writeRequest(Path directory, int ordinal, String handle, ObjectNode args) throws Exception {
        ObjectNode request = json.createObjectNode()
                .put("ordinal", ordinal)
                .put("handle", handle)
                .set("arguments", args);
        Files.writeString(directory.resolve("request-" + ordinal + ".json"),
                json.writeValueAsString(request), StandardCharsets.UTF_8);
    }
}
