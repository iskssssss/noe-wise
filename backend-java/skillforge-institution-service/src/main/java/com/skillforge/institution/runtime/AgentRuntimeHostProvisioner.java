package com.skillforge.institution.runtime;

import com.skillforge.prompts.ChatPrompts;
import com.skillforge.prompts.DelegationPrompts;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.skillforge.core.foundation.persistence.ServicePrincipalMapper;
import com.skillforge.core.foundation.persistence.ServicePrincipalDO;
import com.skillforge.core.foundation.application.ServicePrincipalService;
import com.skillforge.runtime.api.InboundKind;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.port.ModelPort.EnforcementLevel;
import com.skillforge.runtime.api.port.ModelPort.FrozenModelTarget;
import com.skillforge.runtime.api.port.ModelPort.GenerationOptions;
import com.skillforge.runtime.api.port.ModelPort.ReasoningPolicy;
import com.skillforge.runtime.api.port.ModelPort.RequestedModelStrategy;
import com.skillforge.runtime.api.port.ModelPort.ResponseContract;
import com.skillforge.runtime.api.port.ModelPort.ResponseFormat;
import com.skillforge.runtime.api.port.ModelPort.ResponseFormatKind;
import com.skillforge.runtime.api.port.PortalCallKind;
import com.skillforge.runtime.starter.AgentRuntimeModelConfiguration;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import com.skillforge.runtime.starter.AgentRuntimeAdmissionConfiguration;
import com.skillforge.runtime.session.spi.SessionContextPolicy;
import com.skillforge.runtime.session.spi.SessionPrompt;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.model.PlatformModelConfig;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.core.foundation.runtimeconfig.AgentExecutionConfig;
import com.skillforge.core.foundation.runtimeconfig.RunSettingsService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import com.skillforge.core.foundation.integration.runtime.AgentRuntimeSystemGrants;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * 为租户建立 Chat NewRun 所需的最小宿主事实：Chat 服务主体、worker 系统授权、
 * 租户模型绑定与准备配置。缺失模型来源时失败关闭，不编造凭据。
 */
@Service
public class AgentRuntimeHostProvisioner {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AgentRuntimeHostProvisioner.class);

    static final Set<String> RUNTIME_SCOPES = Set.of("sessions.write", "sessions.read");
    private static final int CONTEXT_SAFETY_PERCENT = 5;
    private static final int PERCENT_SCALE = 100;

    private final ServicePrincipalMapper principals;
    private final ServicePrincipalService principalService;
    private final AgentRuntimeSystemGrants systemGrants;
    private final AgentRuntimeProperties properties;
    private final com.skillforge.core.shared.config.SkillForgeProperties resources;
    private final AgentRuntimeModelConfiguration models;
    private final PlatformModelCatalogService catalog;
    private final OperationalSettingsService settings;
    private final RunSettingsService runSettings;
    private final com.skillforge.institution.runtime.AgentRunBindings studioRuns;
    private final com.skillforge.runtime.api.port.ExecutionBudgetPort budgets;

    public AgentRuntimeHostProvisioner(
            ServicePrincipalMapper principals,
            ServicePrincipalService principalService,
            AgentRuntimeSystemGrants systemGrants,
            AgentRuntimeProperties properties,
            com.skillforge.core.shared.config.SkillForgeProperties resources,
            AgentRuntimeModelConfiguration models,
            PlatformModelCatalogService catalog, OperationalSettingsService settings, RunSettingsService runSettings,
            com.skillforge.institution.runtime.AgentRunBindings studioRuns,
            com.skillforge.runtime.api.port.ExecutionBudgetPort budgets) {
        this.principals = Objects.requireNonNull(principals);
        this.principalService = Objects.requireNonNull(principalService);
        this.systemGrants = Objects.requireNonNull(systemGrants);
        this.properties = Objects.requireNonNull(properties);
        this.resources = Objects.requireNonNull(resources);
        this.models = Objects.requireNonNull(models);
        this.catalog = Objects.requireNonNull(catalog);
        this.settings = Objects.requireNonNull(settings);
        this.runSettings = Objects.requireNonNull(runSettings);
        this.studioRuns = Objects.requireNonNull(studioRuns);
        this.budgets = Objects.requireNonNull(budgets);
    }

    /**
     * 幂等装配 Chat 入口所需的身份、worker 授权和准备配置。
     *
     * @param tenantId 产品租户 ID
     */
    public ResultRef provision(String tenantId) {
        TenantId tenant = tenant(tenantId);
        provisionAccess(tenantId);
        return prepare(tenant, Optional.empty(), "", null).profile();
    }

    /** 在可信 Chat 入口构造前幂等装配服务主体与 worker 系统授权。 */
    public void provisionAccess(String tenantId) {
        TenantId tenant = tenant(tenantId);
        ensureChatPrincipal(tenantId);
        systemGrants.register(tenant, properties.getWorkerId(), RUNTIME_SCOPES);
    }

    private void ensureChatPrincipal(String tenantId) {
        ServicePrincipalDO existing = principals.selectOne(new QueryWrapper<ServicePrincipalDO>()
                .eq("tenant_id", Long.parseLong(tenantId))
                .eq("integration_id", AgentRuntimeHostContracts.CHAT_INTEGRATION_ID)
                .orderByDesc("created_at")
                .last("LIMIT 1"));
        if (existing != null) {
            if (!ServicePrincipalService.ACTIVE.equals(existing.getStatus())) {
                throw new IllegalStateException("chat_service_principal_inactive");
            }
            return;
        }
        principalService.issue(tenantId, AgentRuntimeHostContracts.CHAT_INTEGRATION_ID, RUNTIME_SCOPES);
    }

    /** NewRun 使用受理边界捕获的发布快照，模型、角色和运行配置固定到本 Run。 */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Prepared prepareNewRun(String tenantId, String agentId, String runId) {
        var studio = studioRuns.find(new com.skillforge.runtime.api.RunRef(tenant(tenantId), runId));
        if (studio.isPresent()) {
            var binding = studio.orElseThrow();
            requireStudioActor(binding, agentId);
            var member = binding.member();
            var frozen = runSettings.capture(tenantId, runId, member.modelId(), member.modelRevision(), member.executionConfig(), config -> {
                freezeBudget(tenantId, runId, binding, config);
                return prepare(tenant(tenantId), Optional.of(new AgentId(agentId)), studioInstructions(binding),
                        catalog.readRevision(config.modelId(), config.modelRevision()), config, runId,
                        delegationPolicy(binding), delegationTargets(binding), delegationEnabled(binding)).profile();
            });
            return restore(tenant(tenantId), runId, frozen);
        }
        throw new IllegalStateException("agent_runtime_run_binding_missing");
    }

    /** 新执行链只冻结配置并计算预算计划；预算登记由 ExecutionStore.admit 的事务完成。 */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public ExecutionPreparation prepareExecutionRun(String tenantId, String agentId, String runId) {
        var run = new com.skillforge.runtime.api.RunRef(tenant(tenantId), runId);
        var binding = studioRuns.find(run).orElseThrow(() -> new IllegalStateException("agent_runtime_run_binding_missing"));
        // 此入口只核验已受理 Run；新的受理必须携带同次冻结目录，不能重新按当前能力选提示词。
        requireStudioActor(binding, agentId);
        var frozen = runSettings.requireFrozen(tenantId, runId);
        var registration = budgetRegistration(tenantId, runId, binding, frozen.executionConfig(),
                () -> runSettings.read(frozen, com.skillforge.core.foundation.runtimeconfig.ChatBudgetSettings.GROUP));
        return new ExecutionPreparation(new ResultRef(frozen.preparationProfile()), registration);
    }

    /** 可信受理入口复用原绑定与同次目录；精确主/成员 Run 身份和当前访问权仍在准备边界核验。 */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public ExecutionPreparation prepareExecutionRun(String tenantId, String agentId, String runId, AgentRunBindings.Binding binding,
            AgentRuntimeExecutionCatalog.FrozenCatalog capabilities) {
        Objects.requireNonNull(binding);
        Objects.requireNonNull(capabilities);
        if (!capabilities.identity().runRef().equals(new RunRef(tenant(tenantId), runId))
                || !capabilities.identity().owner().equals(binding.owner())
                || !capabilities.identity().agentId().equals(binding.actor())
                || binding.role() == AgentRunBindings.Role.PRIMARY
                    && !binding.scope().primaryRun().equals(capabilities.identity().runRef()))
            throw new IllegalStateException("execution_preparation_binding_mismatch");
        return prepareExecution(tenantId, agentId, runId, binding, Optional.of(capabilities));
    }

    /** 同步 capture 回调生成的预算仅在此次准备内传回；重入从原冻结修订计算一次。 */
    private ExecutionPreparation prepareExecution(String tenantId, String agentId, String runId, AgentRunBindings.Binding binding,
            Optional<AgentRuntimeExecutionCatalog.FrozenCatalog> capabilities) {
        requireStudioActor(binding, agentId);
        var member = binding.member();
        var capturedBudget = new AtomicReference<ExecutionBudgetPort.Registration>();
        var frozen = runSettings.capture(tenantId, runId, member.modelId(), member.modelRevision(), member.executionConfig(), config -> {
            var registration = budgetRegistration(tenantId, runId, binding, config);
            capturedBudget.set(registration);
            return prepare(tenant(tenantId), Optional.of(new AgentId(agentId)), studioInstructions(binding),
                    catalog.readRevision(config.modelId(), config.modelRevision()), config, runId,
                    delegationPolicy(binding), delegationTargets(binding), delegationEnabled(binding), Optional.of(registration), capabilities).profile();
        });
        var registration = capturedBudget.get();
        if (registration == null) registration = budgetRegistration(tenantId, runId, binding, frozen.executionConfig(),
                () -> runSettings.read(frozen, com.skillforge.core.foundation.runtimeconfig.ChatBudgetSettings.GROUP));
        return new ExecutionPreparation(new ResultRef(frozen.preparationProfile()), registration);
    }

    /** 原冻结 Profile 和预算计划；此返回值不代表预算已经受理或派发获准。 */
    public record ExecutionPreparation(ResultRef profile, com.skillforge.runtime.api.port.ExecutionBudgetPort.Registration budget) { }

    private Prepared prepare(TenantId tenant, Optional<AgentId> agentId, String role, String runId) {
        return prepare(tenant, agentId, role, runId, com.skillforge.core.foundation.runtimeconfig.AgentExecutionConfig.inherited());
    }
    private Prepared prepare(TenantId tenant, Optional<AgentId> agentId, String role, String runId,
            com.skillforge.core.foundation.runtimeconfig.AgentExecutionConfig executionConfig) {
        var selected = catalog.resolveLlm(tenant.value(), agentId.map(AgentId::value).orElse(null));
        if (runId == null) return prepare(tenant, agentId, role, selected, null, null, null, null, false);
        var frozen = runSettings.capture(tenant.value(), runId, selected.id(), selected.revision(), executionConfig, config -> {
            freezeBudget(tenant.value(), runId, null, config);
            return prepare(tenant, agentId, role, catalog.readRevision(config.modelId(), config.modelRevision()),
                    config, runId, null, null, false).profile();
        });
        return restore(tenant, runId, frozen);
    }

    /** 新动作和动作恢复均继承本 Run 的发布快照，发布新修订只影响下一轮。 */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Prepared prepareOperation(String tenantId, String agentId, String runId, String operationKey) {
        var tenant = tenant(tenantId);
        var studio = studioRuns.find(new com.skillforge.runtime.api.RunRef(tenant, runId));
        if (studio.isPresent()) {
            var binding = studio.orElseThrow();
            requireStudioActor(binding, agentId);
            String instructions = studioInstructions(binding);
            var frozen = runSettings.captureFrozenOperation(tenantId, runId, operationKey, instructions);
            return restore(tenant, runId, frozen);
        }
        throw new IllegalStateException("agent_runtime_run_binding_missing");
    }

    private void requireStudioActor(com.skillforge.institution.runtime.AgentRunBindings.Binding binding, String agentId) {
        if (!binding.actor().value().equals(agentId)) throw new IllegalStateException("studio_execution_actor_mismatch");
        studioRuns.authorize(binding);
    }

    private String studioInstructions(com.skillforge.institution.runtime.AgentRunBindings.Binding binding) {
        return ChatPrompts.roleInstructions(binding.member().systemPrompt(), binding.member().roleDescription());
    }

    private boolean delegationEnabled(com.skillforge.institution.runtime.AgentRunBindings.Binding binding) {
        return binding.role()==AgentRunBindings.Role.PRIMARY && binding.scope().delegation().enabled();
    }
    private String delegationPolicy(AgentRunBindings.Binding binding) {
        if (binding.role() == AgentRunBindings.Role.DELEGATE) return DelegationPrompts.child();
        return delegationEnabled(binding) ? DelegationPrompts.primary() : null;
    }
    /** 目录是公开数据，与通用行为指引独立；实时授权由操作边界强制执行。 */
    private String delegationTargets(AgentRunBindings.Binding binding) {
        if(!delegationEnabled(binding))return null;
        var targets=binding.scope().directory().values().stream()
                .filter(member->!member.agentId().equals(binding.actor().value()))
                .filter(member->binding.scope().delegation().targetAgentIds().contains(member.agentId()))
                .filter(member->{try{studioRuns.authorizeTarget(binding,member.agentId());return true;}
                    catch(SecurityException | com.skillforge.core.shared.exception.ApiException denied){return false;}})
                .sorted(java.util.Comparator.comparing(com.skillforge.institution.team.TeamDefinition.Member::agentId))
                .map(member->java.util.Map.of("target_handle","member:"+member.agentId(),"name",member.name(),
                        "role_description",member.roleDescription(),"capabilities",member.capabilities())).toList();
        try{return DelegationPrompts.directory(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(targets));}
        catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new IllegalStateException("delegation_directory_invalid",invalid);}
    }

    private Prepared prepare(TenantId tenant, Optional<AgentId> agentId, String role,
            PlatformModelConfig selected, RunSettingsService.CaptureConfig frozen,
            String runId, String delegationPolicy, String delegationTargets, boolean delegationEnabled) {
        return prepare(tenant, agentId, role, selected, frozen, runId, delegationPolicy, delegationTargets, delegationEnabled, Optional.empty());
    }

    private Prepared prepare(TenantId tenant, Optional<AgentId> agentId, String role,
            PlatformModelConfig selected, RunSettingsService.CaptureConfig frozen,
            String runId, String delegationPolicy, String delegationTargets, boolean delegationEnabled,
            Optional<com.skillforge.runtime.api.port.ExecutionBudgetPort.Registration> executionBudget) {
        return prepare(tenant, agentId, role, selected, frozen, runId, delegationPolicy, delegationTargets,
                delegationEnabled, executionBudget, Optional.empty());
    }

    private Prepared prepare(TenantId tenant, Optional<AgentId> agentId, String role,
            PlatformModelConfig selected, RunSettingsService.CaptureConfig frozen,
            String runId, String delegationPolicy, String delegationTargets, boolean delegationEnabled,
            Optional<ExecutionBudgetPort.Registration> executionBudget,
            Optional<AgentRuntimeExecutionCatalog.FrozenCatalog> capabilities) {

        var prompts = frozen == null ? settings.read("prompts") : runSettings.read(frozen,"prompts");
        var calls = frozen == null ? settings.read("calls") : runSettings.read(frozen,"calls");
        SessionPrompt instructions = ChatPrompts.prompt(
                prompts.text("agent_decision_policy"), prompts.revision(),
                agentId.map(id -> "agent:" + id.value()).orElse(null), role);
        if (delegationPolicy != null) {
            var modules = new ArrayList<>(instructions.modules());
            modules.add(new SessionPrompt.Module("delegation-policy", SessionPrompt.Type.DELEGATION_POLICY,
                    ChatPrompts.sourceVersion(delegationPolicy), delegationPolicy));
            if (delegationTargets != null) {
                modules.add(new SessionPrompt.Module("delegation-targets", SessionPrompt.Type.DELEGATION_TARGETS,
                        ChatPrompts.sourceVersion(delegationTargets), delegationTargets));
            }
            instructions = new SessionPrompt(modules);
        }
        if (capabilities.isPresent()) instructions = AgentRuntimePromptSelection.select(instructions, capabilities.orElseThrow());
        int output = selected.maxOutputTokens();
        long window = selected.contextWindowTokens();
        long safety = Math.floorDiv(Math.addExact(Math.multiplyExact(window, CONTEXT_SAFETY_PERCENT),
                PERCENT_SCALE - 1), PERCENT_SCALE);
        long promptTokens = window - output - safety;
        if (promptTokens <= 1) throw new IllegalArgumentException("model_context_capacity_insufficient");
        long characters = resources.getContextContentCharacters();
        if (characters <= 0 || characters > Integer.MAX_VALUE)
            throw new IllegalArgumentException("context_content_resource_limit_invalid");
        int history = SessionContextPolicy.HISTORY_PAGE_SIZE;
        var contextPolicy = SessionContextPolicy.forInputBudget(promptTokens);
        var provider = com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.standard().resolve(
                new com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.Selection(
                        selected.providerId(), selected.serviceId(), selected.profileVersion(), selected.modelId(),
                        com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ModelProtocol.fromId(selected.protocol()),
                        com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.AuthenticationScheme.fromId(selected.authenticationScheme()),
                        selected.endpoint()));
        var reasoningPolicy = ReasoningPolicy.valueOf(selected.reasoning());
        com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.standard()
                .validateReasoning(provider, reasoningPolicy);
        boolean reasoning = reasoningPolicy != ReasoningPolicy.DISABLED && reasoningPolicy != ReasoningPolicy.DEFAULT;
        // DEFAULT 不显式请求推理；冻结目标据此声明 reasoning=false 时，请求必须显式禁用推理。
        var effectiveReasoning = reasoning ? reasoningPolicy : ReasoningPolicy.DISABLED;
        var responseFormats = java.util.EnumSet.of(ResponseFormatKind.TEXT);
        if (provider.capabilities().jsonObject() == com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.SupportLevel.SUPPORTED)
            responseFormats.add(ResponseFormatKind.JSON_OBJECT);
        if (provider.capabilities().strictJsonSchema() == com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.SupportLevel.SUPPORTED)
            responseFormats.add(ResponseFormatKind.JSON_SCHEMA);
        com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.standard().validateCapabilities(
                provider, responseFormats, true, true, reasoning);
        int timeout = Math.min(selected.timeoutSeconds(), calls.integer("chat_timeout_seconds"));
        // 绑定身份覆盖模型来源与调用策略；恢复旧动作不能把共享当前指针重新发布为新 revision。
        String bindingId = AgentRuntimeHostContracts.MODEL_BINDING_ID
                + agentId.map(id -> ".agent-" + id.value()).orElse("")
                + ".model-" + selected.id() + "-" + selected.revision() + ".timeout-" + timeout;
        FrozenModelTarget target = models.publishBinding(tenant, bindingId, agentId,
                new AgentRuntimeModelConfiguration.BindingPlan(
                        new RequestedModelStrategy(AgentRuntimeHostContracts.MODEL_STRATEGY_ID,
                                AgentRuntimeHostContracts.MODEL_STRATEGY_VERSION),
                        responseFormats,
                        true, reasoning, Duration.ofSeconds(timeout), selected.imagePolicy(),
                        com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.standard()
                                .effectivePrefixCapabilities(provider, selected.prefixCapabilities())), source(selected));
        String settingsIdentity = ".view-v3.prompts-" + prompts.revision() + ".characters-" + characters + ".history-" + history + ".input-" + promptTokens
                + ".policy-" + digest(contextPolicy.toString())
                + ".instructions-" + digest(instructions.render(true)) + ".binding-" + digest(bindingId)
                + ".reasoning-" + effectiveReasoning.name()
                + ".model-" + target.binding().revision()
                + agentId.map(id -> ".agent-" + id.value()).orElse("");
        ResultRef tokenizerProfile = new ResultRef(AgentRuntimeHostContracts.TOKENIZER_PROFILE.value() + settingsIdentity);
        // 准备配置不可变；渲染协议升级必须生成新身份，不能覆盖同名旧配置。
        ResultRef preparationProfile = new ResultRef(AgentRuntimeHostContracts.PREPARATION_PROFILE.value()
                + ".renderer-" + AgentRuntimeHostContracts.PREPARATION_RENDERER + settingsIdentity);
        models.registerTokenizer(new AgentRuntimeModelConfiguration.TokenizerPlan(
                tokenizerProfile, "v1", AgentRuntimeHostContracts.TOKENIZER_VERSION,
                AgentRuntimeHostContracts.TOKENIZER_ENCODING, target,
                new AgentRuntimeModelConfiguration.RoleCosts(1, 2, 3), 3, 4,
                100000, Math.toIntExact(characters)));
        ResultRef registered = models.registerPreparation(new AgentRuntimeModelConfiguration.PreparationPlan(
                preparationProfile, AgentRuntimeHostContracts.PREPARATION_VERSION,
                AgentRuntimeHostContracts.PREPARATION_RENDERER, instructions, target.binding(),
                new RequestedModelStrategy(AgentRuntimeHostContracts.MODEL_STRATEGY_ID, AgentRuntimeHostContracts.MODEL_STRATEGY_VERSION),
                new ResponseContract(new ResponseFormat.NativeConversation(), EnforcementLevel.STRICT, "native_agent_conversation"),
                new GenerationOptions(OptionalInt.of(output), selected.temperature() == null
                        ? OptionalDouble.empty() : OptionalDouble.of(selected.temperature())),
                effectiveReasoning, PortalCallKind.MODEL_STREAM,
                tokenizerProfile, contextPolicy, promptTokens, characters, history, delegationEnabled,
                Set.of(InboundKind.MESSAGE, InboundKind.ACTION)));
        var ledger = executionBudget.isPresent() ? executionBudget : frozen == null ? Optional.<com.skillforge.runtime.api.port.ExecutionBudgetPort.Registration>empty()
                : budgets.find(new com.skillforge.runtime.api.RunRef(tenant, runId));
        if (frozen != null && ledger.isEmpty()) throw new IllegalStateException("budget_scope_missing");
        return new Prepared(registered, ledger.map(AgentRuntimeHostProvisioner::loopBudget).orElseGet(() -> {
            var limit = com.skillforge.core.foundation.runtimeconfig.ChatBudgetSettings.freeze(settings.read("task_budget"),
                    com.skillforge.runtime.api.port.ExecutionBudgetPort.Scenario.STANDARD,
                    com.skillforge.runtime.api.port.ExecutionBudgetPort.Role.PRIMARY,
                    com.skillforge.core.foundation.runtimeconfig.AgentExecutionConfig.inherited());
            return loopBudget(new com.skillforge.runtime.api.port.ExecutionBudgetPort.Registration(
                    new com.skillforge.runtime.api.RunRef(tenant, "provision"), new com.skillforge.runtime.api.RunRef(tenant, "provision"),
                    com.skillforge.runtime.api.port.ExecutionBudgetPort.Role.PRIMARY, new ExecutionBudgetPort.Policy.Enforced(limit)));
        }));
    }

    /** 恢复只验证并读取精确 Profile 与账本，不按当前代码或部署策略重算。 */
    private Prepared restore(TenantId tenant, String runId, RunSettingsService.FrozenConfig frozen) {
        LOG.debug("恢复运行准备 tenant={} run={} profile={}", tenant.value(), runId, frozen.preparationProfile());
        var reference = new ResultRef(frozen.preparationProfile());
        if (models.preparationProfile(tenant, reference).isEmpty())
            throw new IllegalStateException("run_preparation_profile_missing");
        var registration = budgets.find(new com.skillforge.runtime.api.RunRef(tenant, runId))
                .orElseThrow(() -> new IllegalStateException("budget_scope_missing"));
        var prepared = new Prepared(reference, loopBudget(registration));
        LOG.debug("恢复运行准备完成 tenant={} run={} root={}", tenant.value(), runId, registration.root());
        return prepared;
    }

    /** 按真实产品目标和授权冻结范围选场景；不从请求context或已消费次数推断。 */
    private void freezeBudget(String tenantId,String runId,AgentRunBindings.Binding binding,RunSettingsService.CaptureConfig config) {
        LOG.debug("冻结有效预算 tenant={} run={}", tenantId, runId);
        var run = new com.skillforge.runtime.api.RunRef(tenant(tenantId), runId);
        if (budgets.find(run).isPresent()) {
            LOG.debug("复用已登记预算 tenant={} run={}", tenantId, runId);
            return;
        }
        budgets.register(budgetRegistration(tenantId, runId, binding, config));
    }

    private com.skillforge.runtime.api.port.ExecutionBudgetPort.Registration budgetRegistration(
            String tenantId, String runId, AgentRunBindings.Binding binding, RunSettingsService.CaptureConfig config) {
        return budgetRegistration(tenantId, runId, binding, config.executionConfig(),
                () -> runSettings.read(config, com.skillforge.core.foundation.runtimeconfig.ChatBudgetSettings.GROUP));
    }

    /** 预算按本次捕获值或原冻结精确修订计算；成员继承父根，不读取平台当前配置。 */
    private ExecutionBudgetPort.Registration budgetRegistration(
            String tenantId, String runId, AgentRunBindings.Binding binding,
            AgentExecutionConfig executionConfig, Supplier<OperationalSettingsService.Snapshot> taskBudget) {
        var run = new com.skillforge.runtime.api.RunRef(tenant(tenantId), runId);
        var role = binding != null && binding.role()==AgentRunBindings.Role.DELEGATE
                ? com.skillforge.runtime.api.port.ExecutionBudgetPort.Role.MEMBER : com.skillforge.runtime.api.port.ExecutionBudgetPort.Role.PRIMARY;
        var parent = binding == null ? Optional.<com.skillforge.runtime.api.port.ExecutionBudgetPort.Registration>empty()
                : binding.parentRunRef().flatMap(budgets::find);
        if (role==com.skillforge.runtime.api.port.ExecutionBudgetPort.Role.MEMBER && parent.isEmpty())
            throw new IllegalStateException("budget_parent_not_registered");
        var scenario = parent.map(value -> value.policy().requireEnforced().scenario()).orElseGet(() -> {
            if(binding != null && binding.owner().target().kind()==com.skillforge.runtime.api.SessionTarget.Kind.TEAM)
                return com.skillforge.runtime.api.port.ExecutionBudgetPort.Scenario.TEAM;
            if(binding != null && delegationEnabled(binding) && delegationTargetsAvailable(binding))
                return com.skillforge.runtime.api.port.ExecutionBudgetPort.Scenario.DELEGATION;
            return com.skillforge.runtime.api.port.ExecutionBudgetPort.Scenario.STANDARD;
        });
        var limits = parent.isPresent()
                ? com.skillforge.core.foundation.runtimeconfig.ChatBudgetSettings.freezeMember(parent.orElseThrow().policy().requireEnforced(), executionConfig)
                : com.skillforge.core.foundation.runtimeconfig.ChatBudgetSettings.freeze(
                        taskBudget.get(), scenario, role, executionConfig);
        return new com.skillforge.runtime.api.port.ExecutionBudgetPort.Registration(run,parent.map(value->value.root()).orElse(run),role,new ExecutionBudgetPort.Policy.Enforced(limits));
    }
    private boolean delegationTargetsAvailable(AgentRunBindings.Binding binding) {
        return binding.scope().directory().keySet().stream().filter(id -> !id.equals(binding.actor().value()))
                .anyMatch(id -> {try {studioRuns.authorizeTarget(binding,id);return true;}
                    catch(SecurityException | com.skillforge.core.shared.exception.ApiException denied){return false;}});
    }
    static AgentRuntimeAdmissionConfiguration.Budget loopBudget(com.skillforge.runtime.api.port.ExecutionBudgetPort.Registration registration) {
        var limit=registration.policy().requireEnforced();var resources=limit.run();
        // 开放式Agent循环只消费业务模型额度；辅助调用由同一冻结账本独立计量。
        return new AgentRuntimeAdmissionConfiguration.Budget(Math.toIntExact(resources.businessModels()),
                Math.toIntExact(resources.tools()),Math.toIntExact(resources.knowledge()),resources.inputTokens(),resources.outputTokens(),
                resources.totalTokens(),Duration.ofSeconds(limit.runActiveSeconds()));
    }

    private static AgentRuntimeModelConfiguration.ModelSource source(PlatformModelConfig model) {
        return new AgentRuntimeModelConfiguration.ModelSource("platform-model:" + model.id() + ":" + model.revision(),
                model.modelId(), model.endpoint(), model.apiKeyEncrypted(), model.providerId(), model.serviceId(),
                model.profileVersion(), model.protocol(), model.authenticationScheme(), model.dialectVersion());
    }

    /** 新任务的准备配置和累计预算来自同一配置读取事务。 */
    public record Prepared(ResultRef profile, AgentRuntimeAdmissionConfiguration.Budget budget) { }

    private static String digest(String instructions) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(instructions.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static TenantId tenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        return new TenantId(tenantId);
    }
}
