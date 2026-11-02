package com.skillforge.execution;

/** 区分通用代码执行与 Workflow 脚本的隔离协议。 */
public enum ExecutionProfile {
    STANDARD,
    WORKFLOW_SCRIPT_V1,
    WORKSPACE_SHELL_V1
}
