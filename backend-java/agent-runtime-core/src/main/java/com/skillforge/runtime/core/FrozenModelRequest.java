package com.skillforge.runtime.core;

import com.skillforge.runtime.json.CodecLimits;

import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.media.*;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.ModelPort.EnforcementLevel;
import com.skillforge.runtime.api.port.ModelPort.PromptPrefixCapabilities;
import com.skillforge.runtime.api.port.ModelPort.CapabilitySupportStatus;
import com.skillforge.runtime.api.port.ModelPort.ToolUpdateMode;
import com.skillforge.runtime.api.port.ModelPort.FrozenModelTarget;
import com.skillforge.runtime.api.port.ModelPort.GenerationOptions;
import com.skillforge.runtime.api.port.ModelPort.ModelMessage;
import com.skillforge.runtime.api.port.ModelPort.ModelToolCall;
import com.skillforge.runtime.api.port.ModelPort.ModelToolKind;
import com.skillforge.runtime.api.port.ModelPort.ModelBindingRef;
import com.skillforge.runtime.api.port.ModelPort.ModelCapabilitySnapshot;
import com.skillforge.runtime.api.port.ModelPort.ModelRole;
import com.skillforge.runtime.api.port.ModelPort.ModelTool;
import com.skillforge.runtime.api.port.ModelPort.ToolChoice;
import com.skillforge.runtime.api.port.ModelPort.ReasoningPolicy;
import com.skillforge.runtime.api.port.ModelPort.RequestedModelStrategy;
import com.skillforge.runtime.api.port.ModelPort.ResponseContract;
import com.skillforge.runtime.api.port.ModelPort.ResponseFormat;
import com.skillforge.runtime.api.port.ModelPort.ResponseFormatKind;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.DateTimeException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/** 冻结 provider-neutral 模型请求；参数可编码不等于 Policy 已允许、快照已提交或外调已授权。 */
public record FrozenModelRequest(
        RequestedModelStrategy requestedStrategy,
        FrozenModelTarget target,
        List<ModelMessage> messages,
        List<ModelTool> tools,
        ToolChoice toolChoice,
        ResponseContract response,
        GenerationOptions generation,
        ReasoningPolicy reasoning,
        com.skillforge.runtime.api.port.ExecutionBudgetPort.Category budgetCategory) {

    /** 实际模型请求材料的唯一版本；不内嵌 Run、checkpoint 或调用许可。 */
    public static final String SCHEMA = "skillforge.runtime.execution.model-request.v2";

    /** 复用现有完整请求编码，Session 直接保存这份实际请求，不再包装另一份准备快照。 */
    public TypedPayload encode() {
        return new TypedPayload(SCHEMA, CanonicalJson.encode(canonicalValue()));
    }

    /** 按明确版本和有界解析恢复原请求，拒绝旧格式、未知字段和非规范文本。 */
    public static FrozenModelRequest decode(TypedPayload payload, CodecLimits limits) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(limits, "limits");
        if (!SCHEMA.equals(payload.schemaId())) throw new IllegalArgumentException("unsupported model request schema");
        var request = decode(CanonicalJson.parse(payload.canonicalJson(), limits));
        if (!request.encode().equals(payload)) throw new IllegalArgumentException("noncanonical model request");
        return request;
    }

    public FrozenModelRequest(RequestedModelStrategy requestedStrategy, FrozenModelTarget target,
            List<ModelMessage> messages, List<ModelTool> tools, ToolChoice toolChoice,
            ResponseContract response, GenerationOptions generation, ReasoningPolicy reasoning) {
        this(requestedStrategy,target,messages,tools,toolChoice,response,generation,reasoning,
                com.skillforge.runtime.api.port.ExecutionBudgetPort.Category.BUSINESS_MODEL);
    }

    static final String TARGET_SCHEMA = "skillforge.runtime.model-target.v1";

    /** 无工具准备使用显式空目录；不从 system prompt 或能力句柄反向解析工具。 */
    public FrozenModelRequest(RequestedModelStrategy requestedStrategy,
            FrozenModelTarget target, List<ModelMessage> messages,
            ResponseContract response, GenerationOptions generation,
            ReasoningPolicy reasoning) {
        this(requestedStrategy, target, messages, List.of(), ToolChoice.NONE,
                response, generation, reasoning);
    }

    /** 复用 API 值约束并在取得 permit 前校验消息唯一性和目标能力，绝不伪造 ModelCall。 */
    public FrozenModelRequest {
        Objects.requireNonNull(requestedStrategy, "requestedStrategy");
        Objects.requireNonNull(target, "target");
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
        Objects.requireNonNull(toolChoice, "toolChoice");
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(generation, "generation");
        Objects.requireNonNull(reasoning, "reasoning");
        Objects.requireNonNull(budgetCategory, "budgetCategory");
        if (budgetCategory != com.skillforge.runtime.api.port.ExecutionBudgetPort.Category.BUSINESS_MODEL
                && budgetCategory != com.skillforge.runtime.api.port.ExecutionBudgetPort.Category.AUXILIARY_MODEL)
            throw new IllegalArgumentException("model_budget_category_invalid");
        HashSet<String> messageIds = new HashSet<>();
        for (ModelMessage message : messages) {
            if (message.protocolState().isPresent() && !target.effectiveModelId().equals(message.protocolState().orElseThrow().modelId()))
                throw new IllegalArgumentException("native_protocol_model_mismatch");
            if (!messageIds.add(message.messageId())) {
                throw new IllegalArgumentException("duplicate model messageId");
            }
        }
        com.skillforge.runtime.api.port.ModelPort.validateConversation(messages);
        var imagePolicy = target.capabilities().imagePolicy();
        int imageCount=0;
        for(var message:messages) for(var part:message.contents()) if(part instanceof ModelContent.Image image) {
            if(!imagePolicy.enabled() || message.role()==ModelRole.TOOL && !imagePolicy.toolImages())
                throw new IllegalArgumentException("model_image_unsupported");
            if(!image.media().owner().tenantId().equals(target.binding().tenantId())
                    || image.media().byteLength()>imagePolicy.maxImageBytes()
                    || image.media().width()>imagePolicy.maxDimension() || image.media().height()>imagePolicy.maxDimension())
                throw new IllegalArgumentException("model_image_variant_invalid");
            imageCount++;
        }
        if(imageCount>imagePolicy.maxImages())throw new IllegalArgumentException("model_image_count_exceeded");
        var toolNames = new HashSet<String>();
        var toolHandles = new HashSet<String>();
        for (ModelTool tool : tools) {
            if (!toolNames.add(tool.wireName()) || !toolHandles.add(tool.toolHandle())) {
                throw new IllegalArgumentException("duplicate model tool identity");
            }
        }
        if (tools.isEmpty() && toolChoice != ToolChoice.NONE) {
            throw new IllegalArgumentException("invalid model tool choice");
        }
        if (!target.capabilities().responseFormats().contains(formatKind(response.format()))) {
            throw new IllegalArgumentException("response format must be supported by frozen target");
        }
        if (!target.capabilities().reasoning() && reasoning != ReasoningPolicy.DISABLED) {
            throw new IllegalArgumentException("unsupported reasoning must be explicitly disabled");
        }
    }

    /** 显式序列化注册 modelRequest 字段，不把调用控制、供应商参数或原始 JSON 片段带入快照。 */
    CanonicalJson.Value canonicalValue() {
        return CanonicalJson.object(List.of(
                new CanonicalJson.Field("requestedStrategy", CanonicalJson.object(List.of(
                        new CanonicalJson.Field("strategyId", CanonicalJson.string(requestedStrategy.strategyId())),
                        new CanonicalJson.Field("strategyVersion", CanonicalJson.string(requestedStrategy.strategyVersion()))))),
                new CanonicalJson.Field("target", targetValue()),
                new CanonicalJson.Field("messages", CanonicalJson.array(messages.stream().map(FrozenModelRequest::messageValue).toList())),
                new CanonicalJson.Field("tools", CanonicalJson.array(tools.stream().map(FrozenModelRequest::toolValue).toList())),
                new CanonicalJson.Field("toolChoice", CanonicalJson.string(toolChoice.name())),
                new CanonicalJson.Field("response", CanonicalJson.object(List.of(
                        new CanonicalJson.Field("format", formatValue(response.format())),
                        new CanonicalJson.Field("enforcement", CanonicalJson.string(response.enforcement().name())),
                        new CanonicalJson.Field("negotiationReasonCode", CanonicalJson.string(response.negotiationReasonCode()))))),
                new CanonicalJson.Field("generation", generationValue()),
                new CanonicalJson.Field("reasoning", CanonicalJson.string(reasoning.name())),
                new CanonicalJson.Field("budgetCategory", CanonicalJson.string(budgetCategory.name()))));
    }

    /** 将已通过有界词法解析的节点恢复为完整请求，未知字段和任何规范表示漂移均拒绝。 */
    static FrozenModelRequest decode(CanonicalJson.Value value) {
        try {
            value.requireFields("requestedStrategy", "target", "messages", "tools", "toolChoice", "response", "generation", "reasoning", "budgetCategory");
            var strategy = value.field("requestedStrategy").requireFields("strategyId", "strategyVersion");
            var response = value.field("response").requireFields("format", "enforcement", "negotiationReasonCode");
            var generation = value.field("generation").requireFields("maxOutputTokens", "temperature");
            var maxTokens = generation.field("maxOutputTokens");
            var temperature = generation.field("temperature");
            FrozenModelRequest request = new FrozenModelRequest(
                    new RequestedModelStrategy(strategy.field("strategyId").stringValue(), strategy.field("strategyVersion").stringValue()),
                    decodeTarget(value.field("target")),
                    value.field("messages").arrayValue().stream().map(FrozenModelRequest::decodeMessage).toList(),
                    value.field("tools").arrayValue().stream().map(FrozenModelRequest::decodeTool).toList(),
                    ToolChoice.valueOf(value.field("toolChoice").stringValue()),
                    new ResponseContract(decodeFormat(response.field("format")),
                            EnforcementLevel.valueOf(response.field("enforcement").stringValue()),
                            response.field("negotiationReasonCode").stringValue()),
                    new GenerationOptions(maxTokens.isNull() ? OptionalInt.empty() : OptionalInt.of(maxTokens.intValue()),
                            temperature.isNull() ? OptionalDouble.empty() : OptionalDouble.of(Double.parseDouble(temperature.stringValue()))),
                    ReasoningPolicy.valueOf(value.field("reasoning").stringValue()),
                    com.skillforge.runtime.api.port.ExecutionBudgetPort.Category.valueOf(value.field("budgetCategory").stringValue()));
            // JSON 层无法判定 Set 顺序、Duration 别名或 decimal 的规范语义，必须再比对完整重编码。
            if (!CanonicalJson.encode(request.canonicalValue()).equals(CanonicalJson.encode(value))) {
                throw new IllegalArgumentException("frozen model request is not canonical");
            }
            return request;
        } catch (IllegalArgumentException | DateTimeException exception) {
            // 无效枚举/整数/时间是协议拒绝，不把 JDK 异常中的原始字段值放入错误链。
            throw new IllegalArgumentException("invalid frozen model request");
        }
    }

    /** 只对 binding 身份构造 canonical target，内容寻址的 targetHandle 不含 endpoint 或密钥。 */
    CanonicalJson.Value bindingValue() {
        return CanonicalJson.object(List.of(
                new CanonicalJson.Field("tenantId", CanonicalJson.string(target.binding().tenantId().value())),
                new CanonicalJson.Field("bindingId", CanonicalJson.string(target.binding().bindingId())),
                new CanonicalJson.Field("revision", CanonicalJson.integer(target.binding().revision()))));
    }

    /** targetHandle 与 binding revision 绑定；其他配置漂移由完整 invocation fingerprint 检测。 */
    String targetHandle() {
        return TARGET_SCHEMA + ":" + CanonicalJson.hash(TARGET_SCHEMA, bindingValue());
    }

    /** 日志只显示类型，不输出冻结的 system prompt、用户输入或业务 JSON Schema。 */
    @Override
    public String toString() {
        return "FrozenModelRequest[REDACTED]";
    }

    /** 保存完整解析目标和能力事实；格式集合按 ASCII 枚举名排序，符合其编码字节序。 */
    private CanonicalJson.Value targetValue() {
        var capabilities = target.capabilities();
        return CanonicalJson.object(List.of(
                new CanonicalJson.Field("binding", bindingValue()),
                new CanonicalJson.Field("effectiveModelId", CanonicalJson.string(target.effectiveModelId())),
                new CanonicalJson.Field("capabilities", CanonicalJson.object(List.of(
                        new CanonicalJson.Field("responseFormats", CanonicalJson.array(capabilities.responseFormats().stream()
                                .map(Enum::name).sorted().map(CanonicalJson::string).toList())),
                        new CanonicalJson.Field("streaming", CanonicalJson.bool(capabilities.streaming())),
                        new CanonicalJson.Field("reasoning", CanonicalJson.bool(capabilities.reasoning())),
                        new CanonicalJson.Field("capabilityFactRef", CanonicalJson.string(capabilities.capabilityFactRef().value())),
                        new CanonicalJson.Field("imagePolicy", imagePolicyValue(capabilities.imagePolicy())),
                        new CanonicalJson.Field("prefixCapabilities", prefixCapabilitiesValue(capabilities.prefixCapabilities()))))),
                new CanonicalJson.Field("timeout", CanonicalJson.string(target.timeout().toString())),
                new CanonicalJson.Field("resolutionRef", CanonicalJson.string(target.resolutionRef().value()))));
    }

    /** 可选协议能力属于冻结目标，恢复时不能重新查询当前供应商目录。 */
    private static CanonicalJson.Value prefixCapabilitiesValue(
            PromptPrefixCapabilities capabilities) {
        return CanonicalJson.object(List.of(
                new CanonicalJson.Field("systemUpdatesInHistory", CanonicalJson.string(capabilities.systemUpdatesInHistory().name())),
                new CanonicalJson.Field("toolUpdates", CanonicalJson.string(capabilities.toolUpdates().name())),
                new CanonicalJson.Field("retainToolsWithToolChoiceNone", CanonicalJson.string(capabilities.retainToolsWithToolChoiceNone().name()))));
    }

    /** 当前冻结格式要求完整能力字段；缺失或未知状态按外层有界解码失败处理。 */
    private static PromptPrefixCapabilities decodePrefixCapabilities(CanonicalJson.Value value) {
        value.requireFields("systemUpdatesInHistory", "toolUpdates", "retainToolsWithToolChoiceNone");
        return new PromptPrefixCapabilities(
                CapabilitySupportStatus.valueOf(value.field("systemUpdatesInHistory").stringValue()),
                ToolUpdateMode.valueOf(value.field("toolUpdates").stringValue()),
                CapabilitySupportStatus.valueOf(value.field("retainToolsWithToolChoiceNone").stringValue()));
    }

    /** Optional 生成参数使用显式 null，temperature 按注册十进制字符串规则规范化负零。 */
    private CanonicalJson.Value generationValue() {
        CanonicalJson.Value maxTokens = generation.maxOutputTokens().isPresent()
                ? CanonicalJson.integer(generation.maxOutputTokens().getAsInt()) : CanonicalJson.nil();
        CanonicalJson.Value temperature = generation.temperature().isPresent()
                ? CanonicalJson.string(BigDecimal.valueOf(generation.temperature().getAsDouble()).stripTrailingZeros().toPlainString())
                : CanonicalJson.nil();
        return CanonicalJson.object(List.of(
                new CanonicalJson.Field("maxOutputTokens", maxTokens),
                new CanonicalJson.Field("temperature", temperature)));
    }

    /** 消息 id、角色和内容均保留，渲染次序由外层 List 冻结。 */
    private static CanonicalJson.Value messageValue(ModelMessage message) {
        return CanonicalJson.object(List.of(
                new CanonicalJson.Field("messageId", CanonicalJson.string(message.messageId())),
                new CanonicalJson.Field("role", CanonicalJson.string(message.role().name())),
                new CanonicalJson.Field("contents", CanonicalJson.array(message.contents().stream().map(FrozenModelRequest::contentValue).toList())),
                new CanonicalJson.Field("toolCalls", CanonicalJson.array(message.toolCalls().stream().map(call -> CanonicalJson.object(List.of(
                        new CanonicalJson.Field("callId", CanonicalJson.string(call.callId())),
                        new CanonicalJson.Field("wireName", CanonicalJson.string(call.wireName())),
                        new CanonicalJson.Field("arguments", payloadValue(call.arguments()))))).toList())),
                new CanonicalJson.Field("toolCallId", message.toolCallId().map(CanonicalJson::string).orElse(CanonicalJson.nil())),
                new CanonicalJson.Field("reasoningContent", message.reasoningContent().map(CanonicalJson::string).orElse(CanonicalJson.nil())),
                new CanonicalJson.Field("protocolState", NativeProtocolStateCodec.encode(message.protocolState()))));
    }

    private static CanonicalJson.Value imagePolicyValue(ModelImagePolicy p) {
        return CanonicalJson.object(List.of(
                new CanonicalJson.Field("enabled", CanonicalJson.bool(p.enabled())),
                new CanonicalJson.Field("toolImages", CanonicalJson.bool(p.toolImages())),
                new CanonicalJson.Field("maxImages", CanonicalJson.integer(p.maxImages())),
                new CanonicalJson.Field("maxImageBytes", CanonicalJson.integer(p.maxImageBytes())),
                new CanonicalJson.Field("maxRequestBytes", CanonicalJson.integer(p.maxRequestBytes())),
                new CanonicalJson.Field("maxDimension", CanonicalJson.integer(p.maxDimension())),
                new CanonicalJson.Field("tileSize", CanonicalJson.integer(p.tileSize())),
                new CanonicalJson.Field("baseTokens", CanonicalJson.integer(p.baseTokens())),
                new CanonicalJson.Field("tileTokens", CanonicalJson.integer(p.tileTokens())),
                new CanonicalJson.Field("version", CanonicalJson.string(p.version()))));
    }

    private static ModelImagePolicy decodeImagePolicy(CanonicalJson.Value v) {
        v.requireFields("enabled", "toolImages", "maxImages", "maxImageBytes", "maxRequestBytes", "maxDimension",
                "tileSize", "baseTokens", "tileTokens", "version");
        return new ModelImagePolicy(v.field("enabled").booleanValue(), v.field("toolImages").booleanValue(),
                v.field("maxImages").intValue(), v.field("maxImageBytes").longValue(), v.field("maxRequestBytes").longValue(),
                v.field("maxDimension").intValue(), v.field("tileSize").intValue(), v.field("baseTokens").intValue(),
                v.field("tileTokens").intValue(), v.field("version").stringValue());
    }

    private static CanonicalJson.Value contentValue(ModelContent content) {
        if (content instanceof ModelContent.Text text) return CanonicalJson.object(List.of(
                new CanonicalJson.Field("type", CanonicalJson.string("text")),
                new CanonicalJson.Field("text", CanonicalJson.string(text.text()))));
        var image = (ModelContent.Image) content;
        var media = image.media();
        return CanonicalJson.object(List.of(new CanonicalJson.Field("type", CanonicalJson.string("image")),
                new CanonicalJson.Field("detail", CanonicalJson.string(image.detail().name())),
                new CanonicalJson.Field("media", CanonicalJson.object(List.of(
                        new CanonicalJson.Field("owner", CanonicalJson.object(List.of(
                                new CanonicalJson.Field("tenantId", CanonicalJson.string(media.owner().tenantId().value())),
                                new CanonicalJson.Field("sessionId", CanonicalJson.string(media.owner().sessionId()))))),
                        new CanonicalJson.Field("id", CanonicalJson.string(media.id())),
                        new CanonicalJson.Field("sha256", CanonicalJson.string(media.sha256())),
                        new CanonicalJson.Field("mimeType", CanonicalJson.string(media.mimeType())),
                        new CanonicalJson.Field("byteLength", CanonicalJson.integer(media.byteLength())),
                        new CanonicalJson.Field("width", CanonicalJson.integer(media.width())),
                        new CanonicalJson.Field("height", CanonicalJson.integer(media.height())))))));
    }

    private static ModelContent decodeContent(CanonicalJson.Value v) {
        if (v.field("type").stringValue().equals("text")) {
            v.requireFields("type", "text"); return new ModelContent.Text(v.field("text").stringValue());
        }
        v.requireFields("type", "media", "detail");
        if (!v.field("type").stringValue().equals("image")) throw new IllegalArgumentException("model_content_type_invalid");
        var m = v.field("media").requireFields("owner", "id", "sha256", "mimeType", "byteLength", "width", "height");
        var o = m.field("owner").requireFields("tenantId", "sessionId");
        return new ModelContent.Image(new MediaRef(new SessionRef(new TenantId(o.field("tenantId").stringValue()),
                o.field("sessionId").stringValue()), m.field("id").stringValue(), m.field("sha256").stringValue(),
                m.field("mimeType").stringValue(), m.field("byteLength").longValue(), m.field("width").intValue(),
                m.field("height").intValue()), ImageDetail.valueOf(v.field("detail").stringValue()));
    }

    private static CanonicalJson.Value toolValue(ModelTool tool) {
        return CanonicalJson.object(List.of(
                new CanonicalJson.Field("wireName", CanonicalJson.string(tool.wireName())),
                new CanonicalJson.Field("toolHandle", CanonicalJson.string(tool.toolHandle())),
                new CanonicalJson.Field("description", CanonicalJson.string(tool.description())),
                new CanonicalJson.Field("inputSchema", payloadValue(tool.inputSchema())),
                new CanonicalJson.Field("strict", CanonicalJson.bool(tool.strict())),
                new CanonicalJson.Field("kind", CanonicalJson.string(tool.kind().name())),
                new CanonicalJson.Field("batchEligible", CanonicalJson.bool(tool.batchEligible()))));
    }

    /** 显式封闭响应变体，避免 Text 和 JsonObject 都被编码为无区分的空对象。 */
    private static CanonicalJson.Value formatValue(ResponseFormat format) {
        CanonicalJson.Value schema = format instanceof ResponseFormat.JsonSchema jsonSchema
                ? payloadValue(jsonSchema.schema()) : CanonicalJson.nil();
        return CanonicalJson.object(List.of(
                new CanonicalJson.Field("kind", CanonicalJson.string(format instanceof ResponseFormat.NativeConversation
                        ? "NATIVE_CONVERSATION" : formatKind(format).name())),
                new CanonicalJson.Field("schema", schema)));
    }

    /** TypedPayload 是带 schema 的不透明规范文本；编码不替代其所属 registry 的内容校验。 */
    private static CanonicalJson.Value payloadValue(TypedPayload payload) {
        return CanonicalJson.object(List.of(
                new CanonicalJson.Field("schemaId", CanonicalJson.string(payload.schemaId())),
                new CanonicalJson.Field("canonicalJson", CanonicalJson.string(payload.canonicalJson()))));
    }

    /** 穷尽 API sealed 变体，新增响应格式时必须显式更新 Core，而不是按字段猜测。 */
    private static ResponseFormatKind formatKind(ResponseFormat format) {
        return switch (format) {
            case ResponseFormat.Text ignored -> ResponseFormatKind.TEXT;
            case ResponseFormat.NativeConversation ignored -> ResponseFormatKind.TEXT;
            case ResponseFormat.JsonObject ignored -> ResponseFormatKind.JSON_OBJECT;
            case ResponseFormat.JsonSchema ignored -> ResponseFormatKind.JSON_SCHEMA;
        };
    }

    /** 显式解析完整冻结目标，能力集合不得靠去重掩盖重复编码。 */
    private static FrozenModelTarget decodeTarget(CanonicalJson.Value value) {
        value.requireFields("binding", "effectiveModelId", "capabilities", "timeout", "resolutionRef");
        var binding = value.field("binding").requireFields("tenantId", "bindingId", "revision");
        var capabilities = value.field("capabilities").requireFields("responseFormats", "streaming", "reasoning", "capabilityFactRef", "imagePolicy", "prefixCapabilities");
        List<ResponseFormatKind> formats = capabilities.field("responseFormats").arrayValue().stream()
                .map(item -> ResponseFormatKind.valueOf(item.stringValue())).toList();
        if (new HashSet<>(formats).size() != formats.size()) {
            throw new IllegalArgumentException("duplicate response capability");
        }
        return new FrozenModelTarget(new ModelBindingRef(new TenantId(binding.field("tenantId").stringValue()),
                binding.field("bindingId").stringValue(), binding.field("revision").longValue()),
                value.field("effectiveModelId").stringValue(), new ModelCapabilitySnapshot(Set.copyOf(formats),
                        capabilities.field("streaming").booleanValue(), capabilities.field("reasoning").booleanValue(),
                        new ResultRef(capabilities.field("capabilityFactRef").stringValue()), decodeImagePolicy(capabilities.field("imagePolicy")),
                        decodePrefixCapabilities(capabilities.field("prefixCapabilities"))),
                Duration.parse(value.field("timeout").stringValue()), new ResultRef(value.field("resolutionRef").stringValue()));
    }

    /** 原生调用、结果关联与推理历史一并冻结，不能从正文反向猜测结构。 */
    private static ModelMessage decodeMessage(CanonicalJson.Value value) {
        value.requireFields("messageId", "role", "contents", "toolCalls", "toolCallId", "reasoningContent", "protocolState");
        var calls = value.field("toolCalls").arrayValue().stream().map(tool -> {
            tool.requireFields("callId", "wireName", "arguments");
            var arguments = tool.field("arguments").requireFields("schemaId", "canonicalJson");
            return new ModelToolCall(tool.field("callId").stringValue(), tool.field("wireName").stringValue(),
                    new TypedPayload(arguments.field("schemaId").stringValue(), arguments.field("canonicalJson").stringValue()));
        }).toList();
        return new ModelMessage(value.field("messageId").stringValue(), ModelRole.valueOf(value.field("role").stringValue()),
                value.field("contents").arrayValue().stream().map(FrozenModelRequest::decodeContent).toList(), calls,
                value.field("toolCallId").isNull() ? Optional.empty() : Optional.of(value.field("toolCallId").stringValue()),
                value.field("reasoningContent").isNull() ? Optional.empty() : Optional.of(value.field("reasoningContent").stringValue()),
                NativeProtocolStateCodec.decode(value.field("protocolState")));
    }

    private static ModelTool decodeTool(CanonicalJson.Value value) {
        value.requireFields("wireName", "toolHandle", "description", "inputSchema", "strict", "kind", "batchEligible");
        var schema = value.field("inputSchema").requireFields("schemaId", "canonicalJson");
        return new ModelTool(value.field("wireName").stringValue(),
                value.field("toolHandle").stringValue(), value.field("description").stringValue(),
                new TypedPayload(schema.field("schemaId").stringValue(),
                        schema.field("canonicalJson").stringValue()),
                value.field("strict").booleanValue(), ModelToolKind.valueOf(value.field("kind").stringValue()),
                value.field("batchEligible").booleanValue());
    }

    /** Text/JsonObject 必须显式 schema=null；JsonSchema 必须携带注册 TypedPayload。 */
    private static ResponseFormat decodeFormat(CanonicalJson.Value value) {
        value.requireFields("kind", "schema");
        var schema = value.field("schema");
        if ("NATIVE_CONVERSATION".equals(value.field("kind").stringValue())) {
            if (!schema.isNull()) throw new IllegalArgumentException("native conversation must have null schema");
            return new ResponseFormat.NativeConversation();
        }
        ResponseFormatKind kind = ResponseFormatKind.valueOf(value.field("kind").stringValue());
        if (kind == ResponseFormatKind.JSON_SCHEMA) {
            schema.requireFields("schemaId", "canonicalJson");
            return new ResponseFormat.JsonSchema(new TypedPayload(schema.field("schemaId").stringValue(),
                    schema.field("canonicalJson").stringValue()));
        }
        if (!schema.isNull()) {
            throw new IllegalArgumentException("non-schema response format must have null schema");
        }
        return kind == ResponseFormatKind.TEXT ? new ResponseFormat.Text() : new ResponseFormat.JsonObject();
    }
}
