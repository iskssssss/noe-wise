package com.skillforge.core.workflow.dto.response;

import java.util.List;

/** Builder diff 审阅响应；没有已发布基线时 {@code baseDefinitionVersion} 为 null、changes 为空。 */
public record WorkflowDraftDiffResponse(
        long draftRevision,
        Long baseDefinitionVersion,
        List<WorkflowDefinitionDiffEntry> changes) {
}
