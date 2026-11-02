package com.skillforge.runtime.session.spi;

/** Workflow原业务等待的封闭响应schema；typed恢复不创建旧Session输入。 */
public final class SessionWorkflowWakeups {
    public static final String SIGNAL_SCHEMA="skillforge.runtime.workflow-external-signal.v1";
    public static final String ASYNC_RESPONSE_SCHEMA="skillforge.runtime.workflow-async-response.v1";
    private SessionWorkflowWakeups() { }
}
