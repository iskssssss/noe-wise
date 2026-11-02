package com.skillforge.core.agent.model;

/** 定义所有者在首次发布前选择的消费方式；发布后不可切换。 */
public enum AgentDistributionPolicy {
    /** 允许独立安装，副本由目标机构独立发布，源更新不改写副本。 */
    OPEN_INSTALL,
    /** 只能按当前有效授权部署，实例持续保留来源和授权依据。 */
    AUTHORIZED_DEPLOY
}
