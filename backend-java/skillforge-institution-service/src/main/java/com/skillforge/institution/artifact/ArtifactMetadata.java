package com.skillforge.institution.artifact;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/** 公开产物清单；不携带存储位置、访问票据或文件正文。 */
public record ArtifactMetadata(String id, @JsonProperty("session_id") String sessionId,
        @JsonProperty("run_id") String runId, @JsonProperty("invocation_id") String invocationId,
        String type, String name, long size, String sha256,
        @JsonProperty("expires_at") Instant expiresAt, String status, List<File> files,
        @JsonProperty("artifact_inputs") List<ArtifactProvenance> artifactInputs) {
    public ArtifactMetadata {
        files = List.copyOf(files);
        artifactInputs = List.copyOf(artifactInputs);
    }
    public record File(String path, long size, String sha256) { }
}
