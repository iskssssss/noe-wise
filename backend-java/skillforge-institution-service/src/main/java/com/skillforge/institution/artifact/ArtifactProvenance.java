package com.skillforge.institution.artifact;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 已发布产物引用的不可变来源事实；正文不进入元数据或日志。 */
public record ArtifactProvenance(
        @JsonProperty("artifact_id") String artifactId,
        String path,
        long size,
        String sha256) { }
