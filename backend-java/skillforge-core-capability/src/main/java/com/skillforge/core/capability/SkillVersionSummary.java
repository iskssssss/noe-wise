package com.skillforge.core.capability;

/** 历史版本选择器所需的标量，不读取包文件或元数据正文。 */
public record SkillVersionSummary(Long id, String versionKey, String status) {}
