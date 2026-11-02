package com.skillforge.institution.memory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates.Source;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates.ExtractionInput;
import com.skillforge.core.agent.systemagent.SystemAgentExecution;
import com.skillforge.core.agent.systemagent.SystemAgentTypes.RunView;
import com.skillforge.core.agent.systemagent.SystemAgentTypes.FrozenRun;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** 已有 SystemAgent 是唯一模型调用者；包装器只保留冻结身份与真实终态。 */
class MemoryExtractionAgentTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final SystemAgentExecution execution = mock(SystemAgentExecution.class);
    private final MemoryExtractionAgent agent = new MemoryExtractionAgent(execution, JSON);
    private final ExtractionInput input = new MemoryExtractionCandidates(JSON).typedInput(7,
            List.of(new Source("s0", "请记住我偏好本地部署。", Instant.parse("2026-10-23T00:00:00Z"))), List.of());

    @Test void completedKeepsExactFrozenInputAndOriginalOperationIdentity() {
        boundRun(run("COMPLETED", "{\"schemaVersion\":2,\"candidates\":[]}", null));
        var bindings=new ArrayList<MemoryExtractionAgent.DispatchBinding>();
        var result = agent.extract("1", "original-operation", input,()->{},bindings::add);
        assertEquals(MemoryExtractionAgent.Status.COMPLETED, result.status());
        assertTrue(result.candidates().isEmpty());
        assertEquals("run-1", result.systemRunId());
        assertEquals("system-agent-config:agent:2", result.configurationRef());
        assertEquals("system-agent-invocation:run-1", result.invocationRef());
        assertEquals(List.of(new MemoryExtractionAgent.DispatchBinding(result.systemRunId(),result.configurationRef(),result.invocationRef())),bindings);
        verify(execution).executePurposeBound(eq("memory-extraction"), eq("1"), eq("original-operation"),
                eq(new MemoryExtractionCandidates(JSON).encodeInput(input)),any());
    }

    @Test void groupDispatchPassesGuardToSystemClaimAndPreservesV3Output() {
        var contract = new MemoryExtractionCandidates(JSON);
        var groupInput = contract.typedGroupInput(7, new MemoryExtractionCandidates.GroupContext("21", List.of(7L, 8L)),
                input.sources(), List.of());
        Runnable guard = mock(Runnable.class);
        boundRun(run("COMPLETED", "{\"schemaVersion\":3,\"candidates\":[]}", null));
        var bindings=new ArrayList<MemoryExtractionAgent.DispatchBinding>();
        var result = agent.extract("1", "group-operation", groupInput, guard,bindings::add);
        assertEquals(MemoryExtractionAgent.Status.COMPLETED, result.status());
        verify(guard).run();
        assertEquals("run-1",bindings.getFirst().systemRunId());
        verify(execution).executePurposeBound(eq("memory-extraction"),eq("1"),eq("group-operation"),eq(contract.encodeInput(groupInput)),any());
        verify(execution, never()).executePurpose(anyString(), anyString(), anyString(), anyString());
    }

    @Test void runningRemainsPendingRatherThanEmptySuccess() {
        boundRun(run("RUNNING", null, null));
        assertEquals(MemoryExtractionAgent.Status.PENDING, agent.extract("1", "same-key", input,()->{},ignored->{}).status());
    }

    @Test void rejectedClaimGuardNeverPublishesDispatchBinding() {
        boundRun(run("READY",null,null));
        var bindings=new ArrayList<MemoryExtractionAgent.DispatchBinding>();
        assertThrows(IllegalStateException.class,()->agent.extract("1","same-key",input,
                ()->{throw new IllegalStateException("memory_group_extraction_paused");},bindings::add));
        assertTrue(bindings.isEmpty());
    }

    @Test void unknownAndUnclassifiedExecutionFailureRetainUnknownReceipt() {
        for (String error : List.of("system_agent_outcome_unknown", "system_agent_execution_failed")) {
            boundRun(run("FAILED", null, error));
            var result = agent.extract("1", "same-key", input,()->{},ignored->{});
            assertEquals(MemoryExtractionAgent.Status.UNKNOWN, result.status());
            assertEquals("run-1", result.systemRunId());
        }
    }

    @Test void explicitModelRejectionDoesNotMasqueradeAsUnknownOrEmpty() {
        boundRun(run("FAILED", null, "system_agent_input_invalid"));
        assertEquals(MemoryExtractionAgent.Status.REJECTED, agent.extract("1", "same-key", input,()->{},ignored->{}).status());
    }

    @Test void invalidTypedInputIsRejectedBeforeProvider() {
        assertThrows(IllegalArgumentException.class, () -> agent.extract("1", "key", null,()->{},ignored->{}));
        verifyNoInteractions(execution);
    }

    @Test void candidateEvidenceMustBelongToOriginalFrozenSource() throws Exception {
        var inputNode = input;
        String valid = """
            {"schemaVersion":2,"candidates":[{"type":"preference","subject":"user:7","predicate":"hosting",
             "object":{"text":"本地部署"},"signal":"explicit_save","sensitive":false,"scope":"user_agent",
             "temporal":{"kind":"stable","from":null,"to":null},"conditions":[],
             "evidence":[{"sourceId":"s0","quote":"我偏好本地部署"}],"relation":{"kind":"new","target":null},"uncertainties":[]}]}
            """;
        assertEquals(1, agent.parse(valid, inputNode).size());
        assertThrows(IllegalArgumentException.class, () -> agent.parse(valid.replace("我偏好本地部署", "虚构的来源"), inputNode));
    }

    private static RunView run(String status, String output, String error) {
        return new RunView("run-1", "agent", 2L, false, status, output, error, null, null, 1, Instant.now());
    }

    @SuppressWarnings("unchecked")
    private void boundRun(RunView view) {
        doAnswer(call->{
                    Consumer<FrozenRun> bind=call.getArgument(4);
                    bind.accept(new FrozenRun(view.id(),view.agentId(),"1","memory-extraction","MODEL_TASK",view.version(),
                            false,null,1,"frozen-input",List.of(),view));
                    return view;
                }).when(execution).executePurposeBound(anyString(),anyString(),anyString(),anyString(),any());
    }
}
