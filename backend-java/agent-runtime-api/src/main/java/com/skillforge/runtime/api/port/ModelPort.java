package com.skillforge.runtime.api.port;

import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.media.ModelContent;
import com.skillforge.runtime.api.media.ModelImagePolicy;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TypedPayload;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Flow;

/**
 * 执行由 Session 登记派发、已冻结目标和实际请求的规范模型调用。
 * 实现不得重新选择模型；流式实现负责 sequence 从 1 严格递增、恰好一个终态及终态后静默。
 */
public interface ModelPort {
    /** 生产模型适配器归一后的文本与结构化结果文档；不是Agent内部决策快照。 */
    String TEXT_OUTPUT_SCHEMA = "skillforge.runtime.model.text.v1";
    String JSON_OUTPUT_SCHEMA = "skillforge.runtime.model.json.v1";

    /**
     * 执行非流式模型调用，并以类型化结果表达成功、确定失败或结果未知。
     *
     * @param call 完整冻结且已通过能力协商的模型调用
     * @return 不泄露供应商载荷的规范模型结果
     */
    ModelResult generate(ModelCall call);

    /**
     * 执行流式模型调用；仅当 target capability 声明 streaming 时实现才可发起外部请求。
     * 业务失败必须通过终态事件表达，不能把供应商文本作为 {@code onError} 传播。
     *
     * @param call 完整冻结且已通过流式能力协商的模型调用
     * @return 同时响应订阅取消与调用取消信号的事件发布者
     */
    Flow.Publisher<ModelStreamEvent> stream(ModelCall call);

    /** 表示当前租户宿主配置中的模型绑定及其正 revision。 */
    record ModelBindingRef(TenantId tenantId, String bindingId, long revision) {

        /** 拒绝缺失租户、空白绑定身份或非正 revision。 */
        public ModelBindingRef {
            tenantId = PortContracts.requireNonNull(tenantId, "tenantId");
            bindingId = PortContracts.requireText(bindingId, "bindingId");
            revision = PortContracts.requirePositive(revision, "revision");
        }
    }

    /** 表示由调用方请求并需与冻结目标共同保存的模型策略身份。 */
    record RequestedModelStrategy(String strategyId, String strategyVersion) {

        /** 拒绝空白策略身份或版本。 */
        public RequestedModelStrategy {
            strategyId = PortContracts.requireText(strategyId, "strategyId");
            strategyVersion = PortContracts.requireText(strategyVersion, "strategyVersion");
        }
    }

    /** 枚举规范响应格式，用于解析能力与调用契约协商。 */
    enum ResponseFormatKind {
        TEXT,
        JSON_OBJECT,
        JSON_SCHEMA
    }

    /** 能力只有已证实支持才可启用优化；未知不等于明确不支持。 */
    enum CapabilitySupportStatus { SUPPORTED, UNSUPPORTED, UNVERIFIED }

    /** 工具目录更新协议；NONE 表示只能重建完整声明，不能追加历史更新事件。 */
    enum ToolUpdateMode { NONE, ADDITION_ONLY, ADD_REMOVE, UNVERIFIED }

    /** 冻结具体服务和模型的前缀保持能力，不从供应商品牌或兼容协议推断。 */
    record PromptPrefixCapabilities(CapabilitySupportStatus systemUpdatesInHistory,
            ToolUpdateMode toolUpdates, CapabilitySupportStatus retainToolsWithToolChoiceNone) {
        /** 拒绝缺失能力状态，避免默认值扩大供应商承诺。 */
        public PromptPrefixCapabilities {
            systemUpdatesInHistory = PortContracts.requireNonNull(systemUpdatesInHistory, "systemUpdatesInHistory");
            toolUpdates = PortContracts.requireNonNull(toolUpdates, "toolUpdates");
            retainToolsWithToolChoiceNone = PortContracts.requireNonNull(
                    retainToolsWithToolChoiceNone, "retainToolsWithToolChoiceNone");
        }

        /** 未选择或尚未核实服务时，所有可选优化默认关闭。 */
        public static PromptPrefixCapabilities unverified() {
            return new PromptPrefixCapabilities(CapabilitySupportStatus.UNVERIFIED,
                    ToolUpdateMode.UNVERIFIED, CapabilitySupportStatus.UNVERIFIED);
        }
    }

    /** 表示解析时冻结的响应格式、流式、reasoning 与前缀保持能力事实。 */
    record ModelCapabilitySnapshot(
            Set<ResponseFormatKind> responseFormats,
            boolean streaming,
            boolean reasoning,
            ResultRef capabilityFactRef,
            ModelImagePolicy imagePolicy,
            PromptPrefixCapabilities prefixCapabilities) {

        public ModelCapabilitySnapshot(Set<ResponseFormatKind> responseFormats, boolean streaming,
                boolean reasoning, ResultRef capabilityFactRef) {
            this(responseFormats, streaming, reasoning, capabilityFactRef, ModelImagePolicy.textOnly(),
                    PromptPrefixCapabilities.unverified());
        }

        /** 未选择前缀优化的调用仍须显式保守冻结，不借用供应商默认能力。 */
        public ModelCapabilitySnapshot(Set<ResponseFormatKind> responseFormats, boolean streaming,
                boolean reasoning, ResultRef capabilityFactRef, ModelImagePolicy imagePolicy) {
            this(responseFormats, streaming, reasoning, capabilityFactRef, imagePolicy,
                    PromptPrefixCapabilities.unverified());
        }

        /** 纯文本调用可以冻结已解析的前缀能力，无需构造图片策略。 */
        public ModelCapabilitySnapshot(Set<ResponseFormatKind> responseFormats, boolean streaming,
                boolean reasoning, ResultRef capabilityFactRef, PromptPrefixCapabilities prefixCapabilities) {
            this(responseFormats, streaming, reasoning, capabilityFactRef, ModelImagePolicy.textOnly(),
                    prefixCapabilities);
        }

        /** 防御复制格式集合，并拒绝空集合、null 元素、缺失 TEXT 或事实引用。 */
        public ModelCapabilitySnapshot {
            imagePolicy = java.util.Objects.requireNonNull(imagePolicy, "imagePolicy");
            prefixCapabilities = PortContracts.requireNonNull(prefixCapabilities, "prefixCapabilities");
            responseFormats = PortContracts.immutableSet(responseFormats, "responseFormats");
            capabilityFactRef = PortContracts.requireNonNull(
                    capabilityFactRef, "capabilityFactRef");
            // TEXT 是所有模型绑定必须具备的最低规范响应能力。
            if (responseFormats.isEmpty() || !responseFormats.contains(ResponseFormatKind.TEXT)) {
                throw new IllegalArgumentException(
                        "responseFormats must be non-empty and contain TEXT");
            }
        }
    }

    /** 表示解析完成后冻结的租户绑定、有效模型、能力、超时与解析事实。 */
    record FrozenModelTarget(
            ModelBindingRef binding,
            String effectiveModelId,
            ModelCapabilitySnapshot capabilities,
            Duration timeout,
            ResultRef resolutionRef) {

        /** 拒绝缺失目标字段、空白有效模型身份或非正超时。 */
        public FrozenModelTarget {
            binding = PortContracts.requireNonNull(binding, "binding");
            effectiveModelId = PortContracts.requireText(effectiveModelId, "effectiveModelId");
            capabilities = PortContracts.requireNonNull(capabilities, "capabilities");
            timeout = PortContracts.requireNonNull(timeout, "timeout");
            resolutionRef = PortContracts.requireNonNull(resolutionRef, "resolutionRef");
            // 冻结目标必须提供可用于 Core 计算 deadline 的严格正 Duration。
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
        }
    }

    /** 表示携带冻结快照、消息、响应协商、生成参数和 reasoning 策略的完整调用。 */
    record ModelCall(
            ProviderCallContext context,
            RequestedModelStrategy requestedStrategy,
            FrozenModelTarget target,
            ResultRef invocationSnapshotRef,
            List<ModelMessage> messages,
            List<ModelTool> tools,
            ToolChoice toolChoice,
            ResponseContract response,
            GenerationOptions generation,
            ReasoningPolicy reasoning,
            ExecutionBudgetPort.Category budgetCategory) {

        public ModelCall(ProviderCallContext context, RequestedModelStrategy requestedStrategy,
                FrozenModelTarget target, ResultRef invocationSnapshotRef, List<ModelMessage> messages,
                List<ModelTool> tools, ToolChoice toolChoice, ResponseContract response,
                GenerationOptions generation, ReasoningPolicy reasoning) {
            this(context, requestedStrategy, target, invocationSnapshotRef, messages, tools, toolChoice,
                    response, generation, reasoning, ExecutionBudgetPort.Category.BUSINESS_MODEL);
        }

        /** 无工具的调用使用显式空目录；保留调用点简洁但不从上下文推断工具。 */
        public ModelCall(ProviderCallContext context,
                RequestedModelStrategy requestedStrategy, FrozenModelTarget target,
                ResultRef invocationSnapshotRef, List<ModelMessage> messages,
                ResponseContract response, GenerationOptions generation,
                ReasoningPolicy reasoning) {
            this(context, requestedStrategy, target, invocationSnapshotRef, messages,
                    List.of(), ToolChoice.NONE, response, generation, reasoning);
        }

        /** 拒绝缺失值、跨租户目标、未支持响应格式或未支持 reasoning。 */
        public ModelCall {
            context = PortContracts.requireNonNull(context, "context");
            requestedStrategy = PortContracts.requireNonNull(
                    requestedStrategy, "requestedStrategy");
            target = PortContracts.requireNonNull(target, "target");
            invocationSnapshotRef = PortContracts.requireNonNull(
                    invocationSnapshotRef, "invocationSnapshotRef");
            messages = PortContracts.immutableList(messages, "messages");
            tools = PortContracts.immutableList(tools, "tools");
            toolChoice = PortContracts.requireNonNull(toolChoice, "toolChoice");
            response = PortContracts.requireNonNull(response, "response");
            generation = PortContracts.requireNonNull(generation, "generation");
            reasoning = PortContracts.requireNonNull(reasoning, "reasoning");
            budgetCategory = PortContracts.requireNonNull(budgetCategory, "budgetCategory");
            if (budgetCategory != ExecutionBudgetPort.Category.BUSINESS_MODEL
                    && budgetCategory != ExecutionBudgetPort.Category.AUXILIARY_MODEL)
                throw new IllegalArgumentException("model_budget_category_invalid");
            HashSet<String> messageIds = new HashSet<>();
            // 有序消息可以重复内容，但逻辑 messageId 必须唯一，避免同一身份映射多个位置。
            for (ModelMessage message : messages) {
                if (!messageIds.add(message.messageId())) {
                    throw new IllegalArgumentException(
                            "messages must not contain duplicate messageId");
                }
            }
            validateConversation(messages);
            HashSet<String> toolNames = new HashSet<>();
            HashSet<String> toolHandles = new HashSet<>();
            for (ModelTool tool : tools) {
                if (!toolNames.add(tool.wireName()) || !toolHandles.add(tool.toolHandle())) {
                    throw new IllegalArgumentException("tools must have unique wireName and toolHandle");
                }
            }
            for (var message : messages) if (message.protocolState().isPresent()
                    && !target.effectiveModelId().equals(message.protocolState().orElseThrow().modelId()))
                throw new IllegalArgumentException("native_protocol_model_mismatch");
            // NONE 禁用本轮调用，但可以保留已发送声明以保持供应商前缀；空目录不能请求调用。
            if (tools.isEmpty() && toolChoice != ToolChoice.NONE) {
                throw new IllegalArgumentException("toolChoice must be NONE when tools are empty");
            }
            // 冻结绑定只能属于当前运行归属的租户。
            PortContracts.requireSameTenant(
                    context.callContext().runIdentity().owner().sessionRef().tenantId(),
                    target.binding().tenantId(),
                    "target.binding.tenantId");
            // Core 只能把解析能力快照已经声明支持的响应格式交给 adapter。
            if (!target.capabilities().responseFormats().contains(formatKind(response.format()))) {
                throw new IllegalArgumentException(
                        "response format must be supported by target capabilities");
            }
            // 不支持 reasoning 的目标只能收到显式 DISABLED，不能靠 adapter 猜测降级。
            if (!target.capabilities().reasoning() && reasoning != ReasoningPolicy.DISABLED) {
                throw new IllegalArgumentException(
                        "reasoning must be DISABLED when target does not support reasoning");
            }
        }
    }

    /**
     * 冻结模型可见工具；wireName 仅用于供应商协议，执行仍只接受同条目的 opaque handle。
     * strict 是供应商支持时的严格参数生成偏好；完整参数校验始终由宿主执行，
     * 不等同于 ResponseContract 中必须由供应商支持的强制响应格式。
     * batchEligible 表示模型同轮多调用的生成资格，不授予执行或并行权限；实际并行仍由宿主冻结工具策略决定。
     * 普通工具由宿主只读与确认策略声明该资格；原生控制仅固定包资源读取 read_skill_resource 可声明。
     */
    record ModelTool(String wireName, String toolHandle, String description,
            TypedPayload inputSchema, boolean strict, ModelToolKind kind, boolean batchEligible) {
        public ModelTool {
            wireName = PortContracts.requireText(wireName, "wireName");
            toolHandle = PortContracts.requireText(toolHandle, "toolHandle");
            description = PortContracts.requireNonNull(description, "description");
            inputSchema = PortContracts.requireNonNull(inputSchema, "inputSchema");
            kind = PortContracts.requireNonNull(kind, "kind");
            if (batchEligible && !(kind == ModelToolKind.TOOL_CALL
                    || kind == ModelToolKind.AGENT_ACTION && wireName.equals("read_skill_resource")))
                throw new IllegalArgumentException("control action cannot enter tool batch");
        }
        /** 原生工具定义中的模型可见文本，不含宿主执行句柄或治理类型。 */
        public String accountingText() {
            return wireName + "\n" + description + "\n" + inputSchema.canonicalJson();
        }
    }

    /** 普通工具绑定与 Runtime 控制动作函数明确区分，不能从供应商函数名猜测权限。 */
    enum ModelToolKind { TOOL_CALL, AGENT_ACTION }

    /** 模型工具选择策略；Runtime 当前不要求模型必须调用工具。 */
    enum ToolChoice { NONE, AUTO, REQUIRED }

    /** 表示发送给规范模型的有序角色消息。 */
    record ModelMessage(String messageId, ModelRole role, List<ModelContent> contents,
                        List<ModelToolCall> toolCalls, Optional<String> toolCallId,
                        Optional<String> reasoningContent, Optional<NativeProtocolState> protocolState) {

        public ModelMessage(String messageId, ModelRole role, List<ModelContent> contents,
                List<ModelToolCall> toolCalls, Optional<String> toolCallId, Optional<String> reasoningContent) {
            this(messageId, role, contents, toolCalls, toolCallId, reasoningContent, Optional.empty());
        }

        public ModelMessage(String messageId, ModelRole role, String content) {
            this(messageId, role, List.of(new ModelContent.Text(content)), List.of(), Optional.empty(), Optional.empty());
        }

        public ModelMessage(String messageId, ModelRole role, List<ModelContent> contents) {
            this(messageId, role, contents, List.of(), Optional.empty(), Optional.empty());
        }

        public ModelMessage(String messageId, ModelRole role, String content,
                List<ModelToolCall> toolCalls, Optional<String> toolCallId,
                Optional<String> reasoningContent) {
            this(messageId, role, List.of(new ModelContent.Text(content)), toolCalls, toolCallId, reasoningContent);
        }

        public ModelMessage(String messageId, ModelRole role, String content,
                List<ModelToolCall> toolCalls, Optional<String> toolCallId, Optional<String> reasoningContent,
                Optional<NativeProtocolState> protocolState) {
            this(messageId, role, List.of(new ModelContent.Text(content)), toolCalls, toolCallId, reasoningContent, protocolState);
        }

        /** 只连接原文字项，视觉内容由独立计量口径处理。 */
        public String textContent() {
            return contents.stream().filter(ModelContent.Text.class::isInstance)
                    .map(ModelContent.Text.class::cast).map(ModelContent.Text::text)
                    .collect(java.util.stream.Collectors.joining());
        }

        /** 拒绝空白消息身份、缺失角色或 null 内容；内容是否为空由注册上层 schema 决定。 */
        public ModelMessage {
            messageId = PortContracts.requireText(messageId, "messageId");
            role = PortContracts.requireNonNull(role, "role");
            contents = List.copyOf(contents);
            toolCalls = List.copyOf(toolCalls);
            if (toolCalls.size() > com.skillforge.runtime.api.ToolCallLimits.READ_ONLY_BATCH || toolCalls.stream().map(ModelToolCall::callId).distinct().count() != toolCalls.size())
                throw new IllegalArgumentException("invalid model tool call batch");
            toolCallId = PortContracts.requireNonNull(toolCallId, "toolCallId");
            reasoningContent = PortContracts.requireNonNull(reasoningContent, "reasoningContent");
            protocolState = PortContracts.requireNonNull(protocolState, "protocolState");
            if (!toolCalls.isEmpty() && role != ModelRole.ASSISTANT
                    || reasoningContent.isPresent() && role != ModelRole.ASSISTANT
                    || protocolState.isPresent() && role != ModelRole.ASSISTANT
                    || (role == ModelRole.TOOL) != toolCallId.isPresent()) {
                throw new IllegalArgumentException("model message role and tool content mismatch");
            }
            toolCallId.ifPresent(value -> PortContracts.requireText(value, "toolCallId"));
        }

        /** 将所有模型可见内容纳入 token 与字符预算，消息身份本身不计入模型正文。 */
        public String accountingText() {
            return textContent() + toolCalls.stream().map(call -> "\n" + call.callId() + "\n" + call.wireName()
                    + "\n" + call.arguments().canonicalJson()).collect(java.util.stream.Collectors.joining())
                    + toolCallId.map(value -> "\n" + value).orElse("")
                    + reasoningContent.map(value -> "\n" + value).orElse("")
                    + protocolState.map(value -> value.items().stream().map(TypedPayload::canonicalJson)
                            .collect(java.util.stream.Collectors.joining("\n", "\n", ""))).orElse("");
        }

        @Override public String toString() { return "ModelMessage[role=" + role + ",REDACTED]"; }
    }

    /** 原生私有续接协议，不能作为公开正文或用户消息重解释。 */
    enum NativeProtocol { CHAT_COMPLETIONS, RESPONSES, MESSAGES }

    /** 完整原生assistant项保序冻结；只允许同协议、方言和模型继续使用。 */
    record NativeProtocolState(NativeProtocol protocol, String dialectVersion, String modelId, List<TypedPayload> items) {
        public NativeProtocolState {
            protocol = PortContracts.requireNonNull(protocol, "protocol");
            dialectVersion = PortContracts.requireText(dialectVersion, "dialectVersion");
            modelId = PortContracts.requireText(modelId, "modelId");
            items = List.copyOf(items);
            if (items.isEmpty()) throw new IllegalArgumentException("native_protocol_items_required");
        }
        @Override public String toString() { return "NativeProtocolState[REDACTED]"; }
    }

    /** 由已提交动作重建的原生调用历史，不产生新的执行请求。 */
    record ModelToolCall(String callId, String wireName, TypedPayload arguments) {
        public ModelToolCall {
            callId = PortContracts.requireText(callId, "callId");
            wireName = PortContracts.requireText(wireName, "wireName");
            arguments = PortContracts.requireNonNull(arguments, "arguments");
        }
    }

    /** 枚举规范消息角色，不暴露供应商专属角色。 */
    enum ModelRole {
        SYSTEM,
        USER,
        ASSISTANT,
        TOOL
    }

    /** 封闭正文格式与原生会话；原生消息由 Session 解释，适配器不生成 Agent 动作。 */
    sealed interface ResponseFormat
            permits ResponseFormat.Text, ResponseFormat.JsonObject, ResponseFormat.JsonSchema,
                    ResponseFormat.NativeConversation {

        /** 原生正文和工具调用均是消息事实；正文即使是 JSON 也不作为动作解码。 */
        record NativeConversation() implements ResponseFormat { }

        /** 表示自由文本响应。 */
        record Text() implements ResponseFormat {
        }

        /** 表示规范 JSON 对象响应。 */
        record JsonObject() implements ResponseFormat {
        }

        /** 表示必须依据注册 schema 约束的 JSON 响应。 */
        record JsonSchema(TypedPayload schema) implements ResponseFormat {

            /** 拒绝缺失的 schema 化响应约束。 */
            public JsonSchema {
                schema = PortContracts.requireNonNull(schema, "schema");
            }
        }
    }

    /** 表示 Core 已协商的响应格式、执行强度和稳定协商原因。 */
    record ResponseContract(
            ResponseFormat format,
            EnforcementLevel enforcement,
            String negotiationReasonCode) {

        /** 拒绝缺失格式、执行强度或空白协商原因。 */
        public ResponseContract {
            format = PortContracts.requireNonNull(format, "format");
            enforcement = PortContracts.requireNonNull(enforcement, "enforcement");
            negotiationReasonCode = PortContracts.requireText(
                    negotiationReasonCode, "negotiationReasonCode");
        }
    }

    /** 枚举响应契约的严格执行与尽力执行级别。 */
    enum EnforcementLevel {
        STRICT,
        BEST_EFFORT
    }

    /** 表示可选的正输出 token 上限与有限 0..2 temperature。 */
    record GenerationOptions(OptionalInt maxOutputTokens, OptionalDouble temperature) {

        /** 拒绝 null Optional、非正 token 上限及非有限或越界 temperature。 */
        public GenerationOptions {
            maxOutputTokens = PortContracts.requireNonNull(maxOutputTokens, "maxOutputTokens");
            temperature = PortContracts.requireNonNull(temperature, "temperature");
            // OptionalInt 存在时表达显式预算，因此必须是正数。
            if (maxOutputTokens.isPresent() && maxOutputTokens.getAsInt() <= 0) {
                throw new IllegalArgumentException("maxOutputTokens must be positive");
            }
            // temperature 必须拒绝 NaN、无穷和 0..2 之外的供应商参数。
            if (temperature.isPresent()
                    && (!Double.isFinite(temperature.getAsDouble())
                            || temperature.getAsDouble() < 0
                            || temperature.getAsDouble() > 2)) {
                throw new IllegalArgumentException(
                        "temperature must be finite and between 0 and 2");
            }
        }
    }

    /** 能力层核验协议和模型支持的启用状态或明确推理程度，不能静默降级。 */
    enum ReasoningPolicy {
        DEFAULT,
        DISABLED, ENABLED, MINIMAL, LOW, MEDIUM, HIGH, XHIGH, MAX
    }

    /** 供应商报告的计量；缓存和推理分别是输入、输出的子集，不参与重复求和。 */
    record ModelUsage(OptionalLong inputTokens, OptionalLong outputTokens,
                      OptionalLong cachedInputTokens, OptionalLong cacheWriteInputTokens,
                      OptionalLong reasoningOutputTokens) {

        /** 仅报告输入输出的协议显式保留其他字段未知。 */
        public ModelUsage(OptionalLong inputTokens, OptionalLong outputTokens) {
            this(inputTokens, outputTokens, OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty());
        }

        /** 拒绝 null Optional 或存在时为负的 token 数。 */
        public ModelUsage {
            inputTokens = PortContracts.requireNonNull(inputTokens, "inputTokens");
            outputTokens = PortContracts.requireNonNull(outputTokens, "outputTokens");
            cachedInputTokens = requireTokens(cachedInputTokens, "cachedInputTokens");
            cacheWriteInputTokens = requireTokens(cacheWriteInputTokens, "cacheWriteInputTokens");
            reasoningOutputTokens = requireTokens(reasoningOutputTokens, "reasoningOutputTokens");
            // 已提供的输入 token 是已确认计量值，允许供应商明确返回零。
            if (inputTokens.isPresent() && inputTokens.getAsLong() < 0) {
                throw new IllegalArgumentException("inputTokens must be non-negative");
            }
            // 已提供的输出 token 是已确认计量值，允许供应商明确返回零。
            if (outputTokens.isPresent() && outputTokens.getAsLong() < 0) {
                throw new IllegalArgumentException("outputTokens must be non-negative");
            }
        }

        private static OptionalLong requireTokens(OptionalLong value, String name) {
            PortContracts.requireNonNull(value, name);
            if (value.isPresent() && value.getAsLong() < 0) throw new IllegalArgumentException(name + " must be non-negative");
            return value;
        }
    }

    /** 完整原生响应的有序公开内容块；私有推理单独保存在 Completed 中。 */
    record AssistantTurn(List<AssistantContent> contents) {
        /** 空消息也是已知响应事实，合法性与完成资格由 Session 判定。 */
        public AssistantTurn {
            contents = List.copyOf(PortContracts.requireNonNull(contents, "contents"));
        }

        /** 正文只从文本块派生，不把参数或私有推理混入回答。 */
        public String content() {
            return contents.stream().filter(AssistantContent.Text.class::isInstance)
                    .map(AssistantContent.Text.class::cast).map(AssistantContent.Text::text)
                    .collect(java.util.stream.Collectors.joining());
        }

        /** 工具顺序与供应商完整响应一致，不代表已校验、获准或执行。 */
        public List<AssistantContent.ToolCall> toolCalls() {
            return contents.stream().filter(AssistantContent.ToolCall.class::isInstance)
                    .map(AssistantContent.ToolCall.class::cast).toList();
        }

        public AssistantTurnKind kind() {
            return toolCalls().isEmpty() ? AssistantTurnKind.TEXT : AssistantTurnKind.TOOL_CALLS;
        }

        /** 原生正文可能含用户私有数据，不进入默认对象日志。 */
        @Override public String toString() { return "AssistantTurn[REDACTED]"; }
    }

    /** 原始工具参数保持原字符串；参数合法性由 Session 的冻结工具契约判断。 */
    sealed interface AssistantContent permits AssistantContent.Text, AssistantContent.ToolCall {
        record Text(String text) implements AssistantContent {
            public Text {
                text = PortContracts.requireUnicode(PortContracts.requireNonNull(text, "text"), "text");
            }
            @Override public String toString() { return "AssistantContent.Text[REDACTED]"; }
        }

        record ToolCall(String callId, String wireName, String argumentsJson) implements AssistantContent {
            public ToolCall {
                callId = PortContracts.requireUnicode(PortContracts.requireText(callId, "callId"), "callId");
                wireName = PortContracts.requireUnicode(PortContracts.requireText(wireName, "wireName"), "wireName");
                argumentsJson = PortContracts.requireUnicode(
                        PortContracts.requireNonNull(argumentsJson, "argumentsJson"), "argumentsJson");
            }
            @Override public String toString() { return "AssistantContent.ToolCall[REDACTED]"; }
        }
    }

    /** TOOL_CALLS 只表示原供应商完成响应含工具调用，不表示调用已获授权或执行。 */
    enum AssistantTurnKind { TEXT, TOOL_CALLS }

    /** 封闭非流式模型成功、确定失败与结果未知三种结果。 */
    sealed interface ModelResult
            permits ModelResult.Completed, ModelResult.Failed, ModelResult.OutcomeUnknown {

        /**
         * 表示模型调用已得到确定终态；实现必须保证有效模型身份与调用冻结 target 完全相等。
         * 消费者须先检查完成原因：length 和 content_filter 的输出可能不完整或为占位载荷，
         * 不得作为完整业务输出采纳，也不表示通过业务输出 schema 校验。
         * usage 保留供应商已报告的用量，未报告的字段保持缺失而非推断为零。
         */
        record Completed(
                TypedPayload output,
                String effectiveModelId,
                ModelUsage usage,
                String finishReasonCode,
                ResultRef resultRef,
                Optional<String> reasoningContent,
                Optional<AssistantTurn> assistantTurn, Optional<NativeProtocolState> protocolState) implements ModelResult {

            public Completed(TypedPayload output, String effectiveModelId, ModelUsage usage, String finishReasonCode,
                    ResultRef resultRef, Optional<String> reasoningContent, Optional<AssistantTurn> assistantTurn) {
                this(output, effectiveModelId, usage, finishReasonCode, resultRef, reasoningContent, assistantTurn, Optional.empty());
            }

            /** 非原生或未提供原生回合事实的结果显式保留缺失，不推断工具分支。 */
            public Completed(TypedPayload output, String effectiveModelId,
                    ModelUsage usage, String finishReasonCode, ResultRef resultRef,
                    Optional<String> reasoningContent) {
                this(output, effectiveModelId, usage, finishReasonCode, resultRef,
                        reasoningContent, Optional.empty());
            }

            /** 无显式推理输出时的便利构造器；缺失与空推理具有相同公开语义。 */
            public Completed(TypedPayload output, String effectiveModelId,
                    ModelUsage usage, String finishReasonCode, ResultRef resultRef) {
                this(output, effectiveModelId, usage, finishReasonCode, resultRef,
                        Optional.empty());
            }

            /** 拒绝缺失输出、空白模型或完成原因、缺失 usage 与结果事实。 */
            public Completed {
                output = PortContracts.requireNonNull(output, "output");
                effectiveModelId = PortContracts.requireText(effectiveModelId, "effectiveModelId");
                usage = PortContracts.requireNonNull(usage, "usage");
                finishReasonCode = PortContracts.requireText(
                        finishReasonCode, "finishReasonCode");
                resultRef = PortContracts.requireNonNull(resultRef, "resultRef");
                reasoningContent = PortContracts.requireNonNull(
                        reasoningContent, "reasoningContent");
                reasoningContent = reasoningContent.filter(value -> !value.isEmpty())
                        .map(value -> PortContracts.requireUnicode(value, "reasoningContent"));
                assistantTurn = PortContracts.requireNonNull(assistantTurn, "assistantTurn");
                protocolState = PortContracts.requireNonNull(protocolState, "protocolState");
                if (protocolState.isPresent() && !effectiveModelId.equals(protocolState.orElseThrow().modelId()))
                    throw new IllegalArgumentException("native_protocol_model_mismatch");
            }

            @Override public String toString() { return "CompletedModelResult[REDACTED]"; }
        }

        /** 表示模型调用确定失败，失败事实可声明已经证明安全的后续动作。 */
        record Failed(PortFailure failure, Optional<java.time.Instant> retryAt) implements ModelResult {
            public Failed(PortFailure failure) { this(failure, Optional.empty()); }

            /** 拒绝缺失的稳定失败事实。 */
            public Failed {
                failure = PortContracts.requireNonNull(failure, "failure");
                retryAt = requireRetryAt(failure, retryAt);
            }
        }

        /** 表示模型调用结果未知；Core 不自动重放，因此重试模式必须为 NONE。 */
        record OutcomeUnknown(PortFailure failure) implements ModelResult {

            /** 拒绝缺失失败事实或任何非 NONE 的未知结果重试模式。 */
            public OutcomeUnknown {
                failure = requireOutcomeUnknownFailure(failure);
            }
        }
    }

    /**
     * 封闭流式推理、内容、原生工具参数、用量和三个终态事件。
     * 发布者负责跨事件严格递增 sequence、单终态收敛和终态后不再发布元素。
     */
    sealed interface ModelStreamEvent
            permits ModelStreamEvent.ContentDelta,
                    ModelStreamEvent.ReasoningDelta,
                    ModelStreamEvent.ToolCallDelta,
                    ModelStreamEvent.UsageDelta,
                    ModelStreamEvent.Completed,
                    ModelStreamEvent.Failed,
                    ModelStreamEvent.OutcomeUnknown {

        /** 表示带正 sequence 的规范内容增量。 */
        record ContentDelta(long sequence, String content) implements ModelStreamEvent {

            /** 拒绝非正 sequence 或 null 内容；跨事件递增由发布者验证。 */
            public ContentDelta {
                sequence = PortContracts.requirePositive(sequence, "sequence");
                content = PortContracts.requireNonNull(content, "content");
                PortContracts.requireUnicode(content, "content");
            }
        }

        /** 供应商明确返回的推理文本增量；与答案正文和工具决策参数保持独立。 */
        record ReasoningDelta(long sequence, String content) implements ModelStreamEvent {

            /** 拒绝非正 sequence 或 null 内容；跨事件递增由发布者验证。 */
            public ReasoningDelta {
                sequence = PortContracts.requirePositive(sequence, "sequence");
                content = PortContracts.requireNonNull(content, "content");
                PortContracts.requireUnicode(content, "content");
            }
        }

        /** 原生工具调用参数增量；toolHandle 已由适配器从冻结 wireName 精确还原。 */
        record ToolCallDelta(long sequence, int index, String callId,
                String toolHandle, String argumentsDelta) implements ModelStreamEvent {
            public ToolCallDelta {
                sequence = PortContracts.requirePositive(sequence, "sequence");
                if (index < 0) throw new IllegalArgumentException("index must not be negative");
                callId = PortContracts.requireText(callId, "callId");
                toolHandle = PortContracts.requireText(toolHandle, "toolHandle");
                argumentsDelta = PortContracts.requireNonNull(argumentsDelta, "argumentsDelta");
            }
        }

        /** 表示带正 sequence 的已确认 token 使用量增量。 */
        record UsageDelta(long sequence, ModelUsage usage) implements ModelStreamEvent {

            /** 拒绝非正 sequence 或缺失 usage；跨事件递增由发布者验证。 */
            public UsageDelta {
                sequence = PortContracts.requirePositive(sequence, "sequence");
                usage = PortContracts.requireNonNull(usage, "usage");
            }
        }

        /** 表示流式调用唯一的成功终态。 */
        record Completed(ModelResult.Completed result) implements ModelStreamEvent {

            /** 拒绝缺失的成功结果。 */
            public Completed {
                result = PortContracts.requireNonNull(result, "result");
            }
        }

        /** 表示流式调用唯一的确定失败终态。 */
        record Failed(PortFailure failure, Optional<java.time.Instant> retryAt) implements ModelStreamEvent {
            public Failed(PortFailure failure) { this(failure, Optional.empty()); }

            /** 拒绝缺失的稳定失败事实。 */
            public Failed {
                failure = PortContracts.requireNonNull(failure, "failure");
                retryAt = requireRetryAt(failure, retryAt);
            }
        }

        /** 表示流式调用唯一的结果未知终态，禁止 Core 自动重放。 */
        record OutcomeUnknown(PortFailure failure) implements ModelStreamEvent {

            /** 拒绝缺失失败事实或任何非 NONE 的未知结果重试模式。 */
            public OutcomeUnknown {
                failure = requireOutcomeUnknownFailure(failure);
            }
        }
    }

    /** 把封闭响应格式映射为能力快照中的规范枚举。 */
    private static ResponseFormatKind formatKind(ResponseFormat format) {
        // 无负载格式按具体封闭类型映射，避免以类名或字符串猜测协议。
        if (format instanceof ResponseFormat.Text || format instanceof ResponseFormat.NativeConversation) {
            return ResponseFormatKind.TEXT;
        }
        if (format instanceof ResponseFormat.JsonObject) {
            return ResponseFormatKind.JSON_OBJECT;
        }
        // permits 闭包中剩余分支只能是带注册 schema 的 JSON_SCHEMA。
        return ResponseFormatKind.JSON_SCHEMA;
    }

    /** 原生历史必须由唯一调用和紧随的对应结果组成，禁止孤立、错配或重复的调用身份。 */
    static void validateConversation(List<ModelMessage> messages) {
        var pending = new java.util.ArrayDeque<String>();
        var calls = new HashSet<String>();
        for (var message : messages) {
            if (!pending.isEmpty()) {
                if (message.role() != ModelRole.TOOL || !message.toolCallId().orElseThrow().equals(pending.removeFirst()))
                    throw new IllegalArgumentException("model tool history result mismatch");
            } else if (message.role() == ModelRole.TOOL) {
                throw new IllegalArgumentException("model tool history missing call");
            }
            for (var call : message.toolCalls()) {
                if (!calls.add(call.callId())) throw new IllegalArgumentException("duplicate model tool call id");
                pending.addLast(call.callId());
            }
        }
        if (!pending.isEmpty()) throw new IllegalArgumentException("model tool history missing result");
    }

    /** 校验模型结果未知只能使用 NONE，避免未确认调用被自动重放。 */
    private static Optional<java.time.Instant> requireRetryAt(PortFailure failure, Optional<java.time.Instant> retryAt) {
        PortContracts.requireNonNull(retryAt, "retryAt");
        if (retryAt.isPresent() && (!"model_rate_limited".equals(failure.reasonCode()) || failure.retryMode() != RetryMode.NONE))
            throw new IllegalArgumentException("model retry requires confirmed rate limit rejection");
        return retryAt;
    }

    private static PortFailure requireOutcomeUnknownFailure(PortFailure failure) {
        failure = PortContracts.requireNonNull(failure, "failure");
        // 模型没有稳定幂等键、结果查询或跨账本协调入口来安全承接未知结果。
        if (failure.retryMode() != RetryMode.NONE) {
            throw new IllegalArgumentException("model outcome unknown retryMode must be NONE");
        }
        return failure;
    }
}
