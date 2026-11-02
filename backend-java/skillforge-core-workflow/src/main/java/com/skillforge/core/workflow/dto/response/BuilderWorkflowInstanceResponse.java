package com.skillforge.core.workflow.dto.response;

import java.time.Instant;
import java.util.Map;

/** Builder 工作流安装实例；只暴露不可变定义与 Capability 身份。 */
public record BuilderWorkflowInstanceResponse(
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long id,
        String instanceId,
        String workflowId,
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long definitionVersionId,
        Long installationRevision,
        @io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*")
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long capabilityVersionId,
        String status,
        Map<String, Object> config,
        String customPrompt,
        Instant createdAt) {
}
