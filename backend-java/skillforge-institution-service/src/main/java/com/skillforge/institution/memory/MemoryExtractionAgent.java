package com.skillforge.institution.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates.Candidate;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates.ExtractionInput;
import com.skillforge.core.agent.systemagent.SystemAgentExecution;
import com.skillforge.core.agent.systemagent.SystemAgentTypes.RunView;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/** 消费原冻结来源调用既有系统用途；未知或在途结果不转换成空候选或新调用。 */
@Service
public final class MemoryExtractionAgent {
    private final SystemAgentExecution execution;
    private final MemoryExtractionCandidates candidates;

    public MemoryExtractionAgent(SystemAgentExecution execution, ObjectMapper json) {
        this.execution = execution;
        this.candidates = new MemoryExtractionCandidates(json);
    }

    /** 资格和原系统运行归属与模型领取一起提交，首次用量发生前已有可靠来源。 */
    Result extract(String tenant, String logicalKey, ExtractionInput input, Runnable beforeDispatch,
            Consumer<DispatchBinding> bindDispatch) {
        Objects.requireNonNull(beforeDispatch);
        Objects.requireNonNull(bindDispatch);
        var run = execution.executePurposeBound("memory-extraction", tenant, logicalKey,
                candidates.encodeInput(input), frozen -> {
                    beforeDispatch.run();
                    bindDispatch.accept(binding(frozen.id(), frozen.agentId(), frozen.version()));
                });
        return result(run, input);
    }

    private Result result(RunView run, ExtractionInput input) {
        var binding = binding(run.id(), run.agentId(), run.version());
        String configuration = binding.configurationRef();
        String invocation = binding.invocationRef();
        if ("COMPLETED".equals(run.status())) {
            return new Result(Status.COMPLETED, candidates.parse(run.output(), input), run.id(), configuration, invocation, null);
        }
        if ("READY".equals(run.status()) || "RUNNING".equals(run.status())) {
            return new Result(Status.PENDING, List.of(), run.id(), configuration, invocation, "memory_extraction_model_pending");
        }
        boolean unknown = "system_agent_outcome_unknown".equals(run.errorCode()) || "system_agent_execution_failed".equals(run.errorCode());
        return new Result(unknown ? Status.UNKNOWN : Status.REJECTED, List.of(), run.id(), configuration, invocation,
                unknown ? "memory_extraction_model_outcome_unknown" : "memory_extraction_model_rejected");
    }

    List<Candidate> parse(String output, ExtractionInput input) { return candidates.parse(output, input); }

    private static DispatchBinding binding(String run, String agent, Long version) {
        return new DispatchBinding(run, "system-agent-config:" + agent + ":" + (version == null ? "draft" : version),
                "system-agent-invocation:" + run);
    }

    record DispatchBinding(String systemRunId, String configurationRef, String invocationRef) { }

    enum Status { COMPLETED, PENDING, UNKNOWN, REJECTED }

    record Result(Status status, List<Candidate> candidates, String systemRunId, String configurationRef,
            String invocationRef, String reasonCode) {
        Result { candidates = List.copyOf(candidates); }
    }
}
