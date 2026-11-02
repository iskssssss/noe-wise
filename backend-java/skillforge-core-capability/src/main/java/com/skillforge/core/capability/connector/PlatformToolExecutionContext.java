package com.skillforge.core.capability.connector;

import com.skillforge.runtime.api.port.CancellationSignal;
import com.skillforge.runtime.api.port.PortCallContext;
import com.skillforge.runtime.api.port.RunIdentity;
import java.time.Instant;
import java.util.Objects;

/**
 * 平台 Tool Handler 的可信执行上下文。
 *
 * <p>该上下文只能由 Runtime Provider 根据已开始的 Action attempt 与调用控制构造，
 * 不包含可复用的 Action permit，也不得从模型参数补全或覆盖。
 * @param programChild 是否为冻结程序中的子调用，与 Workflow 节点来源独立
 * @param workflowNode 是否来自已验证的 WorkflowNodeOrigin，不由工具参数指定
 */
public record PlatformToolExecutionContext(
        PortCallContext callContext,
        String invocationId,
        String attemptId,
        boolean programChild,
        boolean workflowNode) {

    /** 普通调用构造入口；程序子身份由 Provider 从冻结调用链设置。 */
    public PlatformToolExecutionContext(PortCallContext callContext, String invocationId, String attemptId) {
        this(callContext, invocationId, attemptId, false, false);
    }

    /** 已有程序子调用入口；Workflow 来源只能由 Provider 的冻结调用解码取得。 */
    public PlatformToolExecutionContext(PortCallContext callContext, String invocationId, String attemptId, boolean programChild) {
        this(callContext, invocationId, attemptId, programChild, false);
    }

    public PlatformToolExecutionContext {
        callContext = Objects.requireNonNull(callContext, "callContext");
        invocationId = requireText(invocationId, "invocationId");
        attemptId = requireText(attemptId, "attemptId");
    }

    public RunIdentity run() { return callContext.runIdentity(); }
    public Instant deadlineAt() { return callContext.control().deadlineAt(); }
    public CancellationSignal cancellationSignal() { return callContext.control().cancellationSignal(); }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
