package com.skillforge.runtime.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.runtime.api.CapabilityRef;
import com.skillforge.runtime.api.CapabilityType;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.ToolEffect;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.media.ImageDetail;
import com.skillforge.runtime.api.media.MediaRef;
import com.skillforge.runtime.api.media.ModelContent;
import com.skillforge.runtime.api.port.PortalCallKind;
import com.skillforge.runtime.api.port.CapabilitySnapshotRef;
import com.skillforge.runtime.api.port.KnowledgePortal.FrozenKnowledgeTarget;
import com.skillforge.runtime.api.port.KnowledgePortal.KnowledgeOutcome;
import com.skillforge.runtime.api.port.PortalOutcomeSnapshot;
import com.skillforge.runtime.api.port.PortalResolution;
import com.skillforge.runtime.api.port.ModelPort;
import com.skillforge.runtime.api.port.ModelPort.ModelMessage;
import com.skillforge.runtime.api.port.ModelPort.ModelRole;
import com.skillforge.runtime.api.port.ModelPort.ModelToolCall;
import com.skillforge.runtime.api.port.ToolPortal;
import com.skillforge.runtime.api.port.ToolPortal.FrozenToolTarget;
import com.skillforge.runtime.api.port.ToolPortal.ToolBinding;
import com.skillforge.runtime.api.port.ToolPortal.ToolOutcome;
import com.skillforge.runtime.api.port.ToolPortal.ToolInputMethod;
import com.skillforge.runtime.api.port.ToolPortal.ToolInputResponse;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.codec.spi.ToolOutcomeCapacityExceeded;
import com.skillforge.runtime.core.CoreRuntime;
import com.skillforge.runtime.core.FrozenKnowledgeRequest;
import com.skillforge.runtime.core.FrozenModelRequest;
import com.skillforge.runtime.session.AgentExecutionModelMedia.MediaTransformation;
import com.skillforge.runtime.session.spi.ExecutionStore.WaitRef;
import com.skillforge.runtime.session.spi.ExecutionStore.Material;
import com.skillforge.runtime.session.spi.ExecutionStore.Prepared;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** 新执行链实际请求和实际结果的协议边界；固定字段显式编码，不反射、不重建来源图。 */
public final class AgentExecutionCodec {
    private static final String CALL_IDENTITY_POLICY_FIELD = "callIdentityPolicy";
    public static final String TOOL_REQUEST_SCHEMA = "skillforge.runtime.execution.tool-request.v2";
    public static final String INPUT_SCHEMA = "skillforge.runtime.execution.input.v1";
    public static final String OUTCOME_SCHEMA = "skillforge.runtime.execution.portal-outcome.v1";
    public static final String TOOL_FAILURE_SCHEMA = "skillforge.runtime.execution.tool-failure.v1";
    public static final String TOOL_RESPONSES_SCHEMA = "skillforge.runtime.execution.tool-responses.v1";
    public static final String HUMAN_RESPONSE_SCHEMA = "skillforge.runtime.execution.human-response.v1";
    public static final String KNOWLEDGE_REQUEST_SCHEMA = "skillforge.runtime.execution.knowledge-request.v1";
    public static final String MODEL_REQUEST_SCHEMA = "skillforge.runtime.execution.prepared-model-request.v4";
    public static final String MODEL_MANIFEST_SCHEMA = "skillforge.runtime.execution.model-request-manifest.v3";
    private static final String LEGACY_MODEL_REQUEST_SCHEMA = "skillforge.runtime.execution.prepared-model-request.v3";
    private static final String LEGACY_MODEL_MANIFEST_SCHEMA = "skillforge.runtime.execution.model-request-manifest.v2";
    public static boolean isModelManifest(String schema) {
        return MODEL_MANIFEST_SCHEMA.equals(schema) || LEGACY_MODEL_MANIFEST_SCHEMA.equals(schema);
    }
    public static final String MODEL_MESSAGE_SCHEMA = "skillforge.runtime.execution.model-message.v1";
    public static final String MODEL_TOOL_SET_SCHEMA = "skillforge.runtime.execution.model-tool-set.v1";
    private final CodecLimits limits;

    /** 同一解析配额用于首次编码和结果恢复，不能以新生成材料绕过限制。 */
    public AgentExecutionCodec(CodecLimits limits) { this.limits = Objects.requireNonNull(limits); }

    /** 入口一次保存真实初始历史与用户消息；该材料由 base.input 引用，不复制成每轮完整上下文。 */
    public TypedPayload encodeInput(List<ModelMessage> messages, TypedPayload frozenInput) {
        Objects.requireNonNull(frozenInput, "frozenInput");
        ModelPort.validateConversation(messages);
        var root = object(); root.set("frozenInput", payload(frozenInput)); var values = root.putArray("messages");
        for (var message : messages) {
            var value = values.addObject(); value.put("messageId", message.messageId()); value.put("role", message.role().name());
            var contents = value.putArray("contents"); message.contents().forEach(part -> contents.add(content(part)));
            var calls = value.putArray("toolCalls");
            for (var call : message.toolCalls()) {
                var item = calls.addObject(); item.put("callId", call.callId()); item.put("wireName", call.wireName()); item.set("arguments", payload(call.arguments()));
            }
            if (message.toolCallId().isPresent()) value.put("toolCallId", message.toolCallId().orElseThrow()); else value.putNull("toolCallId");
            if (message.reasoningContent().isPresent()) value.put("reasoningContent", message.reasoningContent().orElseThrow()); else value.putNull("reasoningContent");
            value.set("protocolState", protocolState(message.protocolState()));
        }
        return encode(INPUT_SCHEMA, root);
    }

    /** 恢复入口持久消息的完整协议字段及媒体引用，不从旧 Session checkpoint 读取。 */
    public List<ModelMessage> decodeInput(TypedPayload payload) {
        var root = parse(payload, INPUT_SCHEMA); fields(root, "frozenInput", "messages");
        var frozenInput = readPayload(root.get("frozenInput"));
        if (!root.get("messages").isArray()) throw invalid();
        var result = new ArrayList<ModelMessage>();
        for (var value : root.get("messages")) {
            fields(value, "messageId", "role", "contents", "toolCalls", "toolCallId", "reasoningContent", "protocolState");
            if (!value.get("contents").isArray() || !value.get("toolCalls").isArray()) throw invalid();
            var contents = new ArrayList<ModelContent>(); value.get("contents").forEach(part -> contents.add(readContent(part)));
            var calls = new ArrayList<ModelToolCall>();
            for (var call : value.get("toolCalls")) {
                fields(call, "callId", "wireName", "arguments");
                calls.add(new ModelToolCall(string(call, "callId"), string(call, "wireName"), readPayload(call.get("arguments"))));
            }
            result.add(new ModelMessage(string(value, "messageId"), ModelRole.valueOf(string(value, "role")), contents, calls,
                    value.get("toolCallId").isNull() ? Optional.empty() : Optional.of(string(value, "toolCallId")),
                    value.get("reasoningContent").isNull() ? Optional.empty() : Optional.of(string(value, "reasoningContent")),
                    readProtocolState(value.get("protocolState"))));
        }
        if (result.stream().map(ModelMessage::messageId).distinct().count() != result.size() || !encodeInput(result, frozenInput).equals(payload)) throw invalid();
        return List.copyOf(result);
    }

    private static JsonNode protocolState(Optional<ModelPort.NativeProtocolState> state) {
        if (state.isEmpty()) return JsonNodeFactory.instance.nullNode();
        var actual = state.orElseThrow(); var value = object();
        value.put("protocol", actual.protocol().name()).put("dialectVersion", actual.dialectVersion()).put("modelId", actual.modelId());
        var items = value.putArray("items"); actual.items().forEach(item -> items.add(payload(item)));
        return value;
    }

    private static Optional<ModelPort.NativeProtocolState> readProtocolState(JsonNode value) {
        if (value.isNull()) return Optional.empty();
        fields(value, "protocol", "dialectVersion", "modelId", "items");
        if (!value.get("items").isArray()) throw invalid();
        var items = new ArrayList<TypedPayload>(); value.get("items").forEach(item -> items.add(readPayload(item)));
        return Optional.of(new ModelPort.NativeProtocolState(ModelPort.NativeProtocol.valueOf(string(value, "protocol")),
                string(value, "dialectVersion"), string(value, "modelId"), items));
    }

    /** 读取原受理业务输入，保留extraParameters、输入和媒体引用供宿主归属及指纹验证。 */
    public TypedPayload frozenInput(TypedPayload input) {
        var root = parse(input, INPUT_SCHEMA); fields(root, "frozenInput", "messages");
        return readPayload(root.get("frozenInput"));
    }

    /** 实际模型请求及每个图片位置的来源到变体对应；恢复不依赖媒体服务或当前配置。 */
    public record ModelCallRequest(FrozenModelRequest request, List<MediaTransformation> transformations, long supplementalIncludedThrough,
            Optional<AgentExecutionOutputs.Directive> output, Optional<TypedPayload> progress,
            List<com.skillforge.runtime.api.KnowledgeSourceVersion> knowledgeSources, boolean knowledgeSourcesDeclared) {
        public ModelCallRequest(FrozenModelRequest request, List<MediaTransformation> transformations, long supplementalIncludedThrough,
                Optional<AgentExecutionOutputs.Directive> output, Optional<TypedPayload> progress) {
            this(request, transformations, supplementalIncludedThrough, output, progress, List.of(), true);
        }
        public ModelCallRequest(FrozenModelRequest request, List<MediaTransformation> transformations, long supplementalIncludedThrough,
                Optional<AgentExecutionOutputs.Directive> output, Optional<TypedPayload> progress,
                List<com.skillforge.runtime.api.KnowledgeSourceVersion> knowledgeSources) {
            this(request, transformations, supplementalIncludedThrough, output, progress, knowledgeSources, true);
        }
        public ModelCallRequest(FrozenModelRequest request, List<MediaTransformation> transformations, long supplementalIncludedThrough,
                Optional<AgentExecutionOutputs.Directive> output) { this(request, transformations, supplementalIncludedThrough, output, Optional.empty()); }
        public ModelCallRequest {
            Objects.requireNonNull(request, "request"); transformations = List.copyOf(transformations);
            Objects.requireNonNull(output,"output"); Objects.requireNonNull(progress,"progress");
            knowledgeSources = knowledgeSources.stream().distinct().sorted(java.util.Comparator
                    .comparing(com.skillforge.runtime.api.KnowledgeSourceVersion::sourceId)
                    .thenComparing(com.skillforge.runtime.api.KnowledgeSourceVersion::sourceVersion)).toList();
            if (!knowledgeSourcesDeclared && !knowledgeSources.isEmpty()) throw invalid();
            if (supplementalIncludedThrough < 0) throw invalid();
            new AgentExecutionModelMedia.Prepared(request.messages(), transformations);
        }
        public void requireCorrespondence(List<ModelMessage> originals) {
            new AgentExecutionModelMedia.Prepared(request.messages(), transformations).requireCorrespondence(originals);
        }
        @Override public String toString() { return "ExecutionModelCallRequest[REDACTED]"; }
    }

    /** 唯一实际模型材料封套，固定保存冻结请求和有序图片引用映射。 */
    public TypedPayload encodeModelRequest(ModelCallRequest request) {
        var value = object(); value.set("request", payload(request.request().encode()));
        value.put("supplementalIncludedThrough", Long.toString(request.supplementalIncludedThrough())); var transformations = value.putArray("transformations");
        request.output().ifPresentOrElse(output->value.set("output",AgentExecutionOutputs.encode(output)),()->value.putNull("output"));
        request.progress().ifPresentOrElse(progress->value.set("progress",payload(progress)),()->value.putNull("progress"));
        if (request.knowledgeSourcesDeclared()) {
            var sources = value.putArray("knowledgeSources");
            for (var source : request.knowledgeSources()) {
                var item = sources.addObject(); item.put("sourceId", source.sourceId()); item.put("sourceVersion", source.sourceVersion());
            }
        }
        for (var transformation : request.transformations()) {
            var item = transformations.addObject(); item.put("messageIndex", Integer.toString(transformation.messageIndex()));
            item.put("contentIndex", Integer.toString(transformation.contentIndex()));
            item.set("source", modelMedia(transformation.source())); item.set("prepared", modelMedia(transformation.prepared()));
        }
        return encode(request.knowledgeSourcesDeclared() ? MODEL_REQUEST_SCHEMA : LEGACY_MODEL_REQUEST_SCHEMA, value);
    }

    /** 固定一层正文分片；同内容message/tool-set仅持久保存一次，不形成递归前缀链。 */
    public record ModelRequestMaterials(Material root, List<Material> parts) {
        public ModelRequestMaterials { Objects.requireNonNull(root); parts = List.copyOf(parts); }
    }

    public ModelRequestMaterials encodeModelRequestMaterials(ModelCallRequest request) {
        var full = encodeModelRequest(request);
        var envelope = parse(full, full.schemaId());
        var frozen = (ObjectNode) parse(request.request().encode(), FrozenModelRequest.SCHEMA);
        var root = object();
        var messages = frozen.remove("messages"); var tools = frozen.remove("tools");
        root.set("request", frozen); var refs = root.putArray("messageRefs");
        var parts = new LinkedHashMap<ResultRef,Material>();
        for (var message : messages) {
            var value = object(); value.set("message", message);
            var content = encode(MODEL_MESSAGE_SCHEMA,value); var material = new Material(reference(content),content);
            refs.add(material.reference().value()); parts.putIfAbsent(material.reference(),material);
        }
        var value = object(); value.set("tools", tools);
        var content = encode(MODEL_TOOL_SET_SCHEMA,value); var material = new Material(reference(content),content);
        parts.putIfAbsent(material.reference(),material); root.put("toolsRef",material.reference().value());
        root.set("transformations",envelope.get("transformations"));
        root.set("supplementalIncludedThrough",envelope.get("supplementalIncludedThrough")); root.set("output",envelope.get("output")); root.set("progress",envelope.get("progress"));
        if (request.knowledgeSourcesDeclared()) root.set("knowledgeSources", envelope.get("knowledgeSources"));
        var payload = encode(request.knowledgeSourcesDeclared() ? MODEL_MANIFEST_SCHEMA : LEGACY_MODEL_MANIFEST_SCHEMA,root);
        return new ModelRequestMaterials(new Material(reference(payload),payload),List.copyOf(parts.values()));
    }

    /** 首次派发直接消费Prepared携带的叶；不访问数据库。 */
    public ModelCallRequest decodeModelRequest(Prepared prepared) {
        return decodeModelRequest(prepared, refs -> { throw invalid(); });
    }

    /** 恢复和诊断一次批读固定一层叶；完整重建请求仍应用原总解析限额。 */
    public ModelCallRequest decodeModelRequest(Prepared prepared,
            Function<List<ResultRef>,List<Optional<Material>>> loader) {
        if (isModelManifest(prepared.request().content().schemaId())
                && !reference(prepared.request().content()).equals(prepared.request().reference())) throw invalid();
        if (prepared.requestParts().isEmpty()) return decodeModelRequest(prepared.request().content(),loader);
        var parts = new LinkedHashMap<ResultRef,Material>();
        for (var part : prepared.requestParts()) {
            if (!reference(part.content()).equals(part.reference()) || parts.putIfAbsent(part.reference(),part) != null) throw invalid();
        }
        return decodeModelRequest(prepared.request().content(),refs -> {
            if (!parts.keySet().equals(new LinkedHashSet<>(refs))) throw invalid();
            return refs.stream().map(ref -> Optional.ofNullable(parts.get(ref))).toList();
        });
    }

    public List<ResultRef> modelRequestReferences(TypedPayload payload) {
        if (!isModelManifest(payload.schemaId())) throw invalid();
        var root = parse(payload,payload.schemaId());
        if (MODEL_MANIFEST_SCHEMA.equals(payload.schemaId()))
            fields(root,"request","messageRefs","toolsRef","transformations","supplementalIncludedThrough","output","progress","knowledgeSources");
        else fields(root,"request","messageRefs","toolsRef","transformations","supplementalIncludedThrough","output","progress");
        if (!root.get("messageRefs").isArray()) throw invalid();
        var refs = new LinkedHashSet<ResultRef>();
        for (var item : root.get("messageRefs")) { if (!item.isTextual()) throw invalid(); refs.add(new ResultRef(item.textValue())); }
        refs.add(new ResultRef(string(root,"toolsRef"))); return List.copyOf(refs);
    }

    public ModelCallRequest decodeModelRequest(TypedPayload payload,
            Function<List<ResultRef>,List<Optional<Material>>> loader) {
        if (!isModelManifest(payload.schemaId())) return decodeModelRequest(payload);
        var root = parse(payload,payload.schemaId()); var refs = modelRequestReferences(payload);
        var loaded = loader.apply(refs); if (loaded.size()!=refs.size()) throw invalid();
        var parts = new LinkedHashMap<ResultRef,Material>();
        for (int index=0;index<refs.size();index++) {
            var part=loaded.get(index).orElseThrow(AgentExecutionCodec::invalid);
            if (!part.reference().equals(refs.get(index)) || !reference(part.content()).equals(part.reference())) throw invalid();
            parts.put(part.reference(),part);
        }
        if (!root.get("request").isObject()) throw invalid();
        var frozen=(ObjectNode)root.get("request").deepCopy();
        if (frozen.has("messages") || frozen.has("tools")) throw invalid();
        var messages=frozen.putArray("messages");
        for (var item:root.get("messageRefs")) {
            var value=parse(parts.get(new ResultRef(item.textValue())).content(),MODEL_MESSAGE_SCHEMA);
            fields(value,"message"); messages.add(value.get("message"));
        }
        var tools=parse(parts.get(new ResultRef(string(root,"toolsRef"))).content(),MODEL_TOOL_SET_SCHEMA);
        fields(tools,"tools"); frozen.set("tools",tools.get("tools"));
        String json=com.skillforge.runtime.json.RuntimeJson.writeCanonical(
                com.skillforge.runtime.json.RuntimeJson.parse(SessionRuntimePayloadJson.write(frozen),limits));
        var envelope=object(); envelope.set("request",payload(new TypedPayload(FrozenModelRequest.SCHEMA,json)));
        envelope.set("supplementalIncludedThrough",root.get("supplementalIncludedThrough"));
        envelope.set("transformations",root.get("transformations")); envelope.set("output",root.get("output")); envelope.set("progress",root.get("progress"));
        boolean declared = MODEL_MANIFEST_SCHEMA.equals(payload.schemaId());
        if (declared) envelope.set("knowledgeSources", root.get("knowledgeSources"));
        var result=decodeModelRequest(encode(declared ? MODEL_REQUEST_SCHEMA : LEGACY_MODEL_REQUEST_SCHEMA,envelope));
        if (!encodeModelRequestMaterials(result).root().content().equals(payload)) throw invalid();
        return result;
    }

    /** 精确恢复原实际请求和对应关系；旧raw请求和未知字段均明确拒绝。 */
    public ModelCallRequest decodeModelRequest(TypedPayload payload) {
        boolean declared = MODEL_REQUEST_SCHEMA.equals(payload.schemaId());
        if (!declared && !LEGACY_MODEL_REQUEST_SCHEMA.equals(payload.schemaId())) throw invalid();
        var value = parse(payload, payload.schemaId());
        if (declared) fields(value, "request", "supplementalIncludedThrough", "transformations","output","progress","knowledgeSources");
        else fields(value, "request", "supplementalIncludedThrough", "transformations","output","progress");
        var sources = new ArrayList<com.skillforge.runtime.api.KnowledgeSourceVersion>();
        if (declared) {
            if (!value.get("knowledgeSources").isArray()) throw invalid();
            for (var item : value.get("knowledgeSources")) {
                fields(item, "sourceId", "sourceVersion");
                sources.add(new com.skillforge.runtime.api.KnowledgeSourceVersion(string(item,"sourceId"), string(item,"sourceVersion")));
            }
        }
        if (!value.get("transformations").isArray()) throw invalid();
        var transformations = new ArrayList<MediaTransformation>();
        for (var item : value.get("transformations")) {
            fields(item, "messageIndex", "contentIndex", "source", "prepared");
            transformations.add(new MediaTransformation(Math.toIntExact(number(item, "messageIndex")), Math.toIntExact(number(item, "contentIndex")),
                    readModelMedia(item.get("source")), readModelMedia(item.get("prepared"))));
        }
        var result = new ModelCallRequest(FrozenModelRequest.decode(readPayload(value.get("request")), limits), transformations, number(value, "supplementalIncludedThrough"),
                value.get("output").isNull()?Optional.empty():Optional.of(AgentExecutionOutputs.decode(value.get("output"))),
                value.get("progress").isNull()?Optional.empty():Optional.of(readPayload(value.get("progress"))), sources, declared);
        if (!encodeModelRequest(result).equals(payload)) throw invalid();
        return result;
    }

    /**
     * 从原实际 MODEL 请求读取 Program RPC 可见的唯一原生工具声明，不重建当前能力目录。
     * 宿主须先验证已采纳 MODEL attempt 及其请求材料来源；本方法不证明来源、当前授权或调用许可。
     * @throws ProgramToolRejected 原请求禁止工具调用，或名称没有唯一的普通工具声明
     */
    public ModelPort.ModelTool requireProgramTool(TypedPayload modelRequest, String wireName) {
        var original=decodeModelRequest(modelRequest).request();
        return requireProgramTool(new ProgramTools(original.toolChoice(),original.tools()),wireName);
    }

    public ModelPort.ModelTool requireProgramTool(TypedPayload modelRequest, String wireName,
            Function<List<ResultRef>,List<Optional<Material>>> loader) {
        return requireProgramTool(programTools(modelRequest,loader),wireName);
    }

    /** 完整原请求校验后只保留程序需要的冻结声明，不持有历史消息或调用许可。 */
    public record ProgramTools(ModelPort.ToolChoice toolChoice,List<ModelPort.ModelTool> tools) {
        public ProgramTools { Objects.requireNonNull(toolChoice); tools=List.copyOf(tools); }
    }

    public ProgramTools programTools(TypedPayload modelRequest,Function<List<ResultRef>,List<Optional<Material>>> loader) {
        var original=decodeModelRequest(modelRequest,loader).request();
        return new ProgramTools(original.toolChoice(),original.tools());
    }

    public ModelPort.ModelTool requireProgramTool(ProgramTools request, String wireName) {
        if (request.toolChoice() == ModelPort.ToolChoice.NONE)
            throw new ProgramToolRejected(ProgramToolRejected.Reason.REQUIRES_DIRECT_CALL);
        var matches = request.tools().stream().filter(tool -> tool.wireName().equals(wireName)).toList();
        if (matches.size() != 1 || matches.getFirst().kind() != ModelPort.ModelToolKind.TOOL_CALL)
            throw new ProgramToolRejected(ProgramToolRejected.Reason.NOT_AVAILABLE);
        return matches.getFirst();
    }

    /** 原 MODEL 声明校验的确定拒绝；不涵盖材料来源、存储、授权或真实子执行失败。 */
    public static final class ProgramToolRejected extends IllegalStateException {
        public enum Reason {
            NOT_AVAILABLE("program_tool_not_available"),
            REQUIRES_DIRECT_CALL("program_tool_requires_direct_call");
            private final String code;
            Reason(String code) { this.code = code; }
        }
        private final Reason reason;
        private ProgramToolRejected(Reason reason) { super(reason.code); this.reason = reason; }
        public Reason reason() { return reason; }
        public String reasonCode() { return reason.code; }
    }

    /** 图片映射只保留持久引用的固定协议字段，不包含正文或Provider数据。 */
    private static ObjectNode modelMedia(MediaRef reference) {
        var value = object(); var owner = value.putObject("owner"); owner.put("tenantId", reference.owner().tenantId().value());
        owner.put("sessionId", reference.owner().sessionId()); value.put("id", reference.id()); value.put("sha256", reference.sha256());
        value.put("mimeType", reference.mimeType()); value.put("byteLength", Long.toString(reference.byteLength()));
        value.put("width", Integer.toString(reference.width())); value.put("height", Integer.toString(reference.height())); return value;
    }

    /** 引用闭包必须精确；不按id读取或补齐媒体属性。 */
    private static MediaRef readModelMedia(JsonNode value) {
        fields(value, "owner", "id", "sha256", "mimeType", "byteLength", "width", "height");
        var owner = value.get("owner"); fields(owner, "tenantId", "sessionId");
        return new MediaRef(new SessionRef(new TenantId(string(owner, "tenantId")), string(owner, "sessionId")),
                string(value, "id"), string(value, "sha256"), string(value, "mimeType"), number(value, "byteLength"),
                Math.toIntExact(number(value, "width")), Math.toIntExact(number(value, "height")));
    }

    /**
     * 已冻结工具请求；参数及原模型调用身份不包含用户可伪造的执行授权。
     * @param target 原冻结工具目标及能力版本
     * @param binding 原真实 Provider/Handler 绑定
     * @param arguments 模型原规范参数
     * @param callId 本轮原生协议配对身份
     * @param wireName 原模型工具目录中的稳定协议名
     */
    public record ToolRequest(FrozenToolTarget target, ToolBinding binding, TypedPayload arguments,
            String callId, String wireName, Optional<ToolContinuation> continuation) {
        /** 字段必须完整；具体参数 schema 在准备和真实 Handler 边界继续核验。 */
        public ToolRequest {
            Objects.requireNonNull(target); Objects.requireNonNull(binding); Objects.requireNonNull(arguments);
            text(callId); text(wireName); Objects.requireNonNull(continuation);
        }
        /** 首次工具请求不包含任何已接受等待响应。 */
        public ToolRequest(FrozenToolTarget target, ToolBinding binding, TypedPayload arguments, String callId, String wireName) {
            this(target, binding, arguments, callId, wireName, Optional.empty());
        }
        /** 目标和业务参数不进入普通日志。 */
        @Override public String toString() { return "ExecutionToolRequest[REDACTED]"; }
    }

    /** 原等待及精确响应组成一次真实续跑请求；opaque state只按原值传递。 */
    public record ToolContinuation(WaitRef waiting, ResultRef sourceResult, ResultRef response,
            Map<String, ToolInputResponse> responses, Optional<String> requestState, InputId inputId, long acceptedRevision) {
        public ToolContinuation {
            Objects.requireNonNull(waiting); Objects.requireNonNull(sourceResult); Objects.requireNonNull(response);
            responses = Map.copyOf(responses); Objects.requireNonNull(requestState);
            Objects.requireNonNull(inputId);
            if (acceptedRevision <= 0) throw invalid();
            responses.forEach((key, value) -> { text(key); Objects.requireNonNull(value); });
            requestState.ifPresent(AgentExecutionCodec::text);
            if (responses.isEmpty() && requestState.isEmpty()) throw invalid();
        }
        @Override public String toString() { return "ExecutionToolContinuation[REDACTED]"; }
    }

    /** 用户协议响应单独保存一次；响应身份由Store受理回执拥有。 */
    public TypedPayload encodeToolResponses(Map<String, ToolInputResponse> responses) {
        var root = object(); var values = root.putObject("responses");
        new java.util.TreeMap<>(responses).forEach((key, response) -> {
            text(key); var value = values.putObject(key); value.put("method", response.method().wireName());
            value.set("result", payload(response.result()));
        });
        return encode(TOOL_RESPONSES_SCHEMA, root);
    }

    /** 方法、请求key与业务payload精确恢复，不以当前请求补齐缺失字段。 */
    public Map<String, ToolInputResponse> decodeToolResponses(TypedPayload payload) {
        var root = parse(payload, TOOL_RESPONSES_SCHEMA); fields(root, "responses");
        if (!root.get("responses").isObject()) throw invalid();
        var result = new java.util.TreeMap<String, ToolInputResponse>();
        root.get("responses").fields().forEachRemaining(entry -> {
            text(entry.getKey()); fields(entry.getValue(), "method", "result");
            result.put(entry.getKey(), new ToolInputResponse(ToolInputMethod.fromWireName(string(entry.getValue(), "method")), readPayload(entry.getValue().get("result"))));
        });
        if (!encodeToolResponses(result).equals(payload)) throw invalid();
        return Map.copyOf(result);
    }

    /** 人类回复以其真实正文独立保存，不借用工具补参或伪造Provider结果。 */
    public TypedPayload encodeHumanResponse(String response) {
        text(response); var value = object(); value.put("response", response);
        return encode(HUMAN_RESPONSE_SCHEMA, value);
    }

    /** 精确恢复已受理人类回复；未知字段、空值和其他schema均明确拒绝。 */
    public String decodeHumanResponse(TypedPayload payload) {
        var value = parse(payload, HUMAN_RESPONSE_SCHEMA); fields(value, "response");
        var response = string(value, "response"); text(response);
        if (!encodeHumanResponse(response).equals(payload)) throw invalid();
        return response;
    }

    /** 实际Knowledge请求与原生配对字段；不含旧来源Context、内部操作票据或Provider许可。 */
    public record KnowledgeCallRequest(FrozenKnowledgeTarget target, com.skillforge.runtime.api.KnowledgeRequest request,
            CapabilitySnapshotRef snapshot, EventId sourceEventId, String callId, String wireName, TypedPayload arguments) {
        public KnowledgeCallRequest {
            Objects.requireNonNull(target); Objects.requireNonNull(request); Objects.requireNonNull(snapshot); Objects.requireNonNull(sourceEventId);
            Objects.requireNonNull(arguments); text(callId); text(wireName);
            new FrozenKnowledgeRequest(target, request);
            if (!"application/json".equals(arguments.schemaId()) || !snapshot.sessionRef().equals(request.sessionRef())) throw invalid();
        }
        @Override public String toString() { return "ExecutionKnowledgeCallRequest[REDACTED]"; }
    }

    /** target/request完整字段由唯一Core codec编码；Session只增加快照和实际native配对。 */
    public TypedPayload encodeKnowledgeRequest(KnowledgeCallRequest request) {
        var value = object(); value.set("call", payload(new FrozenKnowledgeRequest(request.target(), request.request()).encode(limits)));
        var snapshot = value.putObject("snapshot"); snapshot.put("tenantId", request.snapshot().sessionRef().tenantId().value());
        snapshot.put("sessionId", request.snapshot().sessionRef().sessionId()); snapshot.put("inputId", request.snapshot().inputId().value());
        snapshot.put("snapshotId", request.snapshot().snapshotId()); snapshot.put("generation", Long.toString(request.snapshot().generation()));
        value.put("sourceEventId", request.sourceEventId().value()); value.put("callId", request.callId()); value.put("wireName", request.wireName());
        value.set("arguments", payload(request.arguments()));
        return encode(KNOWLEDGE_REQUEST_SCHEMA, value);
    }

    /** 精确恢复同一次实际请求，不从当前descriptor或source active版本补字段。 */
    public KnowledgeCallRequest decodeKnowledgeRequest(TypedPayload payload) {
        var value = parse(payload, KNOWLEDGE_REQUEST_SCHEMA); fields(value, "call", "snapshot", "sourceEventId", "callId", "wireName", "arguments");
        var call = FrozenKnowledgeRequest.decode(readPayload(value.get("call")), limits); var snapshot = value.get("snapshot");
        fields(snapshot, "tenantId", "sessionId", "inputId", "snapshotId", "generation");
        var request = new KnowledgeCallRequest(call.target(), call.request(), new CapabilitySnapshotRef(
                new SessionRef(new TenantId(string(snapshot, "tenantId")), string(snapshot, "sessionId")),
                new com.skillforge.runtime.api.InputId(string(snapshot, "inputId")), string(snapshot, "snapshotId"), number(snapshot, "generation")),
                new EventId(string(value, "sourceEventId")), string(value, "callId"), string(value, "wireName"), readPayload(value.get("arguments")));
        if (!encodeKnowledgeRequest(request).equals(payload)) throw invalid();
        return request;
    }

    /** 无损读取完整EvidenceBundle联合结果；API引用闭包和正文schema由原生产codec共同核验。 */
    public KnowledgeOutcome decodeKnowledgeOutcome(TypedPayload actual) {
        var payloads = new SessionRuntimePayloads();
        return new CoreRuntime(limits, payloads)
                .decodeKnowledgeOutcome(decodeOutcome(actual), PortalCallKind.KNOWLEDGE_EXECUTE, payloads);
    }

    /** 只保存实际工具请求，不包装旧模型准备或完整上下文。 */
    public TypedPayload encodeTool(ToolRequest request) {
        var value = object();
        value.set("target", target(request.target()));
        var binding = object();
        binding.put("providerType", request.binding().providerType().name());
        binding.put("providerRef", request.binding().providerRef());
        binding.put("toolName", request.binding().toolName());
        value.set("binding", binding);
        value.set("arguments", payload(request.arguments()));
        value.put("callId", request.callId());
        value.put("wireName", request.wireName());
        if (request.continuation().isPresent()) {
            var actual = request.continuation().orElseThrow(); var continuation = value.putObject("continuation");
            continuation.put("actionId", actual.waiting().action().actionId());
            continuation.put("tenantId", actual.waiting().action().tenantId().value());
            continuation.put("generation", Long.toString(actual.waiting().generation()));
            continuation.put("sourceResult", actual.sourceResult().value()); continuation.put("response", actual.response().value());
            continuation.put("inputId", actual.inputId().value());
            continuation.put("acceptedRevision", Long.toString(actual.acceptedRevision()));
            continuation.set("responses", payload(encodeToolResponses(actual.responses())));
            if (actual.requestState().isPresent()) continuation.put("requestState", actual.requestState().orElseThrow()); else continuation.putNull("requestState");
        } else value.putNull("continuation");
        return encode(TOOL_REQUEST_SCHEMA, value);
    }

    /** 严格恢复原目标、绑定、参数和原生调用映射，不查询当前目录。 */
    public ToolRequest decodeTool(TypedPayload payload) {
        var value = parse(payload, TOOL_REQUEST_SCHEMA);
        fields(value, "target", "binding", "arguments", "callId", "wireName", "continuation");
        var binding = value.get("binding");
        fields(binding, "providerType", "providerRef", "toolName");
        Optional<ToolContinuation> continuation = Optional.empty();
        if (!value.get("continuation").isNull()) {
            var actual = value.get("continuation"); fields(actual, "actionId", "tenantId", "generation", "sourceResult", "response", "responses", "requestState", "inputId", "acceptedRevision");
            continuation = Optional.of(new ToolContinuation(new WaitRef(new com.skillforge.runtime.api.port.ActionRef(
                    new TenantId(string(actual, "tenantId")), string(actual, "actionId")), number(actual, "generation")),
                    new ResultRef(string(actual, "sourceResult")), new ResultRef(string(actual, "response")), decodeToolResponses(readPayload(actual.get("responses"))),
                    actual.get("requestState").isNull() ? Optional.empty() : Optional.of(string(actual, "requestState")), new InputId(string(actual, "inputId")), number(actual, "acceptedRevision")));
        }
        var request = new ToolRequest(readTarget(value.get("target")), new ToolBinding(
                ToolBinding.ProviderType.valueOf(string(binding, "providerType")), string(binding, "providerRef"), string(binding, "toolName")),
                readPayload(value.get("arguments")), string(value, "callId"), string(value, "wireName"), continuation);
        if (!encodeTool(request).equals(payload)) throw invalid();
        return request;
    }

    /** 原 Core outcome 注册语义保持不变，只保存一个无损封套供统一结果提交。 */
    public TypedPayload encodeOutcome(PortalOutcomeSnapshot outcome) {
        var value = object();
        value.put("callKind", outcome.callKind().name());
        value.put("resolution", outcome.resolution().name());
        value.put("outcomeType", outcome.outcomeType());
        value.set("outcome", payload(outcome.outcome()));
        return encode(OUTCOME_SCHEMA, value);
    }

    /** 保存确定失败时只丢弃装不下的可选比较事实，不改变失败身份或取得派发权。 */
    public EncodedToolOutcome encodeToolOutcome(CoreRuntime.PortalResults portals, ToolOutcome actual) {
        Objects.requireNonNull(portals); Objects.requireNonNull(actual);
        if (actual instanceof ToolOutcome.Failed failed && failed.comparisonFact().isPresent()) {
            try {
                var snapshot = portals.encodeTool(PortalCallKind.TOOL_INVOKE, actual);
                if (outcomeFits(snapshot)) return new EncodedToolOutcome(encodeOutcome(snapshot), actual);
            } catch (ToolOutcomeCapacityExceeded exceeded) {
                // Core 的准确配额不足；其他编码、校验和注册异常继续传播。
            }
            actual = new ToolOutcome.Failed(failed.failure());
        }
        return new EncodedToolOutcome(encodeOutcome(portals.encodeTool(PortalCallKind.TOOL_INVOKE, actual)), actual);
    }

    /** 供宿主以相同事实生成安全反馈；本值不证明结果已提交。 */
    public record EncodedToolOutcome(TypedPayload payload, ToolOutcome outcome) {
        @Override public String toString() { return "EncodedToolOutcome[REDACTED]"; }
    }

    /** 固定外层对象含 13 个节点、最大深度 3；正文作为字符串再次转义。 */
    private boolean outcomeFits(PortalOutcomeSnapshot snapshot) {
        long units = 14; // 两个对象的括号、六个冒号及四个逗号
        for (String text : List.of("callKind", "resolution", "outcomeType", "outcome", "schemaId", "canonicalJson",
                snapshot.callKind().name(), snapshot.resolution().name(), snapshot.outcomeType(),
                snapshot.outcome().schemaId(), snapshot.outcome().canonicalJson())) {
            units += jsonStringUnits(text);
        }
        return units <= limits.maxCodeUnits() && limits.maxNodes() >= 13 && limits.maxDepth() >= 3;
    }

    /** 与 RuntimeJson 的字符串转义一致，按 UTF-16 单元计量，不分配第二份正文。 */
    private static long jsonStringUnits(String text) {
        long units = 2;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (Character.isHighSurrogate(value)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) throw invalid();
                units += 2;
            } else if (Character.isLowSurrogate(value)) throw invalid();
            else units += switch (value) {
                case '"', '\\', '\b', '\f', '\n', '\r', '\t' -> 2;
                default -> value < 0x20 ? 6 : 1;
            };
        }
        return units;
    }

    /** 封套解码后仍由 Core 的对应模型/工具 codec 校验实际联合类型。 */
    public PortalOutcomeSnapshot decodeOutcome(TypedPayload payload) {
        var value = parse(payload, OUTCOME_SCHEMA);
        fields(value, "callKind", "resolution", "outcomeType", "outcome");
        var result = new PortalOutcomeSnapshot(PortalCallKind.valueOf(string(value, "callKind")),
                PortalResolution.valueOf(string(value, "resolution")), string(value, "outcomeType"), readPayload(value.get("outcome")));
        if (!encodeOutcome(result).equals(payload)) throw invalid();
        return result;
    }

    /** 窄读取原工具结果，使用同一生产Core codec和真实envelope校验，不投影旧来源或状态。 */
    public ToolOutcome decodeToolOutcome(TypedPayload actual) {
        var payloads = new SessionRuntimePayloads();
        return new CoreRuntime(limits, payloads)
                .decodeToolOutcomeForInspection(decodeOutcome(actual), PortalCallKind.TOOL_INVOKE, payloads);
    }

    /** 准确实际模型结果的唯一私有解码边界，保留generate/stream原callKind。 */
    public ModelPort.ModelResult decodeModelOutcome(TypedPayload actual) {
        var payloads = new SessionRuntimePayloads(); var outcome = decodeOutcome(actual);
        if (outcome.callKind() != PortalCallKind.MODEL_GENERATE && outcome.callKind() != PortalCallKind.MODEL_STREAM) throw invalid();
        return new CoreRuntime(limits, payloads).decodeModelOutcome(outcome, outcome.callKind());
    }

    /** 实际能力适配器在返回 CapabilityResult 前生成这份安全材料，Program 再校验其精确内容。 */
    public Optional<TypedPayload> safeToolFeedback(ToolRequest request, ToolOutcome actual) {
        return switch (actual) {
            case ToolOutcome.Succeeded success -> Optional.of(safeToolSuccess(request, success));
            case ToolOutcome.Failed failed -> {
                var failure = object();
                failure.put("reasonCode", failed.failure().reasonCode());
                failure.put("resultRef", failed.failure().resultRef().value());
                yield Optional.of(encode(TOOL_FAILURE_SCHEMA, failure));
            }
            case ToolOutcome.InputRequired ignored -> Optional.empty();
            case ToolOutcome.OutcomeUnknown ignored -> Optional.empty();
        };
    }

    /**
     * 明确标记冻结业务schema或安全字段契约的确定拒绝，外部工具原结果仍须保存。
     * 捕获范围只覆盖生产纯校验器的固定契约异常；技术异常、其他参数错误和错误类型继续传播。
     */
    private TypedPayload safeToolSuccess(ToolRequest request, ToolOutcome.Succeeded success) {
        // 解析配额、非法JSON与Unicode先独立拒绝，不能被归类为已经验证的业务输出拒绝。
        SessionRuntimePayloadJson.parse(request.target().outputSchema().canonicalJson(), limits);
        SessionRuntimePayloadJson.parse(success.observation().canonicalJson(), limits);
        try {
            return SessionRuntimePayloads.safeToolOutput(request.target().outputSchema(), success.observation(), request.target().sensitiveFields(), limits);
        } catch (IllegalArgumentException rejected) {
            if (rejected.getClass() == IllegalArgumentException.class
                    && "session_runtime_payload_invalid".equals(rejected.getMessage())) throw new ToolOutputRejected();
            throw rejected;
        }
    }

    /**
     * 实际工具结果确定不满足冻结输出或安全投影契约，不表示Handler未执行或外部效果失败。
     * 只有上面的固定纯校验边界签发该分类，调用方不能因此吞掉任意运行异常。
     */
    public static final class ToolOutputRejected extends IllegalArgumentException {
        /** 固定原因不保留原业务结果或字段值。 */
        private ToolOutputRejected() { super("execution_tool_output_invalid"); }
    }

    /** 模型只能读取已保存的安全工具正文；媒体保持明确引用，不下载或字符串化图片。 */
    public List<ModelContent> toolContents(TypedPayload feedback) {
        if (TOOL_FAILURE_SCHEMA.equals(feedback.schemaId())) {
            var failure = parse(feedback, TOOL_FAILURE_SCHEMA);
            fields(failure, "reasonCode", "resultRef");
            var reason = string(failure, "reasonCode");
            var message = switch (reason) {
                case "mcp_tool_authentication_required" -> "工具返回认证失败，未取得可用业务结果。";
                case "mcp_tool_permission_denied", "tool_policy_denied" -> "工具访问被权限策略拒绝，未取得可用业务结果。";
                case "mcp_tool_unavailable" -> "工具服务当前不可用，未取得可用业务结果。";
                case "mcp_tool_arguments_invalid" -> "工具拒绝参数，未取得可用数据，不能当作零值或没有记录。";
                case "mcp_tool_arguments_invalid_after_dispatch" -> "工具提示参数可能有误，未取得可用业务结果；失败不代表未执行或没有副作用。";
                case "mcp_tool_timeout" -> "只读工具返回超时失败，未取得可用业务结果。";
                case "mcp_tool_rate_limited" -> "只读工具受到调用频率限制，未取得可用业务结果。";
                case "mcp_pre_dispatch_rejected", "tool_pre_dispatch_rejected", "CANCELLED_BEFORE_DISPATCH" -> "本次工具调用在派发前被拒绝，工具尚未执行。";
                case "READ_REQUIRED", "FILE_VERSION_CONFLICT", "PATH_CHANGED" -> "文件读取前置条件或版本发生变化，本次操作未取得成功回执。";
                default -> "本次工具调用收到确定失败回执，未取得可用业务结果；失败不代表未执行或没有副作用。";
            };
            var nextStep = switch (reason) {
                case "mcp_tool_authentication_required" -> "向用户说明认证问题，请管理员检查工具连接凭据；不要猜测凭据或绕过认证。";
                case "mcp_tool_permission_denied", "tool_policy_denied" -> "向用户说明权限限制，请有权限的人员处理；不要改换身份绕过限制。";
                case "mcp_tool_arguments_invalid" -> "依据本次已授权工具的真实 Schema 修正参数；仍需查询时以修正后的不同参数发起新请求，不要原样重试。";
                case "mcp_tool_arguments_invalid_after_dispatch" -> "先依据本地结果引用核查已有副作用与幂等约束，必要时请有权限的人员核查；确认安全后依据本次已授权工具的真实 Schema 考虑修正参数，不要盲目重发。";
                case "READ_REQUIRED", "FILE_VERSION_CONFLICT", "PATH_CHANGED" -> "先读取当前文件内容和版本，再按原目标判断是否仍需修改。";
                default -> "依据原因码和原结果引用核对失败原因；再次调用前先确认权限、幂等约束及已有副作用，不要盲目重发。";
            };
            return List.of(new ModelContent.Text("工具调用失败。\n原因码：" + reason
                    + "\n结果引用：" + string(failure, "resultRef") + "\n" + message + "\n下一步：" + nextStep));
        }
        var value = parse(feedback, SessionRuntimePayloads.TOOL_RESULT_SCHEMA);
        var result = new ArrayList<ModelContent>();
        String structured = value.has("structuredContent") ? value.get("structuredContent").toString() : null;
        if (structured != null) result.add(new ModelContent.Text(structured));
        if (!value.path("content").isArray()) throw invalid();
        for (var part : value.get("content")) {
            if ("resource".equals(string(part, "type"))) {
                var resource = part.get("resource");
                if (resource == null || !resource.isObject() || resource.has("blob")) throw invalid();
                String uri = string(resource, "uri");
                if (uri.isBlank()) throw invalid();
                var projection = object();
                projection.put("type", "resource");
                projection.put("uri", uri);
                if (resource.has("mimeType")) projection.put("mimeType", string(resource, "mimeType"));
                projection.put("text", string(resource, "text"));
                result.add(new ModelContent.Text(SessionRuntimePayloadJson.write(projection)));
                continue;
            }
            var content = readContent(part);
            // 仅省略与已展示结构化正文逐字一致的文本，保留说明、不同数据及多模态内容。
            if (content instanceof ModelContent.Text text && text.text().equals(structured)) continue;
            result.add(content);
        }
        return List.copyOf(result);
    }

    /** 保留原安全结果及调用配对，并明确重复请求没有触发新的外部执行。 */
    static List<ModelContent> reusedToolContents(List<ModelContent> original) {
        var contents = new ArrayList<ModelContent>();
        contents.add(new ModelContent.Text("本次工具与参数和之前的调用相同，以下复用已有结果，未重新执行工具。请依据已有结果继续；如仍缺少信息，请调整查询条件。原结果的成功、失败及数据范围保持不变。"));
        contents.addAll(original);
        return List.copyOf(contents);
    }

    /** 内容寻址只绑定当前材料，不把 Run 或历史结果递归嵌入指纹。 */
    public static ResultRef reference(TypedPayload content) {
        return new ResultRef(content.schemaId() + ":" + digest(content.schemaId() + "\0" + content.canonicalJson()));
    }

    /** 固定规范 UTF-8 摘要用于真实请求指纹和确定性事件身份；纯计算，不签发派发或采纳权。 */
    public static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("sha256_unavailable", unavailable); }
    }

    /** 明确构造固定字段节点，不借 Map 或反射复制实体。 */
    static ObjectNode object() { return JsonNodeFactory.instance.objectNode(); }

    /** 标准库 JSON 编码后有界回读，整个 payload 的封套和正文都受配额限制。 */
    TypedPayload encode(String schema, ObjectNode value) {
        String json = SessionRuntimePayloadJson.write(value);
        SessionRuntimePayloadJson.parse(json, limits);
        return new TypedPayload(schema, json);
    }

    /** 私有版本必须精确匹配，不能回退到旧准备或来源证明 codec。 */
    JsonNode parse(TypedPayload payload, String schema) {
        if (!schema.equals(payload.schemaId())) throw invalid();
        return SessionRuntimePayloadJson.parse(payload.canonicalJson(), limits);
    }

    /** 检查固定字段闭包，未知字段和缺省字段同样拒绝。 */
    static void fields(JsonNode value, String... names) {
        if (value == null || !value.isObject() || value.size() != names.length) throw invalid();
        for (var name : names) if (!value.has(name)) throw invalid();
    }

    /** 固定字符串字段不接受隐式类型转换。 */
    static String string(JsonNode value, String name) {
        var field = value == null ? null : value.get(name);
        if (field == null || !field.isTextual()) throw invalid();
        return field.textValue();
    }

    /** 控制整数统一保存精确十进制字符串；外部媒体结果也允许实际 JSON 整数。 */
    static long number(JsonNode value, String name) {
        var field = value.get(name);
        if (field == null) throw invalid();
        if (field.isIntegralNumber() && field.canConvertToLong()) return field.longValue();
        if (!field.isTextual()) throw invalid();
        try {
            long result = Long.parseLong(field.textValue());
            if (!Long.toString(result).equals(field.textValue())) throw invalid();
            return result;
        } catch (NumberFormatException rejected) { throw invalid(); }
    }

    /** TypedPayload 在材料封套中只保存其精确 schema 和规范正文。 */
    static ObjectNode payload(TypedPayload content) {
        var value = object(); value.put("schemaId", content.schemaId()); value.put("canonicalJson", content.canonicalJson()); return value;
    }

    /** 读取明确材料封套，不反射转换任意 JSON 对象。 */
    static TypedPayload readPayload(JsonNode value) {
        fields(value, "schemaId", "canonicalJson"); return new TypedPayload(string(value, "schemaId"), string(value, "canonicalJson"));
    }

    /** 图文按协议原顺序编码，图片只包含持久引用。 */
    private static ObjectNode content(ModelContent part) {
        var value = object();
        if (part instanceof ModelContent.Text text) { value.put("type", "text"); value.put("text", text.text()); return value; }
        var image = (ModelContent.Image) part;
        value.put("type", "image"); value.put("detail", image.detail().name());
        var media = object(); var owner = object(); owner.put("tenantId", image.media().owner().tenantId().value()); owner.put("sessionId", image.media().owner().sessionId());
        media.set("owner", owner); media.put("id", image.media().id()); media.put("sha256", image.media().sha256()); media.put("mimeType", image.media().mimeType());
        media.put("byteLength", Long.toString(image.media().byteLength())); media.put("width", Integer.toString(image.media().width())); media.put("height", Integer.toString(image.media().height()));
        value.set("media", media); return value;
    }

    /** 实际工具和入口共享图文引用协议，不把图片降级为正文。 */
    private static ModelContent readContent(JsonNode value) {
        if ("text".equals(string(value, "type"))) { fields(value, "type", "text"); return new ModelContent.Text(string(value, "text")); }
        if (!"image".equals(string(value, "type"))) throw invalid();
        fields(value, "type", "media", "detail");
        var media = value.get("media"); fields(media, "owner", "id", "sha256", "mimeType", "byteLength", "width", "height");
        var owner = media.get("owner"); fields(owner, "tenantId", "sessionId");
        return new ModelContent.Image(new MediaRef(new SessionRef(new TenantId(string(owner, "tenantId")), string(owner, "sessionId")),
                string(media, "id"), string(media, "sha256"), string(media, "mimeType"), number(media, "byteLength"),
                Math.toIntExact(number(media, "width")), Math.toIntExact(number(media, "height"))), ImageDetail.valueOf(string(value, "detail")));
    }

    /** 固定工具目标只保存一次完整版本事实，不包含模型上下文或旧操作协议。 */
    private static ObjectNode target(FrozenToolTarget target) {
        var value = object();
        value.put("handle", target.handle().value());
        var capability = object(); capability.put("type", target.capability().type().name());
        capability.put("capabilityId", target.capability().capabilityId()); capability.put("versionId", target.capability().versionId());
        value.set("capability", capability);
        value.put("deploymentId", target.deploymentId()); value.put("deploymentRevision", Long.toString(target.deploymentRevision()));
        value.set("inputSchema", payload(target.inputSchema())); value.set("outputSchema", payload(target.outputSchema()));
        value.put("effect", target.effect().name()); value.put("idempotent", target.idempotent()); value.put("timeout", target.timeout().toString());
        value.put("modelInvocable", target.modelInvocable()); value.put("confirmation", target.confirmation().name());
        var sensitive = value.putArray("sensitiveFields"); target.sensitiveFields().stream().sorted().forEach(sensitive::add);
        var requirements = value.putArray("providerRequirements"); target.providerRequirements().stream().sorted().forEach(requirements::add);
        value.put("outputSchemaDeclared", target.outputSchemaDeclared());
        value.put(CALL_IDENTITY_POLICY_FIELD, target.callIdentityPolicy().name());
        return value;
    }

    /** 用 API 构造器核验原冻结目标全部语义，不以当前目录补齐字段。 */
    private static FrozenToolTarget readTarget(JsonNode value) {
        fields(value, "handle", "capability", "deploymentId", "deploymentRevision", "inputSchema", "outputSchema", "effect", "idempotent",
                "timeout", "modelInvocable", "confirmation", "sensitiveFields", "providerRequirements", "outputSchemaDeclared", CALL_IDENTITY_POLICY_FIELD);
        var capability = value.get("capability"); fields(capability, "type", "capabilityId", "versionId");
        return new FrozenToolTarget(new ToolPortal.ToolHandle(string(value, "handle")),
                new CapabilityRef(CapabilityType.valueOf(string(capability, "type")), string(capability, "capabilityId"), string(capability, "versionId")),
                string(value, "deploymentId"), number(value, "deploymentRevision"), readPayload(value.get("inputSchema")), readPayload(value.get("outputSchema")),
                ToolEffect.valueOf(string(value, "effect")), bool(value, "idempotent"), Duration.parse(string(value, "timeout")), bool(value, "modelInvocable"),
                ToolPortal.ToolConfirmationPolicy.valueOf(string(value, "confirmation")), texts(value.get("sensitiveFields")), texts(value.get("providerRequirements")), bool(value, "outputSchemaDeclared"),
                ToolPortal.ToolCallIdentityPolicy.valueOf(string(value, CALL_IDENTITY_POLICY_FIELD)));
    }

    /** 布尔字段必须显式提供原值，不能用 Jackson 默认值掩盖缺失。 */
    private static boolean bool(JsonNode value, String field) {
        var node = value.get(field); if (node == null || !node.isBoolean()) throw invalid(); return node.booleanValue();
    }

    /** 冻结集合必须唯一，不能静默去重改变原目标。 */
    private static Set<String> texts(JsonNode value) {
        if (value == null || !value.isArray()) throw invalid();
        var result = new HashSet<String>();
        for (var entry : value) if (!entry.isTextual() || !result.add(entry.textValue())) throw invalid();
        return Set.copyOf(result);
    }

    /** 固定身份不能为空，不截断或替换非法输入。 */
    private static void text(String value) { if (value == null || value.isBlank()) throw invalid(); }

    /** 固定拒绝原因不暴露私有协议材料。 */
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("execution_codec_invalid"); }
}
