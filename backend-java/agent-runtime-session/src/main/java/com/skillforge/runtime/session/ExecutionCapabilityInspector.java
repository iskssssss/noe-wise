package com.skillforge.runtime.session;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EvidenceBundle;
import com.skillforge.runtime.api.EvidenceItem;
import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.KnowledgeDocumentResult;
import com.skillforge.runtime.api.KnowledgeOperation;
import com.skillforge.runtime.api.KnowledgeSourceVersion;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.KnowledgePortal.FrozenKnowledgeTarget;
import com.skillforge.runtime.api.port.KnowledgePortal.KnowledgeOutcome;
import com.skillforge.runtime.api.port.ToolPortal.ToolOutcome;
import com.skillforge.runtime.api.port.ToolPortal.ToolBinding;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowControlResult;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowRecoveredResult;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowResult;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.core.CoreRuntime;
import com.skillforge.runtime.core.FrozenKnowledgeRequest;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 现行能力请求与结果的纯只读投影；不读取模型上下文、不调用 Provider，也不授予来源读取权。 */
public final class ExecutionCapabilityInspector {
    private static final String JSON_SCHEMA = "application/json";
    private final CodecLimits limits;
    private final AgentExecutionCodec codec;
    private final SessionExecutionWorkflows workflows;
    private final SessionExecutionDelegations delegations;
    private final SessionRuntimePayloads payloads = new SessionRuntimePayloads();
    private final ObjectMapper json = JsonMapper.builder().disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();

    /** 配额由宿主传入，公开调用方无需依赖 Core 私有类型。 */
    public ExecutionCapabilityInspector(int maxCodeUnits, int maxNodes, int maxDepth) {
        limits = new CodecLimits(maxCodeUnits, maxNodes, maxDepth);
        codec = new AgentExecutionCodec(limits);
        workflows = new SessionExecutionWorkflows(limits);
        delegations = new SessionExecutionDelegations(limits);
    }

    /** 宿主先核 Run／Action／Attempt／材料引用闭包，再对返回的公开正文执行当前授权及内容门禁。 */
    public Inspection inspect(ExecutionStore.Kind kind, TypedPayload request, Optional<TypedPayload> result) {
        Objects.requireNonNull(kind); Objects.requireNonNull(request); Objects.requireNonNull(result);
        return switch (kind) {
            case TOOL -> tool(request, result);
            case KNOWLEDGE -> knowledge(request, result);
            case WORKFLOW -> workflow(request, result);
            case DELEGATION -> delegation(request, result);
            case MODEL -> throw invalid();
        };
    }

    private Inspection tool(TypedPayload request, Optional<TypedPayload> result) {
        var safe = new ExecutionModelAttemptInspector(limits).inspectCapability(ExecutionStore.Kind.TOOL, request, result);
        var output = safe.output();
        if (output.isEmpty() && result.isPresent()) {
            var actual = codec.decodeToolOutcome(result.orElseThrow());
            output = switch (actual) {
                case ToolOutcome.Failed failure -> Optional.of(status("FAILED", failure.failure().reasonCode()));
                case ToolOutcome.OutcomeUnknown unknown -> Optional.of(status("OUTCOME_UNKNOWN", unknown.failure().reasonCode()));
                case ToolOutcome.InputRequired ignored -> Optional.of(status("INPUT_REQUIRED", null));
                case ToolOutcome.Succeeded ignored -> throw invalid();
            };
        }
        return new Inspection(safe.callId(), safe.title(), safe.input(), output,
                safe.redactedFields().isEmpty() ? Availability.FULL : Availability.PARTIAL,
                availability(output), safe.redactedFields(), Optional.empty(), Optional.of(codec.decodeTool(request).binding()));
    }

    private Inspection knowledge(TypedPayload request, Optional<TypedPayload> result) {
        var call = codec.decodeKnowledgeRequest(request);
        var operation = call.request().operation();
        var input = switch (operation) {
            case KnowledgeOperation.Search search -> body(new KnowledgeInput(call.target().handle().value(), "SEARCH",
                    search.query(), null, null, null, null, null, null, null, null));
            case KnowledgeOperation.ListDocuments list -> body(new KnowledgeInput(call.target().handle().value(), "LIST_DOCUMENTS",
                    null, list.titleFilter(), list.pageSize(), list.cursor().isPresent(), null, null, null, null, null));
            case KnowledgeOperation.ReadDocument read -> body(new KnowledgeInput(call.target().handle().value(), "READ_DOCUMENT",
                    null, null, null, null, read.sourceId(), read.documentId(), read.sourceVersion(), read.offset(), read.maxCharacters()));
        };
        var sources = new LinkedHashSet<KnowledgeSourceVersion>();
        var output = result.map(actual -> {
            var outcome = codec.decodeKnowledgeOutcome(actual);
            return switch (outcome) {
                case KnowledgeOutcome.Retrieved retrieved -> {
                    new FrozenKnowledgeRequest(call.target(), call.request()).requireResult(retrieved.bundle());
                    yield knowledgeOutput(retrieved.bundle(), sources);
                }
                case KnowledgeOutcome.Failed failed -> status("FAILED", failed.failure().reasonCode());
                case KnowledgeOutcome.OutcomeUnknown unknown -> status("OUTCOME_UNKNOWN", unknown.failure().reasonCode());
            };
        });
        var access = new KnowledgeAccess(call.request().tenantId(), call.request().agentId(), call.request().sessionRef(),
                call.target(), sources, call.request().knowledgeRequestId(), call.request().trace());
        return new Inspection(call.callId(), call.wireName(), input, output, Availability.FULL,
                availability(output), List.of(), Optional.of(access), Optional.empty());
    }

    /** 只展示检索已选项；此处不持有最终上下文 delta，不能把检索选择冒充已注入或已引用。 */
    private TypedPayload knowledgeOutput(EvidenceBundle bundle, Set<KnowledgeSourceVersion> sources) {
        var selected = new LinkedHashSet<String>();
        bundle.trace().forEach(step -> selected.addAll(step.selectedItemIds()));
        var items = new ArrayList<KnowledgeItem>();
        for (var item : bundle.items()) {
            if (!selected.contains(item.itemId())) continue;
            sources.add(new KnowledgeSourceVersion(item.sourceId(), item.sourceVersion()));
            items.add(new KnowledgeItem(item.itemId(), item.sourceId(), item.sourceVersion(), item.operator().name(),
                    knowledgeText(item), item.trustLevel()));
        }
        DocumentAccess document = null;
        if (bundle.documentResult().orElse(null) instanceof KnowledgeDocumentResult.CatalogPage page) {
            var documents = page.documents().stream().map(value -> document(value, sources)).toList();
            document = new DocumentAccess("CATALOG_PAGE", documents, page.nextCursor().isPresent(), null, null, null);
        } else if (bundle.documentResult().orElse(null) instanceof KnowledgeDocumentResult.DocumentSlice slice) {
            document = new DocumentAccess("DOCUMENT_SLICE", List.of(document(slice.document(), sources)), false,
                    slice.startOffset(), slice.endOffset(), slice.nextOffset().isPresent() ? slice.nextOffset().getAsInt() : null);
        }
        return body(new KnowledgeOutput(bundle.bundleId(), Long.toString(bundle.version()), bundle.sufficiency().name(),
                "RETRIEVAL_SELECTED", List.copyOf(items), document));
    }

    private String knowledgeText(EvidenceItem item) {
        if (!Set.of(SessionRuntimePayloads.KNOWLEDGE_CHUNK_SCHEMA, SessionRuntimePayloads.KNOWLEDGE_LINK_SCHEMA,
                "skillforge.runtime.knowledge.document-slice.v1").contains(item.content().schemaId())) throw invalid();
        var value = read(item.content(), KnowledgeText.class);
        if (value.text() == null) throw invalid();
        return value.text();
    }

    private static Document document(KnowledgeDocumentResult.Document value, Set<KnowledgeSourceVersion> sources) {
        sources.add(new KnowledgeSourceVersion(value.sourceId(), value.sourceVersion()));
        return new Document(value.sourceId(), value.documentId(), value.sourceVersion(), value.title(), value.totalCharacters());
    }

    private Inspection workflow(TypedPayload request, Optional<TypedPayload> result) {
        var call = workflows.decode(request);
        final TypedPayload input;
        final String callId;
        final String title;
        if (call instanceof SessionExecutionWorkflows.Dispatch dispatch) {
            input = dispatch.parameters();
            callId = dispatch.origin() instanceof SessionExecutionWorkflows.ModelOrigin model ? model.callId() : null;
            title = dispatch.origin() instanceof SessionExecutionWorkflows.ModelOrigin model ? model.wireName() : "workflow";
        } else if (call instanceof SessionExecutionWorkflows.Resume resume) {
            input = resume.response();
            callId = resume.origin() instanceof SessionExecutionWorkflows.ModelOrigin model ? model.callId() : null;
            title = resume.origin() instanceof SessionExecutionWorkflows.ModelOrigin model ? model.wireName() : "workflow";
        } else {
            var cancel = call instanceof SessionExecutionWorkflows.Cancel value ? value : null;
            input = body(new WorkflowControlInput(cancel == null ? "QUERY" : "CANCEL", call.executionRef().executionId(),
                    cancel == null ? null : cancel.reasonCode(), cancel == null ? null : Long.toString(cancel.expectedRevision())));
            callId = null;
            title = "workflow";
        }
        payloads.validate(input, limits);
        var output = result.map(actual -> workflowOutput(call, actual));
        return new Inspection(callId, title, input, output, Availability.FULL, availability(output), List.of(), Optional.empty(), Optional.empty());
    }

    private TypedPayload workflowOutput(SessionExecutionWorkflows.Request request, TypedPayload actual) {
        var envelope = workflows.decodeResult(actual);
        var snapshot = codec.decodeOutcome(envelope.outcome());
        var results = new CoreRuntime(limits, payloads).portalResults(payloads, payloads, payloads);
        if (request instanceof SessionExecutionWorkflows.Query) {
            return switch (results.decodeWorkflowRecovery(snapshot, request.callKind())) {
                case WorkflowRecoveredResult.Completed value -> workflowResult(request.executionRef(), value.result());
                case WorkflowRecoveredResult.Suspended value -> workflowResult(request.executionRef(), value.result());
                case WorkflowRecoveredResult.Failed value -> workflowResult(request.executionRef(), value.result());
                case WorkflowRecoveredResult.Cancelled value -> workflowResult(request.executionRef(), value.result());
                case WorkflowRecoveredResult.Terminated value -> workflowResult(request.executionRef(), value.result());
                case WorkflowRecoveredResult.Absent value -> {
                    requireExecution(request.executionRef(), value.executionRef()); yield status("ABSENT", null);
                }
                case WorkflowRecoveredResult.Rejected value -> status("REJECTED", value.failure().reasonCode());
            };
        }
        if (request instanceof SessionExecutionWorkflows.Cancel) {
            return switch (results.decodeWorkflowControl(snapshot, request.callKind())) {
                case WorkflowControlResult.Applied value -> {
                    requireExecution(request.executionRef(), value.execution().executionRef()); yield status("APPLIED", null);
                }
                case WorkflowControlResult.Replayed value -> {
                    requireExecution(request.executionRef(), value.execution().executionRef()); yield status("REPLAYED", null);
                }
                case WorkflowControlResult.Conflict value -> {
                    requireExecution(request.executionRef(), value.current().executionRef()); yield status("CONFLICT", value.failure().reasonCode());
                }
                case WorkflowControlResult.Rejected value -> status("REJECTED", value.failure().reasonCode());
                case WorkflowControlResult.OutcomeUnknown value -> status("OUTCOME_UNKNOWN", value.failure().reasonCode());
            };
        }
        return workflowResult(request.executionRef(), results.decodeWorkflow(snapshot, request.callKind()));
    }

    private TypedPayload workflowResult(ExecutionRef expected, WorkflowResult result) {
        return switch (result) {
            case WorkflowResult.Completed completed -> {
                requireExecution(expected, completed.executionRef()); yield completed.output();
            }
            case WorkflowResult.Suspended suspended -> {
                requireExecution(expected, suspended.executionRef()); yield status("SUSPENDED", suspended.reason().name());
            }
            case WorkflowResult.Failed failed -> {
                requireExecution(expected, failed.executionRef()); yield status("FAILED", failed.failure().reasonCode());
            }
            case WorkflowResult.Terminated terminated -> {
                requireExecution(expected, terminated.executionRef()); yield status("TERMINATED", terminated.reason().name());
            }
            case WorkflowResult.Cancelled cancelled -> {
                requireExecution(expected, cancelled.executionRef()); yield status("CANCELLED", null);
            }
            case WorkflowResult.OutcomeUnknown unknown -> {
                requireExecution(expected, unknown.executionRef()); yield status("OUTCOME_UNKNOWN", unknown.failure().reasonCode());
            }
            case WorkflowResult.Accepted ignored -> throw invalid();
        };
    }

    private Inspection delegation(TypedPayload request, Optional<TypedPayload> result) {
        var call = delegations.decodeRequest(request);
        var command = call.command();
        var task = command.task().orElse(null);
        var input = body(new DelegationInput(command.operation().name(), command.targetHandle(),
                task == null ? null : task.objective(), task == null ? null : task.context(), task == null ? List.of() : task.constraints(),
                task == null ? null : task.expectedOutput(), command.delegationIds(), command.message(), command.background()));
        // value 是可扩展的业务反馈；只投影下面的明确公开字段，不输出等待票据、内部evidence或任意附加字段。
        var output = result.map(actual -> body(read(delegations.decodeResult(actual).value(), DelegationOutput.class)));
        return new Inspection(call.callId(), call.wireName(), input, output, Availability.FULL,
                output.isPresent() ? Availability.PARTIAL : Availability.NOT_RECORDED, List.of(), Optional.empty(), Optional.empty());
    }

    private TypedPayload status(String status, String reason) { return body(new StatusOutput(status, reason)); }

    private TypedPayload body(Object value) {
        String text = SessionRuntimePayloadJson.write(value);
        SessionRuntimePayloadJson.parse(text, limits);
        return new TypedPayload(JSON_SCHEMA, text);
    }

    private <T> T read(TypedPayload payload, Class<T> type) {
        payloads.validate(payload, limits);
        try { return json.treeToValue(codec.parse(payload, payload.schemaId()), type); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalidPayload) { throw invalid(); }
    }

    private static void requireExecution(ExecutionRef expected, ExecutionRef actual) {
        if (!expected.equals(actual)) throw invalid();
    }

    private static Availability availability(Optional<TypedPayload> value) {
        return value.isPresent() ? Availability.FULL : Availability.NOT_RECORDED;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("execution_capability_inspection_invalid");
    }

    public enum Availability { FULL, PARTIAL, NOT_RECORDED }

    /** 仅含可展示业务正文；来源授权和冻结绑定供宿主控制读取，禁止将其序列化进公开响应。 */
    public record Inspection(String callId, String title, TypedPayload input, Optional<TypedPayload> output,
            Availability inputAvailability, Availability outputAvailability, List<String> redactedFields,
            @JsonIgnore Optional<KnowledgeAccess> knowledgeAccess, @JsonIgnore Optional<ToolBinding> toolBinding) {
        public Inspection {
            Objects.requireNonNull(input); Objects.requireNonNull(output); Objects.requireNonNull(inputAvailability);
            Objects.requireNonNull(outputAvailability); redactedFields = List.copyOf(redactedFields); Objects.requireNonNull(knowledgeAccess);
            Objects.requireNonNull(toolBinding);
        }
        @Override public String toString() { return "ExecutionCapabilityInspection[REDACTED]"; }
    }

    /** 原冻结绑定与本投影实际披露的来源版本；只证明归属，不代表当前授权。 */
    public record KnowledgeAccess(TenantId tenantId, AgentId agentId, SessionRef sessionRef,
            FrozenKnowledgeTarget target, Set<KnowledgeSourceVersion> sourceVersions, String requestId, com.skillforge.runtime.api.TraceEnvelope trace) {
        public KnowledgeAccess {
            Objects.requireNonNull(tenantId); Objects.requireNonNull(agentId); Objects.requireNonNull(sessionRef);
            Objects.requireNonNull(target); sourceVersions = Set.copyOf(sourceVersions); Objects.requireNonNull(requestId); Objects.requireNonNull(trace);
        }
        @Override public String toString() { return "KnowledgeInspectionAccess[REDACTED]"; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record KnowledgeInput(String handle, String operation, String query,
            @JsonProperty("title_filter") String titleFilter, @JsonProperty("page_size") Integer pageSize,
            @JsonProperty("cursor_recorded") Boolean cursorRecorded, @JsonProperty("source_id") String sourceId,
            @JsonProperty("document_id") String documentId, @JsonProperty("source_version") String sourceVersion,
            Integer offset, @JsonProperty("max_characters") Integer maxCharacters) { }
    private record KnowledgeOutput(@JsonProperty("bundle_id") String bundleId, String version, String sufficiency,
            @JsonProperty("selection_scope") String selectionScope, List<KnowledgeItem> items,
            @JsonProperty("document_access") DocumentAccess documentAccess) { }
    private record KnowledgeItem(@JsonProperty("item_id") String itemId, @JsonProperty("source_id") String sourceId,
            @JsonProperty("source_version") String sourceVersion, String operator, String text,
            @JsonProperty("trust_level") String trustLevel) { }
    private record Document(@JsonProperty("source_id") String sourceId, @JsonProperty("document_id") String documentId,
            @JsonProperty("source_version") String sourceVersion, String title,
            @JsonProperty("total_characters") int totalCharacters) { }
    private record DocumentAccess(String type, List<Document> documents, @JsonProperty("has_more") boolean hasMore,
            @JsonProperty("start_offset") Integer startOffset, @JsonProperty("end_offset") Integer endOffset,
            @JsonProperty("next_offset") Integer nextOffset) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record KnowledgeText(String text) { }
    private record StatusOutput(String status, @JsonProperty("reason_code") String reasonCode) { }
    private record WorkflowControlInput(String operation, @JsonProperty("execution_id") String executionId,
            @JsonProperty("reason_code") String reasonCode, @JsonProperty("expected_revision") String expectedRevision) { }
    private record DelegationInput(String operation, @JsonProperty("target_handle") String targetHandle, String objective,
            String context, List<String> constraints, @JsonProperty("expected_output") String expectedOutput,
            @JsonProperty("delegation_ids") List<String> delegationIds, String message, boolean background) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record DelegationOutput(String status, @JsonProperty("delegation_id") String delegationId,
            @JsonProperty("child_run_id") String childRunId, String message, String state, String objective,
            String summary, String reason, Boolean available, List<DelegationEntry> results,
            List<DelegationEntry> delegations, List<DelegationTarget> targets) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record DelegationEntry(@JsonProperty("delegation_id") String delegationId,
            @JsonProperty("agent_name") String agentName, String state, String objective, String summary, String reason,
            @JsonProperty("used_tokens_reported") String usedTokensReported, @JsonProperty("usage_complete") Boolean usageComplete) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DelegationTarget(@JsonProperty("target_handle") String targetHandle, String name,
            @JsonProperty("role_description") String roleDescription) { }
}
