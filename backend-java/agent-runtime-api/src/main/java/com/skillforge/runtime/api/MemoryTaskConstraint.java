package com.skillforge.runtime.api;

/** 宿主核实后传入的任务候选约束；只收窄当前权限范围，不授予访问权。 */
public record MemoryTaskConstraint(String taskId) {
    public MemoryTaskConstraint {
        taskId = ApiContracts.requireText(taskId, "taskId");
    }
}
