package com.skillforge.core.model;

/** 平台模型目录的标量投影，不读取完整配置或计费规则。 */
public record PlatformModelDirectorySummary(String id, String name, String kind, String providerId,
        String modelId, boolean enabled, Long publishedRevision, boolean hasUnpublishedChanges) { }
