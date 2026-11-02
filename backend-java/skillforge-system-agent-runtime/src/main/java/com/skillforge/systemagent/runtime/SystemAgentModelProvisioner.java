package com.skillforge.systemagent.runtime;

import com.skillforge.prompts.SystemAgentPrompts;

import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.model.PlatformModelConfig;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.InboundKind;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.ModelPort.EnforcementLevel;
import com.skillforge.runtime.api.port.ModelPort.GenerationOptions;
import com.skillforge.runtime.api.port.ModelPort.ReasoningPolicy;
import com.skillforge.runtime.api.port.ModelPort.RequestedModelStrategy;
import com.skillforge.runtime.api.port.ModelPort.ResponseContract;
import com.skillforge.runtime.api.port.ModelPort.ResponseFormat;
import com.skillforge.runtime.api.port.ModelPort.ResponseFormatKind;
import com.skillforge.runtime.api.port.PortalCallKind;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.starter.AgentRuntimeModelConfiguration;
import com.skillforge.runtime.starter.AgentRuntimeAdmissionConfiguration;
import com.skillforge.runtime.session.spi.SessionContextPolicy;
import com.skillforge.runtime.session.spi.SessionPreparations;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;

/** 把系统 Agent 冻结模型 revision 映射为 Starter 的模型、tokenizer 与准备配置。 */
final class SystemAgentModelProvisioner {
    private final AgentRuntimeModelConfiguration models;
    private final PlatformModelCatalogService catalog;
    private final SystemAgentRuntimeStore store;
    private final SystemAgentCapabilitySource sources;

    SystemAgentModelProvisioner(AgentRuntimeModelConfiguration models, PlatformModelCatalogService catalog,
            SystemAgentRuntimeStore store, SystemAgentCapabilitySource sources) {
        this.models = models; this.catalog = catalog; this.store = store; this.sources = sources;
    }

    Prepared prepare(SessionOwner owner, InputId input) {
        var source = store.source(owner, input).map(value -> sources.decode(value.sourceData))
                .orElseThrow(() -> new IllegalStateException("system_agent_model_source_missing"));
        return prepare(owner, source);
    }

    Prepared prepare(RunIdentity identity) {
        String runId = identity.runRef().runId();
        if (!runId.startsWith("system-agent-run:")) throw new IllegalStateException("system_agent_run_ref_invalid");
        String id = runId.substring("system-agent-run:".length());
        return prepare(identity.owner(), new InputId("system-agent-input:" + id));
    }

    private Prepared prepare(SessionOwner owner, SystemAgentCapabilitySource.SourceSnapshot source) {
        PlatformModelConfig selected = catalog.readRevision(source.config().modelConfigId(), source.modelRevision());
        if (!selected.enabled() || !"LLM".equals(selected.kind())
                || !selected.id().equals(source.config().modelConfigId())
                || selected.revision() != source.modelRevision()) {
            throw new IllegalStateException("system_agent_model_revision_unavailable");
        }
        int timeout = Math.min(source.config().timeoutSeconds(), selected.timeoutSeconds());
        int output = selected.maxOutputTokens();
        long promptTokens = (long) selected.contextWindowTokens() - output;
        if (timeout <= 0 || output <= 0 || promptTokens <= 0) {
            throw new IllegalStateException("system_agent_model_budget_invalid");
        }
        var contextPolicy = new SessionContextPolicy("context-policy.system-agent.v1", 2_048,
                8_192, 4_096, 1_024, 80, 60, 16);
        if (contextPolicy.immediateToolTokens() >= promptTokens) {
            throw new IllegalStateException("system_agent_context_policy_inconsistent");
        }
        var provider = ModelProviderCatalog.standard().resolve(new ModelProviderCatalog.Selection(
                selected.providerId(), selected.serviceId(), selected.profileVersion(), selected.modelId(),
                ModelProviderCatalog.ModelProtocol.fromId(selected.protocol()),
                ModelProviderCatalog.AuthenticationScheme.fromId(selected.authenticationScheme()),
                selected.endpoint()));
        boolean reasoning = !"DISABLED".equals(selected.reasoning())
                && "SUPPORTED".equals(provider.capabilities().reasoning().name());
        var strategy = new RequestedModelStrategy(SystemAgentRuntimeContracts.STRATEGY_ID,
                SystemAgentRuntimeContracts.STRATEGY_VERSION);
        String identity = selected.id() + "-" + selected.revision() + "-" + timeout;
        var target = models.publishBinding(owner.sessionRef().tenantId(),
                "system-agent-model-" + digest(identity), Optional.of(owner.target().requireAgent()),
                new AgentRuntimeModelConfiguration.BindingPlan(strategy,
                        Set.of(ResponseFormatKind.TEXT, ResponseFormatKind.JSON_OBJECT), true,
                        reasoning, Duration.ofSeconds(timeout), selected.imagePolicy()), source(selected));
        var prompt = SystemAgentPrompts.prompt(source.promptSourceRef(), source.config().systemPrompt());
        String profileIdentity = digest(SessionPreparations.PROFILE_VERSION + "\u0000"
                + SessionPreparations.AGENT_INPUT_RENDERER_VERSION + "\u0000" + contextPolicy.version()
                + "\u0000" + source.promptSourceRef() + "\u0000" + prompt.render(true)
                + "\u0000" + identity + "\u0000" + target.binding().revision());
        ResultRef tokenizer = new ResultRef("system-agent-tokenizer:" + profileIdentity);
        ResultRef preparation = new ResultRef("system-agent-preparation:" + profileIdentity);
        models.registerTokenizer(new AgentRuntimeModelConfiguration.TokenizerPlan(tokenizer, "v1",
                "jtokkit:1.1.0", "cl100k_base", target,
                new AgentRuntimeModelConfiguration.RoleCosts(1, 2, 3), 3, 4, 100000, 8 * 1024 * 1024));
        ResultRef registered = models.registerPreparation(new AgentRuntimeModelConfiguration.PreparationPlan(
                preparation, SessionPreparations.PROFILE_VERSION, SessionPreparations.AGENT_INPUT_RENDERER_VERSION,
                prompt, target.binding(), strategy,
                new ResponseContract(new ResponseFormat.NativeConversation(), EnforcementLevel.STRICT, "native_system_agent_conversation"),
                new GenerationOptions(OptionalInt.of(output), selected.temperature() == null
                        ? OptionalDouble.empty() : OptionalDouble.of(selected.temperature())),
                reasoning ? ReasoningPolicy.DEFAULT : ReasoningPolicy.DISABLED,
                PortalCallKind.MODEL_STREAM, tokenizer, contextPolicy,
                promptTokens, 400_000, 60, false,
                Set.of(InboundKind.MESSAGE)));
        int businessCalls = source.config().maxBusinessModelCalls();
        long outputBudget = Math.multiplyExact((long) output, businessCalls);
        long totalBudget = Math.addExact(promptTokens, outputBudget);
        var budget = new AgentRuntimeAdmissionConfiguration.Budget(businessCalls, businessCalls, 0,
                promptTokens, outputBudget, totalBudget, Duration.ofSeconds(source.config().timeoutSeconds()));
        return new Prepared(registered, budget, selected.modelId());
    }

    private static AgentRuntimeModelConfiguration.ModelSource source(PlatformModelConfig model) {
        return new AgentRuntimeModelConfiguration.ModelSource("platform-model:" + model.id() + ":" + model.revision(),
                model.modelId(), model.endpoint(), model.apiKeyEncrypted(), model.providerId(), model.serviceId(),
                model.profileVersion(), model.protocol(), model.authenticationScheme(), model.dialectVersion());
    }

    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException("system_agent_digest_unavailable", impossible); }
    }

    record Prepared(ResultRef profile, AgentRuntimeAdmissionConfiguration.Budget budget, String modelId) {}
}
