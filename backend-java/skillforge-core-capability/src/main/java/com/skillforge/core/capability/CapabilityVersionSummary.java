package com.skillforge.core.capability;

/** 通用能力详情卡片所需的版本元数据；正文在选定编辑目标后读取。 */
public record CapabilityVersionSummary(
        Long id, String versionKey, String name, String description, String status) {}
