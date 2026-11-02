package com.skillforge.runtime.api;

/** 定义 Workflow Execution 在调用方前台等待或由后台工作器推进的封闭运行模式。 */
public enum ExecutionMode {
    /** 调用方在当前交互边界等待执行推进或形成持久挂起。 */
    FOREGROUND,
    /** 调用方只提交持久任务，后续推进由后台工作器承担。 */
    BACKGROUND
}
