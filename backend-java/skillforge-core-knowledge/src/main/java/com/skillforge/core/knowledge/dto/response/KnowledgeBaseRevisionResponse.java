package com.skillforge.core.knowledge.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/** Knowledge Base revision 生命周期响应。 */
public record KnowledgeBaseRevisionResponse(
        @Schema(type = "string", description = "revision 主键")
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long id,
        @Schema(type = "string", description = "知识库 ID")
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long kbId,
        @Schema(description = "知识库内单调 revision") Long revision,
        @Schema(description = "draft / ready / published / archived") String status,
        @Schema(description = "不可变 source-version manifest") List<String> sourceVersions,
        Instant createdAt,
        Instant readyAt,
        Instant publishedAt,
        @Schema(description = "ready 时冻结的完整 Knowledge Source 治理策略")
        KnowledgeCapabilityPolicyResponse policy,
        @Schema(type = "string", pattern = "[1-9][0-9]*", description = "发布时原子关联的 PUBLISHED KNOWLEDGE_SOURCE CapabilityVersion 主键")
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
        Long capabilityVersionId,
        @Schema(description = "由服务端按 manifest+descriptor 确定性计算的 SHA-256")
        String capabilityContentHash) {
}
