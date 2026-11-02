package com.skillforge.institution.artifact;

/** 检查凭据只保存被检查文件的摘要和私有值范围摘要，不保存提取正文或命中值。 */
public record ArtifactInspectionRow(String path, long size, String sha256, String scopeDigest, String policyVersion) { }
