package com.skillforge.runtime.session.spi;

import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowQuery;
import java.util.Optional;

/** Workflow Provider 在当前查询授权下公开等待用户输入所需的最小事实。 */
public interface WorkflowInteractionView {
    Optional<Pending> find(WorkflowQuery query);

    record Pending(SessionOwner owner, RunRef runRef, ExecutionRef executionRef,
            long expectedRevision, String suspendRef, String actionKey, TypedPayload card) { }
}
