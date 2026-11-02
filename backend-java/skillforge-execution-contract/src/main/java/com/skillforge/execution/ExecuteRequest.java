package com.skillforge.execution;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** 执行控制请求；inputs 仅含清单，不允许内嵌文件内容。 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record ExecuteRequest(String taskId, Language language, String code, List<DependencySpec> dependencies,
        List<ArtifactFile> inputs, String scriptPath, boolean programTools, Instant deadlineAt,
        ExecutionPolicy executionPolicy, List<String> outputFiles,
        ExecutionProfile profile, String environmentVersion, String inputJson, WorkspaceBinding workspace) {
    /** 无持久工作区的任务构造入口，仍显式选择执行 profile。 */
    public ExecuteRequest(String taskId, Language language, String code, List<DependencySpec> dependencies,
            List<ArtifactFile> inputs, String scriptPath, boolean programTools, Instant deadlineAt,
            ExecutionPolicy executionPolicy, List<String> outputFiles,
            ExecutionProfile profile, String environmentVersion, String inputJson) {
        this(taskId, language, code, dependencies, inputs, scriptPath, programTools, deadlineAt,
                executionPolicy, outputFiles, profile, environmentVersion, inputJson, null);
    }
    public ExecuteRequest {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(code, "code");
        dependencies=List.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
        inputs=inputs==null?List.of():List.copyOf(inputs);
        Objects.requireNonNull(deadlineAt, "deadlineAt");
        Objects.requireNonNull(executionPolicy, "executionPolicy");
        outputFiles=outputFiles==null?List.of():List.copyOf(outputFiles);
        Objects.requireNonNull(profile, "profile");
    }
}
