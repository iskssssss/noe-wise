package com.skillforge.runtime.session;

import com.skillforge.runtime.api.ToolEffect;
import com.skillforge.runtime.api.media.ModelContent;
import com.skillforge.runtime.api.port.ModelPort;
import com.skillforge.runtime.api.port.ModelPort.ModelMessage;
import com.skillforge.runtime.api.port.ModelPort.ModelRole;
import com.skillforge.runtime.api.port.ModelPort.ModelToolCall;
import com.skillforge.runtime.api.port.PortalCallKind;
import com.skillforge.runtime.api.port.ToolPortal.ToolConfirmationPolicy;
import com.skillforge.runtime.api.port.ToolPortal.ToolOutcome;
import com.skillforge.runtime.core.AgentAction;
import com.skillforge.runtime.core.AgentDecisionDecoder;
import com.skillforge.runtime.core.AgentExecutionLoop;
import com.skillforge.runtime.core.BudgetGuard;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.core.CoreRuntime;
import com.skillforge.runtime.core.ModelConversation;
import com.skillforge.runtime.core.ToolCallRouting;
import com.skillforge.runtime.session.AgentExecutionCodec.ToolRequest;
import com.skillforge.runtime.session.AgentExecutionProgram.Materials;
import com.skillforge.runtime.session.ExecutionCoordinator.BatchCall;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.spi.ExecutionStore.Run;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 真实tool_calls决定的纯批次校验与完整原生回合配对；不拥有派发、存储或线程。 */
final class AgentExecutionToolBatches {
    private final AgentExecutionLoop loop;
    private final CoreRuntime core;
    private final AgentExecutionCodec codec;
    private final CodecLimits limits;
    private final SessionRuntimePayloads payloads;

    /** 复用单工具同一结果解码和安全投影，不为并行路径另建输出规则。 */
    AgentExecutionToolBatches(AgentExecutionLoop loop, CoreRuntime core, AgentExecutionCodec codec, CodecLimits limits, SessionRuntimePayloads payloads) {
        this.loop = Objects.requireNonNull(loop); this.core = Objects.requireNonNull(core); this.codec = Objects.requireNonNull(codec);
        this.limits = Objects.requireNonNull(limits); this.payloads = Objects.requireNonNull(payloads);
    }

    /** 整批通过静态资格、精确去重、历史与预算预检后才向Session暴露所有槽。 */
    Selection select(Materials before, Duration elapsed) {
        var usage = before.usage();
        var common = BudgetGuard.evaluate(before.configuration().budget(), usage, BudgetGuard.Operation.CONTINUATION, elapsed);
        if (common.isPresent()) return new Limited(common.orElseThrow());
        final List<Slot> slots;
        try { slots = slots(before); }
        catch (BatchRejected rejected) { return new Invalid(rejected.reason); }
        var selected = new ArrayList<Selected>();
        for (var slot : slots) {
            var resolved = loop.resolveTool(slot.call(), loadedTools(before), before.completedTools(), AgentExecutionProgram.currentRead(before, slot.index() - 1), before.modelOrdinal(), slot.index());
            if (resolved instanceof AgentExecutionLoop.LoadedRejected rejected) return new Rejected(rejected.reason());
            if (resolved instanceof AgentExecutionLoop.LoadedToolReuse reuse) selected.add(new Selected(slot, Optional.of(reuse.original())));
            else if (resolved instanceof AgentExecutionLoop.LoadedSelected) {
                var limited = BudgetGuard.evaluate(before.configuration().budget(), usage, BudgetGuard.Operation.TOOL, elapsed);
                if (limited.isPresent()) return new Limited(limited.orElseThrow());
                usage = usage.nextToolCall(); selected.add(new Selected(slot, Optional.empty()));
            } else throw invalid();
        }
        return new Ready(selected);
    }

    /** 分类不授予派发权；即使预算已拒绝，也只能对完整合法批次取得逐槽资格。 */
    boolean validForClassification(Materials before) {
        try { slots(before); return true; }
        catch (BatchRejected rejected) { return false; }
    }

    /** 冻结批次顺序、目标资格及实际参数；未确认工具不会变为并行副作用。 */
    List<Slot> slots(Materials before) {
        if (before.pendingTool().isPresent() || before.pendingHuman().isPresent() || before.pendingDependency().isPresent() || before.problem().isPresent()
                || !(before.decision().orElseThrow(AgentExecutionToolBatches::invalid) instanceof AgentDecisionDecoder.DecodedBatch batch)) throw invalid();
        var result = new ArrayList<Slot>(); var requests = new HashSet<ToolRequest>();
        int index = 0;
        for (var call : batch.calls()) {
            var matches = before.configuration().tools().stream().filter(entry -> entry.target().handle().value().equals(call.toolHandle())).toList();
            if (matches.size() != 1) throw new BatchRejected("tool_not_visible");
            var entry = matches.getFirst();
            if (entry.target().effect() != ToolEffect.READ_ONLY
                    || entry.target().confirmation() != ToolConfirmationPolicy.NEVER || !entry.target().modelInvocable()) throw new BatchRejected("tool_batch_ineligible");
            var declaration = before.configuration().modelTools().stream().filter(tool -> tool.kind() == ModelPort.ModelToolKind.TOOL_CALL
                    && tool.toolHandle().equals(call.toolHandle())).findFirst().orElseThrow(() -> new BatchRejected("tool_not_visible"));
            var original = new ToolRequest(entry.target(), entry.binding(), call.arguments(), "batch-identity", declaration.wireName());
            if (!requests.add(original)) throw new BatchRejected("tool_batch_duplicate");
            int slot = ++index;
            result.add(new Slot(slot, call, new AgentExecutionLoop.LoadedTool(new ToolCallRouting.Binding(entry.target(), entry.binding(), entry.parallelSafe()), declaration.wireName()),
                    new ToolRequest(entry.target(), entry.binding(), call.arguments(), SessionNativeResponses.callId(before,slot-1), declaration.wireName())));
        }
        return List.copyOf(result);
    }

    /** 单个真实回调只做来源和安全验证；不追加不完整assistant/tool历史。 */
    Inspection inspect(Run run, Materials before, BatchCall actual, int index) {
        var slots = slots(before);
        if (index < 1 || index > slots.size()) throw invalid();
        var slot = slots.get(index - 1); var prepared = actual.action(); var request = codec.decodeTool(prepared.request().content());
        long ordinal = before.ordinal();
        for (int position = 0; position < index; position++) {
            var selected = loop.resolveTool(slots.get(position).call(), loadedTools(before), before.completedTools(), AgentExecutionProgram.currentRead(before, position), before.modelOrdinal(), position + 1);
            if (selected instanceof AgentExecutionLoop.LoadedSelected) ordinal = Math.incrementExact(ordinal);
            else if (!(selected instanceof AgentExecutionLoop.LoadedToolReuse) || position == index - 1) throw invalid();
        }
        var original = new ToolRequest(request.target(), request.binding(), request.arguments(), request.callId(), request.wireName());
        long generation = physicalCalls(actual);
        if (prepared.kind() != ExecutionStore.Kind.TOOL || prepared.ordinal() != ordinal
                || !prepared.action().tenantId().equals(run.identity().runRef().tenantId())
                || !prepared.action().actionId().equals("execution:" + run.identity().runRef().runId() + ":" + ordinal)
                || !prepared.target().equals(slot.call().toolHandle()) || !prepared.capability().equals(before.configuration().capabilityRef())
                || !prepared.fingerprint().equals(AgentExecutionCodec.digest(prepared.request().content().schemaId() + "\0" + prepared.request().content().canonicalJson()))
                || !original.equals(slot.request()) || !actual.attemptId().equals(prepared.action().actionId() + ":attempt:" + generation)
                || request.continuation().filter(value -> !value.waiting().action().equals(prepared.action())).isPresent()) throw invalid();
        var outcome = core.decodeToolOutcomeForInspection(codec.decodeOutcome(actual.result().result().content()), PortalCallKind.TOOL_INVOKE, payloads);
        var expectedKind = switch (outcome) {
            case ToolOutcome.Succeeded ignored -> ExecutionStore.ResultKind.SUCCEEDED;
            case ToolOutcome.Failed ignored -> ExecutionStore.ResultKind.FAILED;
            case ToolOutcome.InputRequired ignored -> ExecutionStore.ResultKind.INPUT_REQUIRED;
            default -> ExecutionStore.ResultKind.UNKNOWN;
        };
        if (actual.result().kind() != expectedKind) throw invalid();
        Optional<com.skillforge.runtime.api.TypedPayload> expected; boolean rejected = false;
        try { expected = codec.safeToolFeedback(request, outcome); }
        catch (AgentExecutionCodec.ToolOutputRejected denied) {
            if (!(outcome instanceof ToolOutcome.Succeeded) || actual.result().feedback().isPresent()) throw invalid();
            expected = Optional.empty(); rejected = true;
        }
        if (!expected.equals(actual.result().feedback().map(ExecutionStore.Material::content))) throw invalid();
        var contents = actual.result().feedback().map(value -> codec.toolContents(value.content())).orElse(List.of());
        for (var content : contents) if (content instanceof ModelContent.Image image && !image.media().owner().equals(run.identity().owner().sessionRef())) throw invalid();
        return new Inspection(slot, outcome, contents, rejected);
    }

    /** 最终物理请求的等待代数包含所有已发生INPUT_REQUIRED轮次，不把续跑当成首次调用。 */
    long physicalCalls(BatchCall actual) {
        return codec.decodeTool(actual.action().request().content()).continuation()
                .map(value -> Math.incrementExact(value.waiting().generation())).orElse(1L);
    }

    /** 只从当前已加载观察取原安全内容；来源引用必须逐一相等。 */
    AgentExecutionLoop.ToolObservation reused(Materials before, ExecutionStore.BatchMember.Reused source) {
        var slots = slots(before);
        if (source.slot() < 1 || source.slot() > slots.size()) throw invalid();
        var selected = loop.resolveTool(slots.get(source.slot() - 1).call(), loadedTools(before), before.completedTools(), AgentExecutionProgram.currentRead(before, source.slot() - 1), before.modelOrdinal(), source.slot());
        if (!(selected instanceof AgentExecutionLoop.LoadedToolReuse reuse)) throw invalid();
        var original = reuse.original(); var actual = original.source();
        if (!actual.action().equals(source.action()) || !actual.attemptId().equals(source.attemptId()) || !actual.request().equals(source.request())
                || !actual.result().equals(source.result()) || !original.feedback().equals(Optional.of(source.feedback()))) throw invalid();
        return original;
    }

    /** 全部安全反馈按原槽位配对；实际完成顺序不会改变模型看到的调用顺序。 */
    List<ModelMessage> append(Materials before, List<List<ModelContent>> contents) {
        var slots = slots(before);
        if (contents.size() != slots.size()) throw invalid();
        var assistant = SessionNativeResponses.assistant(before,
                slots.stream().map(slot -> new ModelToolCall(slot.request().callId(),slot.request().wireName(),slot.request().arguments())).toList());
        var results = new ArrayList<ModelMessage>();
        for (int index = 0; index < slots.size(); index++) results.add(new ModelMessage("execution-tool-" + before.modelOrdinal() + "-" + (index + 1), ModelRole.TOOL,
                contents.get(index), List.of(), Optional.of(slots.get(index).request().callId()), Optional.empty()));
        return ModelConversation.append(before.messages(), assistant, SessionNativeResponses.toolResults(before,results,limits));
    }

    /** 整组复用只复制已保存安全内容的原生配对，不制造新工具调用或预算。 */
    List<ModelMessage> reuseMessages(Materials before) {
        var contents = new ArrayList<List<ModelContent>>();
        for (var slot : slots(before)) {
            var resolved = loop.resolveTool(slot.call(), loadedTools(before), before.completedTools(), AgentExecutionProgram.currentRead(before, slot.index() - 1), before.modelOrdinal(), slot.index());
            if (!(resolved instanceof AgentExecutionLoop.LoadedToolReuse reuse) || reuse.original().feedback().isEmpty()) throw invalid();
            contents.add(AgentExecutionCodec.reusedToolContents(reuse.original().contents()));
        }
        return append(before, contents);
    }

    /** 目录完整目标参与历史匹配，不只比较模型展示句柄。 */
    private static List<AgentExecutionLoop.LoadedTool> loadedTools(Materials before) {
        return before.configuration().tools().stream().map(entry -> new AgentExecutionLoop.LoadedTool(
                new ToolCallRouting.Binding(entry.target(), entry.binding(), entry.parallelSafe()), before.configuration().modelTools().stream()
                    .filter(tool -> tool.kind() == ModelPort.ModelToolKind.TOOL_CALL && tool.toolHandle().equals(entry.target().handle().value())).findFirst().orElseThrow(AgentExecutionToolBatches::invalid).wireName())).toList();
    }

    /** 原决定有序槽位及唯一冻结工具请求。 */
    record Slot(int index, AgentAction.ToolCall call, AgentExecutionLoop.LoadedTool tool, ToolRequest request) { }
    /** 一个槽位的新调用或已保存精确结果；两者不能同时发送。 */
    record Selected(Slot slot, Optional<AgentExecutionLoop.ToolObservation> reuse) { }
    /** 全组预检结果只承载纯选择，不授予外部发送权。 */
    sealed interface Selection permits Ready, Rejected, Limited, Invalid { }
    /** 全组原顺序选择，只有reuse为空的槽需要实际Attempt。 */
    record Ready(List<Selected> slots) implements Selection { Ready { slots = List.copyOf(slots); } }
    /** 真实历史不允许新调用的明确拒绝。 */
    record Rejected(AgentExecutionLoop.LoadedRejection reason) implements Selection { }
    /** 静态模型调用拒绝，不产生任何外部派发。 */
    record Invalid(String reason) implements Selection { }
    private static final class BatchRejected extends IllegalArgumentException {
        private final String reason;
        private BatchRejected(String reason) { super(reason); this.reason = reason; }
    }
    /** 原冻结预算预检拒绝；余额仍由Store一次性登记。 */
    record Limited(BudgetGuard.Limit limit) implements Selection { }
    /** 实际原结果及独立安全投影结论，不把本地拒绝改写成外部失败。 */
    record Inspection(Slot slot, ToolOutcome outcome, List<ModelContent> contents, boolean outputRejected) { }
    /** 批次必须完整且来源相符，固定错误不泄露原参数。 */
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("execution_tool_batch_invalid"); }
}
