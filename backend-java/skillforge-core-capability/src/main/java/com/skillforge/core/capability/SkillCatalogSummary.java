package com.skillforge.core.capability;

/** 平台技能工作台摘要，包含定义说明与精确默认版本标签。 */
public record SkillCatalogSummary(
        Long id, String capabilityKey, String name, String description, String status,
        Long defaultVersionId, String defaultVersionKey) {}
