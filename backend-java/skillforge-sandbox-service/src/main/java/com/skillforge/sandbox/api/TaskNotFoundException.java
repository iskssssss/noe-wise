package com.skillforge.sandbox.api;

public class TaskNotFoundException extends RuntimeException {

    public TaskNotFoundException(String taskId) {
        super("未找到执行任务: " + taskId);
    }
}
