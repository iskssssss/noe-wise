package com.skillforge.institution.artifact;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** 用户成果视图；文件下载只使用稳定引用，不保存票据。 */
public record DeliverableView(String id,@JsonProperty("deliverable_id") String deliverableId,int revision,
        boolean current,@JsonProperty("result_run_ids") List<String> resultRunIds,
        @JsonProperty("session_id") String sessionId,@JsonProperty("run_id") String runId,
        String type,String name,long size,String sha256,@JsonProperty("expires_at") String expiresAt,
        String status,List<File> files,@JsonProperty("artifact_inputs") List<ArtifactProvenance> artifactInputs,List<Evidence> evidence) {
    public record File(@JsonProperty("file_id") String fileId,@JsonProperty("artifact_id") String artifactId,
            String path,@JsonProperty("download_name") String downloadName,long size,String sha256,String format,
            @JsonProperty("representation_id") String representationId,@JsonProperty("primary_preview") boolean primaryPreview,boolean entry) { }
    public record Evidence(@JsonProperty("artifact_id") String artifactId,String path,String summary) { }
}
