package com.skillforge.runtime.adapter.jdbc.execution;

import com.skillforge.runtime.api.SessionSubject;
import com.skillforge.runtime.api.port.SessionOwner;

/** Workflow 行的用户投影；GROUP 的完整归属由非空 subject/target 列和组合外键约束。 */
final class ExecutionOwnerColumns {
    private ExecutionOwnerColumns() { }

    static Long user(SessionOwner owner) {
        return owner.subject() instanceof SessionSubject.User user ? user.endUserId().value() : null;
    }
}
