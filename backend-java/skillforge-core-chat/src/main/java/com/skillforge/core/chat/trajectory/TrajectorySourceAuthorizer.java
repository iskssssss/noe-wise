package com.skillforge.core.chat.trajectory;

import com.skillforge.core.chat.observation.SessionObservationScope;

/** 宿主复核轨迹来源的当前历史读权；false 只允许受限占位，不得披露来源正文或计量。 */
public interface TrajectorySourceAuthorizer {
    /** 调用方先核父产品会话、真实持久父边及祖先读权；本方法不创建或恢复任何执行。 */
    boolean canRead(SessionObservationScope scope, TrajectoryQuery.Resource parent, Source source);

    /** 父边上下文来自持久运行关系，不能由 HTTP 参数、节点名称或模型输入构造。 */
    record Source(long tenantId, String sessionId, String rootInputId, String runId,
                  String agentId, String parentRunId, String originKind) { }
}
