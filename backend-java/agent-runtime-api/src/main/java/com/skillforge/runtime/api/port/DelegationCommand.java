package com.skillforge.runtime.api.port;

import java.util.*;

/** 模型只可选择服务端目录句柄及业务任务；执行身份、配置和预算不属于命令。 */
public record DelegationCommand(Operation operation, String targetHandle, Optional<Task> task,
                                List<String> delegationIds, String message, boolean background) {
    public enum Operation { TARGETS, START, LIST, GET, SEND, WAIT, CANCEL }
    public record Task(String objective, String context, List<String> constraints, String expectedOutput) {
        public Task { objective = PortContracts.requireText(objective, "objective");
            context = Objects.requireNonNullElse(context, ""); constraints = List.copyOf(constraints);
            constraints.forEach(value -> PortContracts.requireText(value, "constraint"));
            if (new HashSet<>(constraints).size() != constraints.size()) throw invalid();
            expectedOutput = Objects.requireNonNullElse(expectedOutput, ""); }
        @Override public String toString() { return "DelegatedTask[REDACTED]"; }
    }

    public DelegationCommand {
        Objects.requireNonNull(operation); targetHandle = Objects.requireNonNullElse(targetHandle, "");
        if (operation != Operation.START && !background) throw invalid();
        Objects.requireNonNull(task);
        delegationIds = List.copyOf(delegationIds); message = Objects.requireNonNullElse(message, "");
        delegationIds.forEach(value -> PortContracts.requireText(value, "delegationId"));
        if (new HashSet<>(delegationIds).size() != delegationIds.size()) throw invalid();
        if (operation == Operation.START) {
            if (targetHandle.isBlank() || task.isEmpty()
                    || !delegationIds.isEmpty() || !message.isEmpty()) throw invalid();
        } else {
            if (!targetHandle.isEmpty() || task.isPresent()) throw invalid();
            if ((operation == Operation.TARGETS || operation == Operation.LIST) && !delegationIds.isEmpty()) throw invalid();
            if ((operation == Operation.GET || operation == Operation.SEND || operation == Operation.CANCEL) && delegationIds.size() != 1) throw invalid();
            if (operation == Operation.WAIT && delegationIds.isEmpty()) throw invalid();
            if (operation == Operation.SEND ? message.isBlank() : !message.isEmpty()) throw invalid();
        }
    }
    public static DelegationCommand start(String handle, Task task) {
        return new DelegationCommand(Operation.START, handle, Optional.of(task), List.of(), "", true);
    }
    /** 单段业务指令不携带配置；默认等待由持久协调执行，不阻塞调用线程。 */
    public static DelegationCommand delegate(String handle, String instruction, boolean background) {
        return new DelegationCommand(Operation.START, handle,
                Optional.of(new Task(instruction, "", List.of(), "")), List.of(), "", background);
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("delegation_command_invalid"); }
    @Override public String toString() { return "DelegationCommand[" + operation + "]"; }
}
