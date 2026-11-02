package com.skillforge.runtime.adapter.jdbc.execution;

/** 原Run补充输入的固定关系字段；正文由同租户不可变Material引用拥有。 */
final class ExecutionSupplementRow {
    public String tenantId, runId, inputId, contentRef, fingerprint, actorRef;
    /** Run锁内分配的原补充顺序，不增加模型/工具/知识业务计数。 */
    public long ordinal;
    /** 精确本地context采纳的Run版本；NULL表示仍待采纳，不等同模型消费。 */
    public Long appliedRevision;
}
