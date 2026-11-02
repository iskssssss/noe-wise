package com.skillforge.core.capability.connector;

import java.util.Map;

/** 由会话领域提供其权威公开协议，能力目录仅组合协议，不复制领域字段或限制。 */
public interface ToolSchemaContributor {
    Map<String, Object> codePublicPresentationSchema();
    PlatformToolCatalog.Entry contextReferenceEntry();
}
