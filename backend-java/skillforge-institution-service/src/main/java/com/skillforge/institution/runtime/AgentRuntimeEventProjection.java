package com.skillforge.institution.runtime;

import com.skillforge.core.chat.session.SessionView;
import java.util.Map;
import java.util.Optional;

/** 产品只消费已提交的公开事件；执行状态和来源由当前运行引擎负责。 */
public interface AgentRuntimeEventProjection {
    void reconcile(SessionView session);
    Optional<Map<String, Object>> historyRetentionState(SessionView session);
}
