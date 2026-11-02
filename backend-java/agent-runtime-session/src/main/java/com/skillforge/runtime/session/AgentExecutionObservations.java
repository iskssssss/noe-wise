package com.skillforge.runtime.session;

import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowResult;
import com.skillforge.runtime.session.ExecutionObservation.Fact;
import com.skillforge.runtime.session.ExecutionObservation.Kind;
import com.skillforge.runtime.session.ExecutionObservation.Stage;
import com.skillforge.runtime.session.ExecutionObservation.Status;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.spi.ExecutionStore.Event;
import com.skillforge.runtime.session.spi.ExecutionStore.Prepared;
import com.skillforge.runtime.session.spi.ExecutionStore.Run;
import java.util.ArrayList;
import java.util.List;

/** 将当前已加载的真实模型决定关联到安全操作事实，不查询相邻动作或采样环境时间。 */
final class AgentExecutionObservations {
    private final AgentExecutionCodec codec;
    private final SessionExecutionDelegations delegations;
    private final SessionExecutionWorkflows workflows;
    private final CodecLimits limits;

    AgentExecutionObservations(CodecLimits limits) {
        this.limits = limits;
        codec = new AgentExecutionCodec(limits);
        delegations = new SessionExecutionDelegations(limits);
        workflows = new SessionExecutionWorkflows(limits);
    }

    List<Event> registered(Run run, AgentExecutionProgram.Materials before, Prepared prepared, String attempt) {
        var events = new ArrayList<Event>();
        if (prepared.kind() == ExecutionStore.Kind.MODEL)
            events.add(guard(run, before, prepared, attempt, Stage.MODEL_INPUT, Status.SUCCEEDED, null));
        else if (prepared.kind() == ExecutionStore.Kind.TOOL || prepared.kind() == ExecutionStore.Kind.DELEGATION)
            events.add(guard(run, before, prepared, attempt, Stage.TOOL_INPUT, Status.SUCCEEDED, null));
        events.addAll(operation(run, before, prepared, attempt, Status.REGISTERED));
        return List.copyOf(events);
    }

    List<Event> operation(Run run, AgentExecutionProgram.Materials before, Prepared prepared, String attempt, Status status) {
        String action = prepared.action().actionId();
        var main = fact(run, before, prepared, attempt, action, parent(run, before, prepared), kind(prepared), name(prepared), status, null, null);
        if (prepared.kind() != ExecutionStore.Kind.KNOWLEDGE) return List.of(ExecutionObservation.event(main, limits));
        var retrieval = fact(run, before, prepared, attempt, ExecutionObservation.retrievalId(action), action,
                Kind.RETRIEVAL, "知识操作", status, null, null);
        return List.of(ExecutionObservation.event(main, limits), ExecutionObservation.event(retrieval, limits));
    }

    Event guard(Run run, AgentExecutionProgram.Materials before, Prepared prepared, String attempt,
            Stage stage, Status status, String reason) {
        String step = step(run, before, prepared);
        return ExecutionObservation.event(fact(run, before, prepared, attempt,
                "guard:" + attempt + ":" + stage.name(), step == null ? ExecutionObservation.agentId(run.identity().runRef().runId()) : step,
                Kind.GUARDRAIL, guardName(stage), status, stage, reason), limits);
    }

    String attempt(Prepared prepared) {
        long generation = prepared.kind() == ExecutionStore.Kind.TOOL
                ? codec.decodeTool(prepared.request().content()).continuation().map(value -> Math.incrementExact(value.waiting().generation())).orElse(1L)
                : 1L;
        return prepared.action().actionId() + ":attempt:" + generation;
    }

    /** 仅从本次冻结续跑请求取已接受输入；后继动作继续传播已提交材料中的来源。 */
    InputId input(AgentExecutionProgram.Materials before, Prepared prepared) {
        if (prepared.kind() == ExecutionStore.Kind.TOOL)
            return codec.decodeTool(prepared.request().content()).continuation().map(AgentExecutionCodec.ToolContinuation::inputId)
                    .orElse(before.observationInput());
        if (prepared.kind() == ExecutionStore.Kind.WORKFLOW
                && workflows.decode(prepared.request().content()) instanceof SessionExecutionWorkflows.Resume resume)
            return resume.inputId();
        return before.observationInput();
    }

    Event agentOutput(Run run, AgentExecutionProgram.Materials before, Status status, String reason) {
        var identity = run.identity();
        String action = AgentExecutionProgram.actionId(run, before.modelOrdinal());
        String attempt = action + ":attempt:1";
        boolean originalInput = before.observationInput().equals(identity.causationInputId());
        return ExecutionObservation.event(new Fact("guard:" + attempt + ":" + Stage.AGENT_OUTPUT.name(),
                ExecutionObservation.stepId(action), Kind.GUARDRAIL, guardName(Stage.AGENT_OUTPUT), status,
                identity.runRef().runId(), action, attempt, ExecutionObservation.stepId(action),
                Integer.toString(before.usage().businessModelCalls()), before.observationInput().value(),
                identity.trace().rootInputId().value(), originalInput ? identity.trace().traceId() : null, originalInput ? identity.trace().spanId() : null,
                Stage.AGENT_OUTPUT, reason, null), limits);
    }

    private Fact fact(Run run, AgentExecutionProgram.Materials before, Prepared prepared, String attempt,
            String operation, String parent, Kind kind, String name, Status status, Stage stage, String reason) {
        var identity = run.identity();
        String step = step(run, before, prepared);
        int iteration = prepared.kind() == ExecutionStore.Kind.MODEL
                ? Math.incrementExact(before.usage().businessModelCalls()) : before.usage().businessModelCalls();
        var input = input(before, prepared);
        boolean originalInput = input.equals(identity.causationInputId());
        return new Fact(operation, parent, kind, name, status, identity.runRef().runId(), prepared.action().actionId(),
                attempt, step, step == null ? null : Integer.toString(iteration), input.value(),
                identity.trace().rootInputId().value(), originalInput ? identity.trace().traceId() : null, originalInput ? identity.trace().spanId() : null, stage, reason, null);
    }

    private static String step(Run run, AgentExecutionProgram.Materials before, Prepared prepared) {
        long modelOrdinal = prepared.kind() == ExecutionStore.Kind.MODEL ? prepared.ordinal() : before.modelOrdinal();
        return modelOrdinal == 0 ? null : ExecutionObservation.stepId(AgentExecutionProgram.actionId(run, modelOrdinal));
    }

    private static String parent(Run run, AgentExecutionProgram.Materials before, Prepared prepared) {
        String step = step(run, before, prepared);
        return step == null ? ExecutionObservation.agentId(run.identity().runRef().runId()) : step;
    }

    private static Kind kind(Prepared prepared) {
        return switch (prepared.kind()) {
            case MODEL -> Kind.LLM;
            case TOOL, KNOWLEDGE, DELEGATION -> Kind.TOOL;
            case WORKFLOW -> Kind.WORKFLOW;
        };
    }

    private String name(Prepared prepared) {
        return switch (prepared.kind()) {
            case MODEL -> prepared.target();
            case TOOL -> codec.decodeTool(prepared.request().content()).wireName();
            case KNOWLEDGE -> codec.decodeKnowledgeRequest(prepared.request().content()).wireName();
            case DELEGATION -> delegations.decodeRequest(prepared.request().content()).wireName();
            case WORKFLOW -> workflows.decode(prepared.request().content()).target().handle().value();
        };
    }

    private static String guardName(Stage stage) {
        return switch (stage) {
            case MODEL_INPUT -> "模型输入契约检查";
            case MODEL_OUTPUT -> "模型输出契约检查";
            case TOOL_INPUT -> "工具参数契约检查";
            case TOOL_OUTPUT -> "工具结果契约检查";
            case AGENT_OUTPUT -> "最终输出检查";
        };
    }

    static Status status(ExecutionStore.ResultKind status) {
        return switch (status) {
            case SUCCEEDED -> Status.SUCCEEDED;
            case FAILED -> Status.FAILED;
            case UNKNOWN -> Status.UNKNOWN;
            case INPUT_REQUIRED -> Status.WAITING;
        };
    }

    /** Workflow回执的确定性不等于业务完成；已知挂起、取消和终止保留原类型。 */
    static Status workflowStatus(WorkflowResult result) {
        return switch (result) {
            case WorkflowResult.Completed ignored -> Status.SUCCEEDED;
            case WorkflowResult.Suspended ignored -> Status.WAITING;
            case WorkflowResult.Failed ignored -> Status.FAILED;
            case WorkflowResult.Cancelled ignored -> Status.CANCELLED;
            case WorkflowResult.Terminated ignored -> Status.TERMINATED;
            case WorkflowResult.OutcomeUnknown ignored -> Status.UNKNOWN;
            case WorkflowResult.Accepted ignored -> throw new IllegalArgumentException("execution_workflow_accepted_not_supported");
        };
    }
}
