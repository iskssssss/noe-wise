package com.skillforge.core.workflow.dto.request;

import java.util.Map;

/** 保存当前 draft revision 的布局与视口；两者均不会进入运行定义。 */
public record SaveWorkflowBuilderSnapshotRequest(
        Map<String, Object> layout,
        Map<String, Object> viewport) {
}
