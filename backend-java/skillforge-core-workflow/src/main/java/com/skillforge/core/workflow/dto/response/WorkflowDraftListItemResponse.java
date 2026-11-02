package com.skillforge.core.workflow.dto.response;

/** Builder 容器列表项；只公开当前草稿与发布游标，不返回运行时定义。 */
public record WorkflowDraftListItemResponse(
        String workflowId,
        String status,
        long revision,
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long activeDefinitionVersionId,
        String name,
        String description) {
}
