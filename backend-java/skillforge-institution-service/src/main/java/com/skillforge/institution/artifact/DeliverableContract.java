package com.skillforge.institution.artifact;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.runtime.api.RunStatus;
import com.skillforge.runtime.api.port.RunIdentity;
import java.util.List;

/** 成果协议只携带语义和不透明文件引用；身份、摘要及发布状态由宿主生成。 */
public final class DeliverableContract {
    private DeliverableContract() { }
    public record Plan(List<Planned> deliverables) {
        public Plan { deliverables = copy(deliverables); }
    }
    public record Planned(String key, String title, String kind,
            @JsonProperty("base_revision_id") String baseRevisionId, List<Format> formats) {
        public Planned { formats = copy(formats); }
    }
    public record Format(String format) { }
    public record Finalize(List<Selection> deliverables, List<Evidence> evidence,
            @JsonProperty("abandon_reason") String abandonReason,
            @JsonProperty("goal_completion") GoalCompletionRequest goalCompletion) {
        public Finalize { deliverables = copy(deliverables); evidence = copy(evidence); }
    }
    public record GoalCompletionRequest(@JsonProperty("expected_revision") long expectedRevision) { }
    /** 身份与幂等键由宿主从当前 Goal 和可信 Run 构造，不能由模型指定。 */
    public record GoalCompletionIntent(String goalId, long expectedRevision, String operationId) { }
    public record Publication(@JsonProperty("run_id") String runId,
            @JsonProperty("result_ref") String resultRef, String status, String reason,
            @JsonProperty("revision_refs") List<String> revisionRefs) {
        public Publication { revisionRefs = copy(revisionRefs); }
    }
    public record CompletionView(boolean pending, Publication publication) { }
    /** 已提交的发布事实与冻结意图；只供宿主在发布事务返回后结算 Goal。 */
    public record GoalCompletion(RunIdentity run, GoalCompletionIntent intent,
            RunStatus runStatus, Publication publication) { }
    /** 完整交付清单；文件与版本基准由宿主核验，候选无需预登记成果计划。 */
    public record Selection(String key, String title, String kind,
            @JsonProperty("base_revision_id") String baseRevisionId, List<Representation> representations) {
        public Selection { representations = copy(representations); }
    }
    public record Representation(String role, String format,
            @JsonProperty("primary_preview") boolean primaryPreview,
            @JsonProperty("entry_path") String entryPath, List<FileRef> files,
            @JsonProperty("source_files") List<FileRef> sourceFiles) {
        public Representation { files = copy(files); sourceFiles = copy(sourceFiles); }
    }
    public record FileRef(@JsonProperty("artifact_id") String artifactId, String path,
            @JsonProperty("file_id") String fileId) {
        public FileRef(String artifactId, String path) { this(artifactId, path, null); }
    }
    public record Evidence(@JsonProperty("artifact_id") String artifactId, String path, String summary) { }
    /** 冻结数据只由服务端核验后构造，模型不能传入哈希、文件名或成果ID自证。 */
    record Frozen(List<FrozenItem> deliverables, List<FrozenEvidence> evidence,
            GoalCompletionIntent goalCompletion) { }
    record FrozenItem(String deliverableId, String key, String title, String kind, String baseRevisionId,
            String fingerprint, List<FrozenRepresentation> representations) { }
    record FrozenRepresentation(String role, String format, boolean primaryPreview, String entryPath,
            String sourceHash, List<FrozenFile> files, List<FrozenFile> sources) { }
    record FrozenFile(String fileId, String artifactId, String path, String downloadName, long size, String sha256) { }
    record FrozenEvidence(FrozenFile file, String summary) { }
    private static <T> List<T> copy(List<T> value) { return value == null ? List.of() : List.copyOf(value); }
}
