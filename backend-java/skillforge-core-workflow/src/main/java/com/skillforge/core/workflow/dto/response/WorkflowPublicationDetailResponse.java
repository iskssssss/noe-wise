package com.skillforge.core.workflow.dto.response;

import java.time.Instant;
import java.util.Map;

/** 稳定工作流下的精确不可变发布版本；current仅表示读取时的发布指针。 */
public record WorkflowPublicationDetailResponse(String workflowId, @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long definitionVersionId,
        Long definitionVersion, @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long capabilityVersionId, Instant createdAt, boolean current,
        Map<String, Object> definition) { }
