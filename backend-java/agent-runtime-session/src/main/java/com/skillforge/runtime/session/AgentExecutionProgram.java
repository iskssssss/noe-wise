package com.skillforge.runtime.session;

import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.EvidenceBundle;
import com.skillforge.runtime.api.EvidenceItem;
import com.skillforge.runtime.api.EvidenceRef;
import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.KnowledgeBudget;
import com.skillforge.runtime.api.KnowledgeMode;
import com.skillforge.runtime.api.KnowledgeSourceVersion;
import com.skillforge.runtime.api.CapabilityRef;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunStatus;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.media.ModelContent;
import com.skillforge.runtime.api.port.ActionRef;
import com.skillforge.runtime.api.port.CapabilitySnapshotRef;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.port.ModelPort;
import com.skillforge.runtime.api.port.ModelPort.FrozenModelTarget;
import com.skillforge.runtime.api.port.ModelPort.ModelMessage;
import com.skillforge.runtime.api.port.ModelPort.ModelResult;
import com.skillforge.runtime.api.port.ModelPort.ModelRole;
import com.skillforge.runtime.api.port.ModelPort.ModelTool;
import com.skillforge.runtime.api.port.ModelPort.ModelToolCall;
import com.skillforge.runtime.api.port.ModelPort.ToolChoice;
import com.skillforge.runtime.api.port.PortalCallKind;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.NativeAgentTools;
import com.skillforge.runtime.api.port.KnowledgePortal.FrozenKnowledgeTarget;
import com.skillforge.runtime.api.port.KnowledgePortal.KnowledgeOutcome;
import com.skillforge.runtime.api.port.ToolPortal.ToolOutcome;
import com.skillforge.runtime.api.port.ToolPortal.ToolInputMethod;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.skillforge.runtime.api.port.PlanPort;
import com.skillforge.runtime.api.port.WorkflowPortal.FrozenWorkflowTarget;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowResult;
import com.skillforge.runtime.core.AgentAction;
import com.skillforge.runtime.core.AgentDecisionDecoder;
import com.skillforge.runtime.core.AgentExecutionLoop;
import com.skillforge.runtime.core.BudgetGuard;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.core.CoreRuntime;
import com.skillforge.runtime.core.FrozenModelRequest;
import com.skillforge.runtime.core.FrozenKnowledgeRequest;
import com.skillforge.runtime.core.SkillActivation;
import com.skillforge.runtime.core.LoopBudget;
import com.skillforge.runtime.core.LoopUsage;
import com.skillforge.runtime.core.ModelConversation;
import com.skillforge.runtime.core.ToolCallRouting;
import com.skillforge.runtime.core.PlanActionAdmission;
import com.skillforge.runtime.session.AgentExecutionCodec.ToolRequest;
import com.skillforge.runtime.session.AgentExecutionCodec.ToolContinuation;
import com.skillforge.runtime.session.AgentExecutionCodec.KnowledgeCallRequest;
import com.skillforge.runtime.session.ExecutionCoordinator.Adoption;
import com.skillforge.runtime.session.ExecutionCoordinator.CapabilityResult;
import com.skillforge.runtime.session.ExecutionCoordinator.BatchCall;
import com.skillforge.runtime.session.ExecutionCoordinator.BatchOutcome;
import com.skillforge.runtime.session.ExecutionCoordinator.BatchPlan;
import com.skillforge.runtime.session.ExecutionCoordinator.BatchResponse;
import com.skillforge.runtime.session.ExecutionCoordinator.BatchResult;
import com.skillforge.runtime.session.ExecutionCoordinator.Step;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.spi.ExecutionStore.Material;
import com.skillforge.runtime.session.spi.ExecutionStore.Prepared;
import com.skillforge.runtime.session.spi.ExecutionStore.Run;
import com.skillforge.runtime.session.spi.SessionPreparations;
import com.skillforge.runtime.session.spi.SessionTokenCounter;
import com.skillforge.runtime.session.spi.SessionToolDirectory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * 连续模型—工具—模型执行程序，只准备请求和采用真实回执，不调用 Provider、不写数据库。
 * 活跃消息仅保存在明确材料类型中；恢复读取精确增量引用，不恢复旧 checkpoint 或来源图。
 */
public final class AgentExecutionProgram implements ExecutionCoordinator.Program<AgentExecutionProgram.Materials> {
    private static final String PLAN_POLICY_MESSAGE = "execution-plan-policy";
    public static final String CONTEXT_SCHEMA = "skillforge.runtime.execution.context.v3";
    public static final String COMPLETED_EVENT = "execution.completed";
    public static final String COMPLETED_SCHEMA = "skillforge.runtime.execution.completed.v2";
    public static final String WAITING_EVENT = "execution.waiting";
    public static final String WAITING_SCHEMA = "skillforge.runtime.execution.waiting.v1";
    public static final String ACCEPTED_EVENT = "execution.accepted";
    public static final String ACCEPTED_SCHEMA = "skillforge.runtime.execution.accepted.v1";
    public static final String RESOLVED_EVENT = "interaction.resolved";
    public static final String RESOLVED_SCHEMA = "skillforge.runtime.execution.interaction-resolved.v1";
    public static final String TOOL_RESULT_EVENT = "execution.tool-result";
    public static final String TOOL_RESULT_SCHEMA = "skillforge.runtime.execution.tool-result.v1";
    /** MODEL派发登记的公开来源，不代表首token或生成成功。 */
    public static final String MODEL_STARTED_EVENT = "execution.model-started";
    /** 模型开始事件只包含实际Action及可信因果与固定状态说明。 */
    public static final String MODEL_STARTED_SCHEMA = "skillforge.runtime.execution.model-started.v1";
    public static final String MODEL_RESULT_EVENT = "execution.model-result";
    public static final String MODEL_RESULT_SCHEMA = "skillforge.runtime.execution.model-result.v1";
    public static final String WORKFLOW_STARTED_EVENT = "execution.workflow-started";
    public static final String WORKFLOW_STARTED_SCHEMA = "skillforge.runtime.execution.workflow-started.v1";
    public static final String WORKFLOW_RESULT_EVENT = "execution.workflow-result";
    /** 公开导出结果协议与内部 Workflow 恢复信封严格分离。 */
    public static final String WORKFLOW_RESULT_SCHEMA = "skillforge.runtime.execution.workflow-public-result.v1";
    private final Sources sources;
    private final CoreRuntime core;
    private final CodecLimits limits;
    private final AgentExecutionCodec codec;
    private final AgentExecutionLoop loop;
    private final SessionNativeResponses nativeResponses;
    private final AgentExecutionToolBatches batches;
    private final SessionExecutionDelegations delegations;
    private final SessionExecutionWorkflows workflows;
    private final AgentExecutionSteering steeringInputs;
    private final SessionExecutionPlans plans;
    private final AgentExecutionObservations observations;
    private final SessionRuntimePayloads payloads = new SessionRuntimePayloads();

    /** 连接已有冻结配置、实际结果 codec 和纯决策解析器；不装配旧 Core Model/Tool Cycle。 */
    public AgentExecutionProgram(Sources sources, CoreRuntime core, CodecLimits limits) {
        this.sources = Objects.requireNonNull(sources); this.core = Objects.requireNonNull(core);
        this.limits = Objects.requireNonNull(limits); codec = new AgentExecutionCodec(limits); loop = new AgentExecutionLoop(limits);
        nativeResponses = new SessionNativeResponses(limits);
        batches = new AgentExecutionToolBatches(loop, core, codec, limits, payloads);
        delegations = new SessionExecutionDelegations(limits);
        workflows = new SessionExecutionWorkflows(limits);
        steeringInputs = new AgentExecutionSteering(limits);
        plans = new SessionExecutionPlans(limits);
        observations = new AgentExecutionObservations(limits);
    }

    /** 受理公开事实与Run/input同事务保存，不展开业务正文或整个运行状态。 */
    public static ExecutionStore.Event admissionEvent(RunIdentity identity) {
        Objects.requireNonNull(identity);
        var value = trace(identity, identity.causationInputId());
        return new ExecutionStore.Event(new EventId("execution-accepted:" + AgentExecutionCodec.digest(identity.runRef().toString())),
                ACCEPTED_EVENT, new TypedPayload(ACCEPTED_SCHEMA, SessionRuntimePayloadJson.write(value)));
    }

    /** 原子受理精确响应时关闭对应交互，inputId明确属于本次回复。 */
    public static ExecutionStore.Event resolved(Run run, InputId input, ExecutionStore.WaitRef waiting) {
        Objects.requireNonNull(run); Objects.requireNonNull(input); Objects.requireNonNull(waiting);
        if (!run.identity().runRef().tenantId().equals(waiting.action().tenantId())) throw invalid();
        var value = trace(run.identity(), input); value.put("actionId", waiting.action().actionId());
        value.put("generation", Long.toString(waiting.generation())); value.put("reason", "response_accepted");
        return new ExecutionStore.Event(new EventId("execution-resolved:" + AgentExecutionCodec.digest(run.identity().runRef() + ":" + input.value())),
                RESOLVED_EVENT, new TypedPayload(RESOLVED_SCHEMA, SessionRuntimePayloadJson.write(value)));
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode trace(RunIdentity identity, InputId input) {
        var value = AgentExecutionCodec.object(); value.put("inputId", input.value());
        value.put("rootInputId", identity.trace().rootInputId().value()); value.put("traceId", identity.trace().traceId());
        value.put("spanId", identity.trace().spanId()); value.put("agentId", identity.agentId().value());
        return value;
    }

    /** 复用当前稳定协议名及 native 控制声明的纯投影；工具身份与委派完整句柄均由原冻结目录拥有者提供。 */
    public static List<ModelTool> modelTools(SessionPreparations.Profile profile, List<SessionToolDirectory.Entry> tools,
            Map<String, String> identities, List<KnowledgeBinding> knowledge, List<SkillActivation.Mount> skills, List<WorkflowBinding> workflows,
            List<String> delegationHandles) {
        var result = new ArrayList<>(SessionNativeConversation.modelTools(profile, tools, identities, delegationHandles));
        if (!knowledge.isEmpty() && SessionNativeConversation.enabled(profile)) {
          for (var action : java.util.List.of("knowledge_query", "knowledge_list_documents", "knowledge_read_document")) {
            var eligible = knowledge.stream().filter(binding -> switch (action) {
                case "knowledge_list_documents" -> binding.target().allowedOperators().contains(com.skillforge.runtime.api.KnowledgeOperatorType.DOCUMENT_LIST);
                case "knowledge_read_document" -> binding.target().allowedOperators().contains(com.skillforge.runtime.api.KnowledgeOperatorType.DOCUMENT_READ);
                default -> binding.target().allowedOperators().stream().anyMatch(operator -> !java.util.Set.of(com.skillforge.runtime.api.KnowledgeOperatorType.DOCUMENT_LIST, com.skillforge.runtime.api.KnowledgeOperatorType.DOCUMENT_READ).contains(operator));
            }).toList();
            if (eligible.isEmpty()) continue;
            var declaration = SessionNativeConversation.actionTools(false, java.util.Set.of(action)).stream().filter(tool -> tool.wireName().equals(action)).findFirst().orElseThrow();
            int schemaSize = Math.max(1, declaration.inputSchema().canonicalJson().length());
            var schema = (com.fasterxml.jackson.databind.node.ObjectNode) SessionRuntimePayloadJson.parse(declaration.inputSchema().canonicalJson(), new CodecLimits(schemaSize, schemaSize, 128));
            var handles = ((com.fasterxml.jackson.databind.node.ObjectNode) schema.get("properties").get("knowledge_handle")).putArray("enum");
            eligible.stream().map(binding -> binding.target().handle().value()).sorted().forEach(handles::add);
            result.add(new ModelTool(declaration.wireName(), declaration.toolHandle(), declaration.description(),
                    new TypedPayload(declaration.inputSchema().schemaId(), SessionRuntimePayloadJson.write(schema)), declaration.strict(), declaration.kind(), declaration.batchEligible()));
        }
          }
        if (!skills.isEmpty() && SessionNativeConversation.enabled(profile)) {
            var available = new HashSet<>(Set.of("read_skill_resource"));
            if (skills.stream().anyMatch(mount -> mount.mode() == SkillActivation.Mode.ON_DEMAND && mount.descriptor().modelInvocable())) available.add("activate_skill");
            for (var declaration : SessionNativeConversation.actionTools(false, available)) {
                if (!Set.of("use_skill", "read_skill_resource").contains(declaration.wireName())) continue;
                int size = Math.max(1, declaration.inputSchema().canonicalJson().length());
                var schema = (com.fasterxml.jackson.databind.node.ObjectNode) SessionRuntimePayloadJson.parse(declaration.inputSchema().canonicalJson(), new CodecLimits(size, size, 128));
                var handles = ((com.fasterxml.jackson.databind.node.ObjectNode) schema.get("properties").get("skill_handle")).putArray("enum");
                var eligible = declaration.wireName().equals("use_skill")
                        ? skills.stream().filter(mount -> mount.mode() == SkillActivation.Mode.ON_DEMAND && mount.descriptor().modelInvocable()).toList()
                        : skills;
                eligible.stream().map(SkillActivation.Mount::handle).sorted().forEach(handles::add);
                String description = declaration.description() + "\n" + eligible.stream().map(mount -> mount.handle() + ": " + mount.descriptor().metadata().name() + " - " + mount.descriptor().metadata().description()).collect(java.util.stream.Collectors.joining("\n"));
                result.add(new ModelTool(declaration.wireName(), declaration.toolHandle(), description,
                        new TypedPayload(declaration.inputSchema().schemaId(), SessionRuntimePayloadJson.write(schema)), declaration.strict(), declaration.kind(), false));
            }
        }
        if (!workflows.isEmpty() && SessionNativeConversation.enabled(profile)) {
            var declaration = SessionNativeConversation.actionTools(false, Set.of("dispatch")).getFirst();
            int size = Math.max(1, declaration.inputSchema().canonicalJson().length());
            var schema = (com.fasterxml.jackson.databind.node.ObjectNode) SessionRuntimePayloadJson.parse(declaration.inputSchema().canonicalJson(), new CodecLimits(size, size, 128));
            var handles = ((com.fasterxml.jackson.databind.node.ObjectNode) schema.get("properties").get("handle")).putArray("enum");
            workflows.stream().map(binding -> binding.target().handle().value()).sorted().forEach(handles::add);
            result.add(new ModelTool(declaration.wireName(), declaration.toolHandle(), declaration.description(), new TypedPayload(declaration.inputSchema().schemaId(), SessionRuntimePayloadJson.write(schema)),
                    declaration.strict(), declaration.kind(), false));
        }
        return List.copyOf(result);
    }

    /**
     * 一次领取加载的真实冻结材料，由宿主从所属权威提供。
     * @param profile 已有模型渲染、生成及 tokenizer 策略
     * @param target 原冻结模型目标
     * @param modelTools 包含稳定 wireName 的原模型工具及控制动作目录
     * @param tools 同一冻结快照的真实工具 Entries
     * @param budget 原执行上限，余额仍归预算账本
     * @param capabilitySnapshot 当前输入的能力快照身份
     * @param capabilityRef 该能力快照所属权威中的精确引用
     * @param initialMessages 原输入及初始历史，不从 sourceRun 或 checkpoint 反推
     * @param actor 已由入口/领取边界或补充采纳事务确认的当前可信操作者
     */
    public record Configuration(SessionPreparations.Profile profile, FrozenModelTarget target,
            List<ModelTool> modelTools, List<SessionToolDirectory.Entry> tools, LoopBudget budget,
            CapabilitySnapshotRef capabilitySnapshot, ResultRef capabilityRef,
            List<ModelMessage> initialMessages, VerifiedIdentity actor, List<KnowledgeBinding> knowledge,
            List<SkillActivation.Mount> skills, Set<CapabilityRef> availableCapabilities, List<WorkflowBinding> workflows, PlanPort.State initialPlanState, boolean planRequired, Optional<AuthorizedWorkflowInvocation> initialIntent) {
        /** 冻结目录和消息，模型绑定或目录身份冲突时直接拒绝。 */
        public Configuration {
            Objects.requireNonNull(profile); Objects.requireNonNull(target); Objects.requireNonNull(budget);
            if (budget instanceof LoopBudget.ObserveOnly && profile.generation().maxOutputTokens().isEmpty())
                throw new IllegalArgumentException("observe_only_output_limit_required");
            Objects.requireNonNull(capabilitySnapshot); Objects.requireNonNull(capabilityRef); Objects.requireNonNull(actor);
            modelTools = List.copyOf(modelTools); tools = List.copyOf(tools); initialMessages = List.copyOf(initialMessages); knowledge = List.copyOf(knowledge);
            skills = List.copyOf(skills); availableCapabilities = Set.copyOf(availableCapabilities);
            workflows = List.copyOf(workflows);
            Objects.requireNonNull(initialPlanState); Objects.requireNonNull(initialIntent);
            if (initialIntent.isPresent() && (workflows.size()!=1 || !workflows.getFirst().target().equals(initialIntent.orElseThrow().target())
                    || !tools.isEmpty() || !skills.isEmpty() || !knowledge.isEmpty() || planRequired)) throw invalid();
            if (workflows.stream().map(binding -> binding.target().handle()).distinct().count() != workflows.size()) throw invalid();
            if (knowledge.stream().map(binding -> binding.target().handle()).distinct().count() != knowledge.size()) throw invalid();
            if (!profile.binding().equals(target.binding()) || initialMessages.isEmpty()
                    || tools.stream().map(entry -> entry.target().handle()).distinct().count() != tools.size()
                    || modelTools.stream().map(ModelTool::wireName).distinct().count() != modelTools.size()
                    || modelTools.stream().map(ModelTool::toolHandle).distinct().count() != modelTools.size()) throw invalid();
            for (var entry : tools) {
                var matches = modelTools.stream().filter(tool -> tool.toolHandle().equals(entry.target().handle().value())
                        && tool.kind() == ModelPort.ModelToolKind.TOOL_CALL).toList();
                if (!entry.target().modelInvocable() || matches.size() != 1
                        || !matches.getFirst().inputSchema().equals(entry.target().inputSchema())) throw invalid();
            }
        }
        public Configuration(SessionPreparations.Profile profile,FrozenModelTarget target,List<ModelTool> modelTools,
                List<SessionToolDirectory.Entry> tools,LoopBudget budget,CapabilitySnapshotRef capabilitySnapshot,ResultRef capabilityRef,
                List<ModelMessage> initialMessages,VerifiedIdentity actor,List<KnowledgeBinding> knowledge,List<SkillActivation.Mount> skills,
                Set<CapabilityRef> availableCapabilities,List<WorkflowBinding> workflows,PlanPort.State initialPlanState,boolean planRequired) {
            this(profile,target,modelTools,tools,budget,capabilitySnapshot,capabilityRef,initialMessages,actor,knowledge,skills,availableCapabilities,
                    workflows,initialPlanState,planRequired,Optional.empty());
        }
        /** 仅替换已由Store确认的当前操作者，原冻结配置和原始历史保持不变。 */
        public Configuration withActor(VerifiedIdentity current) {
            Objects.requireNonNull(current);
            if (!actor.owner().equals(current.owner())) throw invalid();
            return actor.equals(current) ? this : new Configuration(profile, target, modelTools, tools, budget,
                    capabilitySnapshot, capabilityRef, initialMessages, current, knowledge, skills, availableCapabilities,
                    workflows, initialPlanState, planRequired, initialIntent);
        }
        /** 配置、业务历史和执行身份不进入普通日志。 */
        @Override public String toString() { return "AgentExecutionConfiguration[REDACTED]"; }
    }

    /** 可信宿主从已授权且持久的输入归属生成，客户端上下文不能构造此类型。 */
    public record AuthorizedWorkflowInvocation(String intentRef,FrozenWorkflowTarget target) {
        public AuthorizedWorkflowInvocation { if(intentRef==null||intentRef.isBlank())throw invalid(); Objects.requireNonNull(target); }
    }

    /** 原冻结知识挂载及真实策略，初次加载一次；Core不查询active source或推测默认授权。 */
    public record KnowledgeBinding(FrozenKnowledgeTarget target, KnowledgeMode mode, String intent, KnowledgeBudget budget,
            Instant asOf, Optional<Instant> freshnessAfter, boolean citationRequired) {
        public KnowledgeBinding {
            Objects.requireNonNull(target); Objects.requireNonNull(mode); Objects.requireNonNull(budget); Objects.requireNonNull(asOf); Objects.requireNonNull(freshnessAfter);
            if (mode == KnowledgeMode.NONE || intent == null || intent.isBlank() || Duration.ofMillis(budget.timeoutMs()).compareTo(target.timeout()) > 0) throw invalid();
        }
    }

    /** 原受理输入及冻结工作流目录共同形成参数，不从模型handle推测或补造初始输入。 */
    public record WorkflowBinding(FrozenWorkflowTarget target, TypedPayload parameters, List<EvidenceRef> evidenceRefs) {
        public WorkflowBinding { Objects.requireNonNull(target); Objects.requireNonNull(parameters); evidenceRefs = List.copyOf(evidenceRefs); }
        @Override public String toString() { return "ExecutionWorkflowBinding[REDACTED]"; }
    }

    /** 只在首次领取/恢复读取权威材料；计量与活动 getter 不重建执行来源。 */
    public interface Sources {
        /** 仅从冻结工具及本次规范参数分类，恢复时使用原已保存结果。 */
        default boolean currentRead(SessionToolDirectory.Entry frozen, TypedPayload arguments) { return false; }
        /** 读取 run.configuration 指向的原冻结配置及该 Run 的原输入，只调用一次。 */
        Configuration configuration(Run run, ResultRef input);
        /** 补充采纳已提交后同步当前drive的操作者，不访问持久存储或重建冻结配置。 */
        default void actorChanged(Run run, VerifiedIdentity actor) { }
        /** 从原工具Attempt只读完整失败事实；无法读取时不把不同操作的同码失败视为相同。 */
        default Optional<TypedPayload> toolFailureEvidence(Run run, AgentExecutionLoop.ToolSource source, com.skillforge.runtime.api.port.PortFailure failure) { return Optional.empty(); }
        /** 恢复时读取明确不可变引用，正常连续执行不调用。 */
        Material material(Run run, ResultRef reference);
        /** 模型manifest固定一层叶的精确集合读取，不能逐叶打开事务。 */
        List<Optional<Material>> materials(Run run, List<ResultRef> references);
        /** 使用既有 tokenizer 和冻结策略计量本次实际消息及工具，不能用字符数冒充 token。 */
        SessionTokenCounter.Measurement measure(Configuration configuration, List<ModelMessage> messages, List<ModelTool> tools);
        /** 本次真正发出前准备媒体，并明确原/派生引用和位置；恢复只验证已保存mapping。 */
        AgentExecutionModelMedia.Prepared prepareModelMessages(Run run, Configuration configuration, List<ModelMessage> messages);
        /** 仅准备新实际MODEL请求时从宿主可信Clock采样一次；恢复不得调用。 */
        AgentExecutionTime.Sample sampleModelTime(Run run);
        /** 返回已确认活动观察加本地单调增量；不得查询数据库或把人工等待计入活动。 */
        Duration elapsed(Run run);
        /**
         * 宿主在新 MODEL 准备时补充受控上下文；已准备请求的采纳只从原 frozen 消息恢复，不得检索或外调。
         * 默认宿主保持原消息，冻结结果仍由现有实际 MODEL request 材料唯一拥有。
         */
        default List<ModelMessage> modelContext(Run run, Configuration configuration, List<ModelMessage> history,
                Optional<List<ModelMessage>> frozenRequestMessages) { return history; }
        /** 新准备只对连续完整安全历史调用独立摘要用途；恢复仅采用原冻结视图。 */
        default com.skillforge.runtime.session.spi.SessionContextSummary.Outcome summarizeContext(Run run, Configuration configuration,
                List<ModelMessage> history, List<ModelMessage> currentContext, String sourceDigest, int maxOutputTokens, long ordinal) {
            return com.skillforge.runtime.session.spi.SessionContextSummary.Outcome.unavailable("SUMMARY_DISABLED");
        }
        /** 持久冷却绑定完整owner及冻结策略，不能在进程重建时清零。 */
        default Optional<AgentExecutionContextViews.Cooldown> contextCompactionCooldown(Run run, Configuration configuration) { return Optional.empty(); }
        /** 原Run已实际准备的业务结果引用；只用于完成事件，不能借用最后模型原结果。 */
        default Optional<String> preparedResult(Run run) { return Optional.empty(); }
        /** 业务输出安全检查只消费实际已解码内容，不消费执行封套或旧Session日志。 */
        default Optional<String> capabilityContentRejection(Run run, List<TypedPayload> contents) { return Optional.empty(); }
        /** 最终公开文本使用宿主同一公共输出契约。 */
        default Optional<String> publicContentRejection(Run run, List<String> contents) { return Optional.empty(); }
        /** 新模型准备前读取账本观察；无预算账本的宿主不伪造余额，恢复使用原冻结消息。 */
        default Optional<ExecutionBudgetPort.Snapshot> budgetSnapshot(Run run) { return Optional.empty(); }
        /** 仅准备新实际MODEL请求时读取宿主义务，恢复必须使用原request中的固化值。 */
        default Optional<AgentExecutionOutputs.Policy> prepareOutput(Run run,Configuration configuration) { return Optional.empty(); }
        /** 仅实际准备时准入未来有限收尾；返回当前真实账本phase，恢复采纳不调用。 */
        /** 业务派发与收尾保护分别使用各自实际请求的输入计量；回执增量由宿主收尾规格另行预留。 */
        default boolean admitOutput(Run run,Configuration configuration,AgentExecutionOutputs.Policy policy,long inputTokens,long outputTokens,long closingInputTokens) { return policy.closing(); }
    }

    /** 宿主已明确证明不能承担有限收尾；正常终态，不重调模型或放松预算。 */
    public static final class OutputBudgetInsufficient extends IllegalStateException {
        private final ExecutionBudgetPort.ClosingFailure failure;
        public OutputBudgetInsufficient(ExecutionBudgetPort.ClosingFailure failure) {
            super(ExecutionTerminationObservation.BUDGET_FAILURE_CODE); this.failure=Objects.requireNonNull(failure);
        }
        public ExecutionBudgetPort.ClosingFailure failure() { return failure; }
    }

    /**
     * 活跃执行的明确类型，不拥有独立写权；仅由结果事务成功后替换。
     * @param configuration 首次读取的冻结配置及已提交的当前操作者
     * @param messages 已完整配对的模型历史
     * @param usage 已采用的实际用量及调用次数
     * @param ordinal 已完成业务动作序号
     * @param modelOrdinal 产生当前决策的原模型动作序号
     * @param model 当前真实模型结果，工具反馈配对后释放
     * @param decision 当前已解码决策，工具反馈配对后释放
     * @param context 已提交的上下文增量引用
     * @param completedTools 已完成工具请求，保护重复非幂等请求不被重新发送
     * @param problem 明确失败、未知或等待，不默认为成功
     * @param lastResult 最近实际结果材料引用
     */
    public record Materials(Configuration configuration, List<ModelMessage> messages, LoopUsage usage,
            long ordinal, long modelOrdinal, Optional<ModelResult.Completed> model,
            Optional<AgentDecisionDecoder.Result> decision, ResultRef context, List<AgentExecutionLoop.ToolObservation> completedTools,
            Optional<Problem> problem, Optional<ResultRef> lastResult, Optional<PendingTool> pendingTool, Optional<PendingHuman> pendingHuman,
            List<KnowledgeObservation> knowledge, AgentExecutionSkills.State skills, Optional<PendingDependency> pendingDependency, SteeringState steering, PlanPort.State planState,
            Optional<AgentExecutionOutputs.Directive> output, InputId observationInput, List<Boolean> currentReads, List<AgentToolProgress.Sample> progress) {
        /** 所有集合和可选值显式冻结，候选材料不得被后续回调原地修改。 */
        public Materials {
            Objects.requireNonNull(configuration); messages = List.copyOf(messages); Objects.requireNonNull(usage);
            Objects.requireNonNull(model); Objects.requireNonNull(decision); Objects.requireNonNull(context);
            completedTools = List.copyOf(completedTools); Objects.requireNonNull(problem); Objects.requireNonNull(lastResult); Objects.requireNonNull(pendingTool); Objects.requireNonNull(pendingHuman);
            if (pendingTool.isPresent() && pendingHuman.isPresent()) throw invalid();
            knowledge = List.copyOf(knowledge);
            Objects.requireNonNull(skills);
            Objects.requireNonNull(pendingDependency);
            Objects.requireNonNull(steering);
            Objects.requireNonNull(planState);
            Objects.requireNonNull(output); Objects.requireNonNull(observationInput);
            currentReads = List.copyOf(currentReads); progress = List.copyOf(progress);
            if (pendingDependency.isPresent() && (pendingTool.isPresent() || pendingHuman.isPresent())) throw invalid();
            if (ordinal < 0 || modelOrdinal < 0 || modelOrdinal > ordinal) throw invalid();
        }
        public Materials(Configuration configuration, List<ModelMessage> messages, LoopUsage usage,
                long ordinal, long modelOrdinal, Optional<ModelResult.Completed> model,
                Optional<AgentDecisionDecoder.Result> decision, ResultRef context, List<AgentExecutionLoop.ToolObservation> completedTools,
                Optional<Problem> problem, Optional<ResultRef> lastResult, Optional<PendingTool> pendingTool, Optional<PendingHuman> pendingHuman,
                List<KnowledgeObservation> knowledge, AgentExecutionSkills.State skills, Optional<PendingDependency> pendingDependency, SteeringState steering,
                PlanPort.State planState, Optional<AgentExecutionOutputs.Directive> output, InputId observationInput) {
            this(configuration, messages, usage, ordinal, modelOrdinal, model, decision, context, completedTools, problem, lastResult,
                    pendingTool, pendingHuman, knowledge, skills, pendingDependency, steering, planState, output, observationInput, List.of(), List.of());
        }
        Materials withCurrentReads(List<Boolean> values) {
            return new Materials(configuration, messages, usage, ordinal, modelOrdinal, model, decision, context, completedTools, problem,
                    lastResult, pendingTool, pendingHuman, knowledge, skills, pendingDependency, steering, planState, output, observationInput, values, progress);
        }
        Materials withProgress(List<AgentToolProgress.Sample> values) {
            return new Materials(configuration, messages, usage, ordinal, modelOrdinal, model, decision, context, completedTools, problem,
                    lastResult, pendingTool, pendingHuman, knowledge, skills, pendingDependency, steering, planState, output, observationInput, currentReads, values);
        }
        /** 模型消息和原结果不进入普通对象日志。 */
        @Override public String toString() { return "AgentExecutionMaterials[ordinal=" + ordinal + ",REDACTED]"; }
    }

    /** 私有完整结果只引用原material；实际注入集合由同次结果业务提交冻结。 */
    public record KnowledgeObservation(KnowledgeCallRequest request, ResultRef result, EvidenceBundle bundle, List<String> injected) {
        public KnowledgeObservation { Objects.requireNonNull(request); Objects.requireNonNull(result); Objects.requireNonNull(bundle); injected = List.copyOf(injected); }
        @Override public String toString() { return "ExecutionKnowledgeObservation[REDACTED]"; }
    }

    /** 等待只保留原模型决定和原结果所需的精确材料，最终反馈前不追加半条工具消息。 */
    public record PendingTool(ExecutionStore.WaitRef waiting, ToolRequest original, ResultRef sourceResult, ToolOutcome.InputRequired input) {
        public PendingTool {
            Objects.requireNonNull(waiting); Objects.requireNonNull(original); Objects.requireNonNull(sourceResult); Objects.requireNonNull(input);
            if (original.continuation().isPresent()) throw invalid();
        }
        @Override public String toString() { return "ExecutionPendingTool[REDACTED]"; }
    }

    /** 人类问题来自已保存的真实模型决定，不生成工具结果或第二次模型调用。 */
    public record PendingHuman(ExecutionStore.WaitRef waiting, String question) {
        public PendingHuman { Objects.requireNonNull(waiting); if (question == null || question.isBlank()) throw invalid(); }
        @Override public String toString() { return "ExecutionPendingHuman[REDACTED]"; }
    }

    /** 内部依赖只保存原业务请求和真实回执引用，不把系统等待当成人类回复。 */
    public sealed interface PendingDependency permits PendingDelegation, PendingPlan, PendingWorkflow { ExecutionStore.WaitRef waiting(); }
    /** 最终反馈前保留原MODEL决定、原委派请求及精确业务依赖；本值只存在内存中。 */
    public record PendingDelegation(ExecutionStore.WaitRef waiting, SessionExecutionDelegations.Request request,
            ResultRef sourceResult, SessionExecutionDelegations.Dependency dependency) implements PendingDependency {
        public PendingDelegation { Objects.requireNonNull(waiting); Objects.requireNonNull(request); Objects.requireNonNull(sourceResult); Objects.requireNonNull(dependency); }
        @Override public String toString() { return "ExecutionPendingDelegation[REDACTED]"; }
    }
    /** Plan本地业务已提交的等待及原模型调用；不再执行业务命令以恢复回执。 */
    public record PendingPlan(ExecutionStore.WaitRef waiting, SessionExecutionPlans.Request request, ResultRef sourceResult, ExecutionStore.PlanWait dependency) implements PendingDependency {
        public PendingPlan { Objects.requireNonNull(waiting); Objects.requireNonNull(request); Objects.requireNonNull(sourceResult); Objects.requireNonNull(dependency); }
        @Override public String toString() { return "ExecutionPendingPlan[REDACTED]"; }
    }
    /** Workflow挂起只保原dispatch和实际业务wait；续跑使用同Action的新真实Attempt。 */
    public record PendingWorkflow(ExecutionStore.WaitRef waiting, SessionExecutionWorkflows.Dispatch original, ResultRef sourceResult, ExecutionStore.WorkflowWait dependency) implements PendingDependency {
        public PendingWorkflow { Objects.requireNonNull(waiting); Objects.requireNonNull(original); Objects.requireNonNull(sourceResult); Objects.requireNonNull(dependency); }
        @Override public String toString() { return "ExecutionPendingWorkflow[REDACTED]"; }
    }

    /** 受理并采纳的补充与实际已被模型请求包含的补充分开；计划等待只使用后者。 */
    public record SteeringState(long appliedThrough, long modelIncludedThrough) {
        public SteeringState { if (appliedThrough < 0 || modelIncludedThrough < 0 || modelIncludedThrough > appliedThrough) throw invalid(); }
    }

    /** @param kind 本次不能继续的确定分类 @param reason 已有结果提供的稳定原因 */
    public record Problem(ProblemKind kind, String reason) {
        /** 不以空原因或自由异常正文伪装业务结果。 */
        public Problem { Objects.requireNonNull(kind); if (reason == null || reason.isBlank()) throw invalid(); }
    }
    /** 保持确定失败、结果未知和工具输入等待的语义差异。 */
    public enum ProblemKind { FAILED, UNKNOWN }

    /** 受理时保存的初始增量标记，实际输入从原受理材料读取，不复制第一次模型实际请求。 */
    public Material initialContext(ResultRef input) {
        Objects.requireNonNull(input);
        var value = AgentExecutionCodec.object(); value.put("type", "base"); value.put("input", input.value());
        return material(codec.encode(CONTEXT_SCHEMA, value));
    }

    @Override public Prepared restorePrepared(Run run, Prepared prepared) {
        if (!AgentExecutionCodec.MODEL_MANIFEST_SCHEMA.equals(prepared.request().content().schemaId())) return prepared;
        var parts=sources.materials(run,codec.modelRequestReferences(prepared.request().content())).stream()
                .map(value -> value.orElseThrow(AgentExecutionProgram::invalid)).toList();
        var restored=new Prepared(prepared.action(),prepared.ordinal(),prepared.kind(),prepared.target(),prepared.capability(),
                prepared.request(),prepared.fingerprint(),parts);
        codec.decodeModelRequest(restored);
        return restored;
    }

    /** 首次加载/恢复只读精确 ref 链，正常每轮直接使用 Adoption 返回的 Materials。 */
    @Override public Materials open(Run run, Material context) {
        var chain = new ArrayList<ContextDelta>(); var seen = new HashSet<ResultRef>();
        var current = context;
        // 每份新context至少对应一次已提交业务revision，补充输入和本地计划不消耗模型/工具次数。
        long bound = run.revision();
        while (!"base".equals(AgentExecutionCodec.string(codec.parse(current.content(), CONTEXT_SCHEMA), "type"))) {
            if (!seen.add(current.reference()) || chain.size() >= bound) throw invalid();
            var delta = delta(current);
            chain.add(delta); current = requireMaterial(run, delta.previous());
        }
        var base = codec.parse(current.content(), CONTEXT_SCHEMA);
        AgentExecutionCodec.fields(base, "type", "input");
        var input = new ResultRef(AgentExecutionCodec.string(base, "input"));
        if (!current.equals(initialContext(input))) throw invalid();
        var configuration = Objects.requireNonNull(sources.configuration(run, input));
        configuration.tools().forEach(entry -> AgentToolProgress.validatePolling(entry.target(), codec));
        if (!configuration.actor().owner().equals(run.identity().owner())
                || !configuration.actor().verificationRef().equals(run.actorVerificationRef())
                || !configuration.target().binding().tenantId().equals(run.identity().runRef().tenantId())
                || !configuration.capabilitySnapshot().sessionRef().equals(run.identity().owner().sessionRef())
                || !configuration.capabilitySnapshot().inputId().equals(run.identity().causationInputId())) throw invalid();
        var skillProgram = skills(configuration); var skillState = skillProgram.initial();
        var initialMessages = new ArrayList<>(configuration.initialMessages()); var skillContents = skillProgram.initialContents(skillState);
        if (!skillContents.isEmpty()) initialMessages.add(new ModelMessage("execution-always-skills", ModelRole.SYSTEM, skillContents));
        var materials = new Materials(configuration, initialMessages, LoopUsage.empty(), 0, 0,
                Optional.empty(), Optional.empty(), current.reference(), List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), List.of(), skillState, Optional.empty(), new SteeringState(0, 0), configuration.initialPlanState(), Optional.empty(), run.identity().causationInputId());
        Collections.reverse(chain);
        for (var item : chain) {
            if (item instanceof ProgressDelta stopped) {
                if (run.phase() != ExecutionStore.Phase.FINISHED || run.status() != RunStatus.TERMINATED) throw invalid();
                var samples = pendingProgress(run, materials);
                var decision = AgentToolProgress.evaluate(samples, materials.steering().appliedThrough());
                if (decision.level() != AgentToolProgress.Level.STOP || !stopped.progress().equals(AgentToolProgress.encode(decision, codec))) throw invalid();
                materials = new Materials(materials.configuration(), materials.messages(), materials.usage(), materials.ordinal(), materials.modelOrdinal(),
                        materials.model(), materials.decision(), stopped.reference(), materials.completedTools(), materials.problem(), materials.lastResult(),
                        materials.pendingTool(), materials.pendingHuman(), materials.knowledge(), materials.skills(), materials.pendingDependency(), materials.steering(),
                        materials.planState(), materials.output(), materials.observationInput()).withCurrentReads(materials.currentReads()).withProgress(samples);
                continue;
            }
            if (item instanceof HumanDelta human) {
                var adopted = adoptHumanResponse(run, materials, human.waiting(), human.input(), requireMaterial(run, human.response()));
                if (!adopted.context().reference().equals(human.reference())) throw invalid();
                materials = adopted.materials(); continue;
            }
            if (item instanceof DependencyDelta dependency) {
                var adopted = adoptDependencyResponse(run, materials, dependency.waiting(), dependency.input(), requireMaterial(run, dependency.response()));
                if (!adopted.context().reference().equals(dependency.reference())) throw invalid();
                materials = adopted.materials(); continue;
            }
            if (item instanceof SupplementalDelta supplemental) {
                var adopted = adoptSupplemental(materials, supplemental.input(), supplemental.ordinal(), requireMaterial(run, supplemental.content()));
                if (!adopted.context().reference().equals(supplemental.reference())) throw invalid();
                materials = adopted.materials(); continue;
            }
            if (item instanceof PlanDelta plan) {
                var request = requireMaterial(run, plan.request()); var original = plans.decodeRequest(request.content());
                var command = new Step.PlanLocal(new ActionRef(run.identity().runRef().tenantId(), "execution:" + run.identity().runRef().runId() + ":" + materials.modelOrdinal()), original.command(), materials.configuration().actor(), request);
                var adopted = planAdoption(run, materials, command, requireMaterial(run, plan.result()));
                if (!adopted.context().reference().equals(plan.reference())) throw invalid();
                materials = adopted.materials(); continue;
            }
            if (item instanceof BatchDelta batch) {
                var calls = new ArrayList<BatchOutcome>();
                for (var entry : batch.results()) {
                    if (entry.reused()) {
                        calls.add(new BatchOutcome.Reused(entry.slot(), new ExecutionStore.BatchMember.Reused(entry.slot(),
                                new ActionRef(run.identity().runRef().tenantId(), entry.action()), entry.attempt(), entry.request(), entry.result(), entry.feedback().orElseThrow(AgentExecutionProgram::invalid))));
                        continue;
                    }
                    var request = requireMaterial(run, entry.request());
                    var result = requireMaterial(run, entry.result());
                    var feedback = entry.feedback().map(reference -> requireMaterial(run, reference));
                    var prepared = prepare(run, materials, ExecutionStore.Kind.TOOL, entry.ordinal(), request);
                    if (!prepared.action().actionId().equals(entry.action())) throw invalid();
                    calls.add(new BatchOutcome.Actual(entry.slot(), new BatchCall(prepared, entry.attempt(), capability(ExecutionStore.Kind.TOOL, result, feedback))));
                }
                var adopted = applyBatch(run, materials, calls, batch.stoppedReason());
                if (!adopted.context().reference().equals(batch.reference())) throw invalid();
                materials = adopted.materials(); continue;
            }
            var delta = (Delta) item;
            var request = requireMaterial(run, delta.request());
            var result = requireMaterial(run, delta.result());
            var feedback = delta.feedback().map(reference -> requireMaterial(run, reference));
            var actual = capability(delta.kind(), result, feedback);
            if (delta.kind() == ExecutionStore.Kind.TOOL) {
                var tool = codec.decodeTool(request.content());
                tool.continuation().ifPresent(continuation -> {
                    var response = requireMaterial(run, continuation.response());
                    if (!codec.decodeToolResponses(response.content()).equals(continuation.responses())) throw invalid();
                });
            }
            if (delta.kind() == ExecutionStore.Kind.WORKFLOW && workflows.decode(request.content()) instanceof SessionExecutionWorkflows.Resume resume
                    && !requireMaterial(run, resume.responseRef()).content().equals(resume.response())) throw invalid();
            var prepared = prepare(run, materials, delta.kind(), delta.ordinal(), request);
            var adopted = delta.kind() == ExecutionStore.Kind.KNOWLEDGE ? adoptKnowledge(run, materials, prepared, actual, delta.knowledgeProjection()) : adopt(run, materials, prepared, actual, delta.currentReads(), true);
            if (!adopted.context().reference().equals(delta.reference())) throw invalid();
            materials = adopted.materials();
        }
        if (!materials.context().equals(context.reference()) || materials.usage().businessModelCalls() != run.progress().decisions()
                || materials.usage().toolCalls() != run.progress().toolCalls() || materials.usage().knowledgeCalls() != run.progress().knowledgeCalls()) throw invalid();
        return materials;
    }

    /** 计算实际 MODEL/TOOL 请求或回答完成；其他已识别动作明确交回后继能力实现。 */
    @Override public Step next(Run run, Materials materials) {
        if (materials.pendingTool().isPresent() || materials.pendingHuman().isPresent() || materials.pendingDependency().isPresent()) throw new IllegalStateException("execution_wait_response_required");
        if (materials.problem().isPresent()) {
            var problem = materials.problem().orElseThrow();
            return problem.kind() == ProblemKind.FAILED ? finish(run, RunStatus.FAILED, "", problem.reason(), materials.lastResult())
                    : new Step.Yield("execution_" + problem.kind().name().toLowerCase(java.util.Locale.ROOT));
        }
        if (materials.configuration().initialIntent().isPresent()) {
            if (materials.ordinal()==0) return authorizedWorkflow(run,materials);
            return authorizedWorkflowCompletion(run,materials);
        }
        if (materials.decision().isPresent()) {
            var selected = loop.next(new AgentExecutionLoop.LoadedInput(materials.decision().orElseThrow(), materials.configuration().budget(),
                    materials.usage(), sources.elapsed(run), loadedTools(materials.configuration()), materials.completedTools(), currentRead(materials, 0), materials.modelOrdinal(), 1));
            if (selected instanceof AgentExecutionLoop.LoadedDecodeFeedback failure)
                return finish(run, RunStatus.FAILED, "", "decision_" + failure.reason().name().toLowerCase(java.util.Locale.ROOT), materials.lastResult());
            if (selected instanceof AgentExecutionLoop.LoadedBatchDeferred) return batch(run, materials);
            if (selected instanceof AgentExecutionLoop.LoadedBudgetDenied denied)
                return finish(run, RunStatus.TERMINATED, "", denied.limit().name(), materials.lastResult());
            if (selected instanceof AgentExecutionLoop.LoadedRejected rejected)
                return rejectedDecision(run, materials, rejected.reason());
            if (selected instanceof AgentExecutionLoop.LoadedToolReuse reuse)
                return model(run, materials, replayMessages(run, materials, reuse));
            var action = ((AgentExecutionLoop.LoadedSelected) selected).action();
            return switch (action) {
                case AgentAction.Respond respond -> respond(run, materials, respond);
                case AgentAction.ToolCall call -> tool(run, materials, call);
                case AgentAction.AskUser ignored -> throw new IllegalStateException("execution_human_wait_missing");
                case AgentAction.KnowledgeAccess query -> knowledge(run, materials, query);
                case AgentAction.Dispatch dispatch -> workflow(run, materials, dispatch);
                case AgentAction.Delegate delegate -> delegation(run, materials, delegate);
                case AgentAction.Plan plan -> localPlan(run, materials, plan);
                case AgentAction.ActivateSkill ignored -> throw new IllegalStateException("execution_skill_adoption_missing");
                case AgentAction.ReadSkillResource ignored -> throw new IllegalStateException("execution_skill_adoption_missing");
            };
        }
        return model(run, materials, materials.messages());
    }

    /** Store已确认授权拒绝且未派发；使用原身份明确失败，不刷新身份或生成新模型调用。 */
    @Override public Optional<Step.Finish> dispatchRejected(Run run, Materials materials, String reason) {
        return "execution_dispatch_unauthorized".equals(reason)
                ? Optional.of(finish(run, RunStatus.FAILED, "", reason, materials.lastResult())) : Optional.empty();
    }

    /** 拒绝新续跑不能抹去已返回的原批次；保留真实物理用量并关闭原等待。 */
    @Override public Optional<Adoption<Materials>> batchDispatchRejectionAdoption(Run run, Materials materials, List<BatchOutcome> results, String reason) {
        return "execution_dispatch_unauthorized".equals(reason) ? Optional.of(applyBatch(run, materials, results, Optional.of(reason))) : Optional.empty();
    }

    /** 确定性决策拒绝必须收口；未决工具仍由原账本收敛，不能因拒绝生成终态或重试。 */
    private Step rejectedDecision(Run run, Materials materials, AgentExecutionLoop.LoadedRejection rejection) {
        var reason = "execution_" + rejection.name().toLowerCase(java.util.Locale.ROOT);
        return switch (rejection) {
            case TOOL_RESULT_UNRESOLVED -> new Step.Yield(reason);
            case MODEL_RESULT_REQUIRED, INVALID_HISTORY, TOOL_NOT_VISIBLE, REPEATED_WRITE_ARGUMENTS, SAFE_FEEDBACK_REQUIRED ->
                    finish(run, RunStatus.FAILED, "", reason, materials.lastResult());
        };
    }

    /** 接受响应后的领取只准备原Action的新Attempt，原opaque state与方法绑定完全保留。 */
    @Override public Step continuation(Run run, Materials materials, ExecutionStore.Waiting waiting, Material response) {
        if (waiting.kind() == ExecutionStore.WaitKind.WORKFLOW) return workflowContinuation(run, materials, waiting, response);
        var pending = materials.pendingTool().orElseThrow(AgentExecutionProgram::invalid);
        var accepted = waiting.acceptedResponse().orElseThrow(AgentExecutionProgram::invalid);
        if (run.phase() != ExecutionStore.Phase.READY || !run.waiting().equals(Optional.of(waiting.reference()))
                || waiting.kind() != ExecutionStore.WaitKind.TOOL
                || !waiting.reference().equals(pending.waiting()) || !waiting.run().equals(run.identity().runRef())
                || waiting.continuationAttempt().isPresent() || !accepted.response().equals(response.reference())
                || !accepted.actorRef().equals(run.actorVerificationRef()) || !waiting.requested().equals(methods(pending.input()))) throw invalid();
        var responses = codec.decodeToolResponses(response.content());
        if (!responses.keySet().equals(waiting.requested().keySet())) throw invalid();
        responses.forEach((key, actual) -> { if (actual.method() != waiting.requested().get(key)) throw invalid(); });
        var limited = BudgetGuard.evaluate(materials.configuration().budget(), materials.usage(), BudgetGuard.Operation.TOOL, sources.elapsed(run));
        if (limited.isPresent()) return finish(run, RunStatus.TERMINATED, "", limited.orElseThrow().name(), materials.lastResult());
        var original = pending.original();
        var request = new ToolRequest(original.target(), original.binding(), original.arguments(), original.callId(), original.wireName(),
                Optional.of(new ToolContinuation(waiting.reference(), pending.sourceResult(), response.reference(), responses, pending.input().requestState(), accepted.input(), accepted.acceptedRevision())));
        var prepared = prepare(run, materials, ExecutionStore.Kind.TOOL, materials.ordinal(), material(codec.encodeTool(request)));
        if (!prepared.action().equals(waiting.reference().action())) throw invalid();
        String attempt = prepared.action().actionId() + ":attempt:" + Math.incrementExact(waiting.reference().generation());
        return new Step.Dispatch(prepared, attempt, new ExecutionBudgetPort.Demand(run.identity().runRef(), attempt, prepared.fingerprint(),
                ExecutionBudgetPort.Category.TOOL, ExecutionBudgetPort.Resources.call(ExecutionBudgetPort.Category.TOOL, 0, 0), false),
                materials.configuration().actor(), Optional.of(waiting.reference()), observations.registered(run, materials, prepared, attempt));
    }

    /** 人类回复的纯采纳候选；等待消费与context只在Store.acceptLocalResponse成功后生效。 */
    @Override public Adoption<Materials> acceptResponse(Run run, Materials materials, ExecutionStore.Waiting waiting, Material response) {
        var accepted = waiting.acceptedResponse().orElseThrow(AgentExecutionProgram::invalid);
        if ((waiting.kind() != ExecutionStore.WaitKind.HUMAN && waiting.kind() != ExecutionStore.WaitKind.DELEGATION && waiting.kind() != ExecutionStore.WaitKind.PLAN) || run.phase() != ExecutionStore.Phase.READY
                || !run.waiting().equals(Optional.of(waiting.reference())) || !waiting.run().equals(run.identity().runRef())
                || waiting.consumedRevision().isPresent() || waiting.continuationAttempt().isPresent() || !waiting.requested().isEmpty()
                || !accepted.response().equals(response.reference()) || !accepted.actorRef().equals(run.actorVerificationRef())) throw invalid();
        if (waiting.kind() == ExecutionStore.WaitKind.DELEGATION) {
            var pending = materials.pendingDependency().orElseThrow(AgentExecutionProgram::invalid);
            if (!(pending instanceof PendingDelegation delegation) || !waiting.internal().equals(Optional.of(internal(delegation.dependency())))) throw invalid();
            return adoptDependencyResponse(run, materials, waiting.reference(), accepted.input(), response);
        }
        if (waiting.kind() == ExecutionStore.WaitKind.PLAN) {
            var pending = materials.pendingDependency().orElseThrow(AgentExecutionProgram::invalid);
            if (!(pending instanceof PendingPlan plan) || !waiting.internal().equals(Optional.of(plan.dependency()))) throw invalid();
            return adoptDependencyResponse(run, materials, waiting.reference(), accepted.input(), response);
        }
        return adoptHumanResponse(run, materials, waiting.reference(), accepted.input(), response);
    }

    /** Store已核实原owner与当前控制权限；本程序只核原材料和最早未采纳顺序。 */
    @Override public Adoption<Materials> acceptInput(Run run, Materials before, ExecutionStore.SupplementalInput input, Material content) {
        if (run.phase() != ExecutionStore.Phase.READY || run.currentAction().isPresent() || run.currentBatch().isPresent() || run.waiting().isPresent()
                || !input.run().equals(run.identity().runRef()) || input.appliedRevision().isPresent() || !input.content().equals(content.reference())
                || !input.fingerprint().equals(AgentExecutionCodec.digest(content.content().schemaId() + "\0" + content.content().canonicalJson()))) throw invalid();
        return adoptSupplemental(before, input.input(), input.ordinal(), content);
    }

    /** 采用Store已核实的实际提交者；只在成功CAS后同步宿主帧与下一动作材料。 */
    @Override public Materials inputCommitted(ExecutionStore.InputAcceptance accepted, Materials materials) {
        if (!accepted.advanced() || !accepted.run().progress().context().equals(materials.context())) throw invalid();
        var configuration = materials.configuration().withActor(accepted.actor());
        var after = new Materials(configuration, materials.messages(), materials.usage(), materials.ordinal(), materials.modelOrdinal(),
                materials.model(), materials.decision(), materials.context(), materials.completedTools(), materials.problem(), materials.lastResult(),
                materials.pendingTool(), materials.pendingHuman(), materials.knowledge(), materials.skills(), materials.pendingDependency(), materials.steering(), materials.planState(), materials.output(), materials.observationInput()).withProgress(materials.progress()).withCurrentReads(materials.currentReads());
        sources.actorChanged(accepted.run(), accepted.actor());
        return after;
    }

    /** 本地采纳和恢复共用实际已保存USER正文；原MODEL决定及原trace完全保留。 */
    private Adoption<Materials> adoptSupplemental(Materials before, InputId input, long ordinal, Material content) {
        if (ordinal != Math.incrementExact(before.steering().appliedThrough()) || before.pendingTool().isPresent() || before.pendingHuman().isPresent() || before.pendingDependency().isPresent()) throw invalid();
        var message = steeringInputs.message(input, content.content());
        if (before.messages().stream().anyMatch(value -> value.messageId().equals(message.messageId()))) throw invalid();
        var messages = new ArrayList<>(before.messages()); messages.add(message); ModelPort.validateConversation(messages);
        var value = AgentExecutionCodec.object(); value.put("type", "supplemental-input"); value.put("previous", before.context().value()); value.put("input", input.value());
        value.put("ordinal", Long.toString(ordinal)); value.put("content", content.reference().value()); var context = material(codec.encode(CONTEXT_SCHEMA, value));
        var after = new Materials(before.configuration(), messages, before.usage(), before.ordinal(), before.modelOrdinal(), before.model(), before.decision(), context.reference(), before.completedTools(),
                before.problem(), before.lastResult(), before.pendingTool(), before.pendingHuman(), before.knowledge(), before.skills(), before.pendingDependency(), new SteeringState(ordinal, before.steering().modelIncludedThrough()), before.planState(), before.output(), input).withProgress(List.of()).withCurrentReads(before.currentReads());
        return new Adoption<>(after, new ExecutionStore.Progress(after.usage().businessModelCalls(), after.usage().toolCalls(), after.usage().knowledgeCalls(), context.reference()), context, List.of(), Optional.empty());
    }

    /** 活跃采纳与精确引用恢复共用这一纯转换，不重复请求原模型或再次追加原问题。 */
    private Adoption<Materials> adoptHumanResponse(Run run, Materials before, ExecutionStore.WaitRef waiting, InputId input, Material response) {
        var pending = before.pendingHuman().orElseThrow(AgentExecutionProgram::invalid);
        if (!pending.waiting().equals(waiting) || before.pendingTool().isPresent() || before.problem().isPresent()) throw invalid();
        var model = before.model().orElseThrow(AgentExecutionProgram::invalid);
        var decision = before.decision().orElseThrow(AgentExecutionProgram::invalid);
        if (!(decision instanceof AgentDecisionDecoder.Decoded decoded) || !(decoded.action() instanceof AgentAction.AskUser question)
                || !question.question().equals(pending.question())) throw invalid();
        var text = codec.decodeHumanResponse(response.content());
        var messages = new ArrayList<>(before.messages());
        if(before.configuration().profile().response().format() instanceof ModelPort.ResponseFormat.NativeConversation) {
            messages=new ArrayList<>(ModelConversation.append(messages,SessionNativeResponses.assistant(before,List.of()),
                    List.of(new ModelMessage("execution-question-result-"+before.modelOrdinal(),ModelRole.TOOL,
                            "用户已回答，回答原文见下一条用户消息；按其真实内容继续，不推定额外授权。",List.of(),Optional.of(SessionNativeResponses.callId(before,0)),Optional.empty()))));
        } else messages.add(new ModelMessage("execution-question-" + before.modelOrdinal(), ModelRole.ASSISTANT,
                List.of(new ModelContent.Text(question.question())), List.of(), Optional.empty(), model.reasoningContent(), model.protocolState()));
        messages.add(new ModelMessage("execution-human-" + input.value(), ModelRole.USER, text));
        ModelPort.validateConversation(messages);
        var value = AgentExecutionCodec.object(); value.put("type", "human-response"); value.put("previous", before.context().value());
        value.put("tenantId", waiting.action().tenantId().value()); value.put("actionId", waiting.action().actionId());
        value.put("generation", Long.toString(waiting.generation())); value.put("input", input.value()); value.put("response", response.reference().value());
        var context = material(codec.encode(CONTEXT_SCHEMA, value));
        var after = new Materials(before.configuration(), messages, before.usage(), before.ordinal(), before.modelOrdinal(), Optional.empty(), Optional.empty(),
                context.reference(), before.completedTools(), Optional.empty(), before.lastResult(), Optional.empty(), Optional.empty(), before.knowledge(), before.skills(), Optional.empty(), before.steering(), before.planState(), before.output(), input).withProgress(List.of());
        return new Adoption<>(after, new ExecutionStore.Progress(after.usage().businessModelCalls(), after.usage().toolCalls(), after.usage().knowledgeCalls(), context.reference()),
                context, List.of(), Optional.empty());
    }

    /** 从本Run上下文定位原模型结果；等待输入后的lastResult可能已是工具中间回执。 */
    private ResultRef progressModelResult(Run run, Materials before) {
        if (before.pendingTool().isEmpty()) return before.lastResult().orElseThrow(AgentExecutionProgram::invalid);
        var current = requireMaterial(run, before.context()); long count = 0;
        while (!"base".equals(AgentExecutionCodec.string(codec.parse(current.content(), CONTEXT_SCHEMA), "type"))) {
            if (++count > run.revision()) throw invalid();
            var item = delta(current);
            if (item instanceof Delta model && model.kind() == ExecutionStore.Kind.MODEL && model.ordinal() == before.modelOrdinal()) return model.result();
            current = requireMaterial(run, item.previous());
        }
        throw invalid();
    }

    /** 治理来源保存原模型实际槽序，不使用剔除Skill资源后的业务槽序。 */
    private static int progressSlot(Materials before, int businessIndex) {
        if (!(before.configuration().profile().response().format() instanceof ModelPort.ResponseFormat.NativeConversation)) return businessIndex + 1;
        var callId = SessionNativeResponses.callId(before, businessIndex);
        var calls = before.model().orElseThrow(AgentExecutionProgram::invalid).assistantTurn().orElseThrow(AgentExecutionProgram::invalid).toolCalls();
        for (int i = 0; i < calls.size(); i++) if (calls.get(i).callId().equals(callId)) return i + 1;
        throw invalid();
    }

    /** 从原持久结果读取确定事实，复用没有新工具Attempt但仍是新模型槽消费。 */
    private ToolOutcome progressOutcome(Run run, AgentExecutionLoop.ToolObservation original) {
        var source = requireMaterial(run, original.source().result());
        var result = core.decodeToolOutcomeForInspection(codec.decodeOutcome(source.content()), PortalCallKind.TOOL_INVOKE, payloads);
        if (result instanceof ToolOutcome.Succeeded && original.resolution() != AgentExecutionLoop.ToolResolution.SUCCEEDED
                || result instanceof ToolOutcome.Failed && original.resolution() != AgentExecutionLoop.ToolResolution.FAILED
                || !(result instanceof ToolOutcome.Succeeded || result instanceof ToolOutcome.Failed)) throw invalid();
        return result;
    }

    /** 同一Run使用同一冻结包；已校验LOADED的相同槽、句柄及路径对应同一反馈。 */
    private java.util.function.Supplier<List<AgentToolProgress.ResourceFact>> progressResources(Materials before) {
        return new java.util.function.Supplier<>() {
            private List<AgentToolProgress.ResourceFact> value;
            @Override public List<AgentToolProgress.ResourceFact> get() {
                if (value != null) return value;
                var resources = new ArrayList<AgentToolProgress.ResourceFact>();
                if (before.configuration().profile().response().format() instanceof ModelPort.ResponseFormat.NativeConversation) {
                    var model = before.model().orElseThrow(AgentExecutionProgram::invalid);
                    var calls = model.assistantTurn().orElseThrow(AgentExecutionProgram::invalid).toolCalls();
                    var reads = nativeResponses.resourceReads(model, before.configuration().modelTools());
                    for (int index = 0; index < calls.size(); index++) {
                        var call = calls.get(index);
                        var read = reads.stream().filter(item -> item.callId().equals(call.callId())).findFirst();
                        if (read.isPresent()) resources.add(new AgentToolProgress.ResourceFact(index + 1, read.orElseThrow().action()));
                    }
                }
                return value = List.copyOf(resources);
            }
        };
    }

    private List<AgentToolProgress.Sample> appendProgress(Run run, Materials before, List<AgentToolProgress.Sample> samples,
            AgentExecutionLoop.ToolObservation observation, int businessIndex, ToolOutcome outcome,
            java.util.function.Supplier<List<AgentToolProgress.ResourceFact>> resources) {
        var evidence = outcome instanceof ToolOutcome.Failed failed
                ? failed.comparisonFact().or(() -> sources.toolFailureEvidence(run, observation.source(), failed.failure())) : Optional.<TypedPayload>empty();
        return AgentToolProgress.append(samples, observation, progressModelResult(run, before), before.modelOrdinal(),
                progressSlot(before, businessIndex), outcome, evidence, resources, codec);
    }

    /** 下一次模型请求前，把本轮已确认复用按模型槽顺序计入；其他能力打断工具周期。 */
    private List<AgentToolProgress.Sample> pendingProgress(Run run, Materials before) {
        if (before.decision().isEmpty() || before.steering().appliedThrough() > before.steering().modelIncludedThrough()) return before.progress();
        var samples = before.progress(); var decision = before.decision().orElseThrow(); var resources = progressResources(before);
        if (decision instanceof AgentDecisionDecoder.Decoded decoded && decoded.action() instanceof AgentAction.ToolCall call) {
            var selected = loop.resolveTool(call, loadedTools(before.configuration()), before.completedTools(), currentRead(before, 0), before.modelOrdinal(), 1);
            if (!(selected instanceof AgentExecutionLoop.LoadedToolReuse reuse)) throw invalid();
            return appendProgress(run, before, samples, reuse.original(), 0, progressOutcome(run, reuse.original()), resources);
        }
        if (decision instanceof AgentDecisionDecoder.DecodedBatch) {
            for (var slot : batches.slots(before)) {
                var selected = loop.resolveTool(slot.call(), loadedTools(before.configuration()), before.completedTools(), currentRead(before, slot.index() - 1), before.modelOrdinal(), slot.index());
                if (!(selected instanceof AgentExecutionLoop.LoadedToolReuse reuse)) throw invalid();
                samples = appendProgress(run, before, samples, reuse.original(), slot.index() - 1, progressOutcome(run, reuse.original()), resources);
            }
            return samples;
        }
        return List.of();
    }

    /** 停止事实作为私有context与终态原子提交，不生成MODEL Action。 */
    private Step.Finish progressStop(Run run, Materials before, AgentToolProgress.Decision decision) {
        var value = AgentExecutionCodec.object(); value.put("type", "tool-progress-stop"); value.put("previous", before.context().value());
        value.set("progress", AgentExecutionCodec.payload(AgentToolProgress.encode(decision, codec)));
        var context = material(codec.encode(CONTEXT_SCHEMA, value));
        var finished = finish(run, RunStatus.TERMINATED, "", "tool_progress_stopped", before.lastResult());
        return new Step.Finish(finished.status(), finished.events(), Optional.of(context));
    }

    /** 下一次模型请求的实际消息可以包含原安全反馈复用，仍只登记这一次真实模型外调。 */
    private Step model(Run run, Materials materials, List<ModelMessage> messages) {
        var progress = AgentToolProgress.evaluate(pendingProgress(run, materials), materials.steering().appliedThrough());
        if (progress.level() == AgentToolProgress.Level.STOP) return progressStop(run, materials, progress);
        var limited = BudgetGuard.evaluate(materials.configuration().budget(), materials.usage(), BudgetGuard.Operation.BUSINESS_MODEL_CALL, sources.elapsed(run));
        if (limited.isPresent()) return finish(run, RunStatus.TERMINATED, "", limited.orElseThrow().name(), materials.lastResult());
        var configuration = materials.configuration();
        messages = AgentToolProgress.messages(messages, progress, Math.incrementExact(materials.ordinal()));
        messages = planInstructions(materials, messages);
        messages = List.copyOf(sources.modelContext(run, configuration, messages, Optional.empty()));
        int corrections=materials.output().map(AgentExecutionOutputs.Directive::correctionsUsed).orElse(0);
        Optional<AgentExecutionOutputs.Directive> output=sources.prepareOutput(run,configuration).map(policy->new AgentExecutionOutputs.Directive(policy,corrections));
        messages = finalAnswerInstructions(configuration, messages, output);
        long ordinal=Math.incrementExact(materials.ordinal());
        messages=AgentExecutionBudgetContext.messages(messages,sources.budgetSnapshot(run),ordinal);
        var originalMessages=AgentExecutionOutputs.messages(messages,output,ordinal,codec);
        var modelTime=Objects.requireNonNull(sources.sampleModelTime(run));
        originalMessages=AgentExecutionTime.append(originalMessages,modelTime,ordinal);
        var modelTools = modelTools(configuration, output);
        var toolPreviews=AgentExecutionToolPreviews.prepare(originalMessages,materials.context(),configuration,sources,modelTools,ordinal,codec);
        var contextView=AgentExecutionContextViews.reduce(originalMessages,toolPreviews.messages(),materials.context(),ordinal,configuration,sources,modelTools,modelTime.sampledAt(),run,
                !output.map(value->value.policy().closing()).orElse(false),codec);
        originalMessages=AgentExecutionToolPreviews.shrinkToFit(originalMessages,contextView.messages(),materials.context(),configuration,sources,modelTools,ordinal,codec).messages();
        var preparedMedia=Objects.requireNonNull(sources.prepareModelMessages(run,configuration,originalMessages));
        preparedMedia.requireCorrespondence(originalMessages);messages=preparedMedia.messages();
        Optional<SessionTokenCounter.Measurement> closingMeasurement=Optional.empty();
        if(output.isPresent() && !output.orElseThrow().policy().closing()) {
            var closingOutput=Optional.of(new AgentExecutionOutputs.Directive(output.orElseThrow().policy().withClosing(true),corrections));
            var closingTools=modelTools(configuration,closingOutput);
            var closingMessages=AgentExecutionTime.append(AgentExecutionOutputs.messages(
                    finalAnswerInstructions(configuration,messages.subList(0,messages.size()-1),closingOutput),closingOutput,ordinal,codec),modelTime,ordinal);
            // 收尾只计量其真实允许工具与提示词；复用已准备媒体，不再次执行外部准备。
            var closingMeasured=sources.measure(configuration,closingMessages,closingTools);
            validateMeasurement(configuration,closingMessages,closingTools,closingMeasured);
            closingMeasurement=Optional.of(closingMeasured);
        }
        var measured=sources.measure(configuration,messages,modelTools);
        validateMeasurement(configuration,messages,modelTools,measured);
        long outputTokens=configuration.profile().generation().maxOutputTokens().isPresent()
                ?configuration.profile().generation().maxOutputTokens().getAsInt()
                :Math.subtractExact(((LoopBudget.Enforced)configuration.budget()).maxOutputTokens(),materials.usage().outputTokens().reportedTokens());
        if(output.isPresent()) {
            try {
                boolean closing=sources.admitOutput(run,configuration,output.orElseThrow().policy(),measured.totalTokens(),outputTokens,closingMeasurement.orElse(measured).totalTokens());
                if(closing!=output.orElseThrow().policy().closing()) {
                    output=Optional.of(new AgentExecutionOutputs.Directive(output.orElseThrow().policy().withClosing(closing),corrections));
                    // 媒体准备已逐项验证原末条时间；重投影政策后仍复用同一样本和图片映射。
                    modelTools=modelTools(configuration,output);
                    messages=finalAnswerInstructions(configuration,messages,output);
                    messages=AgentExecutionTime.append(AgentExecutionOutputs.messages(messages.subList(0,messages.size()-1),output,ordinal,codec),
                            modelTime,ordinal);
                    measured=sources.measure(configuration,messages,modelTools);validateMeasurement(configuration,messages,modelTools,measured);
                    sources.admitOutput(run,configuration,output.orElseThrow().policy(),measured.totalTokens(),outputTokens,measured.totalTokens());
                }
            } catch(OutputBudgetInsufficient insufficient) {
                var completed=finish(run,RunStatus.TERMINATED,"",ExecutionTerminationObservation.BUDGET_FAILURE_CODE,materials.lastResult());
                var original=completed.events().getFirst();
                var value=(com.fasterxml.jackson.databind.node.ObjectNode)codec.parse(original.data(),COMPLETED_SCHEMA);
                value.set("budget_failure",ExecutionTerminationObservation.encode(insufficient.failure()));
                var events=new ArrayList<>(toolPreviews.events());events.addAll(contextView.events());
                events.add(new ExecutionStore.Event(original.id(),original.type(),codec.encode(COMPLETED_SCHEMA,value)));
                return new Step.Finish(completed.status(),events);
            }
        }
        long characters=messages.stream().mapToLong(message->message.accountingText().codePointCount(0,message.accountingText().length())).sum()
                +modelTools.stream().mapToLong(item->item.accountingText().codePointCount(0,item.accountingText().length())).sum();
        if(measured.totalTokens()>configuration.profile().maxTokens()||characters>configuration.profile().maxCharacters()) {
            var finished=finish(run,RunStatus.TERMINATED,"","context_budget_exceeded",materials.lastResult());
            var events=new ArrayList<>(toolPreviews.events());events.addAll(contextView.events());events.addAll(finished.events());return new Step.Finish(finished.status(),events);
        }
        // 普通Agent可自然回答；推理策略不改变是否允许正常正文。
        var toolChoice=AgentExecutionOutputs.finalAnswerOnly(output)||modelTools.isEmpty()?ToolChoice.NONE:ToolChoice.AUTO;
        var request=new FrozenModelRequest(configuration.profile().strategy(),configuration.target(),messages,
                modelTools,toolChoice,
                configuration.profile().response(),configuration.profile().generation(),configuration.profile().reasoning());
        var modelMaterials=codec.encodeModelRequestMaterials(new AgentExecutionCodec.ModelCallRequest(request,preparedMedia.transformations(),materials.steering().appliedThrough(),output,Optional.of(AgentToolProgress.encode(progress,codec))));
        var prepared=prepare(run,materials,ExecutionStore.Kind.MODEL,Math.incrementExact(materials.ordinal()),
                modelMaterials.root(),modelMaterials.parts());
        var dispatched=dispatch(run,materials,prepared,ExecutionBudgetPort.Category.BUSINESS_MODEL,
                ExecutionBudgetPort.Resources.call(ExecutionBudgetPort.Category.BUSINESS_MODEL,measured.totalTokens(),outputTokens));
        return new Step.Dispatch(dispatched.action(),dispatched.attemptId(),new ExecutionBudgetPort.Demand(dispatched.budget().run(),dispatched.budget().attemptId(),dispatched.budget().fingerprint(),dispatched.budget().category(),dispatched.budget().resources(),output.map(value->value.policy().closing()).orElse(false)),dispatched.actor(),dispatched.continuation(),java.util.stream.Stream.concat(java.util.stream.Stream.concat(toolPreviews.events().stream(),contextView.events().stream()),dispatched.events().stream()).toList());
    }

    /** 规划要求随已激活 Skill 保持到本 Run 结束；正文只来自原冻结 Profile。 */
    private static boolean planRequired(Materials materials) {
        return materials.configuration().planRequired() || materials.configuration().skills().stream()
                .anyMatch(mount -> materials.skills().activation().activeHandles().contains(mount.handle())
                        && mount.descriptor().metadata().requiresPlan());
    }

    /** 初始无计划时不展示规划规则；计划建立或受控 Skill 激活后使用同一冻结正文。 */
    private static List<ModelMessage> planInstructions(Materials materials, List<ModelMessage> original) {
        String policy = "";
        if (planRequired(materials) || materials.planState().exists()) {
            policy = materials.configuration().profile().systemPrompt().modules().stream()
                    .filter(module -> module.type() == com.skillforge.runtime.session.spi.SessionPrompt.Type.PLAN_POLICY)
                    .map(com.skillforge.runtime.session.spi.SessionPrompt.Module::content)
                    .collect(java.util.stream.Collectors.joining("\n\n"));
        }
        var existing = original.stream().filter(message -> message.role() == ModelRole.SYSTEM && message.messageId().equals(PLAN_POLICY_MESSAGE)).findFirst();
        if (existing.isPresent() && existing.orElseThrow().textContent().equals(policy)) return List.copyOf(original);
        var result = new ArrayList<>(original.stream().filter(message -> message.role() != ModelRole.SYSTEM
                || !message.messageId().equals(PLAN_POLICY_MESSAGE)).toList());
        if (!policy.isBlank()) {
            int position = result.size();
            if (materials.configuration().target().capabilities().prefixCapabilities().systemUpdatesInHistory()
                    != ModelPort.CapabilitySupportStatus.SUPPORTED) {
                position = 0;
                for (int index = 0; index < result.size(); index++) if (result.get(index).role() == ModelRole.SYSTEM) { position = index + 1; break; }
            }
            result.add(position, new ModelMessage(PLAN_POLICY_MESSAGE, ModelRole.SYSTEM, policy));
        }
        return List.copyOf(result);
    }

    private static List<ModelTool> modelTools(Configuration configuration, Optional<AgentExecutionOutputs.Directive> output) {
        if (AgentExecutionOutputs.finalAnswerOnly(output)) return List.of();
        if (output.isPresent() && output.orElseThrow().policy().closing()) {
            var allowed = output.orElseThrow().policy().closingSpec().toolHandles();
            return configuration.modelTools().stream().filter(tool -> tool.kind() == ModelPort.ModelToolKind.TOOL_CALL
                    && allowed.contains(tool.toolHandle())).toList();
        }
        return configuration.modelTools();
    }

    /** 工具已关闭时省去能力操作模块；原业务、证据、知识引用及另行注入的计划/Skill 正文保留。 */
    private static List<ModelMessage> finalAnswerInstructions(Configuration configuration, List<ModelMessage> messages,
            Optional<AgentExecutionOutputs.Directive> output) {
        if (!AgentExecutionOutputs.finalAnswerOnly(output)) return List.copyOf(messages);
        var source = configuration.initialMessages().stream().filter(message -> message.role() == ModelRole.SYSTEM).findFirst();
        if (source.isEmpty()) return List.copyOf(messages);
        var prompt = configuration.profile().systemPrompt().selectedModules(false,
                        Set.of(com.skillforge.runtime.session.spi.SessionPrompt.Type.RUNTIME_KNOWLEDGE)).stream()
                .map(com.skillforge.runtime.session.spi.SessionPrompt.Module::content)
                .collect(java.util.stream.Collectors.joining("\n\n"));
        return messages.stream().map(message -> message.role() == ModelRole.SYSTEM
                        && message.messageId().equals(source.orElseThrow().messageId())
                ? new ModelMessage(message.messageId(), ModelRole.SYSTEM, prompt) : message).toList();
    }

    /** 新模型请求及phase切换后的消息均须通过同一真实计量对应校验。 */
    private static void validateMeasurement(Configuration configuration,List<ModelMessage> messages,List<ModelTool> tools,SessionTokenCounter.Measurement measured) {
        if(!measured.messages().equals(messages)||!measured.tools().equals(tools)
                ||!measured.profile().target().equals(configuration.target())||!measured.profile().reference().equals(configuration.profile().tokenizerProfileRef()))throw invalid();
    }

    /** 只采用实际结果及与安全转换完全相等的反馈，返回提交候选，不提前更新运行状态。 */
    @Override public Adoption<Materials> adopt(Run run, Materials before, Prepared prepared, CapabilityResult actual) {
        return adopt(run, before, prepared, actual, Optional.empty(), false);
    }

    private Adoption<Materials> adopt(Run run, Materials before, Prepared prepared, CapabilityResult actual,
            Optional<List<Boolean>> restoredReads, boolean restoring) {
        if (prepared.kind() == ExecutionStore.Kind.DELEGATION) return adoptDelegation(run, before, prepared, actual);
        if (prepared.kind() == ExecutionStore.Kind.WORKFLOW) return adoptWorkflow(run, before, prepared, actual);
        if (before.pendingDependency().isPresent()) throw invalid();
        if (prepared.kind() == ExecutionStore.Kind.KNOWLEDGE) return adoptKnowledge(run, before, prepared, actual, Optional.empty());
        boolean continuing = prepared.kind() == ExecutionStore.Kind.TOOL && codec.decodeTool(prepared.request().content()).continuation().isPresent();
        if (prepared.ordinal() != (continuing ? before.ordinal() : Math.incrementExact(before.ordinal()))) throw invalid();
        var snapshot = codec.decodeOutcome(actual.result().content());
        var configuration = before.configuration();
        Optional<ExecutionStore.WaitDeclaration> waiting = Optional.empty();
        List<ExecutionStore.Event> events = List.of();
        Materials after;
        if (prepared.kind() == ExecutionStore.Kind.MODEL) {
            if (before.pendingTool().isPresent() || before.pendingHuman().isPresent()) throw invalid();
            var modelRequest = codec.decodeModelRequest(prepared); var request = modelRequest.request();
            var expectedMessages = before.messages();
            if (before.decision().isPresent()) {
                var prior = before.decision().orElseThrow();
                if (prior instanceof AgentDecisionDecoder.DecodedBatch) expectedMessages = batches.reuseMessages(before);
                else if (prior instanceof AgentDecisionDecoder.Decoded decoded && decoded.action() instanceof AgentAction.ToolCall call) {
                    var resolved = loop.resolveTool(call, loadedTools(configuration), before.completedTools(), currentRead(before, 0), before.modelOrdinal(), 1);
                    if (!(resolved instanceof AgentExecutionLoop.LoadedToolReuse reuse)) throw invalid();
                    expectedMessages = replayMessages(run, before, reuse);
                } else if (prior instanceof AgentDecisionDecoder.Decoded decoded && decoded.action() instanceof AgentAction.KnowledgeAccess query) {
                    var original = reusedKnowledge(before, query).orElseThrow(AgentExecutionProgram::invalid);
                    expectedMessages = KnowledgeExecution.append(run.identity().runRef(),before, knowledgeRequest(run, before, query), original.bundle(), original.injected(), limits);
                } else throw invalid();
            }
            var progressSamples = pendingProgress(run, before);
            var progressDecision = AgentToolProgress.evaluate(progressSamples, before.steering().appliedThrough());
            if (progressDecision.level() == AgentToolProgress.Level.STOP
                    || !modelRequest.progress().equals(Optional.of(AgentToolProgress.encode(progressDecision, codec)))) throw invalid();
            expectedMessages = AgentToolProgress.messages(expectedMessages, progressDecision, prepared.ordinal());
            expectedMessages = planInstructions(before, expectedMessages);
            expectedMessages = List.copyOf(sources.modelContext(run, configuration, expectedMessages, Optional.of(request.messages())));
            if(modelRequest.output().isPresent()&&modelRequest.output().orElseThrow().correctionsUsed()!=before.output().map(AgentExecutionOutputs.Directive::correctionsUsed).orElse(0))throw invalid();
            expectedMessages = finalAnswerInstructions(configuration, expectedMessages, modelRequest.output());
            expectedMessages=AgentExecutionBudgetContext.restore(expectedMessages,request.messages(),prepared.ordinal());
            expectedMessages=AgentExecutionOutputs.messages(expectedMessages,modelRequest.output(),prepared.ordinal(),codec);
            var originalTime=AgentExecutionTime.frozen(request.messages(),prepared.ordinal());
            var originalModelMessages=AgentExecutionTime.append(expectedMessages,originalTime,prepared.ordinal());
            var restoredView=AgentExecutionContextViews.restore(originalModelMessages,request.messages(),before.context(),prepared.ordinal(),codec);
            modelRequest.requireCorrespondence(AgentExecutionToolPreviews.restore(restoredView,request.messages(),before.context(),prepared.ordinal(),codec));
            // 活跃历史保留原媒体来源；引用替换只来自该实际冻结请求。
            expectedMessages=AgentExecutionContextViews.restore(expectedMessages,request.messages(),before.context(),prepared.ordinal(),codec);
            if (!request.target().equals(configuration.target())
                    || !request.tools().equals(modelTools(configuration, modelRequest.output()))
                    || request.toolChoice() != (AgentExecutionOutputs.finalAnswerOnly(modelRequest.output()) || request.tools().isEmpty() ? ToolChoice.NONE : ToolChoice.AUTO)
                    || modelRequest.supplementalIncludedThrough() != before.steering().appliedThrough()) throw invalid();
            // 派生媒体只属于本次实际请求；活跃历史仍保留原source，后续模型不能从上轮缩略图再次派生。
            var result = core.decodeModelOutcome(snapshot, configuration.profile().callKind());
            var usage = before.usage().nextBusinessModelCall();
            Optional<ModelResult.Completed> model = Optional.empty(); Optional<AgentDecisionDecoder.Result> decision = Optional.empty();
            Optional<Problem> problem;
            Optional<PendingHuman> pending = Optional.empty();
            var skillState = before.skills();
            var outputState = modelRequest.output();
            Optional<String> outputRejection = Optional.empty();
            if (result instanceof ModelResult.Completed completed) {
                if (actual.kind() != ExecutionStore.ResultKind.SUCCEEDED || !completed.effectiveModelId().equals(request.target().effectiveModelId())) throw invalid();
                usage = usage.recordModelUsage(completed.usage()); model = Optional.of(completed);
                if(request.response().format() instanceof ModelPort.ResponseFormat.NativeConversation) {
                    var interpreted=nativeResponses.interpret(run.identity().runRef(),completed,request,before.knowledge(),expectedMessages);
                    decision=interpreted.decision();problem=interpreted.problem().map(reason->new Problem(ProblemKind.FAILED,reason));
                } else {
                    problem = switch (completed.finishReasonCode()) {
                        case "length" -> Optional.of(new Problem(ProblemKind.FAILED, "model_output_length"));
                        case "content_filter" -> Optional.of(new Problem(ProblemKind.FAILED, "model_content_filtered"));
                        default -> Optional.empty();
                    };
                    if (problem.isEmpty()) decision = Optional.of(loop.decodeModel(completed.output()));
                }
                var decodedAction=decision;
                var rejected=decodedAction.filter(action->!(action instanceof AgentDecisionDecoder.Failure)).flatMap(action->modelRequest.output().flatMap(directive->AgentExecutionOutputs.rejection(directive,action)));
                outputRejection = rejected.map(value -> value.code());
                if(problem.isEmpty()&&rejected.isPresent()) {
                    if(modelRequest.output().orElseThrow().correctionsUsed()>=AgentExecutionOutputs.MAX_CORRECTIONS)problem=Optional.of(new Problem(ProblemKind.FAILED,"output_correction_limit"));
                    else {expectedMessages=AgentExecutionOutputs.correct(expectedMessages,completed,rejected.orElseThrow(),prepared.ordinal(),modelRequest.output().orElseThrow(),codec,request.response().format() instanceof ModelPort.ResponseFormat.NativeConversation);model=Optional.empty();decision=Optional.empty();
                        outputState=modelRequest.output().map(value->new AgentExecutionOutputs.Directive(value.policy(),Math.incrementExact(value.correctionsUsed())));}
                } else if (problem.isEmpty() && decision.orElseThrow() instanceof AgentDecisionDecoder.Decoded decoded && decoded.action() instanceof AgentAction.AskUser question) {
                    var reference = new ExecutionStore.WaitRef(prepared.action(), 1);
                    pending = Optional.of(new PendingHuman(reference, question.question()));
                    waiting = Optional.of(new ExecutionStore.WaitDeclaration(reference, ExecutionStore.WaitKind.HUMAN, Map.of(), Optional.empty()));
                    events = List.of(waiting(run, reference, question.question()));
                } else if (problem.isEmpty() && decision.orElseThrow() instanceof AgentDecisionDecoder.Decoded decoded
                        && (decoded.action() instanceof AgentAction.ActivateSkill || decoded.action() instanceof AgentAction.ReadSkillResource
                                && (!(request.response().format() instanceof ModelPort.ResponseFormat.NativeConversation)
                                    ||completed.assistantTurn().orElseThrow(AgentExecutionProgram::invalid).toolCalls().size()==1))) {
                    var transition = skills(configuration).apply(before.skills(), decoded.action());
                    var arguments = (com.fasterxml.jackson.databind.node.ObjectNode) SessionRuntimePayloadJson.parse(transition.arguments().canonicalJson(), limits);
                    decoded.commentary().ifPresent(value -> arguments.put("commentary", value));
                    boolean nativeConversation=request.response().format() instanceof ModelPort.ResponseFormat.NativeConversation;
                    var nativeCallId=nativeConversation?completed.assistantTurn().orElseThrow(AgentExecutionProgram::invalid).toolCalls().getFirst().callId():callId(prepared.ordinal());
                    var assistant = SessionNativeResponses.assistant(completed,prepared.ordinal(),nativeConversation,
                            List.of(new ModelToolCall(nativeCallId,transition.wireName(),codec.encode("application/json",arguments))),decoded.commentary().orElse(""));
                    var feedback = new ModelMessage("execution-skill-" + prepared.ordinal(), ModelRole.TOOL, transition.feedback(), List.of(), Optional.of(nativeCallId), Optional.empty());
                    expectedMessages = ModelConversation.append(expectedMessages, assistant, List.of(feedback));
                    skillState = transition.state(); model = Optional.empty(); decision = Optional.empty();
                }
            } else if (result instanceof ModelResult.Failed failed) {
                if (actual.kind() != ExecutionStore.ResultKind.FAILED) throw invalid();
                usage = usage.recordModelUsage(new ModelPort.ModelUsage(OptionalLong.empty(), OptionalLong.empty()));
                problem = Optional.of(new Problem(ProblemKind.FAILED, failed.failure().reasonCode()));
            } else {
                if (actual.kind() != ExecutionStore.ResultKind.UNKNOWN) throw invalid();
                usage = usage.recordModelUsage(new ModelPort.ModelUsage(OptionalLong.empty(), OptionalLong.empty()));
                problem = Optional.of(new Problem(ProblemKind.UNKNOWN, "model_outcome_unknown"));
            }
            after = new Materials(configuration, expectedMessages, usage, prepared.ordinal(), prepared.ordinal(), model, decision,
                    before.context(), before.completedTools(), problem, Optional.of(actual.result().reference()), Optional.empty(), pending, before.knowledge(), skillState, Optional.empty(),
                    new SteeringState(before.steering().appliedThrough(), modelRequest.supplementalIncludedThrough()), before.planState(), outputState, before.observationInput()).withProgress(before.progress());
            if (after.problem().isEmpty() && after.decision().orElse(null) instanceof AgentDecisionDecoder.DecodedBatch
                    && batches.select(after, sources.elapsed(run)) instanceof AgentExecutionToolBatches.Invalid rejected) {
                after = new Materials(configuration, expectedMessages, usage, prepared.ordinal(), prepared.ordinal(), model, decision,
                        before.context(), before.completedTools(), Optional.of(new Problem(ProblemKind.FAILED, rejected.reason())), Optional.of(actual.result().reference()), Optional.empty(), pending, before.knowledge(), skillState, Optional.empty(),
                        new SteeringState(before.steering().appliedThrough(), modelRequest.supplementalIncludedThrough()), before.planState(), outputState, before.observationInput()).withProgress(before.progress());
            }
            boolean toolDecision = decision.orElse(null) instanceof AgentDecisionDecoder.DecodedBatch
                    || decision.orElse(null) instanceof AgentDecisionDecoder.Decoded decoded && decoded.action() instanceof AgentAction.ToolCall;
            after = after.withProgress(toolDecision ? progressSamples : List.of());
            var admitted = rejectInvalidToolDecision(after);
            if (admitted != after) outputRejection = Optional.of(after.decision().orElse(null) instanceof AgentDecisionDecoder.Failure failure
                    && (failure.reason() == AgentDecisionDecoder.Reason.TOOL_NOT_AVAILABLE || failure.reason() == AgentDecisionDecoder.Reason.CONTROL_ACTION_REQUIRES_SINGLE_CALL)
                    ? failure.reason().name().toLowerCase(java.util.Locale.ROOT) : "tool_arguments_invalid");
            after = admitted;
            admitted = rejectUnadmittedPlanAction(after);
            if (admitted != after) outputRejection = Optional.of("plan_action_rejected");
            after = admitted;
            if(result instanceof ModelResult.Completed completed && request.response().format() instanceof ModelPort.ResponseFormat.NativeConversation
                    && after.problem().isEmpty() && after.decision().isPresent() && after.decision().filter(AgentDecisionDecoder.Failure.class::isInstance).isEmpty()) {
                var reads=nativeResponses.resourceReads(completed,request.tools());
                if(!reads.isEmpty()) {
                    boolean group=completed.assistantTurn().orElseThrow(AgentExecutionProgram::invalid).toolCalls().size()>1;
                    Optional<String> rejection=Optional.empty();
                    // 只有冻结的普通只读工具可与资源共轮；真正状态控制仍由原生解码整组拒绝。
                    if(group && decision.orElse(null) instanceof AgentDecisionDecoder.Decoded single && single.action() instanceof AgentAction.ToolCall call) {
                        var entry=SessionToolDirectory.requirePrepared(configuration.tools(),call.toolHandle());
                        if(entry.target().effect()!=com.skillforge.runtime.api.ToolEffect.READ_ONLY
                                ||entry.target().confirmation()!=com.skillforge.runtime.api.port.ToolPortal.ToolConfirmationPolicy.NEVER||!entry.target().modelInvocable())
                            rejection=Optional.of("tool_batch_ineligible");
                    }
                    if(group && rejection.isEmpty())for(var read:reads) {
                        var resource=SkillActivation.readResource(read.action().skillHandle(),read.action().path(),configuration.skills(),configuration.availableCapabilities(),before.skills().activation());
                        if(resource.status()!=SkillActivation.ResourceStatus.LOADED) { rejection=Optional.of("skill_resource_batch_rejected");break; }
                    }
                    var nextSkills=before.skills();
                    if(rejection.isEmpty())for(var read:reads)nextSkills=skills(configuration).apply(nextSkills,read.action()).state();
                    boolean resourcesOnly=decision.orElse(null) instanceof AgentDecisionDecoder.Decoded single && single.action() instanceof AgentAction.ReadSkillResource;
                    var readMessages=expectedMessages;
                    if(rejection.isEmpty() && resourcesOnly) {
                        var assistant=SessionNativeResponses.assistant(after,List.of());
                        readMessages=ModelConversation.append(expectedMessages,assistant,SessionNativeResponses.toolResults(after,List.of(),limits));
                    }
                    after=new Materials(configuration,readMessages,usage,prepared.ordinal(),prepared.ordinal(),
                            resourcesOnly&&rejection.isEmpty()?Optional.empty():model,resourcesOnly&&rejection.isEmpty()?Optional.empty():decision,
                            before.context(),before.completedTools(),rejection.map(reason->new Problem(ProblemKind.FAILED,reason)),Optional.of(actual.result().reference()),Optional.empty(),pending,
                            before.knowledge(),nextSkills,Optional.empty(),new SteeringState(before.steering().appliedThrough(),modelRequest.supplementalIncludedThrough()),before.planState(),outputState,before.observationInput()).withProgress(after.progress());
                }
            }
            var modelEvents = new ArrayList<>(events); modelEvents.add(modelResult(run, prepared, actual.kind())); events = List.copyOf(modelEvents);
            var observed = new ArrayList<>(events);
            observed.addAll(observations.operation(run, before, prepared, observations.attempt(prepared), AgentExecutionObservations.status(actual.kind())));
            if (result instanceof ModelResult.Completed)
                observed.add(observations.guard(run, before, prepared, observations.attempt(prepared), ExecutionObservation.Stage.MODEL_OUTPUT,
                        after.problem().isEmpty() && outputRejection.isEmpty() && (after.decision().isEmpty() || !(after.decision().orElseThrow() instanceof AgentDecisionDecoder.Failure))
                                ? ExecutionObservation.Status.SUCCEEDED : ExecutionObservation.Status.REJECTED,
                        after.problem().map(Problem::reason).orElse(outputRejection.orElse(after.decision().filter(AgentDecisionDecoder.Failure.class::isInstance)
                                .map(ignored -> "model_decision_invalid").orElse(null)))));
            events = List.copyOf(observed);
        } else if (prepared.kind() == ExecutionStore.Kind.TOOL) {
            var request = codec.decodeTool(prepared.request().content());
            if (continuing) checkContinuation(before.pendingTool().orElseThrow(AgentExecutionProgram::invalid), prepared, request);
            else if (before.pendingTool().isPresent() || before.pendingHuman().isPresent()) throw invalid();
            var entry = SessionToolDirectory.requirePrepared(configuration.tools(), request.target().handle().value());
            var modelTool = configuration.modelTools().stream().filter(tool -> tool.toolHandle().equals(request.target().handle().value())
                    && tool.kind() == ModelPort.ModelToolKind.TOOL_CALL).findFirst().orElseThrow(AgentExecutionProgram::invalid);
            if (!entry.target().equals(request.target()) || !entry.binding().equals(request.binding())
                    || !modelTool.wireName().equals(request.wireName())) throw invalid();
            var outcome = core.decodeToolOutcomeForInspection(snapshot, PortalCallKind.TOOL_INVOKE, payloads);
            Optional<TypedPayload> expected;
            boolean outputRejected = false;
            try {
                expected = codec.safeToolFeedback(request, outcome);
            } catch (AgentExecutionCodec.ToolOutputRejected rejected) {
                // 外部效果已确定成功，仅本地输出契约拒绝；仍采用原私有结果，不再请求模型或重调Handler。
                if (!(outcome instanceof ToolOutcome.Succeeded) || actual.kind() != ExecutionStore.ResultKind.SUCCEEDED
                        || actual.feedback().isPresent()) throw invalid();
                expected = Optional.empty(); outputRejected = true;
            }
            if (!expected.equals(actual.feedback().map(Material::content))) throw invalid();
            var usage = before.usage().nextToolCall();
            var messages = before.messages(); var completed = new ArrayList<>(before.completedTools());
            Optional<Problem> problem = Optional.empty();
            Optional<PendingTool> pending = Optional.empty();
            var contentRejection = outcome instanceof ToolOutcome.Succeeded success
                    ? sources.capabilityContentRejection(run, List.of(success.observation())) : Optional.<String>empty();
            if (outputRejected) {
                problem = Optional.of(new Problem(ProblemKind.FAILED, "tool_output_invalid"));
            } else if (contentRejection.isPresent()) {
                problem = Optional.of(new Problem(ProblemKind.FAILED, contentRejection.orElseThrow()));
            } else if (outcome instanceof ToolOutcome.Succeeded || outcome instanceof ToolOutcome.Failed) {
                var expectedKind = outcome instanceof ToolOutcome.Succeeded ? ExecutionStore.ResultKind.SUCCEEDED : ExecutionStore.ResultKind.FAILED;
                if (actual.kind() != expectedKind) throw invalid();
                var feedback = actual.feedback().orElseThrow();
                var contents = codec.toolContents(feedback.content());
                messages = appendTool(run, before, request, contents);
                var frozen = new AgentExecutionLoop.LoadedTool(new ToolCallRouting.Binding(entry.target(), entry.binding(), entry.parallelSafe()), request.wireName());
                completed.add(new AgentExecutionLoop.ToolObservation(new AgentAction.ToolCall(request.target().handle().value(), request.arguments()), frozen,
                        outcome instanceof ToolOutcome.Succeeded ? AgentExecutionLoop.ToolResolution.SUCCEEDED : AgentExecutionLoop.ToolResolution.FAILED,
                        Optional.of(feedback.reference()), contents, new AgentExecutionLoop.ToolSource(prepared.action(),
                                prepared.action().actionId() + ":attempt:" + request.continuation().map(value -> Math.incrementExact(value.waiting().generation())).orElse(1L),
                                prepared.request().reference(), actual.result().reference()), currentRead(before, 0), before.modelOrdinal(), 1));
            } else if (outcome instanceof ToolOutcome.InputRequired input) {
                if (actual.kind() != ExecutionStore.ResultKind.INPUT_REQUIRED) throw invalid();
                long generation = request.continuation().map(value -> Math.incrementExact(value.waiting().generation())).orElse(1L);
                var reference = new ExecutionStore.WaitRef(prepared.action(), generation);
                var original = new ToolRequest(request.target(), request.binding(), request.arguments(), request.callId(), request.wireName());
                pending = Optional.of(new PendingTool(reference, original, actual.result().reference(), input));
                waiting = Optional.of(new ExecutionStore.WaitDeclaration(reference, ExecutionStore.WaitKind.TOOL, methods(input), Optional.empty()));
                events = List.of(waiting(run, reference, input));
            } else {
                if (actual.kind() != ExecutionStore.ResultKind.UNKNOWN) throw invalid();
                problem = Optional.of(new Problem(ProblemKind.UNKNOWN, "tool_outcome_unknown"));
            }
            if (!(outcome instanceof ToolOutcome.InputRequired)) events = List.of(toolResult(run, prepared, request, actual.kind(), outputRejected || contentRejection.isPresent(), outcome));
            var observed = new ArrayList<>(events);
            observed.addAll(observations.operation(run, before, prepared, observations.attempt(prepared), AgentExecutionObservations.status(actual.kind())));
            if (outcome instanceof ToolOutcome.Succeeded || outcome instanceof ToolOutcome.Failed)
                observed.add(observations.guard(run, before, prepared, observations.attempt(prepared), ExecutionObservation.Stage.TOOL_OUTPUT,
                        outputRejected || contentRejection.isPresent() ? ExecutionObservation.Status.REJECTED : ExecutionObservation.Status.SUCCEEDED,
                        outputRejected ? "tool_output_invalid" : contentRejection.orElse(null)));
            events = List.copyOf(observed);
            after = new Materials(configuration, messages, usage, prepared.ordinal(), before.modelOrdinal(),
                    pending.isPresent() ? before.model() : Optional.empty(), pending.isPresent() ? before.decision() : Optional.empty(),
                    before.context(), completed, problem, Optional.of(actual.result().reference()), pending, Optional.empty(), before.knowledge(), before.skills(), Optional.empty(), before.steering(), before.planState(), before.output(), observations.input(before, prepared)).withProgress(before.progress());
            if (problem.isEmpty() && pending.isEmpty() && before.steering().appliedThrough() == before.steering().modelIncludedThrough()
                    && (outcome instanceof ToolOutcome.Succeeded || outcome instanceof ToolOutcome.Failed))
                after = after.withProgress(appendProgress(run, before, before.progress(), completed.getLast(), 0, outcome, progressResources(before)));
        } else throw invalid();
        List<Boolean> reads = before.currentReads();
        if (prepared.kind() == ExecutionStore.Kind.MODEL) {
            var calls = toolCalls(after);
            if (restoring) {
                reads = restoredReads.orElseGet(() -> java.util.Collections.nCopies(calls.size(), false));
                if (reads.size() != calls.size()) throw invalid();
            } else {
                boolean allKnown = calls.stream().allMatch(call -> configuration.tools().stream()
                        .anyMatch(entry -> entry.target().handle().value().equals(call.toolHandle())));
                boolean validBatch = calls.isEmpty() || !(after.decision().orElse(null) instanceof AgentDecisionDecoder.DecodedBatch)
                        || batches.validForClassification(after);
                reads = allKnown && validBatch ? calls.stream().map(call -> sources.currentRead(SessionToolDirectory.requirePrepared(configuration.tools(), call.toolHandle()), call.arguments())).toList()
                        : java.util.Collections.nCopies(calls.size(), false);
            }
        }
        after = after.withCurrentReads(reads);
        var context = prepared.kind() == ExecutionStore.Kind.MODEL && (!restoring || restoredReads.isPresent())
                ? modelContext(before.context(), prepared, actual, reads) : context(before.context(), prepared, actual);
        after = new Materials(after.configuration(), after.messages(), after.usage(), after.ordinal(), after.modelOrdinal(), after.model(), after.decision(),
                context.reference(), after.completedTools(), after.problem(), after.lastResult(), after.pendingTool(), after.pendingHuman(), after.knowledge(), after.skills(), after.pendingDependency(), after.steering(), after.planState(), after.output(), after.observationInput()).withProgress(after.progress()).withCurrentReads(after.currentReads());
        return new Adoption<>(after, new ExecutionStore.Progress(after.usage().businessModelCalls(), after.usage().toolCalls(), after.usage().knowledgeCalls(), context.reference()), context, events, waiting);
    }

    /** 已通过整组预检才登记所有新槽；完整复用直接进入下一真实模型请求。 */
    private Step batch(Run run, Materials before) {
        var selected = batches.select(before, sources.elapsed(run));
        if (selected instanceof AgentExecutionToolBatches.Limited limited) return finish(run, RunStatus.TERMINATED, "", limited.limit().name(), before.lastResult());
        if (selected instanceof AgentExecutionToolBatches.Rejected rejected) return rejectedDecision(run, before, rejected.reason());
        if (selected instanceof AgentExecutionToolBatches.Invalid rejected) return finish(run, RunStatus.FAILED, "", rejected.reason(), before.lastResult());
        var slots = ((AgentExecutionToolBatches.Ready) selected).slots();
        if (slots.stream().allMatch(slot -> slot.reuse().isPresent())) return model(run, before, batches.reuseMessages(before));
        var members = new ArrayList<ExecutionStore.BatchMember>(); long ordinal = before.ordinal();
        for (var slot : slots) {
            if (slot.reuse().isPresent()) {
                var original = slot.reuse().orElseThrow(); var source = original.source();
                members.add(new ExecutionStore.BatchMember.Reused(slot.slot().index(), source.action(), source.attemptId(), source.request(), source.result(), original.feedback().orElseThrow()));
            } else {
                ordinal = Math.incrementExact(ordinal);
                var prepared = prepare(run, before, ExecutionStore.Kind.TOOL, ordinal, material(codec.encodeTool(slot.slot().request())));
                var call = dispatch(run, before, prepared, ExecutionBudgetPort.Category.TOOL, ExecutionBudgetPort.Resources.call(ExecutionBudgetPort.Category.TOOL, 0, 0));
                members.add(new ExecutionStore.BatchMember.Fresh(slot.slot().index(), new ExecutionStore.BatchRequest(call.action(), call.attemptId(), call.budget())));
            }
        }
        return new Step.Batch(new ActionRef(run.identity().runRef().tenantId(), "execution:" + run.identity().runRef().runId() + ":" + before.modelOrdinal()), members, before.configuration().actor());
    }

    /** 同一组只读调用仅在全部冻结目标显式允许时并行。 */
    @Override public boolean batchParallel(Materials materials) {
        return batches.slots(materials).stream().allMatch(slot -> slot.tool().binding().parallelSafe());
    }

    /** 各槽真实结果即时独立落账，等待与公开process事实同次提交；不形成部分模型上下文。 */
    @Override public BatchResult inspectBatch(Run run, Materials before, BatchCall actual, int slot) {
        var inspected = batches.inspect(run, before, actual, slot);
        if (inspected.outcome() instanceof ToolOutcome.InputRequired input) {
            var reference = new ExecutionStore.WaitRef(actual.action().action(), batches.physicalCalls(actual));
            var events = new ArrayList<ExecutionStore.Event>();
            events.add(waiting(run, reference, input));
            events.addAll(observations.operation(run, before, actual.action(), actual.attemptId(), ExecutionObservation.Status.WAITING));
            return new BatchResult(Optional.of(new ExecutionStore.WaitDeclaration(reference, ExecutionStore.WaitKind.TOOL, methods(input), Optional.empty())), events);
        }
        var rejected=inspected.outcome() instanceof ToolOutcome.Succeeded success?sources.capabilityContentRejection(run,List.of(success.observation())):Optional.<String>empty();
        var events = new ArrayList<ExecutionStore.Event>();
        events.add(toolResult(run, actual.action(), inspected.slot().request(), actual.result().kind(), inspected.outputRejected() || rejected.isPresent(), inspected.outcome()));
        events.addAll(observations.operation(run, before, actual.action(), actual.attemptId(), AgentExecutionObservations.status(actual.result().kind())));
        if (inspected.outcome() instanceof ToolOutcome.Succeeded || inspected.outcome() instanceof ToolOutcome.Failed)
            events.add(observations.guard(run, before, actual.action(), actual.attemptId(), ExecutionObservation.Stage.TOOL_OUTPUT,
                    inspected.outputRejected() || rejected.isPresent() ? ExecutionObservation.Status.REJECTED : ExecutionObservation.Status.SUCCEEDED,
                    inspected.outputRejected() ? "tool_output_invalid" : rejected.orElse(null)));
        return new BatchResult(Optional.empty(), events);
    }

    /** 所有槽准确后一次性采纳有序结果；输入等待或UNKNOWN均不能追加半条原生回合。 */
    @Override public Adoption<Materials> adoptBatch(Run run, Materials before, List<BatchOutcome> results) {
        return applyBatch(run, before, results, Optional.empty());
    }

    /** 所有等待已有精确回复才准备新Attempt；不同回复身份各由Store复验，不借用另一回复的actor。 */
    @Override public BatchPlan<Materials> batchContinuation(Run run, Materials before, List<BatchOutcome> results, List<BatchResponse> responses) {
        if (run.phase() != ExecutionStore.Phase.READY || run.currentBatch().isEmpty() || run.waiting().isPresent()) throw invalid();
        var ordered = orderedBatch(before, results); var pending = new ArrayList<Step.Dispatch>();
        var usage = batchUsage(before, ordered); var supplied = new HashSet<ExecutionStore.WaitRef>();
        for (var outcome : ordered) {
            if (outcome instanceof BatchOutcome.Reused reuse) { batches.reused(before, reuse.source()); continue; }
            var actual = ((BatchOutcome.Actual) outcome).call(); validateBatchContinuation(run, actual.action());
            var inspected = batches.inspect(run, before, actual, outcome.slot());
            if (!(inspected.outcome() instanceof ToolOutcome.InputRequired input)) {
                if (!(inspected.outcome() instanceof ToolOutcome.Succeeded || inspected.outcome() instanceof ToolOutcome.Failed)) throw invalid();
                continue;
            }
            var matching = responses.stream().filter(value -> value.waiting().reference().action().equals(actual.action().action())).toList();
            if (matching.size() != 1) throw invalid();
            var response = matching.getFirst(); var waiting = response.waiting(); var accepted = waiting.acceptedResponse().orElseThrow(AgentExecutionProgram::invalid);
            if (!supplied.add(waiting.reference()) || !waiting.reference().equals(new ExecutionStore.WaitRef(actual.action().action(), batches.physicalCalls(actual)))
                    || !waiting.run().equals(run.identity().runRef()) || !waiting.sourceAttempt().equals(actual.attemptId())
                    || waiting.kind() != ExecutionStore.WaitKind.TOOL || waiting.internal().isPresent() || waiting.continuationAttempt().isPresent()
                    || waiting.consumedRevision().isPresent() || !waiting.requested().equals(methods(input))
                    || !accepted.response().equals(response.response().reference()) || accepted.acceptedRevision() > run.revision()) throw invalid();
            var values = codec.decodeToolResponses(response.response().content());
            if (!values.keySet().equals(waiting.requested().keySet())) throw invalid();
            values.forEach((key, value) -> { if (value.method() != waiting.requested().get(key)) throw invalid(); });
            var limited = BudgetGuard.evaluate(before.configuration().budget(), usage, BudgetGuard.Operation.TOOL, sources.elapsed(run));
            if (limited.isPresent()) {
                String reason = limited.orElseThrow().name(); var stopped = applyBatch(run, before, ordered, Optional.of(reason));
                var finish = finish(run, RunStatus.TERMINATED, "", reason, stopped.materials().lastResult());
                return new BatchPlan.Terminate<>(new Adoption<>(stopped.materials(), stopped.next(), stopped.context(), finish.events(), Optional.empty()), RunStatus.TERMINATED);
            }
            usage = usage.nextToolCall(); var original = inspected.slot().request();
            var request = new ToolRequest(original.target(), original.binding(), original.arguments(), original.callId(), original.wireName(),
                    Optional.of(new ToolContinuation(waiting.reference(), actual.result().result().reference(), response.response().reference(), values, input.requestState(), accepted.input(), accepted.acceptedRevision())));
            var prepared = prepare(run, before, ExecutionStore.Kind.TOOL, actual.action().ordinal(), material(codec.encodeTool(request)));
            String attempt = prepared.action().actionId() + ":attempt:" + Math.incrementExact(waiting.reference().generation());
            pending.add(new Step.Dispatch(prepared, attempt, new ExecutionBudgetPort.Demand(run.identity().runRef(), attempt, prepared.fingerprint(),
                    ExecutionBudgetPort.Category.TOOL, ExecutionBudgetPort.Resources.call(ExecutionBudgetPort.Category.TOOL, 0, 0), false), before.configuration().actor(), Optional.of(waiting.reference()), List.of()));
        }
        if (supplied.size() != responses.size() || pending.isEmpty()) throw invalid();
        return new BatchPlan.Resume<>(pending);
    }

    /** 完整native回合或明确预算终止都只保存原slot的精确引用；终止不会追加半组模型消息。 */
    private Adoption<Materials> applyBatch(Run run, Materials before, List<BatchOutcome> results, Optional<String> stoppedReason) {
        var slots = batches.slots(before);
        if (results.size() != slots.size()) throw invalid();
        var ordered = orderedBatch(before, results);
        var contents = new ArrayList<List<ModelContent>>(); var completed = new ArrayList<>(before.completedTools()); var usage = batchUsage(before, ordered);
        var progressSamples = before.progress(); var resources = progressResources(before);
        boolean rejected = false; Optional<String> contentRejection = Optional.empty(); long ordinal = before.ordinal(); var last = before.lastResult();
        for (int index = 0; index < ordered.size(); index++) {
            var outcome = ordered.get(index);
            if (outcome instanceof BatchOutcome.Reused reuse) {
                var original = batches.reused(before, reuse.source()); contents.add(AgentExecutionCodec.reusedToolContents(original.contents()));
                if (before.steering().appliedThrough() == before.steering().modelIncludedThrough())
                    progressSamples = appendProgress(run, before, progressSamples, original, index, progressOutcome(run, original), resources);
                continue;
            }
            var actual = ((BatchOutcome.Actual) outcome).call(); validateBatchContinuation(run, actual.action());
            var inspected = batches.inspect(run, before, actual, index + 1);
            if (!(inspected.outcome() instanceof ToolOutcome.Succeeded || inspected.outcome() instanceof ToolOutcome.Failed)
                    && !(stoppedReason.isPresent() && inspected.outcome() instanceof ToolOutcome.InputRequired)) throw invalid();
            var rejectedContent = inspected.outcome() instanceof ToolOutcome.Succeeded success ? sources.capabilityContentRejection(run, List.of(success.observation())) : Optional.<String>empty();
            if (contentRejection.isEmpty()) contentRejection = rejectedContent;
            contents.add(inspected.contents()); rejected |= inspected.outputRejected() || rejectedContent.isPresent(); ordinal = Math.max(ordinal, actual.action().ordinal()); last = Optional.of(actual.result().result().reference());
            if (stoppedReason.isEmpty() && !inspected.outputRejected() && rejectedContent.isEmpty()) completed.add(new AgentExecutionLoop.ToolObservation(inspected.slot().call(), inspected.slot().tool(),
                    actual.result().kind() == ExecutionStore.ResultKind.SUCCEEDED ? AgentExecutionLoop.ToolResolution.SUCCEEDED : AgentExecutionLoop.ToolResolution.FAILED,
                    actual.result().feedback().map(Material::reference), inspected.contents(), new AgentExecutionLoop.ToolSource(actual.action().action(), actual.attemptId(),
                            actual.action().request().reference(), actual.result().result().reference()), currentRead(before, index), before.modelOrdinal(), index + 1));
            if (stoppedReason.isEmpty() && !inspected.outputRejected() && rejectedContent.isEmpty()
                    && before.steering().appliedThrough() == before.steering().modelIncludedThrough())
                progressSamples = appendProgress(run, before, progressSamples, completed.getLast(), index, inspected.outcome(), resources);
        }
        var value = AgentExecutionCodec.object(); value.put("type", stoppedReason.isPresent() ? "tool-batch-stop" : "tool-batch"); value.put("previous", before.context().value());
        stoppedReason.ifPresent(reason -> value.put("reason", reason));
        var entries = value.putArray("results");
        for (var outcome : ordered) {
            var entry = entries.addObject(); entry.put("slot", Integer.toString(outcome.slot())); entry.put("reused", outcome instanceof BatchOutcome.Reused);
            if (outcome instanceof BatchOutcome.Reused reuse) {
                var source = reuse.source(); entry.put("ordinal", "0"); entry.put("action", source.action().actionId()); entry.put("attempt", source.attemptId());
                entry.put("request", source.request().value()); entry.put("result", source.result().value()); entry.put("feedback", source.feedback().value());
            } else {
                var actual = ((BatchOutcome.Actual) outcome).call(); entry.put("ordinal", Long.toString(actual.action().ordinal())); entry.put("action", actual.action().action().actionId()); entry.put("attempt", actual.attemptId());
                entry.put("request", actual.action().request().reference().value()); entry.put("result", actual.result().result().reference().value());
                if (actual.result().feedback().isPresent()) entry.put("feedback", actual.result().feedback().orElseThrow().reference().value()); else entry.putNull("feedback");
            }
        }
        var context = material(codec.encode(CONTEXT_SCHEMA, value));
        var observationInput = ordered.stream().filter(BatchOutcome.Actual.class::isInstance)
                .map(BatchOutcome.Actual.class::cast)
                .map(outcome -> codec.decodeTool(outcome.call().action().request().content()).continuation())
                .flatMap(Optional::stream).max(java.util.Comparator.comparingLong(ToolContinuation::acceptedRevision))
                .map(ToolContinuation::inputId).orElse(before.observationInput());
        Optional<Problem> problem = stoppedReason.isPresent() ? Optional.of(new Problem(ProblemKind.FAILED, stoppedReason.orElseThrow()))
                : contentRejection.isPresent() ? Optional.of(new Problem(ProblemKind.FAILED, contentRejection.orElseThrow()))
                : rejected ? Optional.of(new Problem(ProblemKind.FAILED, "tool_output_invalid")) : Optional.empty();
        var after = new Materials(before.configuration(), rejected || stoppedReason.isPresent() ? before.messages() : batches.append(before, contents), usage,
                ordinal, before.modelOrdinal(), Optional.empty(), Optional.empty(), context.reference(), completed,
                problem, last,
                Optional.empty(), Optional.empty(), before.knowledge(), before.skills(), Optional.empty(), before.steering(), before.planState(), before.output(), observationInput).withProgress(progressSamples);
        return new Adoption<>(after, new ExecutionStore.Progress(usage.businessModelCalls(), usage.toolCalls(), usage.knowledgeCalls(), context.reference()), context, List.of(), Optional.empty());
    }

    private static List<BatchOutcome> orderedBatch(Materials before, List<BatchOutcome> results) {
        var ordered = results.stream().sorted(java.util.Comparator.comparingInt(BatchOutcome::slot)).toList();
        if (!(before.decision().orElseThrow(AgentExecutionProgram::invalid) instanceof AgentDecisionDecoder.DecodedBatch batch) || ordered.size() != batch.calls().size()) throw invalid();
        for (int index = 0; index < ordered.size(); index++) if (ordered.get(index).slot() != index + 1) throw invalid();
        return ordered;
    }

    private LoopUsage batchUsage(Materials before, List<BatchOutcome> results) {
        int count = before.usage().toolCalls();
        for (var result : results) if (result instanceof BatchOutcome.Actual actual) count = Math.addExact(count, Math.toIntExact(batches.physicalCalls(actual.call())));
        return new LoopUsage(before.usage().businessModelCalls(), count, before.usage().knowledgeCalls(), before.usage().inputTokens(), before.usage().outputTokens());
    }

    /** 恢复只查续跑请求明确引用的原结果与原回复，不依靠当前工具配置或外部重查。 */
    private void validateBatchContinuation(Run run, Prepared prepared) {
        var request = codec.decodeTool(prepared.request().content());
        request.continuation().ifPresent(value -> {
            var source = requireMaterial(run, value.sourceResult()); var response = requireMaterial(run, value.response());
            var outcome = core.decodeToolOutcomeForInspection(codec.decodeOutcome(source.content()), PortalCallKind.TOOL_INVOKE, payloads);
            if (!(outcome instanceof ToolOutcome.InputRequired input) || !value.waiting().action().equals(prepared.action())
                    || !value.requestState().equals(input.requestState()) || !codec.decodeToolResponses(response.content()).equals(value.responses())
                    || !value.responses().keySet().equals(methods(input).keySet())) throw invalid();
            value.responses().forEach((key, actual) -> { if (actual.method() != input.inputRequests().get(key).method()) throw invalid(); });
        });
    }

    /** 委派每个新模型Action都是一条真实业务命令；当前IAM和领域幂等归Host及Store。 */
    private Step delegation(Run run, Materials before, AgentAction.Delegate delegate) {
        var request = delegationRequest(before, delegate);
        var limited = BudgetGuard.evaluate(before.configuration().budget(), before.usage(), BudgetGuard.Operation.TOOL, sources.elapsed(run));
        if (limited.isPresent()) return finish(run, RunStatus.TERMINATED, "", limited.orElseThrow().name(), before.lastResult());
        var prepared = prepare(run, before, ExecutionStore.Kind.DELEGATION, Math.incrementExact(before.ordinal()), material(delegations.encodeRequest(request)));
        return dispatch(run, before, prepared, ExecutionBudgetPort.Category.TOOL, ExecutionBudgetPort.Resources.call(ExecutionBudgetPort.Category.TOOL, 0, 0));
    }

    /** 原决定和冻结声明共同确定正式控制工具；不能用模型参数新增目标目录或授权。 */
    private SessionExecutionDelegations.Request delegationRequest(Materials before, AgentAction.Delegate delegate) {
        if (!before.configuration().profile().delegationEnabled()) throw invalid();
        String wireName = NativeAgentTools.wireName("delegate", Optional.of(delegate.command().operation().name().toLowerCase(java.util.Locale.ROOT)));
        var declaration = before.configuration().modelTools().stream().filter(tool -> tool.kind() == ModelPort.ModelToolKind.AGENT_ACTION && tool.wireName().equals(wireName))
                .findFirst().orElseThrow(AgentExecutionProgram::invalid);
        var arguments = delegationArguments(before, delegate);
        validateControlArguments(declaration, arguments);
        return new SessionExecutionDelegations.Request(delegate.command(), SessionNativeResponses.callId(before,0), wireName);
    }

    /** 委派业务回执不是PortalOutcome，采用所属显式codec并保留原typed结果。 */
    private Adoption<Materials> adoptDelegation(Run run, Materials before, Prepared prepared, CapabilityResult actual) {
        if (before.pendingTool().isPresent() || before.pendingHuman().isPresent() || before.pendingDependency().isPresent()
                || prepared.ordinal() != Math.incrementExact(before.ordinal()) || actual.kind() != ExecutionStore.ResultKind.SUCCEEDED) throw invalid();
        var decoded = before.decision().orElseThrow(AgentExecutionProgram::invalid);
        if (!(decoded instanceof AgentDecisionDecoder.Decoded decision) || !(decision.action() instanceof AgentAction.Delegate delegate)) throw invalid();
        var request = delegations.decodeRequest(prepared.request().content());
        if (!request.equals(delegationRequest(before, delegate)) || !prepared.equals(prepare(run, before, ExecutionStore.Kind.DELEGATION, prepared.ordinal(), prepared.request()))) throw invalid();
        var result = delegations.decodeResult(actual.result().content());
        if (!actual.feedback().map(Material::content).equals(Optional.of(result.value()))) throw invalid();
        Optional<PendingDependency> pending = Optional.empty(); Optional<ExecutionStore.WaitDeclaration> declaration = Optional.empty();
        List<ExecutionStore.Event> events = List.of(); var messages = before.messages();
        if (result.waiting().isPresent()) {
            var dependency = result.waiting().orElseThrow();
            if (dependency.children().stream().anyMatch(child -> !child.tenantId().equals(run.identity().runRef().tenantId()) || child.equals(run.identity().runRef()))) throw invalid();
            var waiting = new ExecutionStore.WaitRef(prepared.action(), 1);
            pending = Optional.of(new PendingDelegation(waiting, request, actual.result().reference(), dependency));
            declaration = Optional.of(new ExecutionStore.WaitDeclaration(waiting, ExecutionStore.WaitKind.DELEGATION, Map.of(), Optional.of(internal(dependency))));
            events = List.of(waiting(run, waiting, ExecutionStore.WaitKind.DELEGATION));
        } else messages = appendDelegation(before, request, result.value());
        var context = context(before.context(), prepared, actual); var usage = before.usage().nextToolCall();
        var after = new Materials(before.configuration(), messages, usage, prepared.ordinal(), before.modelOrdinal(),
                pending.isPresent() ? before.model() : Optional.empty(), pending.isPresent() ? before.decision() : Optional.empty(), context.reference(), before.completedTools(),
                Optional.empty(), Optional.of(actual.result().reference()), Optional.empty(), Optional.empty(), before.knowledge(), before.skills(), pending, before.steering(), before.planState(), before.output(), before.observationInput()).withProgress(before.progress());
        var observed = new ArrayList<>(events);
        observed.addAll(observations.operation(run, before, prepared, observations.attempt(prepared),
                pending.isPresent() ? ExecutionObservation.Status.WAITING : ExecutionObservation.Status.SUCCEEDED));
        events = List.copyOf(observed);
        return new Adoption<>(after, new ExecutionStore.Progress(usage.businessModelCalls(), usage.toolCalls(), usage.knowledgeCalls(), context.reference()), context, events, declaration);
    }

    /** 最终系统结果本地采纳，不再派发START/WAIT、不增加Provider Attempt或用量。 */
    private Adoption<Materials> adoptDependencyResponse(Run run, Materials before, ExecutionStore.WaitRef waiting, InputId input, Material response) {
        var dependency = before.pendingDependency().orElseThrow(AgentExecutionProgram::invalid);
        if (!dependency.waiting().equals(waiting) || before.problem().isPresent() || before.pendingTool().isPresent() || before.pendingHuman().isPresent()) throw invalid();
        final List<ModelMessage> messages; final ExecutionStore.WaitKind kind; var planState = before.planState();
        if (dependency instanceof PendingDelegation pending) {
            if (!before.lastResult().equals(Optional.of(pending.sourceResult()))) throw invalid();
            messages = appendDelegation(before, pending.request(), delegations.decodeResponse(response.content())); kind = ExecutionStore.WaitKind.DELEGATION;
        } else if (dependency instanceof PendingPlan pending) {
            if (!before.lastResult().equals(Optional.of(pending.sourceResult()))) throw invalid();
            var actual = plans.decodeResponse(response.content()); messages = appendPlan(before, pending.request(), actual.value()); planState = actual.state(); kind = ExecutionStore.WaitKind.PLAN;
        } else throw invalid();
        var data = AgentExecutionCodec.object(); data.put("type", "dependency-response"); data.put("previous", before.context().value()); data.put("kind", kind.name());
        data.put("tenantId", waiting.action().tenantId().value()); data.put("actionId", waiting.action().actionId()); data.put("generation", Long.toString(waiting.generation()));
        data.put("input", input.value()); data.put("response", response.reference().value()); var context = material(codec.encode(CONTEXT_SCHEMA, data));
        var after = new Materials(before.configuration(), messages, before.usage(), before.ordinal(), before.modelOrdinal(), Optional.empty(), Optional.empty(), context.reference(), before.completedTools(),
                Optional.empty(), before.lastResult(), Optional.empty(), Optional.empty(), before.knowledge(), before.skills(), Optional.empty(), before.steering(), planState, before.output(), input).withProgress(before.progress());
        List<ExecutionStore.Event> events = List.of();
        if (dependency instanceof PendingDelegation pending) {
            var prepared = prepare(run, before, ExecutionStore.Kind.DELEGATION, before.ordinal(), material(delegations.encodeRequest(pending.request())));
            events = observations.operation(run, after, prepared, observations.attempt(prepared), ExecutionObservation.Status.SUCCEEDED);
        }
        return new Adoption<>(after, new ExecutionStore.Progress(after.usage().businessModelCalls(), after.usage().toolCalls(), after.usage().knowledgeCalls(), context.reference()), context, events, Optional.empty());
    }

    /** 原模型assistant和正式native参数只追加一次，系统响应仍作为匹配的TOOL反馈。 */
    private List<ModelMessage> appendDelegation(Materials before, SessionExecutionDelegations.Request request, TypedPayload value) {
        var result = before.decision().orElseThrow(AgentExecutionProgram::invalid);
        if (!(result instanceof AgentDecisionDecoder.Decoded decoded) || !(decoded.action() instanceof AgentAction.Delegate delegate)
                || !request.equals(delegationRequest(before, delegate))) throw invalid();
        var assistant = SessionNativeResponses.assistant(before,List.of(new ModelToolCall(request.callId(), request.wireName(), delegationArguments(before, delegate))));
        var feedback = new ModelMessage("execution-delegation-" + before.modelOrdinal(), ModelRole.TOOL,
                List.of(new ModelContent.Text("委派操作回执：以下状态和结果属于对应子任务。受理或运行中不代表完成；结果未知时先核对原任务，不要重复启动。\n"+value.canonicalJson())), List.of(), Optional.of(request.callId()), Optional.empty());
        return ModelConversation.append(before.messages(), assistant, List.of(feedback));
    }

    /** 明确投影正式native字段，禁止丢掉无对应原生字段的额外任务结构。 */
    private TypedPayload delegationArguments(Materials before, AgentAction.Delegate delegate) {
        var command = delegate.command(); var arguments = AgentExecutionCodec.object();
        switch (command.operation()) {
            case START -> {
                var task = command.task().orElseThrow(AgentExecutionProgram::invalid);
                if (!task.context().isEmpty() || !task.constraints().isEmpty() || !task.expectedOutput().isEmpty()) throw invalid();
                arguments.put("target_handle", command.targetHandle()); arguments.put("instruction", task.objective()); arguments.put("background", command.background());
            }
            case WAIT -> { var ids = arguments.putArray("ids"); command.delegationIds().forEach(ids::add); }
            case GET -> { if (!command.delegationIds().isEmpty()) arguments.put("id", command.delegationIds().getFirst()); }
            case SEND -> { arguments.put("id", command.delegationIds().getFirst()); arguments.put("message", command.message()); }
            case CANCEL -> arguments.put("id", command.delegationIds().getFirst());
            case TARGETS, LIST -> { }
        }
        before.decision().orElseThrow().commentary().ifPresent(commentary -> arguments.put("commentary", commentary));
        return codec.encode("application/json", arguments);
    }

    /** 内部Wait只冻结领域依赖身份，不把任务正文或子配置复制进Run。 */
    private static ExecutionStore.DelegationWait internal(SessionExecutionDelegations.Dependency dependency) {
        return new ExecutionStore.DelegationWait(dependency.businessWaitId(), dependency.delegationIds(), dependency.children());
    }

    /** 本地Plan控制使用原MODEL ActionId作为唯一业务操作键，状态及回执由LocalPlans同事务提交。 */
    private Step.PlanLocal localPlan(Run run, Materials before, AgentAction.Plan plan) {
        var request = planRequest(before, plan);
        return new Step.PlanLocal(new ActionRef(run.identity().runRef().tenantId(), "execution:" + run.identity().runRef().runId() + ":" + before.modelOrdinal()), plan.command(),
                before.configuration().actor(), material(plans.encodeRequest(request)));
    }

    /** 原Plan决定转为正式native调用；完整任务图仍由PlanCommand所属codec保持。 */
    private SessionExecutionPlans.Request planRequest(Materials before, AgentAction.Plan plan) {
        String wireName = NativeAgentTools.wireName("plan", Optional.of(plan.command().operation().name().toLowerCase(java.util.Locale.ROOT)));
        var declaration = before.configuration().modelTools().stream().filter(tool -> tool.kind() == ModelPort.ModelToolKind.AGENT_ACTION && tool.wireName().equals(wireName)).findFirst().orElseThrow(AgentExecutionProgram::invalid);
        var encoded = new AgentDecisionDecoder(limits).encode(plan); var value = (com.fasterxml.jackson.databind.node.ObjectNode) codec.parse(encoded, encoded.schemaId());
        value.remove("type"); value.remove("operation"); before.decision().orElseThrow().commentary().ifPresent(commentary -> value.put("commentary", commentary));
        var arguments = codec.encode("application/json", value); validateControlArguments(declaration, arguments);
        return new SessionExecutionPlans.Request(plan.command(), SessionNativeResponses.callId(before,0), wireName, arguments, before.steering().modelIncludedThrough());
    }

    /** 实际Plan业务回执仅本地配对或声明真实内部等待，不新增任何能力调用计数。 */
    @Override public Adoption<Materials> planAdoption(Run run, Materials before, Step.PlanLocal command, Material actualResult) {
        if (before.pendingDependency().isPresent() || before.pendingTool().isPresent() || before.pendingHuman().isPresent() || before.problem().isPresent()) throw invalid();
        var decision = before.decision().orElseThrow(AgentExecutionProgram::invalid);
        if (!(decision instanceof AgentDecisionDecoder.Decoded decoded) || !(decoded.action() instanceof AgentAction.Plan plan)
                || !command.equals(localPlan(run, before, plan))) throw invalid();
        var request = plans.decodeRequest(command.request().content()); var result = plans.decodeResult(actualResult.content());
        var messages = before.messages(); Optional<PendingDependency> pending = Optional.empty(); Optional<ExecutionStore.WaitDeclaration> waiting = Optional.empty(); List<ExecutionStore.Event> events = List.of();
        if (result.waiting().isPresent()) {
            var dependency = result.waiting().orElseThrow(); var reference = new ExecutionStore.WaitRef(command.sourceModelAction(), 1);
            pending = Optional.of(new PendingPlan(reference, request, actualResult.reference(), dependency));
            waiting = Optional.of(new ExecutionStore.WaitDeclaration(reference, ExecutionStore.WaitKind.PLAN, Map.of(), Optional.of(dependency))); events = List.of(waiting(run, reference, ExecutionStore.WaitKind.PLAN));
        } else messages = appendPlan(before, request, result.value());
        var value = AgentExecutionCodec.object(); value.put("type", "plan-local"); value.put("previous", before.context().value()); value.put("request", command.request().reference().value()); value.put("result", actualResult.reference().value());
        var context = material(codec.encode(CONTEXT_SCHEMA, value)); var after = new Materials(before.configuration(), messages, before.usage(), before.ordinal(), before.modelOrdinal(),
                pending.isPresent() ? before.model() : Optional.empty(), pending.isPresent() ? before.decision() : Optional.empty(), context.reference(), before.completedTools(), Optional.empty(), Optional.of(actualResult.reference()),
                Optional.empty(), Optional.empty(), before.knowledge(), before.skills(), pending, before.steering(), result.state(), before.output(), before.observationInput()).withProgress(before.progress());
        return new Adoption<>(after, new ExecutionStore.Progress(after.usage().businessModelCalls(), after.usage().toolCalls(), after.usage().knowledgeCalls(), context.reference()), context, events, waiting);
    }

    /** Plan反馈只在业务和Session事务成功后进入原assistant/tool回合。 */
    private List<ModelMessage> appendPlan(Materials before, SessionExecutionPlans.Request request, TypedPayload value) {
        var decision = before.decision().orElseThrow(AgentExecutionProgram::invalid);
        if (!(decision instanceof AgentDecisionDecoder.Decoded decoded) || !(decoded.action() instanceof AgentAction.Plan plan) || !request.equals(planRequest(before, plan))) throw invalid();
        var assistant = SessionNativeResponses.assistant(before,List.of(new ModelToolCall(request.callId(), request.wireName(), request.arguments())));
        return ModelConversation.append(before.messages(), assistant, List.of(new ModelMessage("execution-plan-" + before.modelOrdinal(), ModelRole.TOOL,
                List.of(new ModelContent.Text("计划操作回执：依据返回的审批、步骤和完成状态选择下一步；计划更新本身不证明业务动作已完成。\n"+value.canonicalJson())), List.of(), Optional.of(request.callId()), Optional.empty())));
    }

    /** 控制工具的inputSchema在ModelPort使用application/json承载JSON Schema，不能当作ToolPortal的schemaId兜底。 */
    private void validateControlArguments(ModelTool declaration, TypedPayload arguments) {
        if (declaration.kind() != ModelPort.ModelToolKind.AGENT_ACTION || !"application/json".equals(declaration.inputSchema().schemaId())) throw invalid();
        var schema = SessionRuntimeJsonSchema.project(codec.parse(declaration.inputSchema(), "application/json"));
        SessionRuntimeJsonSchema.validate(schema, codec.parse(arguments, "application/json"));
    }

    /** MODEL入境统一拒绝未声明工具、控制混组和非法参数；资源加载与外部派发在整组通过以后。 */
    private Materials rejectInvalidToolDecision(Materials before) {
        if (before.problem().isPresent() || before.model().isEmpty() || before.decision().isEmpty()) return before;
        var decision = before.decision().orElseThrow();
        if (decision instanceof AgentDecisionDecoder.Failure failure) {
            if (before.configuration().profile().response().format() instanceof ModelPort.ResponseFormat.NativeConversation) {
                if (failure.reason() == AgentDecisionDecoder.Reason.TOOL_NOT_AVAILABLE)
                    return rejectedAction(before, "tool_not_available", "只从本轮已声明的可用工具中选择；不要使用先前阶段或未声明的工具。整组调用均未执行，请重新提交所需调用。");
                if (failure.reason() == AgentDecisionDecoder.Reason.CONTROL_ACTION_REQUIRES_SINGLE_CALL)
                    return rejectedAction(before, "control_action_requires_single_call", "控制工具必须单独调用，不得与其他调用混组；请单独提交本轮所需的控制调用。整组调用均未执行。");
            }
            return switch (failure.reason()) {
                case TOOL_ARGUMENTS_INVALID_JSON, TOOL_ARGUMENTS_NOT_OBJECT, CONTROL_ARGUMENTS_FIELDS_INVALID,
                        CONTROL_COMMENTARY_INVALID, CONTROL_ARGUMENTS_INVALID -> rejectedAction(before, "tool_arguments_invalid",
                        "按本轮工具参数 schema 重新提供 JSON 对象；使用声明的字段、必填项和字段类型，不要添加 schema 未声明的包装字段。");
                default -> before;
            };
        }
        var calls = decision instanceof AgentDecisionDecoder.DecodedBatch batch ? batch.calls()
                : decision instanceof AgentDecisionDecoder.Decoded single && single.action() instanceof AgentAction.ToolCall call ? List.of(call) : List.<AgentAction.ToolCall>of();
        for (var call : calls) {
            var entries = before.configuration().tools().stream().filter(entry -> entry.target().handle().value().equals(call.toolHandle())).toList();
            if (entries.size() != 1) return before;
            var entry = entries.getFirst();
            boolean grouped = decision instanceof AgentDecisionDecoder.DecodedBatch
                    || before.model().orElseThrow().assistantTurn().map(turn -> turn.toolCalls().size() > 1).orElse(false);
            if (grouped && (entry.target().effect() != com.skillforge.runtime.api.ToolEffect.READ_ONLY
                    || entry.target().confirmation() != com.skillforge.runtime.api.port.ToolPortal.ToolConfirmationPolicy.NEVER || !entry.target().modelInvocable())) return before;
            var schema = entry.target().inputSchema();
            if (!"json-schema".equals(schema.schemaId()) || !"application/json".equals(call.arguments().schemaId())) throw invalid();
            // 目录/schema损坏是技术错误；只把已解析参数的明确约束违反反馈给模型。
            var definition = SessionRuntimeJsonSchema.project(SessionRuntimePayloadJson.parse(schema.canonicalJson(), limits));
            var violation = SessionRuntimeJsonSchema.firstViolation(definition,
                    SessionRuntimePayloadJson.parse(call.arguments().canonicalJson(), limits), "");
            if (violation != null) return rejectedAction(before, "tool_arguments_invalid",
                    "工具 " + before.configuration().modelTools().stream().filter(tool -> tool.kind() == ModelPort.ModelToolKind.TOOL_CALL
                            && tool.toolHandle().equals(call.toolHandle())).findFirst().orElseThrow(AgentExecutionProgram::invalid).wireName()
                    + " 的参数位置 " + (violation.path().isEmpty() ? "/" : violation.path()) + " 违反 " + violation.keyword()
                    + " 约束。按本轮工具 schema 修正该字段；删除未声明字段，不添加 schema 未声明的包装字段，不猜测默认值。整组调用均未执行，请重新提交所需调用。");
        }
        return before;
    }

    /** 确定的本地动作拒绝共用有限纠正，不制造工具执行或成功回执。 */
    private Materials rejectedAction(Materials before, String reason, String instruction) {
        long corrections = before.messages().stream().filter(message -> message.role() == ModelRole.SYSTEM
                && message.messageId().startsWith("execution-admission-correction:")).count();
        if (corrections >= AgentExecutionOutputs.MAX_CORRECTIONS)
            return new Materials(before.configuration(), before.messages(), before.usage(), before.ordinal(), before.modelOrdinal(), before.model(), before.decision(),
                    before.context(), before.completedTools(), Optional.of(new Problem(ProblemKind.FAILED, "action_correction_limit")), before.lastResult(), before.pendingTool(),
                    before.pendingHuman(), before.knowledge(), before.skills(), before.pendingDependency(), before.steering(), before.planState(), before.output(), before.observationInput()).withProgress(before.progress());
        String refusal = "本次动作在执行前被拒绝，整组调用均未执行。原因码：" + reason + "。\n" + instruction;
        var messages = new ArrayList<>(before.messages());
        // 未知函数的供应商响应不进入下一次请求；原始结果仍保存在原 MODEL outcome。
        if (!"tool_not_available".equals(reason)) {
            var decision = before.decision().orElseThrow();
            var calls = new ArrayList<ModelToolCall>();
            if (decision instanceof AgentDecisionDecoder.DecodedBatch batch) for (var call : batch.calls())
                calls.add(admissionCall(before, call, calls.size()));
            else if (decision instanceof AgentDecisionDecoder.Decoded single && single.action() instanceof AgentAction.ToolCall call)
                calls.add(admissionCall(before, call, 0));
            else if (decision instanceof AgentDecisionDecoder.Decoded single && !(single.action() instanceof AgentAction.Respond)) {
                final String name; final TypedPayload arguments;
                if (single.action() instanceof AgentAction.Delegate delegate) {
                    name = NativeAgentTools.wireName("delegate", Optional.of(delegate.command().operation().name().toLowerCase(java.util.Locale.ROOT)));
                    arguments = delegationArguments(before, delegate);
                } else {
                    var encoded = new AgentDecisionDecoder(limits).encode(single.action());
                    var value = (com.fasterxml.jackson.databind.node.ObjectNode) codec.parse(encoded, encoded.schemaId());
                    String type = AgentExecutionCodec.string(value, "type"); value.remove("type"); name = NativeAgentTools.wireName(type, Optional.empty());
                    single.commentary().ifPresent(commentary -> value.put("commentary", commentary)); arguments = codec.encode("application/json", value);
                }
                calls.add(new ModelToolCall(SessionNativeResponses.callId(before, 0), name, arguments));
            }
            var assistant = SessionNativeResponses.assistant(before, calls);
            var receipts = assistant.toolCalls().stream().map(call -> new ModelMessage("execution-admission-rejected:" + before.modelOrdinal() + ":" + call.callId(),
                    ModelRole.TOOL, refusal, List.of(), Optional.of(call.callId()), Optional.<String>empty())).toList();
            messages = new ArrayList<>(ModelConversation.append(before.messages(), assistant, receipts));
        }
        messages.add(new ModelMessage("execution-admission-correction:" + before.modelOrdinal(), ModelRole.SYSTEM,
                refusal + "\n已使用 " + (corrections + 1) + " / " + AgentExecutionOutputs.MAX_CORRECTIONS + " 次纠正机会。"));
        return new Materials(before.configuration(), messages, before.usage(), before.ordinal(), before.modelOrdinal(), Optional.empty(), Optional.empty(),
                before.context(), before.completedTools(), Optional.empty(), before.lastResult(), before.pendingTool(), before.pendingHuman(), before.knowledge(), before.skills(),
                before.pendingDependency(), before.steering(), before.planState(), before.output(), before.observationInput()).withProgress(before.progress());
    }

    /** 结构化协议在同一拒绝出口生成原调用身份；原生协议始终采用实际assistant回合。 */
    private ModelToolCall admissionCall(Materials before, AgentAction.ToolCall call, int index) {
        var declaration = before.configuration().modelTools().stream().filter(tool -> tool.kind() == ModelPort.ModelToolKind.TOOL_CALL
                && tool.toolHandle().equals(call.toolHandle())).findFirst().orElseThrow(AgentExecutionProgram::invalid);
        return new ModelToolCall(SessionNativeResponses.callId(before, index), declaration.wireName(), call.arguments());
    }

    /** 计划纯门禁失败只形成原MODEL回合的本地拒绝，模型可据此改走计划控制，不制造外部调用事实。 */
    private Materials rejectUnadmittedPlanAction(Materials before) {
        if (before.problem().isPresent() || before.decision().isEmpty() || before.model().isEmpty()) return before;
        var tools = loadedTools(before.configuration()).stream().map(AgentExecutionLoop.LoadedTool::binding).toList();
        var decision = before.decision().orElseThrow(); Optional<String> rejection = Optional.empty();
        if (decision instanceof AgentDecisionDecoder.Decoded single) rejection = PlanActionAdmission.rejection(planRequired(before), before.planState(), single.action(), tools);
        else if (decision instanceof AgentDecisionDecoder.DecodedBatch batch) for (var call : batch.calls()) {
            rejection = PlanActionAdmission.rejection(planRequired(before), before.planState(), call, tools); if (rejection.isPresent()) break;
        }
        if (rejection.isEmpty()) return before;
        String next = switch (rejection.orElseThrow()) {
            case "plan_required_before_execution" -> "先建立任务计划，再执行需要计划约束的动作。";
            case "plan_steps_not_complete_before_finalization", "plan_steps_not_complete" -> "先核对并完成计划中尚未完成的步骤；不得把未完成步骤标为成功或提前声明任务完成。";
            case "delegate_must_use_plan_step" -> "通过当前计划步骤发起委派，不要绕过计划直接启动子任务。";
            case "plan_step_not_started_or_approved" -> "先核对计划审批状态并启动对应步骤，再执行该步骤允许的动作。";
            default -> "核对当前计划状态和动作前置条件，再决定下一步。";
        };
        return rejectedAction(before, rejection.orElseThrow(), "下一步：" + next);
    }

    /** Workflow真实请求只使用原冻结挂载及原受理参数；模型不能选择新参数或Execution身份。 */
    private Step workflow(Run run, Materials before, AgentAction.Dispatch dispatch) {
        var limited = BudgetGuard.evaluate(before.configuration().budget(), before.usage(), BudgetGuard.Operation.TOOL, sources.elapsed(run));
        if (limited.isPresent()) return finish(run, RunStatus.TERMINATED, "", limited.orElseThrow().name(), before.lastResult());
        var request = workflowRequest(run, before, dispatch); var prepared = prepare(run, before, ExecutionStore.Kind.WORKFLOW, Math.incrementExact(before.ordinal()), material(workflows.encode(request)));
        return dispatch(run, before, prepared, ExecutionBudgetPort.Category.TOOL, ExecutionBudgetPort.Resources.call(ExecutionBudgetPort.Category.TOOL, 0, 0));
    }

    private SessionExecutionWorkflows.Dispatch authorizedWorkflowRequest(Run run,Materials before) {
        var intent=before.configuration().initialIntent().orElseThrow(AgentExecutionProgram::invalid);
        if(before.ordinal()!=0||before.model().isPresent()||before.decision().isPresent()||before.pendingDependency().isPresent())throw invalid();
        var binding=before.configuration().workflows().getFirst();
        return new SessionExecutionWorkflows.Dispatch(before.configuration().capabilitySnapshot(),intent.target(),
                new ExecutionRef(run.identity().runRef().tenantId(),"execution-workflow:"+AgentExecutionCodec.digest(run.identity().runRef()+":"+intent.intentRef())),
                binding.parameters(),binding.evidenceRefs(),new SessionExecutionWorkflows.AuthorizedInputOrigin(intent.intentRef()));
    }
    private Step authorizedWorkflow(Run run,Materials before) {
        var limited=BudgetGuard.evaluate(before.configuration().budget(),before.usage(),BudgetGuard.Operation.TOOL,sources.elapsed(run));
        if(limited.isPresent())return finish(run,RunStatus.TERMINATED,"",limited.orElseThrow().name(),before.lastResult());
        var request=authorizedWorkflowRequest(run,before);
        var prepared=prepare(run,before,ExecutionStore.Kind.WORKFLOW,1,material(workflows.encode(request)));
        return dispatch(run,before,prepared,ExecutionBudgetPort.Category.TOOL,ExecutionBudgetPort.Resources.call(ExecutionBudgetPort.Category.TOOL,0,0));
    }
    /** 可信起始调用只消费原 Workflow 的安全输出，不再进入自由 Agent 模型循环。 */
    private Step authorizedWorkflowCompletion(Run run,Materials before) {
        if(before.model().isPresent()||before.decision().isPresent()||before.lastResult().isEmpty())throw invalid();
        var saved=workflows.decodeResult(requireMaterial(run,before.lastResult().orElseThrow()).content());
        var snapshot=codec.decodeOutcome(saved.outcome());
        var result=core.portalResults(payloads,payloads,payloads).decodeWorkflow(snapshot,snapshot.callKind());
        return switch(result) {
            case WorkflowResult.Completed completed -> finish(run,RunStatus.COMPLETED,completed.output().canonicalJson(),"authorized_workflow_completed",before.lastResult());
            case WorkflowResult.Failed failed -> finish(run,RunStatus.FAILED,"",failed.failure().reasonCode(),before.lastResult());
            case WorkflowResult.Cancelled ignored -> finish(run,RunStatus.CANCELLED,"","workflow_cancelled",before.lastResult());
            case WorkflowResult.Terminated terminated -> finish(run,RunStatus.TERMINATED,"",terminated.reason().name(),before.lastResult());
            case WorkflowResult.OutcomeUnknown ignored -> new Step.Yield("execution_unknown");
            default -> throw invalid();
        };
    }

    /** 原MODEL决定的逻辑Action确定Execution引用，恢复时仍使用同原参数、证据和native调用。 */
    private SessionExecutionWorkflows.Dispatch workflowRequest(Run run, Materials before, AgentAction.Dispatch dispatch) {
        var binding = before.configuration().workflows().stream().filter(value -> value.target().handle().value().equals(dispatch.handle())).findFirst().orElseThrow(AgentExecutionProgram::invalid);
        String name = NativeAgentTools.wireName("dispatch", Optional.empty()); var declaration = before.configuration().modelTools().stream()
                .filter(tool -> tool.kind() == ModelPort.ModelToolKind.AGENT_ACTION && tool.wireName().equals(name)).findFirst().orElseThrow(AgentExecutionProgram::invalid);
        var value = AgentExecutionCodec.object(); value.put("handle", dispatch.handle()); before.decision().orElseThrow().commentary().ifPresent(commentary -> value.put("commentary", commentary));
        var arguments = codec.encode("application/json", value); validateControlArguments(declaration, arguments);
        return new SessionExecutionWorkflows.Dispatch(before.configuration().capabilitySnapshot(), binding.target(),
                new ExecutionRef(run.identity().runRef().tenantId(), "execution-workflow:" + AgentExecutionCodec.digest(run.identity().runRef() + ":" + Math.incrementExact(before.modelOrdinal()))),
                binding.parameters(), binding.evidenceRefs(), SessionNativeResponses.callId(before,0), name, arguments);
    }

    /** 已受理的真实系统信号只恢复原Workflow Action，当前权限和预算仍由新Attempt登记核验。 */
    private Step workflowContinuation(Run run, Materials before, ExecutionStore.Waiting waiting, Material response) {
        var dependency = before.pendingDependency().orElseThrow(AgentExecutionProgram::invalid); var accepted = waiting.acceptedResponse().orElseThrow(AgentExecutionProgram::invalid);
        if (!(dependency instanceof PendingWorkflow pending) || run.phase() != ExecutionStore.Phase.READY || !run.waiting().equals(Optional.of(waiting.reference()))
                || !waiting.run().equals(run.identity().runRef()) || !waiting.reference().equals(pending.waiting()) || !waiting.internal().equals(Optional.of(pending.dependency()))
                || waiting.continuationAttempt().isPresent() || waiting.consumedRevision().isPresent() || !waiting.requested().isEmpty()
                || !accepted.response().equals(response.reference()) || !accepted.actorRef().equals(run.actorVerificationRef())) throw invalid();
        var limited = BudgetGuard.evaluate(before.configuration().budget(), before.usage(), BudgetGuard.Operation.TOOL, sources.elapsed(run));
        if (limited.isPresent()) return finish(run, RunStatus.TERMINATED, "", limited.orElseThrow().name(), before.lastResult());
        var original = pending.original(); var actual = pending.dependency();
        var request = new SessionExecutionWorkflows.Resume(original.snapshot(), original.target(), original.executionRef(), actual.suspendRef(), actual.actionKey(), actual.executionRevision(), response.content(),
                original.origin(), response.reference(), accepted.input());
        var prepared = prepare(run, before, ExecutionStore.Kind.WORKFLOW, before.ordinal(), material(workflows.encode(request)));
        if (!prepared.action().equals(waiting.reference().action())) throw invalid();
        String attempt = prepared.action().actionId() + ":attempt:" + Math.incrementExact(waiting.reference().generation());
        return new Step.Dispatch(prepared, attempt, new ExecutionBudgetPort.Demand(run.identity().runRef(), attempt, prepared.fingerprint(), ExecutionBudgetPort.Category.TOOL,
                ExecutionBudgetPort.Resources.call(ExecutionBudgetPort.Category.TOOL, 0, 0), false), before.configuration().actor(), Optional.of(waiting.reference()), observations.registered(run, before, prepared, attempt));
    }

    /** 完整原PortalOutcome及真实ExecutionWait同封套保存；纯恢复不再查询Workflow live状态。 */
    private Adoption<Materials> adoptWorkflow(Run run, Materials before, Prepared prepared, CapabilityResult actual) {
        if (before.pendingTool().isPresent() || before.pendingHuman().isPresent() || before.problem().isPresent()) throw invalid();
        var request = workflows.decode(prepared.request().content()); final SessionExecutionWorkflows.Dispatch original; final long generation;
        if (request instanceof SessionExecutionWorkflows.Dispatch dispatch) {
            if(dispatch.origin() instanceof SessionExecutionWorkflows.AuthorizedInputOrigin) {
                if(!dispatch.equals(authorizedWorkflowRequest(run,before))||prepared.ordinal()!=1)throw invalid();
            } else {
                var decision = before.decision().orElseThrow(AgentExecutionProgram::invalid);
                if (!(decision instanceof AgentDecisionDecoder.Decoded decoded) || !(decoded.action() instanceof AgentAction.Dispatch selected)
                        || before.pendingDependency().isPresent() || prepared.ordinal() != Math.incrementExact(before.ordinal()) || !dispatch.equals(workflowRequest(run, before, selected))) throw invalid();
            }
            original = dispatch; generation = 1;
        } else if (request instanceof SessionExecutionWorkflows.Resume resume) {
            var dependency = before.pendingDependency().orElseThrow(AgentExecutionProgram::invalid);
            if (!(dependency instanceof PendingWorkflow pending) || prepared.ordinal() != before.ordinal() || !prepared.action().equals(pending.waiting().action())) throw invalid();
            original = pending.original(); var waiting = pending.dependency();
            if (!resume.snapshot().equals(original.snapshot()) || !resume.target().equals(original.target()) || !resume.executionRef().equals(original.executionRef())
                    || !resume.suspendRef().equals(waiting.suspendRef()) || !resume.actionKey().equals(waiting.actionKey()) || resume.expectedRevision() != waiting.executionRevision()
                    || !resume.origin().equals(original.origin())) throw invalid();
            generation = Math.incrementExact(pending.waiting().generation());
        } else throw invalid();
        if (!prepared.equals(prepare(run, before, ExecutionStore.Kind.WORKFLOW, prepared.ordinal(), prepared.request()))) throw invalid();
        var envelope = workflows.decodeResult(actual.result().content()); var snapshot = codec.decodeOutcome(envelope.outcome());
        var result = core.portalResults(payloads, payloads, payloads).decodeWorkflow(snapshot, request.callKind());
        if (!workflowExecution(result).equals(original.executionRef()) || actual.kind() != resultKind(snapshot) || !workflows.feedback(result).equals(actual.feedback().map(Material::content))) throw invalid();
        Optional<PendingDependency> pending = Optional.empty(); Optional<ExecutionStore.WaitDeclaration> waiting = Optional.empty(); Optional<Problem> problem = Optional.empty(); List<ExecutionStore.Event> events = List.of();
        var messages = before.messages();
        if (result instanceof WorkflowResult.Suspended suspended) {
            var dependency = envelope.waiting().orElseThrow(AgentExecutionProgram::invalid);
            if (!dependency.execution().equals(suspended.executionRef()) || !dependency.suspendRef().equals(suspended.suspendRef()) || dependency.executionRevision() != suspended.revision()) throw invalid();
            var reference = new ExecutionStore.WaitRef(prepared.action(), generation); pending = Optional.of(new PendingWorkflow(reference, original, actual.result().reference(), dependency));
            if ((suspended.reason() == com.skillforge.runtime.api.LoopSuspendReason.WORKFLOW_HITL) != envelope.human().isPresent()) throw invalid();
            waiting = Optional.of(new ExecutionStore.WaitDeclaration(reference, ExecutionStore.WaitKind.WORKFLOW, Map.of(), Optional.of(dependency))); events = List.of(workflowWaiting(run, reference, dependency, envelope.human()));
        } else {
            if (envelope.waiting().isPresent()) throw invalid();
            if (result instanceof WorkflowResult.OutcomeUnknown) problem = Optional.of(new Problem(ProblemKind.UNKNOWN, "workflow_outcome_unknown"));
            else if (result instanceof WorkflowResult.Accepted) throw invalid();
            else {
                var rejected = result instanceof WorkflowResult.Completed completed ? sources.capabilityContentRejection(run, List.of(completed.output())) : Optional.<String>empty();
                if (rejected.isPresent()) problem = Optional.of(new Problem(ProblemKind.FAILED, rejected.orElseThrow()));
                else if(original.origin() instanceof SessionExecutionWorkflows.ModelOrigin) messages = appendWorkflow(before, original, actual.feedback().orElseThrow().content());
            }
            events = List.of(workflowResult(run, prepared, result, problem.isEmpty()));
        }
        var context = context(before.context(), prepared, actual); var usage = before.usage().nextToolCall();
        var after = new Materials(before.configuration(), messages, usage, prepared.ordinal(), before.modelOrdinal(), pending.isPresent() ? before.model() : Optional.empty(),
                pending.isPresent() ? before.decision() : Optional.empty(), context.reference(), before.completedTools(), problem, Optional.of(actual.result().reference()), Optional.empty(), Optional.empty(),
                before.knowledge(), before.skills(), pending, before.steering(), before.planState(), before.output(), observations.input(before, prepared)).withProgress(before.progress());
        var observed = new ArrayList<>(events);
        observed.addAll(observations.operation(run, before, prepared, prepared.action().actionId() + ":attempt:" + generation,
                AgentExecutionObservations.workflowStatus(result)));
        events = List.copyOf(observed);
        return new Adoption<>(after, new ExecutionStore.Progress(usage.businessModelCalls(), usage.toolCalls(), usage.knowledgeCalls(), context.reference()), context, events, waiting);
    }

    /** 每个原Workflow native调用只在终态后追加一次结果，不在挂起和续跑间制造半回合。 */
    private List<ModelMessage> appendWorkflow(Materials before, SessionExecutionWorkflows.Dispatch original, TypedPayload value) {
        var decision = before.decision().orElseThrow(AgentExecutionProgram::invalid);
        if (!(decision instanceof AgentDecisionDecoder.Decoded decoded) || !(decoded.action() instanceof AgentAction.Dispatch dispatch)
                || !original.target().handle().value().equals(dispatch.handle()) || !original.callId().equals(SessionNativeResponses.callId(before,0))) throw invalid();
        var assistant = SessionNativeResponses.assistant(before,List.of(new ModelToolCall(original.callId(), original.wireName(), original.arguments())));
        return ModelConversation.append(before.messages(), assistant, List.of(new ModelMessage("execution-workflow-" + before.modelOrdinal(), ModelRole.TOOL,
                List.of(new ModelContent.Text("工作流执行回执：仅依据返回的实际状态和输出报告结果；失败、取消或未完成不能表述为业务成功。"
                        + "完成时 output.schemaId 和 output.canonicalJson 是结果封套，其中 canonicalJson 是表示业务输出的 JSON 字符串；"
                        + "按用户要求使用解码后的业务内容，包含 messages 时区分消息内容与卡片，不要默认把封套作为最终答复。\n"+value.canonicalJson())), List.of(), Optional.of(original.callId()), Optional.empty())));
    }

    /** 原typed结果均携带Execution身份，不能以其他挂载或另一次调用的回执推进。 */
    private static ExecutionRef workflowExecution(WorkflowResult result) {
        return switch (result) {
            case WorkflowResult.Accepted value -> value.executionRef(); case WorkflowResult.Suspended value -> value.executionRef(); case WorkflowResult.Completed value -> value.executionRef();
            case WorkflowResult.Failed value -> value.executionRef(); case WorkflowResult.Terminated value -> value.executionRef(); case WorkflowResult.Cancelled value -> value.executionRef(); case WorkflowResult.OutcomeUnknown value -> value.executionRef();
        };
    }

    private Step knowledge(Run run, Materials before, AgentAction.KnowledgeAccess query) {
        var request = knowledgeRequest(run, before, query);
        var reused = reusedKnowledge(before, query);
        if (reused.isPresent()) return model(run, before, KnowledgeExecution.append(run.identity().runRef(),before, request, reused.orElseThrow().bundle(), reused.orElseThrow().injected(), limits));
        var limited = BudgetGuard.evaluate(before.configuration().budget(), before.usage(), BudgetGuard.Operation.KNOWLEDGE, sources.elapsed(run));
        if (limited.isPresent()) return finish(run, RunStatus.TERMINATED, "", limited.orElseThrow().name(), before.lastResult());
        var prepared = prepare(run, before, ExecutionStore.Kind.KNOWLEDGE, Math.incrementExact(before.ordinal()), material(codec.encodeKnowledgeRequest(request)));
        return dispatch(run, before, prepared, ExecutionBudgetPort.Category.KNOWLEDGE, ExecutionBudgetPort.Resources.call(ExecutionBudgetPort.Category.KNOWLEDGE, 0, 0));
    }

    private Optional<KnowledgeObservation> reusedKnowledge(Materials before, AgentAction.KnowledgeAccess query) {
        if (!(query instanceof AgentAction.KnowledgeQuery)) return Optional.empty();
        return before.knowledge().stream().filter(value -> value.request().target().handle().value().equals(query.knowledgeHandle())
                && value.request().request().operation().equals(query.operation())).findFirst();
    }

    private KnowledgeCallRequest knowledgeRequest(Run run, Materials before, AgentAction.KnowledgeAccess query) {
        var binding = before.configuration().knowledge().stream().filter(value -> value.target().handle().value().equals(query.knowledgeHandle())).findFirst().orElseThrow(AgentExecutionProgram::invalid);
        var wireName = NativeAgentTools.wireName(query instanceof AgentAction.KnowledgeListDocuments ? "knowledge_list_documents"
                : query instanceof AgentAction.KnowledgeReadDocument ? "knowledge_read_document" : "knowledge_query", Optional.empty());
        if (before.configuration().modelTools().stream().noneMatch(tool -> tool.kind() == ModelPort.ModelToolKind.AGENT_ACTION && tool.wireName().equals(wireName))) throw invalid();
        var request = new com.skillforge.runtime.api.KnowledgeRequest("execution-knowledge:" + run.identity().runRef().runId() + ":" + Math.incrementExact(before.ordinal()),
                run.identity().runRef().tenantId(), run.identity().requester(), run.identity().agentId(), run.identity().owner().sessionRef(), run.identity().trace(),
                binding.mode(), binding.intent(), query.operation(), binding.target().sourceVersions().stream().map(KnowledgeSourceVersion::sourceId).collect(java.util.stream.Collectors.toUnmodifiableSet()),
                binding.target().sourceVersions(), query instanceof AgentAction.KnowledgeListDocuments ? java.util.Set.of(com.skillforge.runtime.api.KnowledgeOperatorType.DOCUMENT_LIST)
                : query instanceof AgentAction.KnowledgeReadDocument ? java.util.Set.of(com.skillforge.runtime.api.KnowledgeOperatorType.DOCUMENT_READ)
                : binding.target().allowedOperators().stream().filter(operator -> !java.util.Set.of(com.skillforge.runtime.api.KnowledgeOperatorType.DOCUMENT_LIST, com.skillforge.runtime.api.KnowledgeOperatorType.DOCUMENT_READ).contains(operator)).collect(java.util.stream.Collectors.toUnmodifiableSet()),
                binding.budget(), binding.asOf(), binding.freshnessAfter(), binding.citationRequired());
        return new KnowledgeCallRequest(binding.target(), request, before.configuration().capabilitySnapshot(), admissionEvent(run.identity()).id(),
                SessionNativeResponses.callId(before,0), wireName, KnowledgeExecution.arguments(query, before.decision().orElseThrow().commentary(), codec));
    }

    private Adoption<Materials> adoptKnowledge(Run run, Materials before, Prepared prepared, CapabilityResult actual, Optional<KnowledgeProjection> recovered) {
        if (prepared.ordinal() != Math.incrementExact(before.ordinal()) || before.pendingHuman().isPresent() || before.pendingTool().isPresent() || actual.feedback().isPresent()) throw invalid();
        var decision = before.decision().orElseThrow(AgentExecutionProgram::invalid);
        if (!(decision instanceof AgentDecisionDecoder.Decoded decoded) || !(decoded.action() instanceof AgentAction.KnowledgeAccess query)) throw invalid();
        var request = codec.decodeKnowledgeRequest(prepared.request().content());
        if (!request.equals(knowledgeRequest(run, before, query))) throw invalid();
        var outcome = codec.decodeKnowledgeOutcome(actual.result().content());
        var observations = new ArrayList<>(before.knowledge()); var messages = before.messages();
        Optional<Problem> problem = Optional.empty(); List<String> injected = List.of();
        boolean projectionRejected = recovered.map(KnowledgeProjection::rejected).orElse(false);
        if (outcome instanceof KnowledgeOutcome.Retrieved retrieved) {
            if (actual.kind() != ExecutionStore.ResultKind.SUCCEEDED) throw invalid();
            boolean accepted = true;
            try { new FrozenKnowledgeRequest(request.target(), request.request()).requireResult(retrieved.bundle()); }
            catch (IllegalArgumentException rejected) {
                if (rejected.getClass() != IllegalArgumentException.class || !"invalid Knowledge result binding".equals(rejected.getMessage())) throw rejected;
                accepted = false; problem = Optional.of(new Problem(ProblemKind.FAILED, "knowledge_output_invalid"));
            }
            if (accepted) {
                if (!projectionRejected) {
                    try { injected = recovered.map(KnowledgeProjection::injected).orElseGet(() -> KnowledgeExecution.select(run.identity().runRef(),before, request, retrieved.bundle(), sources, limits)); }
                    catch (KnowledgeExecution.BudgetExceeded rejected) { projectionRejected = true; }
                }
                if (projectionRejected) messages = KnowledgeExecution.failure(before, request, "knowledge_document_budget_exceeded");
                else {
                    KnowledgeExecution.requireSelection(retrieved.bundle(), injected);
                    messages = KnowledgeExecution.append(run.identity().runRef(),before, request, retrieved.bundle(), injected, limits);
                    if (query instanceof AgentAction.KnowledgeReadDocument) {
                        // 重读仍重新授权及派发；相同稳定片段只由最近一次真实结果提供可引用身份。
                        var replaced = java.util.Set.copyOf(injected);
                        observations.replaceAll(previous -> new KnowledgeObservation(previous.request(), previous.result(), previous.bundle(),
                                previous.injected().stream().filter(id -> !replaced.contains(id)).toList()));
                    }
                    observations.add(new KnowledgeObservation(request, actual.result().reference(), retrieved.bundle(), injected));
                }
            } else if (recovered.isPresent() && !recovered.orElseThrow().injected().isEmpty()) throw invalid();
        } else if (outcome instanceof KnowledgeOutcome.Failed failed) {
            if (actual.kind() != ExecutionStore.ResultKind.FAILED) throw invalid();
            messages = KnowledgeExecution.failure(before, request, failed.failure().reasonCode());
        } else {
            if (actual.kind() != ExecutionStore.ResultKind.UNKNOWN) throw invalid();
            problem = Optional.of(new Problem(ProblemKind.UNKNOWN, "knowledge_outcome_unknown"));
        }
        var value = AgentExecutionCodec.object(); value.put("type", "delta"); value.put("previous", before.context().value());
        value.put("kind", ExecutionStore.Kind.KNOWLEDGE.name()); value.put("ordinal", Long.toString(prepared.ordinal()));
        value.put("request", prepared.request().reference().value()); value.put("result", actual.result().reference().value()); value.putNull("feedback");
        var ids = value.putArray("injected"); injected.forEach(ids::add);
        value.put("projectionRejected", projectionRejected);
        var context = material(codec.encode(CONTEXT_SCHEMA, value)); var usage = before.usage().nextKnowledgeCall();
        var after = new Materials(before.configuration(), messages, usage, prepared.ordinal(), before.modelOrdinal(), Optional.empty(), Optional.empty(), context.reference(),
                before.completedTools(), problem, Optional.of(actual.result().reference()), Optional.empty(), Optional.empty(), observations, before.skills(), Optional.empty(), before.steering(), before.planState(), before.output(), before.observationInput()).withProgress(before.progress());
        var observed = new ArrayList<>(this.observations.operation(run, before, prepared, this.observations.attempt(prepared), AgentExecutionObservations.status(actual.kind())));
        if (outcome instanceof KnowledgeOutcome.Retrieved)
            observed.add(this.observations.guard(run, before, prepared, this.observations.attempt(prepared), ExecutionObservation.Stage.TOOL_OUTPUT,
                    problem.isEmpty() && !projectionRejected ? ExecutionObservation.Status.SUCCEEDED : ExecutionObservation.Status.REJECTED,
                    problem.map(Problem::reason).orElse(projectionRejected ? "knowledge_document_budget_exceeded" : null)));
        return new Adoption<>(after, new ExecutionStore.Progress(usage.businessModelCalls(), usage.toolCalls(), usage.knowledgeCalls(), context.reference()), context, observed, Optional.empty());
    }

    private AgentExecutionSkills skills(Configuration configuration) { return new AgentExecutionSkills(configuration.skills(), configuration.availableCapabilities(), limits); }

    private Step respond(Run run, Materials before, AgentAction.Respond response) {
        var publicRejection = sources.publicContentRejection(run, List.of(response.output()));
        if (publicRejection.isPresent()) return outputChecked(run, before,
                finish(run, RunStatus.FAILED, "", publicRejection.orElseThrow(), before.lastResult()), publicRejection.orElseThrow());
        if (response.evidenceItemIds().isEmpty() && before.knowledge().stream().anyMatch(value -> value.request().request().citationRequired() && !value.injected().isEmpty()))
            return outputChecked(run, before, finish(run, RunStatus.FAILED, "", "knowledge_citation_required", before.lastResult()), "knowledge_citation_required");
        var evidence = new ArrayList<com.fasterxml.jackson.databind.node.ObjectNode>();
        for (var id : response.evidenceItemIds()) {
            var matches = before.knowledge().stream().filter(value -> value.injected().contains(id)).toList();
            if (matches.size() != 1) return outputChecked(run, before,
                    finish(run, RunStatus.FAILED, "", "evidence_not_unique_or_injected", before.lastResult()), "evidence_not_unique_or_injected");
            var observation = matches.getFirst(); var item = observation.bundle().items().stream().filter(value -> value.itemId().equals(id)).findFirst().orElseThrow(AgentExecutionProgram::invalid);
            var value = AgentExecutionCodec.object(); value.put("citationId",NativeCitations.citationId(run.identity().runRef(),item)); value.put("itemId", id); value.put("bundleId", observation.bundle().bundleId()); value.put("version", Long.toString(observation.bundle().version()));
            value.put("resultRef", observation.result().value()); value.put("sourceId", item.sourceId()); value.put("sourceVersion", item.sourceVersion()); value.put("trustLevel", item.trustLevel());
            var refs = value.putArray("provenanceRefs"); item.provenanceRefs().forEach(refs::add); var paths = value.putArray("graphPath"); item.graphPath().forEach(paths::add); evidence.add(value);
        }
        var prepared=before.output().map(value->value.policy().preparedResult()).orElseGet(()->sources.preparedResult(run));
        var finish = finish(run, RunStatus.COMPLETED, response.output(), "completed", before.lastResult(),prepared);
        var data = (com.fasterxml.jackson.databind.node.ObjectNode) codec.parse(finish.events().getFirst().data(), COMPLETED_SCHEMA);
        var items = data.putArray("evidence"); evidence.forEach(items::add);
        var original = finish.events().getFirst();
        return outputChecked(run, before,
                new Step.Finish(finish.status(), List.of(new ExecutionStore.Event(original.id(), original.type(), codec.encode(COMPLETED_SCHEMA, data)))), null);
    }

    /** 最终公开正文及引用检查与终态同次提交；检查事实不额外完成一次模型调用。 */
    private Step.Finish outputChecked(Run run, Materials before, Step.Finish finish, String rejection) {
        var events = new ArrayList<>(finish.events());
        events.add(observations.agentOutput(run, before, rejection == null ? ExecutionObservation.Status.SUCCEEDED : ExecutionObservation.Status.REJECTED, rejection));
        return new Step.Finish(finish.status(), events);
    }

    /** 本轮实际请求必须消费原等待，不能借改变参数、opaque状态或method重放首次调用。 */
    private static void checkContinuation(PendingTool pending, Prepared prepared, ToolRequest request) {
        var continuation = request.continuation().orElseThrow(AgentExecutionProgram::invalid);
        var original = new ToolRequest(request.target(), request.binding(), request.arguments(), request.callId(), request.wireName());
        if (!pending.original().equals(original) || !pending.waiting().equals(continuation.waiting())
                || !prepared.action().equals(pending.waiting().action()) || !pending.sourceResult().equals(continuation.sourceResult())
                || !pending.input().requestState().equals(continuation.requestState()) || !methods(pending.input()).keySet().equals(continuation.responses().keySet())) throw invalid();
        continuation.responses().forEach((key, response) -> { if (response.method() != pending.input().inputRequests().get(key).method()) throw invalid(); });
    }

    private static Map<String, ToolInputMethod> methods(ToolOutcome.InputRequired input) {
        var result = new java.util.TreeMap<String, ToolInputMethod>(); input.inputRequests().forEach((key, request) -> result.put(key, request.method()));
        return Map.copyOf(result);
    }

    /** 在新派发前校验原模型目标和参数，不给不可见或需要确认的工具自行补授权。 */
    private Step tool(Run run, Materials materials, AgentAction.ToolCall call) {
        var entry = SessionToolDirectory.requirePrepared(materials.configuration().tools(), call.toolHandle());
        if (entry.target().confirmation() != com.skillforge.runtime.api.port.ToolPortal.ToolConfirmationPolicy.NEVER)
            return new Step.Yield("execution_tool_confirmation_required");
        var modelTool = materials.configuration().modelTools().stream().filter(tool -> tool.toolHandle().equals(call.toolHandle())
                && tool.kind() == ModelPort.ModelToolKind.TOOL_CALL).findFirst().orElseThrow(AgentExecutionProgram::invalid);
        var request = new ToolRequest(entry.target(), entry.binding(), call.arguments(), SessionNativeResponses.callId(materials,0), modelTool.wireName());
        var prepared = prepare(run, materials, ExecutionStore.Kind.TOOL, Math.incrementExact(materials.ordinal()), material(codec.encodeTool(request)));
        return dispatch(run, materials, prepared, ExecutionBudgetPort.Category.TOOL,
                ExecutionBudgetPort.Resources.call(ExecutionBudgetPort.Category.TOOL, 0, 0));
    }

    /** 原 assistant/tool 配对直接携带实际安全正文和图片，不读取旧来源或把结果伪装为用户输入。 */
    private List<ModelMessage> appendTool(Run run, Materials before, ToolRequest request, List<ModelContent> contents) {
        var decoded = before.decision().orElseThrow(AgentExecutionProgram::invalid);
        if (!(decoded instanceof AgentDecisionDecoder.Decoded single) || !(single.action() instanceof AgentAction.ToolCall call)
                || !call.toolHandle().equals(request.target().handle().value()) || !call.arguments().equals(request.arguments())
                || !request.callId().equals(SessionNativeResponses.callId(before,0))) throw invalid();
        for (var part : contents) if (part instanceof ModelContent.Image image
                && !image.media().owner().equals(run.identity().owner().sessionRef())) throw invalid();
        var assistant = SessionNativeResponses.assistant(before,List.of(new ModelToolCall(request.callId(), request.wireName(), request.arguments())));
        var result = new ModelMessage("execution-tool-" + before.modelOrdinal(), ModelRole.TOOL, contents, List.of(), Optional.of(request.callId()), Optional.empty());
        return ModelConversation.append(before.messages(), assistant, SessionNativeResponses.toolResults(before,List.of(result),limits));
    }

    /** 原反馈引用已真实保存才允许复用；本轮assistant使用本轮callId，原效果和用量不重复登记。 */
    private List<ModelMessage> replayMessages(Run run, Materials before, AgentExecutionLoop.LoadedToolReuse reuse) {
        if (reuse.original().feedback().isEmpty()) throw invalid();
        var binding = reuse.tool().binding();
        var request = new ToolRequest(binding.target(), binding.binding(), reuse.call().arguments(), SessionNativeResponses.callId(before,0), reuse.tool().wireName());
        return appendTool(run, before, request, AgentExecutionCodec.reusedToolContents(reuse.original().contents()));
    }

    /** 已加载目录转为Core纯路由值，稳定协议名继续来自本轮真实冻结声明。 */
    private static List<AgentExecutionLoop.LoadedTool> loadedTools(Configuration configuration) {
        return configuration.tools().stream().map(entry -> {
            var tool = configuration.modelTools().stream().filter(model -> model.toolHandle().equals(entry.target().handle().value())
                    && model.kind() == ModelPort.ModelToolKind.TOOL_CALL).findFirst().orElseThrow(AgentExecutionProgram::invalid);
            return new AgentExecutionLoop.LoadedTool(new ToolCallRouting.Binding(entry.target(), entry.binding(), entry.parallelSafe()), tool.wireName());
        }).toList();
    }

    /** 同一实际请求只保存一份材料，动作身份从 Run 与序号确定，指纹由这份完整材料计算。 */
    private Prepared prepare(Run run, Materials materials, ExecutionStore.Kind kind, long ordinal, Material request) {
        var parts = kind == ExecutionStore.Kind.MODEL && AgentExecutionCodec.MODEL_MANIFEST_SCHEMA.equals(request.content().schemaId())
                ? sources.materials(run,codec.modelRequestReferences(request.content())).stream()
                    .map(value -> value.orElseThrow(AgentExecutionProgram::invalid)).toList() : List.<Material>of();
        return prepare(run,materials,kind,ordinal,request,parts);
    }

    private Prepared prepare(Run run, Materials materials, ExecutionStore.Kind kind, long ordinal, Material request, List<Material> parts) {
        String target; ResultRef capability;
        if (kind == ExecutionStore.Kind.MODEL) {
            var model = codec.decodeModelRequest(request.content(),refs -> {
                var values = new java.util.HashMap<ResultRef,Material>();
                for (var part : parts) values.put(part.reference(),part);
                return refs.stream().map(ref -> Optional.ofNullable(values.get(ref))).toList();
            }).request(); target = model.target().effectiveModelId(); capability = model.target().capabilities().capabilityFactRef();
        } else if (kind == ExecutionStore.Kind.TOOL) {
            var tool = codec.decodeTool(request.content()); target = tool.target().handle().value(); capability = materials.configuration().capabilityRef();
        } else if (kind == ExecutionStore.Kind.KNOWLEDGE) {
            var knowledge = codec.decodeKnowledgeRequest(request.content()); target = knowledge.target().handle().value(); capability = materials.configuration().capabilityRef();
        } else if (kind == ExecutionStore.Kind.DELEGATION) {
            target = delegations.decodeRequest(request.content()).command().operation().name().toLowerCase(java.util.Locale.ROOT); capability = materials.configuration().capabilityRef();
        } else if (kind == ExecutionStore.Kind.WORKFLOW) {
            target = workflows.decode(request.content()).target().handle().value(); capability = materials.configuration().capabilityRef();
        } else throw invalid();
        return new Prepared(new ActionRef(run.identity().runRef().tenantId(), actionId(run, ordinal)), ordinal,
                kind, target, capability, request, AgentExecutionCodec.digest(request.content().schemaId() + "\0" + request.content().canonicalJson()),parts);
    }

    /** 已加载序号对应实际准备的 Action 身份；观测只引用它，不从相邻Action逆推Step。 */
    static String actionId(Run run, long ordinal) {
        if (ordinal <= 0) throw invalid();
        return "execution:" + run.identity().runRef().runId() + ":" + ordinal;
    }

    /** 派发命令携带已加载可信身份，当前权限仍由派发事务按该身份核验。 */
    private Step.Dispatch dispatch(Run run, Materials materials, Prepared prepared, ExecutionBudgetPort.Category category, ExecutionBudgetPort.Resources resources) {
        String attempt = prepared.action().actionId() + ":attempt:1";
        var lifecycle = prepared.kind() == ExecutionStore.Kind.MODEL ? List.of(ExecutionModelLifecycle.started(run, prepared, codec))
                : prepared.kind() == ExecutionStore.Kind.WORKFLOW ? List.of(workflowStarted(run, prepared)) : List.<ExecutionStore.Event>of();
        var events = new ArrayList<>(lifecycle);
        events.addAll(observations.registered(run, materials, prepared, attempt));
        return new Step.Dispatch(prepared, attempt, new ExecutionBudgetPort.Demand(run.identity().runRef(), attempt, prepared.fingerprint(), category, resources, materials.output().map(value->value.policy().closing()).orElse(false)), materials.configuration().actor(), Optional.empty(), events);
    }

    /** 公开等待只包含客户端确实需要的请求；opaque continuation state只保存在私有实际结果。 */
    private ExecutionStore.Event waiting(Run run, ExecutionStore.WaitRef reference, ToolOutcome.InputRequired input) {
        var value = AgentExecutionCodec.object(); value.put("actionId", reference.action().actionId());
        value.put("generation", Long.toString(reference.generation())); value.put("kind", "tool"); value.putNull("question");
        value.put("inputId", run.identity().causationInputId().value()); value.put("rootInputId", run.identity().trace().rootInputId().value());
        value.put("traceId", run.identity().trace().traceId()); value.put("spanId", run.identity().trace().spanId()); value.put("agentId", run.identity().agentId().value());
        var requests = value.putObject("requests"); new java.util.TreeMap<>(input.inputRequests()).forEach((key, request) -> {
            var item = requests.putObject(key); item.put("method", request.method().wireName()); item.set("params", AgentExecutionCodec.payload(request.params()));
        });
        return new ExecutionStore.Event(new EventId("execution-wait:" + AgentExecutionCodec.digest(reference.action().toString() + ":" + reference.generation())), WAITING_EVENT, codec.encode(WAITING_SCHEMA, value));
    }

    private ExecutionStore.Event waiting(Run run, ExecutionStore.WaitRef reference, String question) {
        var value = trace(run.identity(), run.identity().causationInputId());
        value.put("actionId", reference.action().actionId()); value.put("generation", Long.toString(reference.generation()));
        value.put("kind", "human"); value.put("question", question); value.putObject("requests");
        return new ExecutionStore.Event(new EventId("execution-wait:" + AgentExecutionCodec.digest(reference.action().toString() + ":" + reference.generation())),
                WAITING_EVENT, codec.encode(WAITING_SCHEMA, value));
    }

    /** 系统依赖等待只呈现过程事实，不生成需要用户回复的卡片或暴露私有依赖正文。 */
    private ExecutionStore.Event waiting(Run run, ExecutionStore.WaitRef reference, ExecutionStore.WaitKind kind) {
        if (kind == ExecutionStore.WaitKind.TOOL || kind == ExecutionStore.WaitKind.HUMAN) throw invalid();
        var value = trace(run.identity(), run.identity().causationInputId()); value.put("actionId", reference.action().actionId());
        value.put("generation", Long.toString(reference.generation())); value.put("kind", kind.name().toLowerCase(java.util.Locale.ROOT)); value.putObject("requests"); value.putNull("question");
        return new ExecutionStore.Event(new EventId("execution-wait:" + AgentExecutionCodec.digest(reference.action().toString() + ":" + reference.generation())), WAITING_EVENT, codec.encode(WAITING_SCHEMA, value));
    }

    /** Human卡仅来自SDK真实InteractionView；外部等待没有卡片，不能用question伪造Workflow输入。 */
    private ExecutionStore.Event workflowWaiting(Run run, ExecutionStore.WaitRef reference, ExecutionStore.WorkflowWait waiting, Optional<SessionExecutionWorkflows.HumanInteraction> human) {
        var event = waiting(run, reference, ExecutionStore.WaitKind.WORKFLOW);
        if (human.isEmpty()) return event;
        var value = (com.fasterxml.jackson.databind.node.ObjectNode) codec.parse(event.data(), WAITING_SCHEMA); var workflow = value.putObject("workflow");
        workflow.put("executionId", waiting.execution().executionId()); workflow.put("suspendRef", waiting.suspendRef()); workflow.put("actionKey", waiting.actionKey());
        workflow.put("executionRevision", Long.toString(waiting.executionRevision())); workflow.set("card", AgentExecutionCodec.payload(human.orElseThrow().card()));
        return new ExecutionStore.Event(event.id(), event.type(), codec.encode(WAITING_SCHEMA, value));
    }

    /** 工具process只公开真实确定性与固定结论，私有结果、安全正文和opaque材料均不展开。 */
    private ExecutionStore.Event toolResult(Run run, Prepared prepared, ToolRequest request, ExecutionStore.ResultKind status, boolean outputRejected, ToolOutcome outcome) {
        var value = trace(run.identity(), run.identity().causationInputId()); value.put("actionId", prepared.action().actionId());
        value.put("toolId", request.target().handle().value()); value.put("callId", request.callId()); value.put("status", status.name());
        value.put("outputAccepted", status == ExecutionStore.ResultKind.SUCCEEDED && !outputRejected);
        boolean businessFailed = !outputRejected && outcome instanceof ToolOutcome.Succeeded success
                && codec.parse(success.observation(), SessionRuntimePayloads.TOOL_RESULT_SCHEMA).path("isError").asBoolean(false);
        value.put("businessFailed", businessFailed);
        value.put("summary", businessFailed ? "工具调用失败" : outputRejected ? "工具调用已完成，输出无法采纳" : switch (status) {
            case SUCCEEDED -> "工具调用已完成"; case FAILED -> "工具调用失败"; case UNKNOWN -> "工具结果未知";
            case INPUT_REQUIRED -> throw invalid();
        });
        return new ExecutionStore.Event(new EventId("execution-tool-result:" + AgentExecutionCodec.digest(prepared.action().toString())),
                TOOL_RESULT_EVENT, codec.encode(TOOL_RESULT_SCHEMA, value));
    }

    private ExecutionStore.Event modelResult(Run run, Prepared prepared, ExecutionStore.ResultKind status) {
        return ExecutionModelLifecycle.finished(run, prepared, status, codec);
    }

    /** 只记录已登记的原 Workflow 派发，不推断节点已运行或请求已送达。 */
    private ExecutionStore.Event workflowStarted(Run run, Prepared prepared) {
        var value = trace(run.identity(), run.identity().causationInputId());
        value.put("actionId", prepared.action().actionId());
        value.put("executionId", workflows.decode(prepared.request().content()).executionRef().executionId());
        value.put("status", "DISPATCHED"); value.put("summary", "工作流调用已登记");
        return new ExecutionStore.Event(new EventId("execution-workflow-started:" + AgentExecutionCodec.digest(prepared.action().toString())),
                WORKFLOW_STARTED_EVENT, codec.encode(WORKFLOW_STARTED_SCHEMA, value));
    }

    /** 只公开能力拥有者声明导出的 Completed.output，内部结果、恢复材料及安装配置不进入事件。 */
    private ExecutionStore.Event workflowResult(Run run, Prepared prepared, WorkflowResult result, boolean accepted) {
        var value = trace(run.identity(), run.identity().causationInputId());
        value.put("actionId", prepared.action().actionId()); value.put("executionId", workflowExecution(result).executionId());
        String status; String summary; java.util.OptionalLong revision;
        switch (result) {
            case WorkflowResult.Completed completed -> { status = "COMPLETED"; summary = accepted ? "工作流已完成" : "工作流已完成，输出无法采纳"; revision = java.util.OptionalLong.of(completed.revision()); }
            case WorkflowResult.Failed failed -> { status = "FAILED"; summary = "工作流执行失败"; revision = failed.revision(); }
            case WorkflowResult.Terminated terminated -> { status = "TERMINATED"; summary = "工作流执行已终止"; revision = java.util.OptionalLong.of(terminated.revision()); }
            case WorkflowResult.Cancelled cancelled -> { status = "CANCELLED"; summary = "工作流执行已取消"; revision = java.util.OptionalLong.of(cancelled.revision()); }
            case WorkflowResult.OutcomeUnknown unknown -> { status = "UNKNOWN"; summary = "工作流结果未知"; revision = unknown.revision(); }
            default -> throw invalid();
        }
        value.put("status", status); value.put("summary", summary);
        if (revision.isPresent()) value.put("executionRevision", Long.toString(revision.getAsLong())); else value.putNull("executionRevision");
        boolean outputAccepted = result instanceof WorkflowResult.Completed && accepted;
        value.put("outputAccepted", outputAccepted);
        if (outputAccepted) value.set("output", AgentExecutionCodec.payload(((WorkflowResult.Completed) result).output())); else value.putNull("output");
        return new ExecutionStore.Event(new EventId("execution-workflow-result:" + AgentExecutionCodec.digest(prepared.action().toString())),
                WORKFLOW_RESULT_EVENT, codec.encode(WORKFLOW_RESULT_SCHEMA, value));
    }

    /** 完成事件只在最终 Store.finish 事务提交后可被 SSE 消费。 */
    private Step.Finish finish(Run run, RunStatus status, String text, String reason, Optional<ResultRef> result) {
        return finish(run,status,text,reason,result,Optional.empty());
    }

    /** 完成引用采用原实际MODEL请求冻结的宿主结果，不在恢复后重算历史成果义务。 */
    private Step.Finish finish(Run run, RunStatus status, String text, String reason, Optional<ResultRef> result,Optional<String> prepared) {
        var value = AgentExecutionCodec.object(); value.put("text", text); value.put("reason", reason); value.put("status", status.name());
        if (result.isPresent()) value.put("resultRef", result.orElseThrow().value()); else value.putNull("resultRef");
        value.put("inputId", run.identity().causationInputId().value());
        value.put("rootInputId", run.identity().trace().rootInputId().value());
        value.put("traceId", run.identity().trace().traceId());
        value.put("spanId", run.identity().trace().spanId());
        value.put("agentId", run.identity().agentId().value());
        value.putArray("evidence");
        if (prepared.isPresent()) value.put("taskResultRef", prepared.orElseThrow()); else value.putNull("taskResultRef");
        var event = new ExecutionStore.Event(finishedEventId(run.identity().runRef()),
                COMPLETED_EVENT, codec.encode(COMPLETED_SCHEMA, value));
        return new Step.Finish(status, List.of(event));
    }

    /** 所有业务消费者按同一原Run精确读取终态事件，不扫描日志或猜测最后模型引用。 */
    public static EventId finishedEventId(RunRef run) {
        return new EventId("execution-finished:" + AgentExecutionCodec.digest(Objects.requireNonNull(run).toString()));
    }

    /** 上下文增量只引用已有实际请求/结果及本次将同事务保存的安全反馈。 */
    private Material context(ResultRef previous, Prepared prepared, CapabilityResult actual) {
        var value = AgentExecutionCodec.object(); value.put("type", "delta"); value.put("previous", previous.value());
        value.put("kind", prepared.kind().name()); value.put("ordinal", Long.toString(prepared.ordinal()));
        value.put("request", prepared.request().reference().value()); value.put("result", actual.result().reference().value());
        if (actual.feedback().isPresent()) value.put("feedback", actual.feedback().orElseThrow().reference().value()); else value.putNull("feedback");
        return material(codec.encode(CONTEXT_SCHEMA, value));
    }

    private Material modelContext(ResultRef previous, Prepared prepared, CapabilityResult actual, List<Boolean> reads) {
        var value = AgentExecutionCodec.object(); value.put("type", "model-delta-current-reads"); value.put("previous", previous.value());
        value.put("kind", prepared.kind().name()); value.put("ordinal", Long.toString(prepared.ordinal()));
        value.put("request", prepared.request().reference().value()); value.put("result", actual.result().reference().value());
        if (actual.feedback().isPresent()) value.put("feedback", actual.feedback().orElseThrow().reference().value()); else value.putNull("feedback");
        var values = value.putArray("currentReads"); reads.forEach(flag -> values.add(flag.booleanValue()));
        return material(codec.encode(CONTEXT_SCHEMA, value));
    }

    private static List<AgentAction.ToolCall> toolCalls(Materials materials) {
        if (materials.problem().isPresent()) return List.of();
        var decision = materials.decision().orElse(null);
        if (decision instanceof AgentDecisionDecoder.DecodedBatch batch) return batch.calls();
        if (decision instanceof AgentDecisionDecoder.Decoded single && single.action() instanceof AgentAction.ToolCall call) return List.of(call);
        return List.of();
    }

    static boolean currentRead(Materials materials, int index) {
        return index < materials.currentReads().size() && materials.currentReads().get(index);
    }

    /** 精确读取当前版本的必要增量字段，拒绝循环、未知字段和伪造引用内容。 */
    private ContextDelta delta(Material material) {
        if (!AgentExecutionCodec.reference(material.content()).equals(material.reference())) throw invalid();
        var value = codec.parse(material.content(), CONTEXT_SCHEMA);
        if ("tool-progress-stop".equals(AgentExecutionCodec.string(value, "type"))) {
            AgentExecutionCodec.fields(value, "type", "previous", "progress");
            return new ProgressDelta(material.reference(), new ResultRef(AgentExecutionCodec.string(value, "previous")), AgentExecutionCodec.readPayload(value.get("progress")));
        }
        if ("human-response".equals(AgentExecutionCodec.string(value, "type"))) {
            AgentExecutionCodec.fields(value, "type", "previous", "tenantId", "actionId", "generation", "input", "response");
            return new HumanDelta(material.reference(), new ResultRef(AgentExecutionCodec.string(value, "previous")),
                    new ExecutionStore.WaitRef(new ActionRef(new com.skillforge.runtime.api.TenantId(AgentExecutionCodec.string(value, "tenantId")),
                            AgentExecutionCodec.string(value, "actionId")), AgentExecutionCodec.number(value, "generation")),
                    new InputId(AgentExecutionCodec.string(value, "input")), new ResultRef(AgentExecutionCodec.string(value, "response")));
        }
        if (Set.of("tool-batch", "tool-batch-stop").contains(AgentExecutionCodec.string(value, "type"))) {
            boolean stopped = "tool-batch-stop".equals(AgentExecutionCodec.string(value, "type"));
            if (stopped) AgentExecutionCodec.fields(value, "type", "previous", "results", "reason"); else AgentExecutionCodec.fields(value, "type", "previous", "results");
            if (!value.get("results").isArray() || value.get("results").size() < 2 || value.get("results").size() > com.skillforge.runtime.api.ToolCallLimits.READ_ONLY_BATCH) throw invalid();
            var results = new ArrayList<BatchDeltaResult>();
            for (var entry : value.get("results")) {
                AgentExecutionCodec.fields(entry, "slot", "reused", "ordinal", "action", "attempt", "request", "result", "feedback");
                if (!entry.get("reused").isBoolean()) throw invalid();
                results.add(new BatchDeltaResult(Math.toIntExact(AgentExecutionCodec.number(entry, "slot")), entry.get("reused").booleanValue(), AgentExecutionCodec.number(entry, "ordinal"), AgentExecutionCodec.string(entry, "action"), AgentExecutionCodec.string(entry, "attempt"), new ResultRef(AgentExecutionCodec.string(entry, "request")),
                        new ResultRef(AgentExecutionCodec.string(entry, "result")), entry.get("feedback").isNull() ? Optional.empty() : Optional.of(new ResultRef(AgentExecutionCodec.string(entry, "feedback")))));
            }
            return new BatchDelta(material.reference(), new ResultRef(AgentExecutionCodec.string(value, "previous")), List.copyOf(results), stopped ? Optional.of(AgentExecutionCodec.string(value, "reason")) : Optional.empty());
        }
        if ("dependency-response".equals(AgentExecutionCodec.string(value, "type"))) {
            AgentExecutionCodec.fields(value, "type", "previous", "kind", "tenantId", "actionId", "generation", "input", "response");
            if (!Set.of(ExecutionStore.WaitKind.DELEGATION.name(), ExecutionStore.WaitKind.PLAN.name()).contains(AgentExecutionCodec.string(value, "kind"))) throw invalid();
            return new DependencyDelta(material.reference(), new ResultRef(AgentExecutionCodec.string(value, "previous")),
                    new ExecutionStore.WaitRef(new ActionRef(new com.skillforge.runtime.api.TenantId(AgentExecutionCodec.string(value, "tenantId")), AgentExecutionCodec.string(value, "actionId")), AgentExecutionCodec.number(value, "generation")),
                    new InputId(AgentExecutionCodec.string(value, "input")), new ResultRef(AgentExecutionCodec.string(value, "response")));
        }
        if ("supplemental-input".equals(AgentExecutionCodec.string(value, "type"))) {
            AgentExecutionCodec.fields(value, "type", "previous", "input", "ordinal", "content");
            return new SupplementalDelta(material.reference(), new ResultRef(AgentExecutionCodec.string(value, "previous")), new InputId(AgentExecutionCodec.string(value, "input")),
                    AgentExecutionCodec.number(value, "ordinal"), new ResultRef(AgentExecutionCodec.string(value, "content")));
        }
        if ("plan-local".equals(AgentExecutionCodec.string(value, "type"))) {
            AgentExecutionCodec.fields(value, "type", "previous", "request", "result");
            return new PlanDelta(material.reference(), new ResultRef(AgentExecutionCodec.string(value, "previous")), new ResultRef(AgentExecutionCodec.string(value, "request")), new ResultRef(AgentExecutionCodec.string(value, "result")));
        }
        var kind = ExecutionStore.Kind.valueOf(AgentExecutionCodec.string(value, "kind"));
        boolean modelReads = "model-delta-current-reads".equals(AgentExecutionCodec.string(value, "type"));
        if (modelReads) {
            if (kind != ExecutionStore.Kind.MODEL) throw invalid();
            AgentExecutionCodec.fields(value, "type", "previous", "kind", "ordinal", "request", "result", "feedback", "currentReads");
        } else if (kind == ExecutionStore.Kind.KNOWLEDGE) AgentExecutionCodec.fields(value, "type", "previous", "kind", "ordinal", "request", "result", "feedback", "injected", "projectionRejected");
        else AgentExecutionCodec.fields(value, "type", "previous", "kind", "ordinal", "request", "result", "feedback");
        if (!modelReads && !"delta".equals(AgentExecutionCodec.string(value, "type"))) throw invalid();
        Optional<List<Boolean>> reads = Optional.empty();
        if (modelReads) {
            if (!value.get("currentReads").isArray()) throw invalid();
            var parsed = new ArrayList<Boolean>();
            value.get("currentReads").forEach(item -> { if (!item.isBoolean()) throw invalid(); parsed.add(item.booleanValue()); });
            reads = Optional.of(List.copyOf(parsed));
        }
        Optional<KnowledgeProjection> injected = Optional.empty();
        if (kind == ExecutionStore.Kind.KNOWLEDGE) {
            if (!value.get("injected").isArray()) throw invalid();
            var ids = new ArrayList<String>(); value.get("injected").forEach(id -> { if (!id.isTextual()) throw invalid(); ids.add(id.textValue()); });
            if (!value.get("projectionRejected").isBoolean()) throw invalid();
            injected = Optional.of(new KnowledgeProjection(List.copyOf(ids), value.get("projectionRejected").booleanValue()));
        }
        return new Delta(material.reference(), new ResultRef(AgentExecutionCodec.string(value, "previous")), kind,
                AgentExecutionCodec.number(value, "ordinal"), new ResultRef(AgentExecutionCodec.string(value, "request")), new ResultRef(AgentExecutionCodec.string(value, "result")),
                value.get("feedback").isNull() ? Optional.empty() : Optional.of(new ResultRef(AgentExecutionCodec.string(value, "feedback"))), injected, reads);
    }

    /** 已保存的原结果通过注册封套恢复确定分类，使用原反馈引用，不查询或重调 Provider。 */
    private CapabilityResult capability(ExecutionStore.Kind actionKind, Material result, Optional<Material> feedback) {
        if (actionKind == ExecutionStore.Kind.DELEGATION) {
            delegations.decodeResult(result.content());
            return new CapabilityResult(ExecutionStore.ResultKind.SUCCEEDED, result, feedback, Optional.empty(), ExecutionBudgetPort.Usage.unreported());
        }
        var snapshot = codec.decodeOutcome(actionKind == ExecutionStore.Kind.WORKFLOW ? workflows.decodeResult(result.content()).outcome() : result.content());
        return new CapabilityResult(resultKind(snapshot), result, feedback, Optional.empty(), ExecutionBudgetPort.Usage.unreported());
    }

    /** 结果确定性只来自原PortalOutcome封套，不能由安全投影或当前业务状态反推。 */
    private static ExecutionStore.ResultKind resultKind(com.skillforge.runtime.api.port.PortalOutcomeSnapshot snapshot) {
        return switch (snapshot.resolution()) {
            case KNOWN_RESULT -> ExecutionStore.ResultKind.SUCCEEDED;
            case KNOWN_FAILURE -> ExecutionStore.ResultKind.FAILED;
            case OUTCOME_UNKNOWN -> ExecutionStore.ResultKind.UNKNOWN;
            case INPUT_REQUIRED -> ExecutionStore.ResultKind.INPUT_REQUIRED;
        };
    }

    /** 恢复读取必须返回指定引用，不接受当前配置或其他材料作为替代。 */
    private Material requireMaterial(Run run, ResultRef reference) {
        var material = Objects.requireNonNull(sources.material(run, reference)); if (!material.reference().equals(reference)) throw invalid(); return material;
    }

    /** 只以实际当前材料生成不可变引用。 */
    private static Material material(TypedPayload payload) { return new Material(AgentExecutionCodec.reference(payload), payload); }

    /** 同一模型回合使用稳定配对身份，不让 Provider 或模型参数另选动作身份。 */
    private static String callId(long ordinal) { return "execution-call:" + ordinal; }

    /** 必要中断恢复的数据只包含精确引用与序号，不保存任何完整历史状态。 */
    private record Delta(ResultRef reference, ResultRef previous, ExecutionStore.Kind kind, long ordinal,
            ResultRef request, ResultRef result, Optional<ResultRef> feedback, Optional<KnowledgeProjection> knowledgeProjection,
            Optional<List<Boolean>> currentReads) implements ContextDelta { }
    private record KnowledgeProjection(List<String> injected, boolean rejected) {
        private KnowledgeProjection { injected = List.copyOf(injected); if (rejected && !injected.isEmpty()) throw invalid(); }
    }
    private record HumanDelta(ResultRef reference, ResultRef previous, ExecutionStore.WaitRef waiting, InputId input,
            ResultRef response) implements ContextDelta { }
    /** 完整工具回合只保存有序真实材料引用，不重复保存任何消息或结果正文。 */
    private record BatchDelta(ResultRef reference, ResultRef previous, List<BatchDeltaResult> results, Optional<String> stoppedReason) implements ContextDelta { }
    /** 每槽精确Attempt及请求/结果/安全反馈；恢复不查当前工具或重跑Provider。 */
    private record BatchDeltaResult(int slot, boolean reused, long ordinal, String action, String attempt, ResultRef request, ResultRef result, Optional<ResultRef> feedback) { }
    /** 系统响应只引用已提交输入和原等待，活跃采纳及恢复使用同一纯转换。 */
    private record DependencyDelta(ResultRef reference, ResultRef previous, ExecutionStore.WaitRef waiting, InputId input, ResultRef response) implements ContextDelta { }
    /** 稳定输入序号和原材料引用足以纯重建补充消息，不复制整个上下文。 */
    private record SupplementalDelta(ResultRef reference, ResultRef previous, InputId input, long ordinal, ResultRef content) implements ContextDelta { }
    /** 本地Plan业务已提交的原请求与实际结果引用，不把业务状态复制进执行context。 */
    private record PlanDelta(ResultRef reference, ResultRef previous, ResultRef request, ResultRef result) implements ContextDelta { }
    private record ProgressDelta(ResultRef reference, ResultRef previous, TypedPayload progress) implements ContextDelta { }
    private sealed interface ContextDelta permits Delta, HumanDelta, BatchDelta, DependencyDelta, SupplementalDelta, PlanDelta, ProgressDelta {
        ResultRef reference();
        ResultRef previous();
    }

    /** 固定原因不暴露用户正文或模型私有输出。 */
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("execution_program_invalid"); }
}
