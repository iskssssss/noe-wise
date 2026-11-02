package com.skillforge.institution.workflowtest;

import com.skillforge.runtime.api.SessionRef;

/** 后台生命周期只取消原持久测试Run，不能受理新输入或调用新目标。 */
@FunctionalInterface
public interface WorkflowTestCancellation {
    void cancelExpired(SessionRef session);
}
