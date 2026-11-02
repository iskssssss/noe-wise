package com.skillforge.execution;

import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** 控制响应仅含真实状态、文件与日志引用；业务结果明确选择，日志正文不自动返回。 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record ExecutionResponse(String taskId, ExecutionState state, String phase, Integer exitCode,
        boolean stdoutTruncated, boolean stderrTruncated, String error, String deliveryError,
        List<ArtifactFile> files, List<LogReference> logs, JsonNode jsonResult, PublicResults publicResults) {
    public ExecutionResponse { files=files==null?List.of():List.copyOf(files); logs=logs==null?List.of():List.copyOf(logs); }
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record LogReference(String stream, int characters, boolean truncated) { }
}
