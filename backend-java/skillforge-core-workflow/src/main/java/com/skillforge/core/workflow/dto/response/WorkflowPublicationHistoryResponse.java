package com.skillforge.core.workflow.dto.response;

import java.time.Instant;
import java.util.List;

/** 稳定工作流身份下的不可变发布历史，草稿保存不计入历史。 */
public record WorkflowPublicationHistoryResponse(String workflowId, @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long currentDefinitionVersionId,
        List<Item> items) {
    public record Item(@com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long definitionVersionId, Long definitionVersion, @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long capabilityVersionId,
            Instant createdAt, boolean current) { }
}
