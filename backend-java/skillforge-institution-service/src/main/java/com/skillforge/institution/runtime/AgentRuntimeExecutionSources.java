package com.skillforge.institution.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;
import com.skillforge.runtime.adapter.jdbc.budget.JdbcExecutionBudgets;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore;
import com.skillforge.runtime.adapter.jdbc.identity.SessionPolicyResource;
import com.skillforge.runtime.adapter.jdbc.identity.ModelPolicyResource;
import com.skillforge.runtime.adapter.jdbc.identity.ToolPolicyResource;
import com.skillforge.runtime.adapter.jdbc.identity.KnowledgePolicyResource;
import com.skillforge.runtime.adapter.jdbc.identity.WorkflowPolicyResource;
import com.skillforge.core.knowledge.integration.runtime.knowledge.JdbcKnowledgeBindings;
import com.skillforge.runtime.adapter.jdbc.model.JdbcModelBindings;
import com.skillforge.core.capability.integration.runtime.tool.JdbcMcpBindings;
import com.skillforge.core.workflow.integration.runtime.workflow.JdbcWorkflowBindings;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.media.ModelContent;
import com.skillforge.runtime.api.port.CapabilitySnapshotRef;
import com.skillforge.runtime.api.port.CancellationSignal;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.port.ExecutionBudgetScope;
import com.skillforge.runtime.api.port.IdempotencyKey;
import com.skillforge.runtime.api.port.IdentityPolicyPort.AuthorizationRequest;
import com.skillforge.runtime.api.port.IdentityPolicyPort.AuthorizationResult;
import com.skillforge.runtime.api.port.ModelPort.ModelMessage;
import com.skillforge.runtime.api.port.ModelPort.ModelRole;
import com.skillforge.runtime.api.port.MediaPort;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.skillforge.runtime.api.port.ToolPortal.FrozenToolTarget;
import com.skillforge.runtime.api.port.ToolPortal.ToolBinding.ProviderType;
import com.skillforge.runtime.api.port.KnowledgePortal.FrozenKnowledgeTarget;
import com.skillforge.runtime.api.port.WorkflowPortal.FrozenWorkflowTarget;
import com.skillforge.runtime.api.ToolEffect;
import com.skillforge.runtime.api.port.DelegationCommand;
import com.skillforge.runtime.session.SessionExecutionDelegations;
import com.skillforge.institution.team.TeamWorkService;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.core.LoopBudget;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.AgentExecutionContextViews;
import com.skillforge.runtime.session.AgentExecutionProgram;
import com.skillforge.runtime.session.AgentExecutionOutputs;
import com.skillforge.runtime.session.AgentExecutionModelMedia;
import com.skillforge.runtime.session.ExecutionCoordinator;
import com.skillforge.runtime.session.SessionExecutionWorkflows;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.spi.SessionContextSummary;
import com.skillforge.runtime.session.spi.SessionPreparations;
import com.skillforge.runtime.session.spi.SessionTokenCounter;
import com.skillforge.runtime.session.spi.SessionToolDirectory;
import com.skillforge.runtime.starter.AgentRuntimeModelConfiguration;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.ibatis.session.SqlSession;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 新执行主链的真实宿主材料来源与当前派发授权；仅由隔离候选装配显式创建。
 * 每个drive读取一次原配置、模型绑定、挂载、身份、输入及预算观察，正常步骤消费同一frame。
 * frame仅在所属drive期间存在；活动控制线程可确认同一真实区间，不替代当前IAM或挂载停用检查。
 */
public final class AgentRuntimeExecutionSources implements AgentExecutionProgram.Sources {
    private static final String COMPACTION_EVENT = "agent.context-compaction-attempt";
    private static final String COMPACTION_SCHEMA = "skillforge.runtime.execution.context-compaction-attempt.v1";
    private final AgentRuntimeModelConfiguration configuration;
    private final JdbcModelBindings models;
    private final JdbcMcpBindings mcp;
    private final JdbcKnowledgeBindings knowledge;
    private final JdbcWorkflowBindings workflows;
    private final MediaPort media;
    private final AgentRuntimePublishedCapabilities capabilities;
    private final AgentRuntimeExecutionCatalog catalogs;
    private final JdbcIdentityPolicyPort identities;
    private final SessionTokenCounter tokens;
    private final ExecutionBudgetPort budgets;
    private final ExecutionStore store;
    private final AgentRunBindings runs;
    private final ObjectProvider<AgentDelegationHost> delegations;
    private final ObjectProvider<InstitutionPlanHost> plans;
    private final ObjectProvider<TeamWorkService> teams;
    private final ObjectProvider<InstitutionOutputContract> outputs;
    private final CodecLimits limits;
    private final AgentExecutionCodec codec;
    private final SessionExecutionWorkflows workflowRequests;
    private final java.time.Clock clock;
    private final GroupMemoryRecall memoryRecall;
    private final SessionContextSummary summaries;
    private final ObjectMapper json;
    private final AgentRuntimeCompactionMapper compactions;
    /** 同一Run仅有一个drive frame；计时器只观察活动区间，业务材料由drive加载一次。 */
    private final Map<RunRef, Acquired> active = new ConcurrentHashMap<>();
    /** 防止复用平台线程时嵌套或错误释放其他Run的作用域。 */
    private final ThreadLocal<RunRef> owned = new ThreadLocal<>();

    /** 连接真实事实拥有者；不能以空配置、放行授权或内存账本替代生产依赖。 */
    public AgentRuntimeExecutionSources(AgentRuntimeModelConfiguration configuration, JdbcModelBindings models, JdbcMcpBindings mcp,
            JdbcKnowledgeBindings knowledge,
            JdbcWorkflowBindings workflows,
            MediaPort media,
            AgentRuntimePublishedCapabilities capabilities, AgentRuntimeExecutionCatalog catalogs, JdbcIdentityPolicyPort identities, SessionTokenCounter tokens,
            ExecutionBudgetPort budgets, ExecutionStore store, AgentRunBindings runs, AgentRuntimeProperties properties,
            ObjectProvider<AgentDelegationHost> delegations, ObjectProvider<InstitutionPlanHost> plans, ObjectProvider<TeamWorkService> teams,
            ObjectProvider<InstitutionOutputContract> outputs, java.time.Clock clock, GroupMemoryRecall memoryRecall,
            SessionContextSummary summaries, ObjectMapper json, AgentRuntimeCompactionMapper compactions) {
        this.configuration = Objects.requireNonNull(configuration); this.models = Objects.requireNonNull(models); this.mcp = Objects.requireNonNull(mcp);
        this.knowledge = Objects.requireNonNull(knowledge);
        this.workflows = Objects.requireNonNull(workflows);
        this.media = Objects.requireNonNull(media);
        this.capabilities = Objects.requireNonNull(capabilities); this.identities = Objects.requireNonNull(identities);
        this.catalogs = Objects.requireNonNull(catalogs);
        this.tokens = Objects.requireNonNull(tokens); this.budgets = Objects.requireNonNull(budgets);
        this.store = Objects.requireNonNull(store); this.runs = Objects.requireNonNull(runs); Objects.requireNonNull(properties);
        this.delegations = Objects.requireNonNull(delegations);
        this.plans = Objects.requireNonNull(plans); this.teams = Objects.requireNonNull(teams);
        this.outputs = Objects.requireNonNull(outputs);
        this.clock = Objects.requireNonNull(clock);
        this.memoryRecall = Objects.requireNonNull(memoryRecall);
        this.summaries = Objects.requireNonNull(summaries); this.json = Objects.requireNonNull(json);
        this.compactions = Objects.requireNonNull(compactions);
        limits = new CodecLimits(properties.getMaxPayloadCodeUnits(), properties.getMaxPayloadNodes(), properties.getMaxPayloadDepth());
        codec = new AgentExecutionCodec(limits);
        workflowRequests = new SessionExecutionWorkflows(limits);
    }

    /** 新实际MODEL请求的UTC样本，时钟采样不依赖输入正文、历史或用户时区。 */
    @Override public com.skillforge.runtime.session.AgentExecutionTime.Sample sampleModelTime(ExecutionStore.Run run) {
        return com.skillforge.runtime.session.AgentExecutionTime.sample(clock);
    }

    @Override public boolean currentRead(SessionToolDirectory.Entry frozen, TypedPayload arguments) {
        return capabilities.currentRead(frozen, arguments);
    }

    /** 默认记忆只在新准备时选择；采纳实际结果时仅恢复原请求消息，不执行 I/O。 */
    @Override public List<ModelMessage> modelContext(ExecutionStore.Run run, AgentExecutionProgram.Configuration configuration,
            List<ModelMessage> history, Optional<List<ModelMessage>> frozenRequestMessages) {
        if (frozenRequestMessages.isPresent())
            return memoryRecall.restore(run.identity(), history, frozenRequestMessages.orElseThrow());
        var frame = requireLoaded(run);
        if (!frame.configuration.equals(configuration)) throw new IllegalStateException("execution_memory_configuration_mismatch");
        var context = new com.skillforge.runtime.api.port.PortCallContext(run.identity(), configuration.actor(),
                new com.skillforge.runtime.api.port.InvocationControl(clock.instant().plusSeconds(30), cancellation(run)));
        return memoryRecall.prepare(context, configuration.initialMessages(), history);
    }

    /** 仅校验本次已注入来源，不扩大到同一挂载内未使用的文档。 */
    @Override public boolean knowledgeSourcesAllowed(ExecutionStore.Run run, List<com.skillforge.runtime.api.KnowledgeSourceVersion> sources) {
        var frame = requireLoaded(run);
        if (!knownKnowledgeSources(frame.configuration, sources)) return false;
        return knowledge.currentSourcesAvailable(run.identity().runRef().tenantId(), sources);
    }

    private static boolean knownKnowledgeSources(AgentExecutionProgram.Configuration configuration,
            List<com.skillforge.runtime.api.KnowledgeSourceVersion> sources) {
        return sources.stream().allMatch(source -> configuration.knowledge().stream()
                .anyMatch(policy -> policy.target().sourceVersions().contains(source)));
    }

    /** 辅助摘要沿用父Run预算作用域、剩余活动时间和可信取消信号，不另开无限执行窗口。 */
    @Override public SessionContextSummary.Outcome summarizeContext(ExecutionStore.Run run,
            AgentExecutionProgram.Configuration configuration, List<ModelMessage> history, List<ModelMessage> currentContext,
            String sourceDigest, int maxOutputTokens, long ordinal) {
        var frame = requireLoaded(run);
        if (!frame.configuration.equals(configuration)) throw new IllegalStateException("execution_summary_configuration_mismatch");
        var budget = (LoopBudget.Enforced) configuration.budget();
        var remaining = budget.maxWallTime().minus(elapsed(frame));
        if (remaining.isZero() || remaining.isNegative()) return SessionContextSummary.Outcome.unavailable("SUMMARY_TIME_EXHAUSTED");
        var cancelled = cancellation(run);
        return summaries.summarize(new SessionContextSummary.Request(
                new SessionPreparations.Key(run.identity(), "agent-execution", Math.toIntExact(ordinal)), configuration.actor(),
                sourceDigest, history, currentContext, maxOutputTokens, remaining, cancelled::isCancellationRequested));
    }

    /** 冷却是同owner和冻结配置的已提交事实，跨Run恢复只采用最大事件序号。 */
    @Override public Optional<AgentExecutionContextViews.Cooldown> contextCompactionCooldown(ExecutionStore.Run run,
            AgentExecutionProgram.Configuration configuration) {
        var frame = requireLoaded(run);
        if (!frame.configuration.equals(configuration)) throw new IllegalStateException("execution_summary_configuration_mismatch");
        String scope = AgentExecutionContextViews.scope(configuration, configuration.modelTools());
        AgentExecutionContextViews.Cooldown latest = null; long latestSequence = 0; long after = 0;
        while (true) {
            var page = compactions.forOwner(run.identity().owner(), COMPACTION_EVENT, after, 100);
            long next = after;
            for (var saved : page) {
                if (saved.sequence() <= after) throw new IllegalStateException("execution_context_compaction_event_invalid");
                next = Math.max(next, saved.sequence());
                if (!COMPACTION_SCHEMA.equals(saved.schemaId())) throw new IllegalStateException("execution_context_compaction_event_invalid");
                try {
                    var value = json.readTree(saved.canonicalJson());
                    var savedScope = value == null ? null : value.get("scope");
                    if (savedScope == null || !savedScope.isTextual() || savedScope.textValue().isBlank())
                        throw new IllegalStateException("execution_context_compaction_event_invalid");
                    if (!scope.equals(savedScope.textValue())) continue;
                    var failures = value.get("failures"); var until = value.get("until");
                    if (failures == null || !failures.isTextual() || !failures.textValue().matches("0|[1-9][0-9]*")
                            || until == null || !until.isTextual()) throw new IllegalStateException("execution_context_compaction_event_invalid");
                    var cooldown = new AgentExecutionContextViews.Cooldown(Integer.parseInt(failures.textValue()), Instant.parse(until.textValue()));
                    if (saved.sequence() > latestSequence) { latest = cooldown; latestSequence = saved.sequence(); }
                } catch (java.io.IOException | IllegalArgumentException | java.time.DateTimeException invalid) {
                    throw new IllegalStateException("execution_context_compaction_event_invalid", invalid);
                }
            }
            if (page.size() < 100) return Optional.ofNullable(latest);
            after = next;
        }
    }

    /** 由已占有执行槽位的gateway在drive前建立单调时钟和临时材料作用域，不访问数据库。 */
    public void begin(RunRef run) {
        Objects.requireNonNull(run); if (owned.get() != null) throw new IllegalStateException("execution_sources_already_active");
        var frame=new Acquired(run,System.nanoTime());
        if (active.putIfAbsent(run, frame) != null)
            throw new IllegalStateException("execution_sources_run_already_active");
        owned.set(run);
        frame.budgetScope=ExecutionBudgetScope.open(new ExecutionBudgetScope.Binding(budgets,run,()->frame.outputClosing,
                ()->frame.configuration==null?Optional.empty():Optional.of(frame.configuration.target().timeout()),
                ()->Optional.ofNullable(frame.outputSizing),()->elapsed(frame)));
    }

    /** gateway必须在finally释放frame，线程复用不得携带上一个Run的身份或模型配置。 */
    public void end(RunRef run) {
        if (!run.equals(owned.get())) throw new IllegalStateException("execution_sources_owner_mismatch");
        var frame = requireActive(run); frame.closed = true; active.remove(run, frame); owned.remove();
        frame.budgetScope.close();
    }

    /** claim成功后加载一次预算活动基线并绑定控制；计时器登记前完成，避免基线重复包含本drive活动。 */
    void bindControl(RunRef run, ExecutionCoordinator.Control control) {
        var frame = requireActive(run);
        if (frame.control != null) throw new IllegalStateException("execution_sources_control_already_bound");
        frame.budgetSnapshot = budgets.snapshot(run);
        frame.confirmedActive = Duration.ofMillis(frame.budgetSnapshot.runActiveMillis());
        frame.control = Objects.requireNonNull(control);
    }

    /** 同步采纳事务返回的操作者，只替换本drive帧中的身份，冻结绑定与预算不重新读取。 */
    @Override public void actorChanged(ExecutionStore.Run run, VerifiedIdentity actor) {
        var frame = requireLoaded(run);
        if (!actor.owner().equals(run.identity().owner()) || !actor.verificationRef().equals(run.actorVerificationRef()))
            throw new IllegalStateException("execution_sources_actor_mismatch");
        frame.configuration = frame.configuration.withActor(actor);
    }

    /** 仅首次领取/恢复加载真实材料；没有sourceRun、旧checkpoint或内部阶段journal读取。 */
    @Override public AgentExecutionProgram.Configuration configuration(ExecutionStore.Run run, ResultRef input) {
        var frame = requireActive(run.identity().runRef());
        if (frame.configuration != null) {
            if (!frame.input.equals(input)) throw new IllegalStateException("execution_sources_input_changed");
            return frame.configuration;
        }
        var profile = configuration.preparationProfile(run.identity().runRef().tenantId(), run.configuration())
                .orElseThrow(() -> new IllegalStateException("execution_preparation_profile_missing"));
        var model = models.findForOwner(profile.binding(), run.identity().agentId())
                .orElseThrow(() -> new IllegalStateException("execution_model_binding_missing"));
        if (!model.strategy().equals(profile.strategy())) throw new IllegalStateException("execution_model_strategy_mismatch");
        var binding = runs.find(run.identity().runRef()).orElseThrow(() -> new IllegalStateException("execution_agent_binding_missing"));
        if (!binding.owner().equals(run.identity().owner()) || !binding.actor().equals(run.identity().agentId()))
            throw new IllegalStateException("execution_agent_binding_mismatch");
        var catalog = catalogs.read(material(run, AgentRuntimeExecutionCatalog.reference(run.identity().runRef())), run.identity());
        var tools = catalog.tools();
        var actor = identities.originalVerification(run.identity().owner(), run.actorVerificationRef())
                .orElseThrow(() -> new IllegalStateException("execution_original_identity_missing"));
        var original = material(run, input);
        var messages = new ArrayList<ModelMessage>();
        messages.add(new ModelMessage("execution-system:" + run.identity().runRef().runId(), ModelRole.SYSTEM, profile.systemPrompt().render()));
        var userHistory = codec.decodeInput(original.content());
        if (userHistory.stream().anyMatch(message -> message.role() == ModelRole.SYSTEM))
            throw new IllegalStateException("execution_input_system_role_rejected");
        messages.addAll(userHistory);
        var observed = frame.budgetSnapshot;
        if (observed == null) throw new IllegalStateException("execution_sources_budget_not_loaded");
        var policy = observed.registration().policy(); policy.requireEnforced();
        var budget = LoopBudget.from(policy);
        // 引用所属AgentRunBindings的真实不可变挂载字段，不伪造旧capability freeze或另一份执行快照。
        String mountReference = "agent-run-binding:" + run.identity().runRef().runId() + ":mounts";
        var snapshot = new CapabilitySnapshotRef(run.identity().owner().sessionRef(), run.identity().causationInputId(),
                mountReference, binding.member().mounts().generation());
        frame.runBinding = binding; frame.modelBinding = model; frame.input = input; frame.inputPayload = codec.frozenInput(original.content());
        var mcpBindings = new java.util.LinkedHashMap<FrozenToolTarget, JdbcMcpBindings.Binding>();
        for (var tool : tools) {
            if (tool.binding().providerType() != ProviderType.MCP) continue;
            var frozen = mcp.find(run.identity().runRef().tenantId(), tool.target())
                    .orElseThrow(() -> new IllegalStateException("execution_mcp_binding_missing"));
            if (!frozen.target().equals(tool.target()) || !frozen.providerRef().equals(tool.binding().providerRef())
                    || !frozen.toolName().equals(tool.binding().toolName()))
                throw new IllegalStateException("execution_mcp_binding_mismatch");
            if (mcpBindings.putIfAbsent(tool.target(), frozen) != null)
                throw new IllegalStateException("execution_mcp_binding_duplicate");
        }
        frame.mcpBindings = Map.copyOf(mcpBindings);
        var knowledgePolicies = catalog.knowledge();
        var knowledgeBindings = new java.util.LinkedHashMap<FrozenKnowledgeTarget, JdbcKnowledgeBindings.Binding>();
        for (var selected : knowledgePolicies) {
            var target = selected.target();
            var frozen = knowledge.find(run.identity().runRef().tenantId(), target)
                    .orElseThrow(() -> new IllegalStateException("execution_knowledge_binding_missing"));
            if (!frozen.target().equals(target) || knowledgeBindings.putIfAbsent(target, frozen) != null)
                throw new IllegalStateException("execution_knowledge_binding_mismatch");
        }
        frame.knowledgeBindings = Map.copyOf(knowledgeBindings);
        var workflowBindings = new java.util.HashMap<FrozenWorkflowTarget, JdbcWorkflowBindings.Binding>();
        for (var selected : catalog.workflows()) {
            var target = selected.target();
            var frozen = workflows.find(run.identity().runRef().tenantId(), target)
                    .orElseThrow(() -> new IllegalStateException("execution_workflow_binding_missing"));
            if (!frozen.target().equals(target) || workflowBindings.putIfAbsent(target, frozen) != null)
                throw new IllegalStateException("execution_workflow_binding_mismatch");
        }
        frame.workflowBindings = Map.copyOf(workflowBindings);
        var modelTools = catalog.initialIntent().isPresent()?List.<com.skillforge.runtime.api.port.ModelPort.ModelTool>of():AgentExecutionProgram.modelTools(profile, tools, catalog.identities(), knowledgePolicies, catalog.skills(), catalog.workflows(),
                AgentDelegationHost.frozenTargetHandles(binding));
        frame.configuration = new AgentExecutionProgram.Configuration(profile, model.target(), modelTools, tools, budget,
                snapshot, new ResultRef(mountReference), List.copyOf(messages), actor, List.copyOf(knowledgePolicies), catalog.skills(), catalog.availableCapabilities(),
                catalog.workflows(), catalog.initialPlanState(), catalog.planRequired(),catalog.initialIntent());
        return frame.configuration;
    }

    /** 原请求和结果恢复仅精确读取已提交材料引用。 */
    @Override public ExecutionStore.Material material(ExecutionStore.Run run, ResultRef reference) {
        return store.material(run.identity().runRef().tenantId(), reference)
                .orElseThrow(() -> new IllegalStateException("execution_material_missing"));
    }

    /** 恢复MODEL manifest时按原引用一次批读，不重新生成历史正文。 */
    @Override public List<Optional<ExecutionStore.Material>> materials(ExecutionStore.Run run, List<ResultRef> references) {
        return store.materials(run.identity().runRef().tenantId(), references);
    }

    /** 独立成果引用只由宿主真实冻结结果提供；由完成事件随Store.finish原子持久化。 */
    @Override public Optional<String> preparedResult(ExecutionStore.Run run) {
        return outputs.getObject().preparedResult(run.identity());
    }

    /** 实际能力结果采用前校验其明确公开字段；拒绝随同一次业务采纳提交，不另扫旧日志。 */
    @Override public Optional<String> capabilityContentRejection(ExecutionStore.Run run,List<TypedPayload> payloads) {
        return outputs.getObject().capabilityContentRejection(run.identity(),payloads);
    }

    /** 最终正文的公开校验来自真实宿主范围，不能只在SSE展示层替换后伪报完成。 */
    @Override public Optional<String> publicContentRejection(ExecutionStore.Run run,List<String> texts) {
        return outputs.getObject().publicContentRejection(run.identity(),texts);
    }

    /** 模型只观察真实账本快照，不能据此取得派发许可。 */
    @Override public Optional<ExecutionBudgetPort.Snapshot> budgetSnapshot(ExecutionStore.Run run) { return Optional.of(budgets.snapshot(run.identity().runRef())); }

    /** 当前宿主义务只冻结进新实际请求；两类动作拒绝共用同一正式成果状态。 */
    @Override public Optional<AgentExecutionOutputs.Policy> prepareOutput(ExecutionStore.Run run,AgentExecutionProgram.Configuration configuration) {
        var frame=requireLoaded(run);if(!frame.configuration.equals(configuration))throw new IllegalStateException("execution_output_configuration_mismatch");
        var policy=outputs.getObject().prepareOutput(run,frame.runBinding,configuration.tools());
        if(!(budgets instanceof JdbcExecutionBudgets jdbc))throw new IllegalStateException("execution_transactional_budget_required");
        return Optional.of(policy.withClosing(jdbc.phase(run.identity().runRef())==ExecutionBudgetPort.Phase.CLOSING));
    }

    /** 真实计量保护有界收尾；进入CLOSING后只有冻结允许目标可使用保护，UNKNOWN预留不退款。 */
    @Override public boolean admitOutput(ExecutionStore.Run run,AgentExecutionProgram.Configuration configuration,AgentExecutionOutputs.Policy policy,long inputTokens,long outputTokens,long closingInputTokens) {
        var frame=requireLoaded(run);var reference=run.identity().runRef();
        frame.outputClosing=policy.closingSpec();frame.outputSizing=new ExecutionBudgetScope.ClosingSizing(ExecutionBudgetPort.Category.BUSINESS_MODEL,closingInputTokens,outputTokens,configuration.target().timeout());
        if(!(budgets instanceof JdbcExecutionBudgets jdbc))throw new IllegalStateException("execution_transactional_budget_required");
        // 准备阶段已实际消耗的活动先确认；准入仍在原预算根锁内原子核对全部资源与时间。
        budgets.recordActivity(reference,frame.intervalId,frame.startedAt,frame.startedAt.plusNanos(Math.max(0,System.nanoTime()-frame.startedNanos)));
        var snapshot=budgets.snapshot(reference);
        var calculation=frame.outputSizing.calculation(policy.closingSpec(),snapshot.closingModelActiveMillis());
        if(snapshot.phase()==ExecutionBudgetPort.Phase.CLOSING && calculation.modelWindowMillis()==0) {
            long used=snapshot.closingModelActiveMillis(),limit=configuration.target().timeout().toMillis();
            var fact=new ExecutionBudgetPort.ClosingShortfall(ExecutionBudgetPort.BalanceScope.RUN,"MODEL_WINDOW_MILLIS",
                    Math.addExact(used,1),limit,Math.addExact(used,1)-limit,used,0,limit);
            throw new AgentExecutionProgram.OutputBudgetInsufficient(new ExecutionBudgetPort.ClosingFailure(snapshot.phase(),List.of(fact),calculation));
        }
        var protection=frame.outputSizing.size(policy.closingSpec(),snapshot.closingModelActiveMillis());
        var demand=ExecutionBudgetPort.Resources.call(ExecutionBudgetPort.Category.BUSINESS_MODEL,inputTokens,outputTokens);
        try {return jdbc.admitClosingAndChoosePhase(reference,new ExecutionBudgetPort.ClosingAdmission(protection,protection),policy.closingSpec().toolHandles(),demand);}
        catch(ExecutionBudgetPort.ClosingInsufficient rejected){throw new AgentExecutionProgram.OutputBudgetInsufficient(rejected.failure(calculation));}
    }

    /** Provider取得的是本次原冻结上限与原账本实际剩余共同约束的deadline。 */
    public Duration effectiveTimeout(ExecutionStore.Run run,ExecutionBudgetPort.Category category,Duration configured) {
        var frame=requireLoaded(run);var snapshot=budgets.snapshot(run.identity().runRef());
        var sizing=frame.outputSizing;
        if(sizing==null)sizing=new ExecutionBudgetScope.ClosingSizing(ExecutionBudgetPort.Category.BUSINESS_MODEL,0,0,frame.configuration.target().timeout());
        long unconfirmed=Math.max(0,elapsed(frame).toMillis()-snapshot.runActiveMillis());
        return ExecutionBudgetScope.effectiveTimeout(snapshot,sizing,frame.outputClosing,category,configured,unconfirmed);
    }

    /** 收尾模型实际区间写入原活动账本；跨attempt和drive恢复共用原窗口，不依赖本地累加器。 */
    public <T>T modelActivity(ExecutionStore.Run run,String attempt,boolean closing,java.util.function.Supplier<T> work) {
        if(!closing)return work.get();
        var frame=requireLoaded(run);var activity=new ClosingModelActivity(ExecutionBudgetScope.CLOSING_MODEL_INTERVAL_PREFIX+attempt,clock.instant(),System.nanoTime());
        synchronized(frame){if(frame.closingModelActivity!=null)throw new IllegalStateException("execution_closing_model_already_active");frame.closingModelActivity=activity;}
        Throwable failure=null;
        try {budgets.recordActivity(run.identity().runRef(),activity.id(),activity.start(),activity.start());return work.get();}
        catch(RuntimeException|Error rejected){failure=rejected;throw rejected;}
        finally {
            try {budgets.recordActivity(run.identity().runRef(),activity.id(),activity.start(),activity.through());}
            catch(RuntimeException|Error rejected){if(failure!=null)failure.addSuppressed(rejected);else throw rejected;}
            finally {synchronized(frame){frame.closingModelActivity=null;}}
        }
    }

    /** 使用正式tokenizer计量实际消息与同一冻结工具，不按字符长度估算Token。 */
    @Override public SessionTokenCounter.Measurement measure(AgentExecutionProgram.Configuration configuration, List<ModelMessage> messages, List<com.skillforge.runtime.api.port.ModelPort.ModelTool> tools) {
        return tokens.measure(configuration.profile().tokenizerProfileRef(), configuration.target(), messages, tools);
    }

    /**
     * 新模型派发前实际准备每个图片位置；计量随后针对返回消息，映射随请求原子冻结。
     * 已保存请求的恢复由Session核对原mapping，不调用本入口，也不卸载或替换历史图片。
     */
    @Override public AgentExecutionModelMedia.Prepared prepareModelMessages(ExecutionStore.Run run,
            AgentExecutionProgram.Configuration configuration, List<ModelMessage> originals) {
        var frame = requireLoaded(run);
        if (!frame.configuration.equals(configuration)) throw new IllegalStateException("execution_media_configuration_mismatch");
        originals = List.copyOf(originals);
        var owner = run.identity().owner().sessionRef(); var policy = configuration.target().capabilities().imagePolicy();
        int images = 0;
        for (var message : originals) for (var part : message.contents()) {
            if (!(part instanceof ModelContent.Image image)) continue;
            if (!policy.enabled() || message.role() == ModelRole.TOOL && !policy.toolImages())
                throw new IllegalStateException("model_image_unsupported");
            if (!image.media().owner().equals(owner)) throw new IllegalStateException("model_media_owner_mismatch");
            if (++images > policy.maxImages()) throw new IllegalStateException("model_image_count_exceeded");
        }
        if (images == 0) return new AgentExecutionModelMedia.Prepared(originals, List.of());
        var messages = new ArrayList<ModelMessage>();
        var transformations = new ArrayList<AgentExecutionModelMedia.MediaTransformation>();
        for (int messageIndex = 0; messageIndex < originals.size(); messageIndex++) {
            var message = originals.get(messageIndex); var contents = new ArrayList<ModelContent>();
            for (int contentIndex = 0; contentIndex < message.contents().size(); contentIndex++) {
                var part = message.contents().get(contentIndex);
                if (part instanceof ModelContent.Image image) {
                    if (frame.closed || frame.control.cancelled() || frame.control.ownershipLost())
                        throw new IllegalStateException("execution_media_preparation_cancelled");
                    var prepared = Objects.requireNonNull(media.prepare(owner, image.media(), policy));
                    if (!prepared.owner().equals(owner) || prepared.byteLength() > policy.maxImageBytes()
                            || prepared.width() > policy.maxDimension() || prepared.height() > policy.maxDimension())
                        throw new IllegalStateException("model_image_variant_invalid");
                    contents.add(new ModelContent.Image(prepared, image.detail()));
                    transformations.add(new AgentExecutionModelMedia.MediaTransformation(messageIndex, contentIndex, image.media(), prepared));
                } else contents.add(part);
            }
            messages.add(new ModelMessage(message.messageId(), message.role(), contents, message.toolCalls(), message.toolCallId(), message.reasoningContent(), message.protocolState()));
        }
        var prepared = new AgentExecutionModelMedia.Prepared(messages, transformations);
        prepared.requireCorrespondence(originals);
        return prepared;
    }

    /** 无数据库getter；已确认活动观察加本drive单调时间，不把历史人工等待加入时长。 */
    @Override public Duration elapsed(ExecutionStore.Run run) {
        var frame = requireActive(run.identity().runRef());
        return elapsed(frame);
    }
    /** 跨线程预算观察只使用已确认基线和本地单调增量，不访问数据库。 */
    private static Duration elapsed(Acquired frame) {
        if(frame.closed)throw new IllegalStateException("execution_sources_not_active");
        return frame.confirmedActive.plusNanos(Math.max(0, System.nanoTime() - frame.startedNanos));
    }

    /** 实际Provider消费同一次configuration已加载的精确JDBC模型绑定，不重新解析当前模型来源。 */
    public JdbcModelBindings.Binding modelBinding(ExecutionStore.Run run) {
        var frame = requireLoaded(run); return frame.modelBinding;
    }

    /** 本drive领域参与者只读原成员绑定；必须是当前线程拥有的同一已加载Run。 */
    public AgentRunBindings.Binding runBinding(RunRef run) {
        if (!Objects.requireNonNull(run).equals(owned.get())) throw new IllegalStateException("execution_sources_owner_mismatch");
        var frame = requireActive(run);
        if (frame.configuration == null) throw new IllegalStateException("execution_sources_not_loaded");
        return frame.runBinding;
    }

    /** 实际Handler消费原已保存业务输入及附件/上下文引用，不能由模型参数重建身份。 */
    public TypedPayload inbound(ExecutionStore.Run run) { return requireLoaded(run).inputPayload; }

    /** 已冻结MCP精确路由由本drive首次材料加载拥有，Provider不再次做supports或来源查询。 */
    public JdbcMcpBindings.Binding mcpBinding(ExecutionStore.Run run, FrozenToolTarget target) {
        var binding = requireLoaded(run).mcpBindings.get(Objects.requireNonNull(target));
        if (binding == null) throw new IllegalStateException("execution_mcp_binding_not_loaded");
        return binding;
    }

    /** 实际检索只消费本drive已加载的原精确绑定，不再查active revision或另存一份Provider结果。 */
    public JdbcKnowledgeBindings.Binding knowledgeBinding(ExecutionStore.Run run, FrozenKnowledgeTarget target) {
        var binding = requireLoaded(run).knowledgeBindings.get(Objects.requireNonNull(target));
        if (binding == null) throw new IllegalStateException("execution_knowledge_binding_not_loaded");
        return binding;
    }

    /** Workflow仅消费本drive一次读取的原精确安装闭包，不在实际调用前改绑当前实例。 */
    public JdbcWorkflowBindings.Binding workflowBinding(ExecutionStore.Run run, FrozenWorkflowTarget target) {
        var binding = requireLoaded(run).workflowBindings.get(Objects.requireNonNull(target));
        if (binding == null) throw new IllegalStateException("execution_workflow_binding_not_loaded");
        return binding;
    }

    /** 额外参数来自原受理输入，不能用模型工具参数或恢复来源图替代。 */
    public Optional<TypedPayload> extraParameters(ExecutionStore.Run run) {
        return AgentRuntimeMcpExtraParameters.fromInbound(requireLoaded(run).inputPayload);
    }

    /** 捕获本drive单调取消信号；结束、取消或失租均阻止Provider继续发送。 */
    public CancellationSignal cancellation(ExecutionStore.Run run) {
        var frame = requireActive(run.identity().runRef());
        var control = frame.control;
        if (control == null) throw new IllegalStateException("execution_sources_control_missing");
        return () -> frame.closed || control.cancelled() || control.ownershipLost();
    }

    /** 将本drive真实活动确认合入派发/结果事务；准确迟到事实的非活动提交不虚构活动区间。 */
    public void confirmActivity(SqlSession session, RunRef run) {
        var frame = active.get(run);
        // 独立接收准确迟到事实时没有活跃区间；计时器由Control持有frame，关闭先等待其续租结束。
        if (frame == null) return;
        if (frame.closed) throw new IllegalStateException("execution_sources_activity_not_active");
        if (!(budgets instanceof JdbcExecutionBudgets jdbc)) throw new IllegalStateException("execution_transactional_budget_required");
        jdbc.recordActivity(session, run, frame.intervalId, frame.startedAt,
                frame.startedAt.plusNanos(Math.max(0, System.nanoTime() - frame.startedNanos)));
        var closing=frame.closingModelActivity;
        if(closing!=null)jdbc.recordActivity(session,run,closing.id(),closing.start(),closing.through());
    }

    /**
     * Store在派发事务内调用本hook；actor来自已加载身份，IAM和精确当前挂载使用同SqlSession。
     * 不调用Provider、Handler，不查旧Session来源，不另开身份或挂载查询连接。
     */
    public boolean allowed(SqlSession session, ExecutionStore.Run run, VerifiedIdentity actor, ExecutionStore.Prepared prepared) {
        var frame = requireLoaded(run); var loaded = frame.configuration;
        if (!loaded.actor().equals(actor)) return false;
        if (prepared.kind() == ExecutionStore.Kind.DELEGATION)
            return delegations.getObject().requireDispatch(session, run, actor, prepared, frame.runBinding) && planAllows(session, run, prepared, frame.runBinding);
        final String action; final TypedPayload resource;
        if (prepared.kind() == ExecutionStore.Kind.MODEL) {
            if (!memoryRecall.allowed(session, run, actor)) return false;
            var modelCall = codec.decodeModelRequest(prepared);
            if (!modelCall.knowledgeSourcesDeclared()) return false;
            if (!modelCall.knowledgeSources().isEmpty() && (!knownKnowledgeSources(loaded, modelCall.knowledgeSources())
                    || !knowledge.currentSourcesAvailable(session, run.identity().runRef().tenantId(), modelCall.knowledgeSources()))) return false;
            var request = modelCall.request();
            if (!request.target().equals(loaded.target()) || !request.requestedStrategy().equals(loaded.profile().strategy())
                    || !prepared.target().equals(request.target().effectiveModelId())
                    || !prepared.capability().equals(loaded.target().capabilities().capabilityFactRef())) return false;
            action = loaded.profile().callKind() == com.skillforge.runtime.api.port.PortalCallKind.MODEL_STREAM ? "model.stream" : "model.generate";
            resource = ModelPolicyResource.dispatch(run.identity(), request.target().binding(), request.requestedStrategy());
        } else if (prepared.kind() == ExecutionStore.Kind.TOOL) {
            var request = codec.decodeTool(prepared.request().content());
            var frozen = SessionToolDirectory.requirePrepared(loaded.tools(), request.target().handle().value());
            if (!frozen.target().equals(request.target()) || !frozen.binding().equals(request.binding())
                    || !prepared.target().equals(request.target().handle().value()) || !prepared.capability().equals(loaded.capabilityRef())) return false;
            try {
                var current = capabilities.requireCurrent(session, run.identity(), request.target(), frame.runBinding);
                if (!current.binding().equals(request.binding())) return false;
            } catch (IllegalArgumentException denied) { return false; }
            action = "tool.invoke";
            resource = ToolPolicyResource.invocation(run.identity(), request.binding(), request.target(), new IdempotencyKey(prepared.action().actionId()));
        } else if (prepared.kind() == ExecutionStore.Kind.KNOWLEDGE) {
            var request = codec.decodeKnowledgeRequest(prepared.request().content());
            var frozen = loaded.knowledge().stream().filter(candidate -> candidate.target().equals(request.target())).toList();
            if (frozen.size() != 1 || !request.snapshot().equals(loaded.capabilitySnapshot())
                    || !prepared.target().equals(request.target().handle().value()) || !prepared.capability().equals(loaded.capabilityRef())) return false;
            var policy = frozen.getFirst(); var query = request.request(); var identity = run.identity();
            if (!query.tenantId().equals(identity.runRef().tenantId()) || !query.requester().equals(identity.requester())
                    || !query.agentId().equals(identity.agentId()) || !query.sessionRef().equals(identity.owner().sessionRef())
                    || !query.trace().equals(identity.trace()) || query.mode() != policy.mode() || !query.intent().equals(policy.intent())
                    || !query.budget().equals(policy.budget()) || !query.asOf().equals(policy.asOf())
                    || !query.freshnessAfter().equals(policy.freshnessAfter()) || query.citationRequired() != policy.citationRequired()) return false;
            try { capabilities.requireCurrentKnowledge(session, run.identity(), request.target(), frame.runBinding); }
            catch (IllegalArgumentException denied) { return false; }
            action = "knowledge.execute";
            resource = KnowledgePolicyResource.dispatch(run.identity(), request.snapshot(), request.target(), request.request(),
                    request.sourceEventId(), new IdempotencyKey(prepared.action().actionId()), prepared.action(), prepared.request().reference(), prepared.fingerprint());
        } else if (prepared.kind() == ExecutionStore.Kind.WORKFLOW) {
            var request = workflowRequests.decode(prepared.request().content());
            var selected = loaded.workflows().stream().filter(value -> value.target().equals(request.target())).toList();
            if (selected.size() != 1 || !request.snapshot().equals(loaded.capabilitySnapshot())
                    || !prepared.target().equals(request.target().handle().value()) || !prepared.capability().equals(loaded.capabilityRef())) return false;
            if (request instanceof SessionExecutionWorkflows.Dispatch dispatch
                    && (!dispatch.parameters().equals(selected.getFirst().parameters()) || !dispatch.evidenceRefs().equals(selected.getFirst().evidenceRefs()))) return false;
            if (request instanceof SessionExecutionWorkflows.Dispatch || request instanceof SessionExecutionWorkflows.Resume) {
                try {
                    if(loaded.initialIntent().isPresent())capabilities.requireCurrentAuthorizedWorkflow(session,run.identity(),request.target(),frame.runBinding);
                    else capabilities.requireCurrentWorkflow(session, run.identity(), request.target(), frame.runBinding);
                }
                catch (IllegalArgumentException denied) { return false; }
            }
            switch (request) {
                case SessionExecutionWorkflows.Dispatch dispatch -> {
                    action = "workflow.dispatch";
                    resource = WorkflowPolicyResource.dispatchResource(run.identity(), request.snapshot(), request.target(), request.executionRef(),
                            dispatch.parameters(), dispatch.evidenceRefs(), prepared.action(), prepared.request().reference(), prepared.fingerprint());
                }
                case SessionExecutionWorkflows.Resume resume -> {
                    action = "workflow.resume";
                    resource = WorkflowPolicyResource.resumeResource(run.identity(), request.snapshot(), request.target(), request.executionRef(),
                            resume.suspendRef(), resume.actionKey(), resume.expectedRevision(), resume.response(), prepared.action(), prepared.request().reference(), prepared.fingerprint());
                }
                case SessionExecutionWorkflows.Cancel cancel -> {
                    action = "workflow.cancel";
                    resource = WorkflowPolicyResource.cancelResource(run.identity(), request.snapshot(), request.target(), request.executionRef(),
                            cancel.actionKey(), cancel.expectedRevision(), cancel.reasonCode(), prepared.action(), prepared.request().reference(), prepared.fingerprint());
                }
                case SessionExecutionWorkflows.Query query -> {
                    action = "workflow.recover";
                    resource = WorkflowPolicyResource.recoveryResource(run.identity(), request.snapshot(), request.target(), request.executionRef(),
                            prepared.action(), prepared.request().reference(), prepared.fingerprint());
                }
            }
        } else return false;
        return identities.authorize(session, new AuthorizationRequest(actor, action, resource,
                run.identity().trace().traceId(), run.identity().trace().spanId())) instanceof AuthorizationResult.Allowed
                && planAllows(session, run, prepared, frame.runBinding);
    }

    /** 真实派发在同一Run事务内重新核当前计划并绑定实际Action，不复用模型base的旧业务状态。 */
    boolean planAllows(SqlSession session, ExecutionStore.Run run, ExecutionStore.Prepared prepared, AgentRunBindings.Binding binding) {
        boolean readOnly = prepared.kind() == ExecutionStore.Kind.MODEL || prepared.kind() == ExecutionStore.Kind.KNOWLEDGE;
        boolean finalizeOutputs = false; boolean delegateStart = false;
        if (prepared.kind() == ExecutionStore.Kind.TOOL) {
            var request = codec.decodeTool(prepared.request().content());
            readOnly = request.target().effect() == ToolEffect.READ_ONLY
                    && !java.util.Set.of("code_execute", "code_execution", "run_code").contains(request.binding().toolName());
            finalizeOutputs = request.binding().providerType() == ProviderType.PLATFORM && request.binding().toolName().equals("finalize_task_outputs");
        } else if (prepared.kind() == ExecutionStore.Kind.DELEGATION) {
            var operation = new SessionExecutionDelegations(limits).decodeRequest(prepared.request().content()).command().operation();
            readOnly = java.util.Set.of(DelegationCommand.Operation.TARGETS, DelegationCommand.Operation.LIST, DelegationCommand.Operation.GET,
                    DelegationCommand.Operation.WAIT, DelegationCommand.Operation.CANCEL).contains(operation);
            delegateStart = operation == DelegationCommand.Operation.START;
        } else if (prepared.kind() == ExecutionStore.Kind.WORKFLOW) {
            var request = workflowRequests.decode(prepared.request().content());
            readOnly = request instanceof SessionExecutionWorkflows.Cancel || request instanceof SessionExecutionWorkflows.Query;
        }
        if (!(store instanceof JdbcExecutionStore jdbc)) throw new IllegalStateException("execution_transactional_plan_required");
        final boolean frozenReadOnly = readOnly, frozenFinalize = finalizeOutputs, frozenStart = delegateStart;
        try {
            return jdbc.participate(session, run.identity().runRef().tenantId(), () -> plans.getObject().participate(session, run.identity(), binding, 0, () -> {
                java.util.function.Supplier<Boolean> permission = () -> {
                    plans.getObject().requireDispatch(run.identity(), prepared.action(), prepared.kind(), frozenReadOnly, frozenFinalize, frozenStart);
                    return true;
                };
                // Team编排作用域只复用PRIMARY身份；成员仍走同一当前计划核验，不能冒充父Run或跳过PlanPort。
                return binding.role() == AgentRunBindings.Role.PRIMARY
                        ? teams.getObject().participatePlan(run.identity(), binding, permission) : permission.get();
            }));
        } catch (IllegalArgumentException denied) { return false; }
    }

    /** 恢复和取消沿用Session当前IAM；调用方持有Run锁并提供真实入口InputId，不复用过期原因果身份。 */
    public boolean allowedControl(SqlSession session, ExecutionStore.Run run, VerifiedIdentity actor,
            JdbcExecutionStore.Control operation, InputId input) {
        if (!actor.owner().equals(run.identity().owner())) return false;
        String action = switch (operation) {
            case RESUME, RESUME_REPLAY, STEER -> "session.execute";
            case CANCEL -> "session.cancel";
        };
        var resource = SessionPolicyResource.run(run.identity(), operation == JdbcExecutionStore.Control.CANCEL
                ? Optional.of(Objects.requireNonNull(input)) : Optional.empty());
        var request = new AuthorizationRequest(actor, action, resource,
                run.identity().trace().traceId(), run.identity().trace().spanId());
        return (operation == JdbcExecutionStore.Control.RESUME_REPLAY
                ? identities.authorizeResponseReplay(session, request, Objects.requireNonNull(input))
                : identities.authorize(session, request)) instanceof AuthorizationResult.Allowed;
    }

    /** 读取当前drive的单一已加载frame；没有活跃作用域或尚未加载时失败关闭。 */
    private Acquired requireLoaded(ExecutionStore.Run run) {
        var frame = requireActive(run.identity().runRef());
        if (frame.configuration == null) throw new IllegalStateException("execution_sources_not_loaded"); return frame;
    }
    /** 显式生命周期边界，不从线程身份补造Run。 */
    private Acquired requireActive(RunRef run) {
        var frame = active.get(run); if (frame == null || frame.closed) throw new IllegalStateException("execution_sources_not_active"); return frame;
    }

    /** 单一drive持有的真实已加载材料；结束清空，不作为当前授权缓存。 */
    private static final class Acquired {
        private final RunRef run;
        private final long startedNanos;
        private final Instant startedAt = Instant.now();
        private final String intervalId = "execution-active:" + UUID.randomUUID();
        /** 只由活动控制建立一次，跨线程观察的取消状态本身由Control拥有。 */
        private volatile ExecutionCoordinator.Control control;
        /** drive释放后信号保持取消，不允许旧Provider观察进入下一次作用域。 */
        private volatile boolean closed;
        private Duration confirmedActive = Duration.ZERO;
        /** 计时器登记前读取一次，后续配置加载复用，不能把本drive已确认活动重复加入基线。 */
        private ExecutionBudgetPort.Snapshot budgetSnapshot;
        private ExecutionBudgetScope budgetScope;
        private volatile ExecutionBudgetPort.ClosingSpec outputClosing=ExecutionBudgetPort.ClosingSpec.TEXT;
        private volatile ExecutionBudgetScope.ClosingSizing outputSizing;
        private volatile ClosingModelActivity closingModelActivity;
        private ResultRef input;
        private TypedPayload inputPayload;
        private AgentRunBindings.Binding runBinding;
        private JdbcModelBindings.Binding modelBinding;
        private Map<FrozenToolTarget, JdbcMcpBindings.Binding> mcpBindings = Map.of();
        private Map<FrozenKnowledgeTarget, JdbcKnowledgeBindings.Binding> knowledgeBindings = Map.of();
        private Map<FrozenWorkflowTarget, JdbcWorkflowBindings.Binding> workflowBindings = Map.of();
        /** 最后发布，保证Provider只看到完整的一次加载材料。 */
        private volatile AgentExecutionProgram.Configuration configuration;
        /** 仅记录单调时间和本次作用域身份，不执行I/O。 */
        private Acquired(RunRef run, long startedNanos) { this.run = run; this.startedNanos = startedNanos; }
    }
    private record ClosingModelActivity(String id,Instant start,long startedNanos) {
        Instant through(){return start.plusNanos(Math.max(0,System.nanoTime()-startedNanos));}
    }
}
