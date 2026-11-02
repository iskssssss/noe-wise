package com.skillforge.institution.artifact;

/** 文件清理内部引用，不向调用方暴露磁盘位置。 */
public record ArtifactFileRow(String id,String artifactId,String path,String status) { }
