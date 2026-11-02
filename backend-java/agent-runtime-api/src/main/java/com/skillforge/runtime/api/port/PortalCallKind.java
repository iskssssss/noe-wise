package com.skillforge.runtime.api.port;

/** 枚举进入 Action Ledger 的规范 Provider 调用种类。 */
public enum PortalCallKind {
    MODEL_GENERATE,
    MODEL_STREAM,
    TOOL_INVOKE,
    WORKFLOW_DISPATCH,
    WORKFLOW_RESUME,
    WORKFLOW_RECOVER_RESULT,
    WORKFLOW_CANCEL,
    KNOWLEDGE_EXECUTE
}
