package com.skillforge.core.workflow.dto.response;

import java.util.Map;

/** 当前 draft revision 的 BuilderSnapshot；未保存时 layout/viewport 为空对象。 */
public record WorkflowBuilderSnapshotResponse(
        String workflowId,
        long draftRevision,
        Map<String, Object> layout,
        Map<String, Object> viewport) {
}
