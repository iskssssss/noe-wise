package com.skillforge.runtime.session.spi;

import com.skillforge.runtime.api.port.CommittedSessionEvent;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import java.util.List;
import java.util.Optional;

/** 显式选择任务的只读上下文；普通请求不分类、不搜索旧任务，也不以任务维护阻塞回复。 */
@FunctionalInterface
public interface SessionTaskContext {
    /** 显式任务选择无效或无权读取；不重试猜测另一任务。 */
    final class SelectionRejected extends IllegalStateException {
        public SelectionRejected() { super("task_selection_rejected"); }
    }
    String EVENT_TYPE = "agent.task-context";
    String SCHEMA = "skillforge.runtime.task-context.v3";

    Optional<CommittedSessionEvent> prepare(SessionPreparations.Key key, VerifiedIdentity actor,
                                          List<CommittedSessionEvent> sources);

    static SessionTaskContext disabled() {
        return (key, actor, sources) -> Optional.empty();
    }
}
