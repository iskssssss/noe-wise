package com.skillforge.execution;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;
import java.util.Objects;

/** ZIP 首项只冻结执行控制与文件清单；正文始终在后续条目中逐流传输。 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record ExecutionManifest(ExecuteRequest execution, List<ArtifactFile> artifacts) {
    public ExecutionManifest {
        Objects.requireNonNull(execution, "execution");
        artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
        if (artifacts.size() > ExecutionLimits.MAX_FILES) throw new IllegalArgumentException("execution_files_limit");
    }
}
