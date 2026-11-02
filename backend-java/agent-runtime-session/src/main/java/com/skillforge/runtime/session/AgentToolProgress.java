package com.skillforge.runtime.session;

import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.ToolEffect;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ModelPort.ModelMessage;
import com.skillforge.runtime.api.port.ModelPort.ModelRole;
import com.skillforge.runtime.api.port.ToolPortal.ToolOutcome;
import com.skillforge.runtime.api.port.ToolPortal.FrozenToolTarget;
import com.skillforge.runtime.api.port.ToolPortal.ToolBinding;
import com.skillforge.runtime.api.port.RetryMode;
import com.skillforge.runtime.core.AgentAction;
import com.skillforge.runtime.core.AgentExecutionLoop.ToolObservation;
import com.skillforge.runtime.core.AgentExecutionLoop.ToolSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 只依据已采用的模型槽和确定的原工具结果计算可恢复的有界进展。 */
final class AgentToolProgress {
    static final String SCHEMA = "skillforge.runtime.tool-progress.v3";
    private static final int WINDOW = 20;
    enum Level { CLEAR, WARN, STOP }
    sealed interface Result permits Success, Failure { }
    record Success(TypedPayload observation) implements Result {
        @Override public String toString() { return "ToolProgressSuccess[REDACTED]"; }
    }
    record Failure(String reason, RetryMode retryMode, java.util.Optional<TypedPayload> evidence, java.util.Optional<ToolSource> unknownSource) implements Result {
        @Override public String toString() { return "ToolProgressFailure[REDACTED]"; }
    }
    record ResourceFact(int slot, AgentAction.ReadSkillResource action) {
        @Override public String toString() { return "ToolProgressResource[REDACTED]"; }
    }
    record Signature(FrozenToolTarget target, ToolBinding binding, TypedPayload arguments, Result result, List<ResourceFact> resources) {
        Signature { resources = List.copyOf(resources); }
        @Override public String toString() { return "ToolProgressSignature[REDACTED]"; }
    }
    record Sample(Signature signature, ToolSource source, ResultRef modelResult, long modelOrdinal, int slot, boolean stoppable) {
        Sample { Objects.requireNonNull(signature); Objects.requireNonNull(source); Objects.requireNonNull(modelResult); if (modelOrdinal < 1 || slot < 1) throw new IllegalArgumentException("tool_progress_invalid"); }
        @Override public String toString() { return "ToolProgressSample[REDACTED]"; }
    }
    record Decision(Level level, int period, int repetitions, List<Sample> samples, long steeringThrough) {
        Decision { samples = List.copyOf(samples); }
    }
    private AgentToolProgress() { }

    /** 首次装入冻结目录时执行一次，必须早于任何Provider派发。 */
    static void validatePolling(FrozenToolTarget target, AgentExecutionCodec codec) {
        var schema = codec.parse(target.inputSchema(), target.inputSchema().schemaId());
        var polling = schema.get("x-skillforge-polling");
        if (polling != null && (!polling.isBoolean() || polling.booleanValue() && target.effect() != ToolEffect.READ_ONLY))
            throw new IllegalArgumentException("tool_progress_polling_invalid");
    }

    static List<Sample> append(List<Sample> before, ToolObservation observation, ResultRef modelResult, long modelOrdinal, int slot,
            ToolOutcome outcome, java.util.Optional<TypedPayload> failureEvidence, java.util.function.Supplier<List<ResourceFact>> resources, AgentExecutionCodec codec) {
        var target = observation.tool().binding().target();
        var schema = codec.parse(target.inputSchema(), target.inputSchema().schemaId());
        var polling = schema.get("x-skillforge-polling");
        if (polling != null && polling.booleanValue()) return List.of();
        final Result result;
        if (outcome instanceof ToolOutcome.Succeeded success) result = new Success(success.observation());
        else if (outcome instanceof ToolOutcome.Failed failed) result = new Failure(failed.failure().reasonCode(), failed.failure().retryMode(),
                failureEvidence, failureEvidence.isEmpty() ? java.util.Optional.of(observation.source()) : java.util.Optional.empty());
        else throw new IllegalArgumentException("tool_progress_outcome_invalid");
        boolean stoppable = outcome instanceof ToolOutcome.Failed || target.effect() == ToolEffect.READ_ONLY;
        var value = new Sample(new Signature(target, observation.tool().binding().binding(), observation.call().arguments(), result, resources.get()),
                observation.source(), modelResult, modelOrdinal, slot, stoppable);
        var samples = new ArrayList<>(before); samples.add(value);
        return List.copyOf(samples.subList(Math.max(0, samples.size() - WINDOW), samples.size()));
    }

    static Decision evaluate(List<Sample> samples, long steeringThrough) {
        int size = samples.size(); Decision warning = null;
        for (int period = 1; period <= 4; period++) {
            if (size < period * 3) continue;
            int laps = 1;
            while (size >= period * (laps + 1)) {
                int prior = size - period * (laps + 1); boolean same = true;
                for (int i = 0; i < period; i++) if (!samples.get(prior + i).signature().equals(samples.get(size - period + i).signature())) { same = false; break; }
                if (!same) break;
                laps++;
            }
            if (laps >= 3) {
                var cycle = samples.subList(size - period * laps, size);
                if (laps >= 5 && cycle.stream().allMatch(Sample::stoppable)) return new Decision(Level.STOP, period, laps, samples, steeringThrough);
                if (warning == null) warning = new Decision(Level.WARN, period, laps, samples, steeringThrough);
            }
        }
        return warning != null ? warning : new Decision(Level.CLEAR, 0, 0, samples, steeringThrough);
    }

    static TypedPayload encode(Decision decision, AgentExecutionCodec codec) {
        var root = AgentExecutionCodec.object(); root.put("version", "tool-progress.v3"); root.put("level", decision.level().name());
        root.put("period", Integer.toString(decision.period())); root.put("repetitions", Integer.toString(decision.repetitions()));
        root.put("steeringThrough", Long.toString(decision.steeringThrough()));
        var samples = root.putArray("sources");
        for (var sample : decision.samples()) {
            var value = samples.addObject(); value.put("modelResult", sample.modelResult().value()); value.put("modelOrdinal", Long.toString(sample.modelOrdinal())); value.put("slot", Integer.toString(sample.slot()));
            value.put("tenant", sample.source().action().tenantId().value()); value.put("action", sample.source().action().actionId());
            value.put("attempt", sample.source().attemptId()); value.put("request", sample.source().request().value()); value.put("result", sample.source().result().value());
        }
        return codec.encode(SCHEMA, root);
    }

    static List<ModelMessage> messages(List<ModelMessage> messages, Decision decision, long ordinal) {
        if (decision.level() == Level.CLEAR) return messages;
        var result = new ArrayList<>(messages);
        result.add(new ModelMessage("execution-tool-progress-" + ordinal, ModelRole.USER,
                "工具进展：最近出现长度为 " + decision.period() + " 的相同调用和结果周期，连续 " + decision.repetitions() + " 轮。"));
        return List.copyOf(result);
    }
}
