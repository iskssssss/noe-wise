package com.skillforge.core.chat.contract;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;

/** 用户选择的公开材料；只携带选择器和版本，内容与名称必须由宿主重新授权读取。 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ChatInputReference.ArtifactFiles.class, name = ChatInputReference.ARTIFACT_FILES_KIND),
        @JsonSubTypes.Type(value = ChatInputReference.DeliverableRevision.class, name = ChatInputReference.DELIVERABLE_REVISION_KIND),
        @JsonSubTypes.Type(value = ChatInputReference.Message.class, name = ChatInputReference.MESSAGE_KIND),
        @JsonSubTypes.Type(value = ChatInputReference.Process.class, name = ChatInputReference.PROCESS_KIND)})
public sealed interface ChatInputReference {
    int MAX_REFERENCES=64;
    int MAX_ARTIFACT_FILES=2048;
    String ARTIFACT_FILES_KIND="artifact_files";
    String DELIVERABLE_REVISION_KIND="deliverable_revision";
    String MESSAGE_KIND="message";
    String PROCESS_KIND="process";
    String SESSION_SOURCE_KIND="session";
    String WORK_TASK_SOURCE_KIND="work_task";
    String INPUT_PURPOSE="input";
    String REVISION_BASE_PURPOSE="revision_base";
    String PLAN_TASK_KIND="plan";
    String DIRECT_TASK_KIND="direct";
    String INVALID_REFERENCE="chat_reference_invalid";
    record ArtifactFile(@JsonProperty("artifact_id") String artifactId, String path) {
        public ArtifactFile { artifactId = required(artifactId); path = required(path); }
    }
    record ArtifactFiles(List<ArtifactFile> files) implements ChatInputReference {
        public ArtifactFiles {
            files = List.copyOf(files);
            if (files.isEmpty() || files.size() > MAX_ARTIFACT_FILES || files.stream().distinct().count() != files.size()) throw invalid();
        }
    }
    record DeliverableRevision(@JsonProperty("revision_id") String revisionId, String purpose)
            implements ChatInputReference {
        public DeliverableRevision {
            revisionId = required(revisionId);
            if (!List.of(INPUT_PURPOSE, REVISION_BASE_PURPOSE).contains(purpose)) throw invalid();
        }
    }
    record Message(Source source, @JsonProperty("message_id") String messageId,
                   @JsonProperty("message_version") long messageVersion) implements ChatInputReference {
        public Message { Objects.requireNonNull(source); messageId = required(messageId); if (messageVersion <= 0) throw invalid(); }
    }
    record Process(Source source, @JsonProperty("process_id") String processId,
                   @JsonProperty("updated_sequence") long updatedSequence) implements ChatInputReference {
        public Process { Objects.requireNonNull(source); processId = required(processId); if (updatedSequence <= 0) throw invalid(); }
    }
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({@JsonSubTypes.Type(value = SessionSource.class, name = SESSION_SOURCE_KIND),
            @JsonSubTypes.Type(value = WorkTaskSource.class, name = WORK_TASK_SOURCE_KIND)})
    sealed interface Source permits SessionSource, WorkTaskSource { }
    record SessionSource() implements Source { }
    record WorkTaskSource(@JsonProperty("work_id") String workId,
                          @JsonProperty("task_kind") String taskKind,
                          @JsonProperty("task_id") String taskId) implements Source {
        public WorkTaskSource {
            workId = required(workId); taskId = required(taskId);
            if (!List.of(PLAN_TASK_KIND, DIRECT_TASK_KIND).contains(taskKind)) throw invalid();
        }
    }
    private static String required(String value) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) throw invalid();
        return value;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException(INVALID_REFERENCE); }
}
