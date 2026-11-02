package com.skillforge.core.shared.tracing;

/** 会话输入链和控制操作链共享的外部调用因果上下文。 */
public sealed interface InvocationContext permits TraceContext, ControlOperationContext {
}
