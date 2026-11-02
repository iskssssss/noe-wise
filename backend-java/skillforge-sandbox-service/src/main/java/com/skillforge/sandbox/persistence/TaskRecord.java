package com.skillforge.sandbox.persistence;

import com.skillforge.execution.ArtifactFile;
import com.skillforge.execution.DependencySpec;
import com.skillforge.execution.ExecutionResponse;
import com.skillforge.execution.ExecutionProfile;
import com.skillforge.execution.ExecutionState;
import com.skillforge.execution.Language;
import com.skillforge.execution.WorkspaceBinding;

import java.time.Instant;
import java.util.List;

/** 任务的持久已知事实；未知结果也是终态事实，禁止据此重新执行代码。 */
public record TaskRecord(
        String taskId,
        String requestDigest,
        Language language,
        boolean programTools,
        List<DependencySpec> dependencies,
        Instant deadlineAt,
        ExecutionState state,
        String phase,
        String containerId,
        Integer exitCode,
        String stdout,
        String stderr,
        boolean stdoutTruncated,
        boolean stderrTruncated,
        String error,
        String deliveryError,
        List<ArtifactFile> files,
        Instant workspaceDeletedAt,
        Instant createdAt,
        Instant updatedAt,
        com.skillforge.execution.ExecutionPolicy executionPolicy,
        ExecutionProfile profile,
        String environmentVersion,
        String imageReference,
        String imageId,
        com.fasterxml.jackson.databind.JsonNode jsonResult,
        WorkspaceBinding workspace
) {

    /** 无持久工作区的执行事实入口；持续工作区任务须显式携带可信绑定。 */
    public TaskRecord(String taskId, String requestDigest, Language language, boolean programTools,
            List<DependencySpec> dependencies, Instant deadlineAt, ExecutionState state, String phase,
            String containerId, Integer exitCode, String stdout, String stderr, boolean stdoutTruncated,
            boolean stderrTruncated, String error, String deliveryError, List<ArtifactFile> files,
            Instant workspaceDeletedAt, Instant createdAt, Instant updatedAt,
            com.skillforge.execution.ExecutionPolicy executionPolicy, ExecutionProfile profile,
            String environmentVersion, String imageReference, String imageId,
            com.fasterxml.jackson.databind.JsonNode jsonResult) {
        this(taskId, requestDigest, language, programTools, dependencies, deadlineAt, state, phase, containerId,
                exitCode, stdout, stderr, stdoutTruncated, stderrTruncated, error, deliveryError, files,
                workspaceDeletedAt, createdAt, updatedAt, executionPolicy, profile, environmentVersion,
                imageReference, imageId, jsonResult, null);
    }

    public TaskRecord {
        java.util.Objects.requireNonNull(executionPolicy,"executionPolicy");
        java.util.Objects.requireNonNull(profile, "profile");
        if (imageReference == null || imageReference.isBlank()
                || imageId == null || !imageId.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sandbox_task_environment_identity_invalid");
        }
        if ((profile != ExecutionProfile.WORKFLOW_SCRIPT_V1 && environmentVersion != null)
                || (profile == ExecutionProfile.WORKFLOW_SCRIPT_V1
                && (environmentVersion == null || environmentVersion.isBlank()))) {
            throw new IllegalArgumentException("sandbox_task_environment_version_invalid");
        }
        if ((profile == ExecutionProfile.WORKSPACE_SHELL_V1
                && (language != Language.SHELL || workspace == null || programTools
                || (dependencies != null && !dependencies.isEmpty())))
                || (language == Language.SHELL && profile != ExecutionProfile.WORKSPACE_SHELL_V1)
                || (workspace != null && profile == ExecutionProfile.WORKFLOW_SCRIPT_V1)) {
            throw new IllegalArgumentException("sandbox_task_workspace_profile_invalid");
        }
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        files = files == null ? List.of() : List.copyOf(files);
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
        jsonResult = jsonResult == null || jsonResult.isNull() ? null : jsonResult.deepCopy();
    }

    public TaskRecord withState(ExecutionState nextState, String nextPhase, Instant now) {
        return new TaskRecord(taskId, requestDigest, language, programTools, dependencies, deadlineAt, nextState, nextPhase,
                containerId, exitCode, stdout, stderr, stdoutTruncated, stderrTruncated, error,
                deliveryError, files, workspaceDeletedAt, createdAt, now, executionPolicy, profile,
                environmentVersion, imageReference, imageId, jsonResult, workspace);
    }

    public TaskRecord withContainer(String nextContainerId, Instant now) {
        return new TaskRecord(taskId, requestDigest, language, programTools, dependencies, deadlineAt, state, phase,
                nextContainerId, exitCode, stdout, stderr, stdoutTruncated, stderrTruncated, error,
                deliveryError, files, workspaceDeletedAt, createdAt, now, executionPolicy, profile,
                environmentVersion, imageReference, imageId, jsonResult, workspace);
    }

    public TaskRecord finished(ExecutionState terminalState, String terminalPhase, Integer terminalExitCode,
                               String terminalStdout, String terminalStderr, boolean terminalStdoutTruncated,
                               boolean terminalStderrTruncated, String terminalError,
                               List<ArtifactFile> terminalFiles, Instant now) {
        return finished(terminalState, terminalPhase, terminalExitCode, terminalStdout, terminalStderr,
                terminalStdoutTruncated, terminalStderrTruncated, terminalError, null, terminalFiles, null, now);
    }

    public TaskRecord finished(ExecutionState terminalState, String terminalPhase, Integer terminalExitCode,
                               String terminalStdout, String terminalStderr, boolean terminalStdoutTruncated,
                               boolean terminalStderrTruncated, String terminalError, String terminalDeliveryError,
                               List<ArtifactFile> terminalFiles, Instant now) {
        return finished(terminalState, terminalPhase, terminalExitCode, terminalStdout, terminalStderr,
                terminalStdoutTruncated, terminalStderrTruncated, terminalError, terminalDeliveryError,
                terminalFiles, null, now);
    }

    public TaskRecord finished(ExecutionState terminalState, String terminalPhase, Integer terminalExitCode,
                               String terminalStdout, String terminalStderr, boolean terminalStdoutTruncated,
                               boolean terminalStderrTruncated, String terminalError, String terminalDeliveryError,
                               List<ArtifactFile> terminalFiles,
                               com.fasterxml.jackson.databind.JsonNode terminalJsonResult, Instant now) {
        return new TaskRecord(taskId, requestDigest, language, programTools, dependencies, deadlineAt, terminalState,
                terminalPhase, containerId, terminalExitCode, terminalStdout, terminalStderr,
                terminalStdoutTruncated, terminalStderrTruncated, terminalError, terminalDeliveryError, terminalFiles,
                workspaceDeletedAt, createdAt, now, executionPolicy, profile, environmentVersion, imageReference,
                imageId, terminalJsonResult, workspace);
    }

    public TaskRecord withWorkspaceDeleted(Instant now) {
        return new TaskRecord(taskId, requestDigest, language, programTools, dependencies, deadlineAt, state, phase,
                containerId, exitCode, stdout, stderr, stdoutTruncated, stderrTruncated, error, deliveryError,
                List.of(), now, createdAt, now, executionPolicy, profile, environmentVersion, imageReference,
                imageId, jsonResult, workspace);
    }

    @Override
    public com.fasterxml.jackson.databind.JsonNode jsonResult() {
        return jsonResult == null ? null : jsonResult.deepCopy();
    }
}
