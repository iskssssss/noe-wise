package com.skillforge.core.agent;

import java.util.Optional;

/** 宿主公开定义部署解析扩展；普通独立 Agent 返回空，Run 冻结后不再解析当前定义。 */
public interface AgentExecutionPublicationResolver {
    /** 新Run冻结前准备宿主需要的机构部署，独立Agent无需准备；恢复已冻结Run不调用。 */
    default void prepareExecution(long tenantId,long agentId) { }
    /** 解析当前定义与机构绑定；缺依赖或授权撤销必须拒绝，不能回退旧发布。 */
    Optional<AgentPublicationService.Publication> resolve(long tenantId, long agentId);
}
