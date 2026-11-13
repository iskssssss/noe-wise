package com.skillforge.institution.dto.capability;

import com.skillforge.core.capability.connector.PlatformToolCatalog;
import java.util.Map;

/**
 * 机构端可用于 ToolDeployment Provider 选择的只读平台工具投影。
 *
 * <p>身份、描述与严格 Tool 描述直接来自 {@link PlatformToolCatalog}；
 * 该 DTO 不包含执行器、凭证或任何写入入口字段。
 */
public record PlatformToolResponse(
        String name,
        String title,
        String description,
        String contentHash,
        Map<String, Object> descriptor) {

    public static PlatformToolResponse from(PlatformToolCatalog.Entry entry) {
        return new PlatformToolResponse(
                entry.name(), entry.title(), entry.description(), entry.contentHash(), entry.descriptor());
    }
}
