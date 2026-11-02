package com.skillforge.runtime.session;

import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.session.spi.ExecutionStore;

/** 模型公开过程只取真实派发与结果提交事实；首token、私有推理及参数分片不构成独立展示进度。 */
final class ExecutionModelLifecycle {
    /** 生命周期事件仅由所属Program在已有业务边界构造，不拥有存储或Provider权限。 */
    private ExecutionModelLifecycle() { }

    /** 只表示本次MODEL派发已登记，不推断网络已送达、首token已到达或生成成功。 */
    static ExecutionStore.Event started(ExecutionStore.Run run, ExecutionStore.Prepared prepared, AgentExecutionCodec codec) {
        return event(run, prepared, AgentExecutionProgram.MODEL_STARTED_EVENT, AgentExecutionProgram.MODEL_STARTED_SCHEMA,
                "DISPATCHED", "模型调用已登记", codec);
    }

    /** 结果状态与真实结果同事务保存；UNKNOWN保留未知，不能替换成成功概要。 */
    static ExecutionStore.Event finished(ExecutionStore.Run run, ExecutionStore.Prepared prepared,
            ExecutionStore.ResultKind status, AgentExecutionCodec codec) {
        String summary = switch (status) {
            case SUCCEEDED -> "模型调用已完成";
            case FAILED -> "模型调用失败";
            case UNKNOWN -> "模型结果未知";
            case INPUT_REQUIRED -> throw new IllegalArgumentException("execution_model_result_invalid");
        };
        return event(run, prepared, AgentExecutionProgram.MODEL_RESULT_EVENT, AgentExecutionProgram.MODEL_RESULT_SCHEMA,
                status.name(), summary, codec);
    }

    /** 封闭安全协议只含派发身份、可信因果、状态与固定说明，JSON编码止于事件边界。 */
    private static ExecutionStore.Event event(ExecutionStore.Run run, ExecutionStore.Prepared prepared,
            String type, String schema, String status, String summary, AgentExecutionCodec codec) {
        if (prepared.kind() != ExecutionStore.Kind.MODEL) throw new IllegalArgumentException("execution_model_kind_required");
        var identity = run.identity(); var value = AgentExecutionCodec.object();
        value.put("actionId", prepared.action().actionId()); value.put("status", status); value.put("summary", summary);
        value.put("inputId", identity.causationInputId().value()); value.put("rootInputId", identity.trace().rootInputId().value());
        value.put("traceId", identity.trace().traceId()); value.put("spanId", identity.trace().spanId()); value.put("agentId", identity.agentId().value());
        String prefix = AgentExecutionProgram.MODEL_STARTED_EVENT.equals(type) ? "execution-model-started:" : "execution-model-result:";
        return new ExecutionStore.Event(new EventId(prefix + AgentExecutionCodec.digest(prepared.action().toString())),
                type, codec.encode(schema, value));
    }
}
