package com.skillforge.core.capability.application;

import java.util.List;

/** 发布事务内同步推进关联路由；失败必须向上传播以回滚整个发布事务。 */
public interface OpenPlatformPublicationListener {
    void advanceBindings(long tenantId, String resourceId, long resourceRevision, List<String> deploymentIds);
}
