package com.skillforge.core.workflow;

import com.skillforge.core.capability.KnowledgeSourceDescriptor;
import com.skillforge.core.capability.tool.ToolDeployment;

/** 安装时跨领域的发布/Provider 校验；运行授权仍在每次节点调用边界复核。 */
public interface WorkflowInstallationTargetAccess {
    void requireProvider(long tenant, ToolDeployment deployment);
    void requireKnowledge(long tenant, KnowledgeSourceDescriptor source, long revision);
    /** 仅在新执行准入解析当前发布源；返回精确修订供本轮冻结。 */
    long currentKnowledgeRevision(long tenant, KnowledgeSourceDescriptor source);
    void requireAgent(long tenant, String agentId);
}
