package com.skillforge.core.workflow.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 审核所见模板修订和真实包资源快照；不冒充 Builder 的不可变 DefinitionVersion。 */
public record MarketplaceReviewDetailResponse(
        String workflowId, String name, String version, String status, Instant updatedAt,
        String description, String author, List<Map<String, Object>> configSchema,
        List<String> requiredPermissions, List<Map<String, Object>> tools,
        List<Map<String, Object>> connectorRequirements, List<PackageFile> files) {
    public MarketplaceReviewDetailResponse { files = List.copyOf(files); }

    /** 文本按 UTF-8 展示，其他资源仅展示身份和真实字节数，不执行资源内容。 */
    public record PackageFile(String name, long sizeBytes, String content) {}
}
