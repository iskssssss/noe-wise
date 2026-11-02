package com.skillforge.core.workflow.dto.response;

/** 成功发布不可变 DefinitionVersion 的结果。 */
public record WorkflowPublishResponse(
        String workflowId,
        long draftRevision,
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) long definitionVersionId,
        long definitionVersion,
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) long capabilityVersionId) {
}
