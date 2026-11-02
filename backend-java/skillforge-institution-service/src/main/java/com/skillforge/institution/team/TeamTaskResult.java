package com.skillforge.institution.team;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 团队任务成果的不可变内部事实；文件正文和授权凭据始终留在各自事实源。 */
public record TeamTaskResult(
        @JsonProperty("schema_id") String schemaId,
        @JsonProperty("result_id") String resultId,
        @JsonProperty("task_id") String taskId,
        @JsonProperty("output_name") String outputName,
        String title,
        String kind,
        String status,
        String summary,
        String content,
        List<Representation> representations,
        List<String> limitations,
        @JsonProperty("operation_receipt") OperationReceipt operationReceipt,
        Source source,
        Map<String, Object> authorization) {

    public static final String SCHEMA_ID = "team-task-result.v1";
    public static final Set<String> KINDS = Set.of("text", "document", "spreadsheet", "presentation",
            "image", "design", "website", "application", "code", "media", "operation", "configuration");
    public static final Set<String> STATUSES = Set.of("succeeded", "partial", "failed");
    public static final Set<String> ROLES = Set.of("source", "preview", "export", "external");

    public TeamTaskResult {
        schemaId = require(schemaId, 100, "schema_id");
        if (!SCHEMA_ID.equals(schemaId)) throw new IllegalArgumentException("unsupported result schema");
        resultId = require(resultId, 240, "result_id");
        taskId = require(taskId, 240, "task_id");
        outputName = require(outputName, 240, "output_name");
        title = require(title, 200, "title");
        kind = require(kind, 40, "kind");
        status = require(status, 40, "status");
        if (!KINDS.contains(kind) || !STATUSES.contains(status)) throw new IllegalArgumentException("invalid result kind or status");
        summary = optional(summary, 8_000, "summary");
        content = optional(content, 65_536, "content");
        representations = List.copyOf(representations == null ? List.of() : representations);
        limitations = List.copyOf(limitations == null ? List.of() : limitations);
        if (representations.size() > 32 || limitations.size() > 20) throw new IllegalArgumentException("too many result details");
        limitations.forEach(value -> require(value, 2_000, "limitation"));
        if ("text".equals(kind)) {
            if (content.isBlank() || limitations.isEmpty()) throw new IllegalArgumentException("text result requires content limitations");
        } else if (!content.isBlank()) {
            throw new IllegalArgumentException("only text result may carry inline content");
        }
        if ("operation".equals(kind) != (operationReceipt != null)) {
            throw new IllegalArgumentException("operation result requires verified receipt");
        }
        Objects.requireNonNull(source, "source");
        authorization = Map.copyOf(authorization == null ? Map.of() : authorization);
    }

    public record Representation(String label, @JsonProperty("media_type") String mediaType,
            @JsonProperty("artifact_id") String artifactId, String path, String role,
            @JsonProperty("sha256") String sha256) {
        public Representation {
            label = require(label, 200, "representation label");
            mediaType = require(mediaType, 200, "media_type");
            role = require(role, 20, "role");
            if (!ROLES.contains(role)) throw new IllegalArgumentException("invalid representation role");
            artifactId = optional(artifactId, 100, "artifact_id");
            path = optional(path, 2_000, "path");
            sha256 = optional(sha256, 64, "sha256");
            if ("external".equals(role)) {
                if (!artifactId.isBlank() || path.isBlank() || !sha256.isBlank())
                    throw new IllegalArgumentException("external representation requires verified public address");
            } else {
                if (artifactId.isBlank() != path.isBlank())
                    throw new IllegalArgumentException("artifact representation requires id and path");
                if (!artifactId.isBlank() && !sha256.matches("[0-9a-f]{64}"))
                    throw new IllegalArgumentException("artifact representation requires verified digest");
            }
        }
    }

    /** 从 Action Ledger 复制的内部核验回执；业务摘要不能替代这些核验字段。 */
    public record OperationReceipt(
            @JsonProperty("source_tool") String sourceTool,
            @JsonProperty("action_ref") String actionRef,
            @JsonProperty("invocation_id") String invocationId,
            @JsonProperty("attempt_id") String attemptId,
            @JsonProperty("fact_ref") String factRef,
            @JsonProperty("action_state") String actionState,
            @JsonProperty("observation_schema") String observationSchema,
            @JsonProperty("observation_json") String observationJson,
            @JsonProperty("public_urls") List<String> publicUrls) {
        public OperationReceipt {
            sourceTool = require(sourceTool, 240, "source_tool");
            actionRef = require(actionRef, 240, "action_ref");
            invocationId = require(invocationId, 240, "invocation_id");
            attemptId = require(attemptId, 240, "attempt_id");
            factRef = require(factRef, 240, "fact_ref");
            actionState = require(actionState, 40, "action_state");
            if (!Set.of("SUCCEEDED", "FAILED").contains(actionState))
                throw new IllegalArgumentException("operation action_state is invalid");
            observationSchema = require(observationSchema, 240, "observation_schema");
            observationJson = require(observationJson, 65_536, "observation_json");
            publicUrls = List.copyOf(publicUrls == null ? List.of() : publicUrls);
            if (publicUrls.size() > 32) throw new IllegalArgumentException("too many operation public urls");
            publicUrls.forEach(value -> require(value, 2_000, "public_url"));
        }
    }

    /** 来源事实与授权摘要分别保存，授权摘要不得包含令牌、密钥或原始许可。 */
    public record Source(String kind, @JsonProperty("event_ref") String eventRef,
            @JsonProperty("invocation_id") String invocationId,
            @JsonProperty("attempt_id") String attemptId,
            @JsonProperty("run_id") String runId) {
        public Source {
            kind = require(kind, 40, "source kind");
            eventRef = optional(eventRef, 240, "event_ref");
            invocationId = require(invocationId, 240, "invocation_id");
            attemptId = require(attemptId, 240, "attempt_id");
            runId = require(runId, 240, "run_id");
        }
    }

    private static String require(String value, int maximum, String field) {
        if (value == null || value.isBlank() || !value.equals(value.trim()) || value.length() > maximum)
            throw new IllegalArgumentException(field + " is invalid");
        return value;
    }

    private static String optional(String value, int maximum, String field) {
        value = Objects.requireNonNullElse(value, "");
        if (!value.equals(value.trim()) || value.length() > maximum) throw new IllegalArgumentException(field + " is invalid");
        return value;
    }
}
