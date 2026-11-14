package com.skillforge.institution.runtime;

import com.skillforge.prompts.ChatPrompts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.skillforge.core.agent.mapper.AgentMapper;
import com.skillforge.core.agent.model.AgentDO;
import com.skillforge.core.foundation.persistence.ServicePrincipalMapper;
import com.skillforge.core.foundation.application.ServicePrincipalService;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.ModelPort.FrozenModelTarget;
import com.skillforge.runtime.api.port.ModelPort.ModelBindingRef;
import com.skillforge.runtime.starter.AgentRuntimeModelConfiguration;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import com.skillforge.core.foundation.integration.runtime.AgentRuntimeSystemGrants;
import com.skillforge.runtime.session.spi.SessionPrompt;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentRuntimeHostProvisionerTest {
    @Test void messagesBindingFreezesOnlySupportedFormatsAndRestoresExactPublishedPreparation() {
        var f = new Fixture();
        when(f.catalog.readRevision("10", 3)).thenReturn(messagesModel("DISABLED"));
        var prepared = f.host.prepareNewRun("1", "7", "run");
        var binding = ArgumentCaptor.forClass(AgentRuntimeModelConfiguration.BindingPlan.class);
        verify(f.models).publishBinding(any(), any(), any(), binding.capture(), any());
        assertEquals(java.util.Set.of(com.skillforge.runtime.api.port.ModelPort.ResponseFormatKind.TEXT), binding.getValue().responseFormats());
        assertTrue(binding.getValue().streaming());
        assertFalse(binding.getValue().reasoning());
        var preparation = ArgumentCaptor.forClass(AgentRuntimeModelConfiguration.PreparationPlan.class);
        verify(f.models).registerPreparation(preparation.capture());
        assertInstanceOf(com.skillforge.runtime.api.port.ModelPort.ResponseFormat.NativeConversation.class, preparation.getValue().response().format());
        assertEquals(com.skillforge.runtime.api.port.ModelPort.ReasoningPolicy.DISABLED, preparation.getValue().reasoning());
        clearInvocations(f.models, f.catalog);
        when(f.catalog.readRevision("10", 3)).thenReturn(messagesModel("DEFAULT"));
        assertEquals(prepared, f.host.prepareNewRun("1", "7", "run"));
        verifyNoInteractions(f.catalog);
        verify(f.models, org.mockito.Mockito.never()).publishBinding(any(), any(), any(), any(), any());
    }

    @Test void unsupportedReasoningEffortCannotBeAdoptedByNativeConversation() {
        var f = new Fixture();
        when(f.catalog.readRevision("10", 3)).thenReturn(messagesModel("MEDIUM"));
        assertThrows(IllegalArgumentException.class, () -> f.host.prepareNewRun("1", "7", "run"));
        verify(f.models, org.mockito.Mockito.never()).publishBinding(any(), any(), any(), any(), any());
        verify(f.models, org.mockito.Mockito.never()).registerPreparation(any());
    }

    @Test void defaultReasoningFreezesExplicitlyDisabledWhileEffortStaysExact() {
        for (String selected : List.of("DEFAULT", "LOW")) {
            var f = new Fixture();
            when(f.catalog.readRevision("10", 3)).thenReturn(messagesModel(selected));
            f.host.prepareNewRun("1", "7", "run");
            var preparation = ArgumentCaptor.forClass(AgentRuntimeModelConfiguration.PreparationPlan.class);
            verify(f.models).registerPreparation(preparation.capture());
            assertEquals(
                    "DEFAULT".equals(selected)
                            ? com.skillforge.runtime.api.port.ModelPort.ReasoningPolicy.DISABLED
                            : com.skillforge.runtime.api.port.ModelPort.ReasoningPolicy.LOW,
                    preparation.getValue().reasoning());
        }
    }

    private static com.skillforge.core.model.PlatformModelConfig messagesModel(String reasoning) {
        return new com.skillforge.core.model.PlatformModelConfig("10", 3, "Messages", "LLM", true,
                "deepseek", "deepseek-api", "2026-09-13", "anthropic-messages", "anthropic-api-key",
                "deepseek-messages-v1", "https://api.deepseek.com", "deepseek-flash", "encrypted",
                8192, 64000, 4096, 0.5, reasoning, 90, null, null, null, null, null, "CNY");
    }

    @Test
    void businessReplyStyleKeepsNativeInteractionBoundary() {
        String role = "Agent 角色与业务指令：请用简洁中文回答";
        var prompt = ChatPrompts.prompt("平台策略", 7, "agent:7", role);
        String instructions = prompt.render();
        assertEquals(List.of("host-core", "platform-task-policy", "agent-role", "chat-runtime-policy", "chat-runtime-actions",
                        "chat-runtime-tools", "chat-runtime-workflow", "chat-runtime-skill", "chat-runtime-memory", "chat-runtime-knowledge",
                        "chat-runtime-task", "chat-runtime-goal", "chat-runtime-discovery", "chat-runtime-program", "chat-runtime-code",
                        "chat-runtime-code-workspace", "chat-runtime-code-artifacts", "plan-policy"),
                prompt.modules().stream().map(SessionPrompt.Module::name).toList());
        assertEquals(SessionPrompt.Type.HOST, prompt.modules().getFirst().type());
        assertEquals(SessionPrompt.Type.PLATFORM, prompt.modules().get(1).type());
        assertEquals(SessionPrompt.Type.AGENT, prompt.modules().get(2).type());
        assertEquals(SessionPrompt.Type.RUNTIME_ACTIONS, prompt.modules().get(4).type());
        assertEquals(SessionPrompt.Type.RUNTIME_CODE_WORKSPACE, prompt.modules().get(15).type());
        assertEquals(SessionPrompt.Type.RUNTIME_CODE_ARTIFACTS, prompt.modules().get(16).type());
        assertTrue(prompt.modules().subList(3, prompt.modules().size() - 1).stream()
                .allMatch(module -> module.type().name().startsWith("RUNTIME")));
        assertEquals(SessionPrompt.Type.PLAN_POLICY, prompt.modules().getLast().type());
        assertTrue(!instructions.contains("当前处于计划模式"));
        assertEquals("7", prompt.modules().get(1).sourceVersion());
        assertEquals("agent:7", prompt.modules().get(2).sourceVersion());
        for (var module : prompt.modules().subList(3, prompt.modules().size())) {
            assertEquals(ChatPrompts.sourceVersion(module.content()), module.sourceVersion());
        }
        assertTrue(instructions.indexOf("R-CORE-01") < instructions.indexOf(role));
        assertTrue(instructions.indexOf(role) < instructions.indexOf("交互与能力"));
        assertTrue(instructions.contains("能够回答时直接输出 assistant 正文"));
        assertFalse(instructions.contains("final_answer"));
        assertTrue(instructions.contains("knowledge_query"));
        assertTrue(instructions.contains("use_skill"));
        assertTrue(instructions.contains("运行时权限与确认检查"));
        assertEquals(List.of("host-core", "platform-task-policy", "chat-runtime-policy", "chat-runtime-actions",
                        "chat-runtime-tools", "chat-runtime-workflow", "chat-runtime-skill", "chat-runtime-memory", "chat-runtime-knowledge",
                        "chat-runtime-task", "chat-runtime-goal", "chat-runtime-discovery", "chat-runtime-program", "chat-runtime-code",
                        "chat-runtime-code-workspace", "chat-runtime-code-artifacts", "plan-policy"),
                ChatPrompts.prompt("平台策略", 7, "agent:7", " ").modules().stream()
                        .map(SessionPrompt.Module::name).toList());
    }

    @Test
    void knowledgeScopeGuidanceCreatesNewProfileWhileRestoringOriginalFrozenPrompt() {
        var current = ChatPrompts.prompt("平台策略", 5, "agent:7",
                ChatPrompts.roleInstructions("服务助手", "负责申请"));
        var knowledge = current.modules().stream().filter(module -> module.type() == SessionPrompt.Type.RUNTIME_KNOWLEDGE)
                .findFirst().orElseThrow();
        assertTrue(knowledge.content().contains("可能受条目、字符和上下文预算限制"));
        assertTrue(knowledge.content().contains("若本轮提供文档目录或读取工具"));
        assertTrue(knowledge.content().contains("不编造遗漏数量或截断事实"));
        assertEquals(ChatPrompts.sourceVersion(knowledge.content()), knowledge.sourceVersion());
        String previousText = knowledge.content().lines().filter(line -> !line.startsWith("R-KNOW-02 "))
                .collect(java.util.stream.Collectors.joining("\n")) + "\n";
        var previous = new SessionPrompt(current.modules().stream().map(module -> module == knowledge
                ? new SessionPrompt.Module(module.name(), module.type(), ChatPrompts.sourceVersion(previousText), previousText)
                : module).toList());
        assertNotEquals(knowledge.sourceVersion(), ChatPrompts.sourceVersion(previousText));

        var original = new Fixture();
        AgentRuntimeHostProvisioner.Prepared originalPreparation;
        try (var prompts = org.mockito.Mockito.mockStatic(ChatPrompts.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            prompts.when(() -> ChatPrompts.prompt(any(), org.mockito.ArgumentMatchers.anyLong(), any(), any())).thenReturn(previous);
            originalPreparation = original.host.prepareNewRun("1", "7", "run");
        }
        var originalReference = originalPreparation.profile();
        var oldPlans = ArgumentCaptor.forClass(AgentRuntimeModelConfiguration.PreparationPlan.class);
        verify(original.models).registerPreparation(oldPlans.capture());
        var oldPlan = oldPlans.getValue();
        var stored = new com.skillforge.runtime.session.spi.SessionPreparations.Profile(oldPlan.reference(), oldPlan.version(),
                oldPlan.rendererVersion(), com.skillforge.runtime.session.spi.SessionAdmissionFacts.ContextKind.AGENT_INPUT,
                oldPlan.systemPrompt(), oldPlan.binding(), oldPlan.strategy(), oldPlan.response(), oldPlan.generation(),
                oldPlan.reasoning(), oldPlan.callKind(), oldPlan.tokenizerProfileRef(), oldPlan.contextPolicy(),
                oldPlan.maxTokens(), oldPlan.maxCharacters(), oldPlan.maxHistoryEvents(), oldPlan.delegationEnabled(), oldPlan.allowedInputs());
        when(original.models.preparationProfile(new TenantId("1"), originalReference)).thenReturn(Optional.of(stored));
        clearInvocations(original.models, original.catalog, original.settings);
        assertEquals(originalPreparation, original.host.prepareNewRun("1", "7", "run"));
        verifyNoInteractions(original.catalog, original.settings);
        verify(original.models, org.mockito.Mockito.never()).registerPreparation(any());
        assertEquals(previous, original.models.preparationProfile(new TenantId("1"), originalReference).orElseThrow().systemPrompt());
        assertFalse(stored.systemPrompt().render().contains("R-KNOW-02"));

        var next = new Fixture();
        assertNotEquals(originalReference, next.host.prepareNewRun("1", "7", "run").profile());
        var newPlans = ArgumentCaptor.forClass(AgentRuntimeModelConfiguration.PreparationPlan.class);
        verify(next.models).registerPreparation(newPlans.capture());
        assertTrue(newPlans.getValue().systemPrompt().render().contains("R-KNOW-02"));
        assertEquals(oldPlan.binding(), newPlans.getValue().binding());
    }

    @Test
    void freezesCapacityResourcesAndExactProfileWithoutRebuildingOnRestore() {
        var f = new Fixture();
        var prepared = f.host.prepareNewRun("1", "7", "run");
        var plans = ArgumentCaptor.forClass(AgentRuntimeModelConfiguration.PreparationPlan.class);
        verify(f.models).registerPreparation(plans.capture());
        var plan = plans.getValue();
        assertEquals(56_704, plan.maxTokens()); // 64000 - 4096 - 3200，只扣一次。
        assertEquals(32L * 1024 * 1024, plan.maxCharacters());
        assertEquals(100, plan.maxHistoryEvents());
        assertEquals("context-policy.v2", plan.contextPolicy().version());
        assertEquals(12_500, plan.contextPolicy().immediateToolTokens());
        assertEquals(75, plan.contextPolicy().pressurePercent());
        assertEquals(50, plan.contextPolicy().summaryTargetPercent());
        assertEquals(20, plan.contextPolicy().recentPercent());
        var tokenizer = ArgumentCaptor.forClass(AgentRuntimeModelConfiguration.TokenizerPlan.class);
        verify(f.models).registerTokenizer(tokenizer.capture());
        assertEquals(32 * 1024 * 1024, tokenizer.getValue().maxContentCodeUnits());
        clearInvocations(f.models, f.catalog, f.settings);
        f.resources.setContextContentCharacters(1000);
        assertEquals(prepared, f.host.prepareOperation("1", "7", "run", "model:1"));
        assertEquals(prepared, f.host.prepareNewRun("1", "7", "run"));
        verify(f.models, times(2)).preparationProfile(any(), any());
        verifyNoInteractions(f.catalog, f.settings);
        verify(f.models, org.mockito.Mockito.never()).registerPreparation(any());
        assertThrows(IllegalStateException.class, () -> f.host.prepareNewRun("1", "7", "other"));
    }

    /** 只把具体服务和模型已解析的能力送入不可变绑定，未知模型保持未核实。 */
    @Test void freezesAdoptedCapabilitiesWithoutModelNameInference() {
        var verified = new Fixture();
        when(verified.catalog.readRevision("10", 3)).thenReturn(model("10", 3, 4096, "deepseek-flash"));
        verified.host.prepareNewRun("1", "7", "run");
        var verifiedPlan = ArgumentCaptor.forClass(AgentRuntimeModelConfiguration.BindingPlan.class);
        verify(verified.models).publishBinding(any(), any(), any(), verifiedPlan.capture(), any());
        assertEquals(com.skillforge.runtime.api.port.ModelPort.CapabilitySupportStatus.SUPPORTED,
                verifiedPlan.getValue().prefixCapabilities().retainToolsWithToolChoiceNone());

        var unverified = new Fixture();
        unverified.host.prepareNewRun("1", "7", "run");
        var unverifiedPlan = ArgumentCaptor.forClass(AgentRuntimeModelConfiguration.BindingPlan.class);
        verify(unverified.models).publishBinding(any(), any(), any(), unverifiedPlan.capture(), any());
        assertEquals(verifiedPlan.getValue().prefixCapabilities(), unverifiedPlan.getValue().prefixCapabilities());

        var configured = new Fixture();
        var selected = org.mockito.Mockito.spy(model("10", 3, 4096, "new-account-model"));
        var adopted = new com.skillforge.runtime.api.port.ModelPort.PromptPrefixCapabilities(
                com.skillforge.runtime.api.port.ModelPort.CapabilitySupportStatus.SUPPORTED,
                com.skillforge.runtime.api.port.ModelPort.ToolUpdateMode.NONE,
                com.skillforge.runtime.api.port.ModelPort.CapabilitySupportStatus.UNSUPPORTED);
        org.mockito.Mockito.doReturn(adopted).when(selected).prefixCapabilities();
        when(configured.catalog.readRevision("10", 3)).thenReturn(selected);
        configured.host.prepareNewRun("1", "7", "run");
        var configuredPlan = ArgumentCaptor.forClass(AgentRuntimeModelConfiguration.BindingPlan.class);
        verify(configured.models).publishBinding(any(), any(), any(), configuredPlan.capture(), any());
        assertEquals(adopted, configuredPlan.getValue().prefixCapabilities());
    }

    @Test
    void smallWindowUsesDynamicThresholdAndNewRunAdoptsDeploymentResourceChange() {
        var f = new Fixture();
        f.resources.setContextContentCharacters(2_000_000);
        when(f.catalog.readRevision("10", 3)).thenReturn(model("10", 3, 60_000));
        f.host.prepareNewRun("1", "7", "run");
        var plans = ArgumentCaptor.forClass(AgentRuntimeModelConfiguration.PreparationPlan.class);
        verify(f.models).registerPreparation(plans.capture());
        assertEquals(800, plans.getValue().maxTokens());
        assertEquals(799, plans.getValue().contextPolicy().immediateToolTokens());
        assertEquals(2_000_000, plans.getValue().maxCharacters());
    }

    @Test
    void insufficientWindowCannotPublishAnIncompleteRunFreeze() {
        var f = new Fixture();
        when(f.catalog.readRevision("10", 3)).thenReturn(model("10", 3, 60_800));
        assertThrows(IllegalArgumentException.class, () -> f.host.prepareNewRun("1", "7", "run"));
        verify(f.mapper, org.mockito.Mockito.never()).insert(any(), any(), any());
        verify(f.models, org.mockito.Mockito.never()).registerPreparation(any());
    }

    /** 当前主绑定不重查，首次准备只消费一次捕获预算；重入读取精确旧修订且继续当前授权。 */
    @Test void executionPreparationReusesThisCaptureOnceAndReauthorizesThePassedPrimaryBinding(){
        var f=new Fixture();var binding=binding("服务助手","负责申请",3);
        var capabilities=capabilities(binding);
        var prepared=f.host.prepareExecutionRun("1","7","run",binding,capabilities);
        var published=ArgumentCaptor.forClass(AgentRuntimeModelConfiguration.PreparationPlan.class);
        verify(f.models).registerPreparation(published.capture());
        assertTrue(published.getValue().systemPrompt().render().contains("R-ACT-03"));
        assertFalse(published.getValue().systemPrompt().render().contains("R-CODE-03"));
        assertFalse(published.getValue().systemPrompt().render().contains("R-SKILL-01"));
        verify(f.bindings).authorize(binding);verify(f.bindings,org.mockito.Mockito.never()).find(any());
        verify(f.runs,times(1)).read(org.mockito.ArgumentMatchers.any(com.skillforge.core.foundation.runtimeconfig.RunSettingsService.CaptureConfig.class),
                org.mockito.ArgumentMatchers.eq("task_budget"));
        for(String group:List.of("task_budget","prompts","calls","sandbox","workspace","artifacts"))verify(f.settings).read(group);
        verify(f.settings,org.mockito.Mockito.never()).readRevision(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong());

        clearInvocations(f.runs,f.settings,f.models,f.bindings);
        assertEquals(prepared,f.host.prepareExecutionRun("1","7","run",binding,capabilities));
        verify(f.bindings).authorize(binding);verify(f.bindings,org.mockito.Mockito.never()).find(any());
        verify(f.settings).readRevision("task_budget",6);
        verify(f.settings,org.mockito.Mockito.never()).read(org.mockito.ArgumentMatchers.anyString());
        verifyNoInteractions(f.models);

        clearInvocations(f.runs,f.settings,f.models,f.bindings);
        org.mockito.Mockito.doThrow(new SecurityException("current_access_revoked")).when(f.bindings).authorize(binding);
        assertThrows(SecurityException.class,()->f.host.prepareExecutionRun("1","7","run",binding,capabilities));
        verifyNoInteractions(f.runs,f.settings,f.models);
    }

    /** 辅助额度不占业务循环次数，较大的辅助额度也不能使本地业务上限溢出。 */
    @Test void loopBudgetUsesOnlyFrozenRunBusinessModelCalls() {
        var resources = new com.skillforge.runtime.api.port.ExecutionBudgetPort.Resources(7, Long.MAX_VALUE, 4, 2, 80, 20, 100);
        var limits = new com.skillforge.runtime.api.port.ExecutionBudgetPort.Limits(
                com.skillforge.runtime.api.port.ExecutionBudgetPort.Scenario.STANDARD, resources, resources, resources, 300, 180, "frozen-budget:7");
        var run = new com.skillforge.runtime.api.RunRef(new TenantId("1"), "original-run");
        var registration = new com.skillforge.runtime.api.port.ExecutionBudgetPort.Registration(run, run,
                com.skillforge.runtime.api.port.ExecutionBudgetPort.Role.PRIMARY, new com.skillforge.runtime.api.port.ExecutionBudgetPort.Policy.Enforced(limits));
        var budget = AgentRuntimeHostProvisioner.loopBudget(registration);
        assertEquals(7, budget.maxBusinessModelCalls()); assertEquals(4, budget.maxToolCalls());
        assertEquals(2, budget.maxKnowledgeCalls()); assertEquals(java.time.Duration.ofSeconds(180), budget.maxWallTime());
        assertEquals(100, budget.maxTotalTokens());
    }

    /** 调用方拿到的主绑定只适用于其精确租户/Run/Actor，错误身份不能读取配置或发布Profile。 */
    @Test void passedPrimaryBindingRejectsAnotherTenantRunOrActorBeforePreparation(){
        var f=new Fixture();var binding=binding("服务助手","负责申请",3);
        var capabilities=capabilities(binding);
        assertThrows(IllegalStateException.class,()->f.host.prepareExecutionRun("2","7","run",binding,capabilities));
        assertThrows(IllegalStateException.class,()->f.host.prepareExecutionRun("1","7","another-run",binding,capabilities));
        assertThrows(IllegalStateException.class,()->f.host.prepareExecutionRun("1","8","run",binding,capabilities));
        verifyNoInteractions(f.bindings,f.runs,f.settings,f.models);
    }

    private static AgentRuntimeExecutionCatalog.FrozenCatalog capabilities(AgentRunBindings.Binding binding) {
        var identity=mock(com.skillforge.runtime.api.port.RunIdentity.class);
        when(identity.owner()).thenReturn(binding.owner());when(identity.agentId()).thenReturn(binding.actor());
        when(identity.runRef()).thenReturn(binding.scope().primaryRun());
        return new AgentRuntimeExecutionCatalog.FrozenCatalog(identity,List.of(),java.util.Map.of(),List.of(),List.of(),
                java.util.Set.of(),List.of(),com.skillforge.runtime.api.port.PlanPort.State.absent(),com.skillforge.runtime.session.SessionCollaboration.Selection.inactive(),Optional.empty());
    }

    /** 使用真实冻结服务，Profile 装配入口与预算登记仍走生产调用链。 */
    private static final class Fixture {
        final AgentRuntimeModelConfiguration models = mock(AgentRuntimeModelConfiguration.class);
        final com.skillforge.core.model.PlatformModelCatalogService catalog = mock(com.skillforge.core.model.PlatformModelCatalogService.class);
        final com.skillforge.core.foundation.settings.OperationalSettingsService settings = mock(com.skillforge.core.foundation.settings.OperationalSettingsService.class);
        final com.skillforge.core.foundation.runtimeconfig.RunSettingsMapper mapper = mock(com.skillforge.core.foundation.runtimeconfig.RunSettingsMapper.class);
        final com.skillforge.core.shared.config.SkillForgeProperties resources = new com.skillforge.core.shared.config.SkillForgeProperties();
        final AgentRunBindings bindings = mock(AgentRunBindings.class);
        final com.skillforge.core.foundation.runtimeconfig.RunSettingsService runs;
        final AgentRuntimeHostProvisioner host;
        Fixture() {
            var stored = new java.util.HashMap<String, String>();
            when(mapper.find(any(), any())).thenAnswer(c -> stored.get(c.getArgument(0) + ":" + c.getArgument(1)));
            when(mapper.insert(any(), any(), any())).thenAnswer(c -> { stored.put(c.getArgument(0) + ":" + c.getArgument(1), c.getArgument(2)); return 1; });
            when(mapper.findOperation(any(), any(), any())).thenAnswer(c -> stored.get(c.getArgument(0) + ":" + c.getArgument(1) + ":" + c.getArgument(2)));
            when(mapper.insertOperation(any(), any(), any(), any())).thenAnswer(c -> { stored.put(c.getArgument(0) + ":" + c.getArgument(1) + ":" + c.getArgument(2), c.getArgument(3)); return 1; });
            var profileRefs = new java.util.HashSet<com.skillforge.runtime.api.ResultRef>();
            when(models.registerPreparation(any())).thenAnswer(c -> {
                var plan = c.getArgument(0, AgentRuntimeModelConfiguration.PreparationPlan.class);
                profileRefs.add(plan.reference()); return plan.reference();
            });
            when(models.preparationProfile(any(), any())).thenAnswer(c -> profileRefs.contains(c.getArgument(1))
                    ? Optional.of(mock(com.skillforge.runtime.session.spi.SessionPreparations.Profile.class)) : Optional.empty());
            var target = target(3);
            when(models.publishBinding(any(), any(), any(), any(), any())).thenReturn(target);
            when(catalog.readRevision("10", 3)).thenReturn(model("10", 3, 4096));
            var snapshots = java.util.Map.of("task_budget", BudgetTestFacts.settings(6),
                    "prompts", snapshot(5, java.util.Map.of("agent_decision_policy", "平台策略")),
                    "calls", snapshot(1, java.util.Map.of("chat_timeout_seconds", 60)),
                    "sandbox", snapshot(1, java.util.Map.of()), "workspace", snapshot(1, java.util.Map.of()),
                    "artifacts", snapshot(1, java.util.Map.of()));
            snapshots.forEach((key, value) -> { when(settings.read(key)).thenReturn(value); when(settings.readRevision(key, value.revision())).thenReturn(value); });
            runs = org.mockito.Mockito.spy(new com.skillforge.core.foundation.runtimeconfig.RunSettingsService(mapper, settings, new com.fasterxml.jackson.databind.ObjectMapper()));
            when(bindings.find(new com.skillforge.runtime.api.RunRef(new TenantId("1"), "run"))).thenReturn(Optional.of(binding("服务助手", "负责申请", 3)));
            host = new AgentRuntimeHostProvisioner(mock(ServicePrincipalMapper.class), mock(ServicePrincipalService.class),
                    mock(AgentRuntimeSystemGrants.class), new AgentRuntimeProperties(), resources, models, catalog, settings, runs,
                    bindings, BudgetTestFacts.ledger());
        }
    }

    private static AgentRunBindings.Binding binding(String prompt, String role, long modelRevision) {
        var owner = com.skillforge.runtime.api.port.SessionOwner.user(
                new com.skillforge.runtime.api.SessionRef(new TenantId("1"), "session"),
                com.skillforge.runtime.api.SessionTarget.agent(new com.skillforge.runtime.api.AgentId("7")),
                new com.skillforge.runtime.api.EndUserId(9));
        var member = new com.skillforge.institution.team.TeamService.FrozenMember("7", "助手", prompt, role,
                com.skillforge.core.foundation.runtimeconfig.AgentExecutionConfig.inherited(), "10", modelRevision,
                new com.skillforge.core.agent.capabilitymount.CapabilityMountSetResult(1L,7L,1,List.of()),
                101L, 1, com.skillforge.core.agent.model.AgentDelegationConfig.disabled());
        var profile = new com.skillforge.institution.team.TeamDefinition.Member("7", "revision", "助手", role, List.of());
        var scope = new AgentRunBindings.Scope(owner, new com.skillforge.runtime.api.RunRef(new TenantId("1"), "run"),
                new com.skillforge.runtime.api.AgentId("7"), com.skillforge.core.agent.model.AgentDelegationConfig.disabled(),
                Optional.empty(), java.util.Map.of("7",member),java.util.Map.of("7",profile),new AgentRunBindings.Requester("5", ""),java.util.Map.of(),
                new com.skillforge.runtime.api.RunRequester.EndUser(owner.requireUser()));
        return new AgentRunBindings.Binding(owner, new com.skillforge.runtime.api.AgentId("7"), scope, member,
                AgentRunBindings.Role.PRIMARY, Optional.empty());
    }

    private static AgentDO agent(Long tenantId,String prompt,String role) {
        var agent=new AgentDO();agent.setId(7L);agent.setTenantId(tenantId);
        agent.setStatus("published");agent.setSystemPrompt(prompt);agent.setRoleDescription(role);return agent;
    }

    private static com.skillforge.core.model.PlatformModelConfig model(
            String id, long revision, int output) {
        return model(id, revision, output, "deepseek-chat");
    }

    /** 测试精确选择模型，避免从 deepseek 品牌自动放行前缀能力。 */
    private static com.skillforge.core.model.PlatformModelConfig model(
            String id, long revision, int output, String modelId) {
        return new com.skillforge.core.model.PlatformModelConfig(id, revision,
                "chat", "LLM", true, "deepseek", "deepseek-api", "2026-09-13",
                "openai-chat-completions", "bearer-api-key", "openai-compatible-chat-v1",
                "https://api.deepseek.com", modelId, "encrypted",
                8192, 64000, output, 0.5, "DEFAULT", 90,
                null, null, null, null, null, "CNY");
    }

    private static com.skillforge.core.foundation.settings.OperationalSettingsService.Snapshot snapshot(
            long revision, java.util.Map<String, Object> values) {
        return new com.skillforge.core.foundation.settings.OperationalSettingsService.Snapshot(
                revision, values);
    }

    private static FrozenModelTarget target(long revision) {
        var target = mock(FrozenModelTarget.class);
        when(target.binding()).thenReturn(new ModelBindingRef(
                new TenantId("1"), "chat", revision));
        return target;
    }
}
