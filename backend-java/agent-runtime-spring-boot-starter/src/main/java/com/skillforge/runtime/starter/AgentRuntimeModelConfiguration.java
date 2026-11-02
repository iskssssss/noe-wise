package com.skillforge.runtime.starter;

import com.skillforge.runtime.adapter.jdbc.model.JdbcModelBindings;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.InboundKind;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.media.ModelImagePolicy;
import com.skillforge.runtime.api.port.ModelPort.FrozenModelTarget;
import com.skillforge.runtime.api.port.ModelPort.GenerationOptions;
import com.skillforge.runtime.api.port.ModelPort.ModelBindingRef;
import com.skillforge.runtime.api.port.ModelPort.PromptPrefixCapabilities;
import com.skillforge.runtime.api.port.ModelPort.ReasoningPolicy;
import com.skillforge.runtime.api.port.ModelPort.RequestedModelStrategy;
import com.skillforge.runtime.api.port.ModelPort.ResponseContract;
import com.skillforge.runtime.api.port.ModelPort.ResponseFormatKind;
import com.skillforge.runtime.api.port.PortalCallKind;
import com.skillforge.runtime.session.spi.SessionAdmissionFacts;
import com.skillforge.runtime.session.spi.SessionContextPolicy;
import com.skillforge.runtime.session.spi.SessionPreparations;
import com.skillforge.runtime.session.spi.SessionTokenCounter;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 发布 NewRun 所需的不可变模型、tokenizer 与输入准备配置。 */
public final class AgentRuntimeModelConfiguration {
    private final BindingPublisher bindingPublisher;
    private final BindingLookup bindings;
    private final TokenProfiles tokens;
    private final PreparationProfiles preparations;

    AgentRuntimeModelConfiguration(BindingPublisher bindingPublisher,
            BindingLookup bindings, TokenProfiles tokens,
            PreparationProfiles preparations) {
        this.bindingPublisher = Objects.requireNonNull(bindingPublisher);
        this.bindings = Objects.requireNonNull(bindings);
        this.tokens = Objects.requireNonNull(tokens);
        this.preparations = Objects.requireNonNull(preparations);
    }

    /** Workflow worker 显式携带租户读取准备配置，不依赖 Session worker 的线程绑定。 */
    public Optional<SessionPreparations.Profile> preparationProfile(TenantId tenantId, ResultRef reference) {
        return preparations.read(Objects.requireNonNull(tenantId), Objects.requireNonNull(reference));
    }

    /** 受信宿主校验模型选择后发布加密来源；此能力不对用户输入或模型动作开放。 */
    public FrozenModelTarget publishBinding(TenantId tenantId, String bindingId,
            Optional<AgentId> agentId, BindingPlan plan, ModelSource source) {
        Objects.requireNonNull(tenantId); Objects.requireNonNull(agentId);
        Objects.requireNonNull(plan);
        return bindingPublisher.publish(tenantId, bindingId, agentId,
                new JdbcModelBindings.Profile(plan.strategy(), plan.responseFormats(),
                        plan.streaming(), plan.reasoning(), plan.timeout(), plan.imagePolicy(), plan.prefixCapabilities()),
                new JdbcModelBindings.Source(source.reference(),
                        new JdbcModelBindings.ProviderConfiguration(source.modelId(), source.endpoint(),
                                new JdbcModelBindings.ProviderProfile(source.providerId(), source.serviceId(),
                                        source.profileVersion(), source.protocol(), source.authenticationScheme(), source.dialectVersion())),
                        source.encryptedCredential())).target();
    }

    /** 平台无关的宿主模型来源，只保存引用和调用绑定所需事实。 */
    public record ModelSource(String reference, String modelId, String endpoint, String encryptedCredential,
            String providerId, String serviceId, String profileVersion, String protocol,
            String authenticationScheme, String dialectVersion) {
        @Override public String toString() { return "ModelSource[reference=" + reference + "]"; }
    }

    /** 登记与冻结模型目标完全一致的 tokenizer profile。 */
    public ResultRef registerTokenizer(TokenizerPlan plan) {
        Objects.requireNonNull(plan);
        var binding = bindings.find(plan.target().binding())
                .orElseThrow(() -> failure("agent_runtime_model_binding_missing"));
        if (!binding.target().equals(plan.target())) {
            throw failure("agent_runtime_model_target_mismatch");
        }
        var profile = new SessionTokenCounter.Profile(plan.reference(), plan.version(),
                plan.tokenizerVersion(), plan.encoding(), plan.target(),
                new SessionTokenCounter.RoleCosts(plan.roleCosts().system(),
                        plan.roleCosts().user(), plan.roleCosts().assistant()),
                plan.perMessageFramingTokens(), plan.replyPrimingTokens(),
                plan.maxMessages(), plan.maxContentCodeUnits());
        var registered = tokens.register(plan.target().binding().tenantId(), profile);
        if (!registered.equals(profile)) {
            throw failure("agent_runtime_tokenizer_profile_mismatch");
        }
        return registered.reference();
    }

    /** 登记 Agent 输入准备配置，并先核对 binding、strategy 与 tokenizer 来源闭包。 */
    public ResultRef registerPreparation(PreparationPlan plan) {
        Objects.requireNonNull(plan);
        var binding = bindings.find(plan.binding())
                .orElseThrow(() -> failure("agent_runtime_model_binding_missing"));
        if (!binding.strategy().equals(plan.strategy())) {
            throw failure("agent_runtime_model_strategy_mismatch");
        }
        var token = tokens.read(plan.binding().tenantId(), plan.tokenizerProfileRef())
                .orElseThrow(() -> failure("agent_runtime_tokenizer_profile_missing"));
        if (!token.target().binding().equals(plan.binding())) {
            throw failure("agent_runtime_tokenizer_binding_mismatch");
        }
        var profile = new SessionPreparations.Profile(plan.reference(), plan.version(),
                plan.rendererVersion(), SessionAdmissionFacts.ContextKind.AGENT_INPUT,
                plan.systemPrompt(), plan.binding(), plan.strategy(),
                plan.response(), plan.generation(), plan.reasoning(), plan.callKind(),
                plan.tokenizerProfileRef(), plan.contextPolicy(), plan.maxTokens(), plan.maxCharacters(),
                plan.maxHistoryEvents(), plan.delegationEnabled(), plan.allowedInputs());
        var registered = preparations.register(plan.binding().tenantId(), profile);
        if (!registered.equals(profile)) {
            throw failure("agent_runtime_preparation_profile_mismatch");
        }
        return registered.reference();
    }

    /** 模型来源支持的稳定策略、响应形态、调用时限与已核实前缀能力。 */
    public record BindingPlan(RequestedModelStrategy strategy,
            Set<ResponseFormatKind> responseFormats, boolean streaming,
            boolean reasoning, Duration timeout, ModelImagePolicy imagePolicy,
            PromptPrefixCapabilities prefixCapabilities) {
        /** 未解析服务能力的装配只冻结保守状态，不能自动启用可选优化。 */
        public BindingPlan(RequestedModelStrategy strategy, Set<ResponseFormatKind> responseFormats,
                boolean streaming, boolean reasoning, Duration timeout, ModelImagePolicy imagePolicy) {
            this(strategy, responseFormats, streaming, reasoning, timeout, imagePolicy,
                    PromptPrefixCapabilities.unverified());
        }
        public BindingPlan(RequestedModelStrategy strategy, Set<ResponseFormatKind> responseFormats,
                boolean streaming, boolean reasoning, Duration timeout) {
            this(strategy, responseFormats, streaming, reasoning, timeout, ModelImagePolicy.textOnly());
        }
        public BindingPlan(RequestedModelStrategy strategy,
                Set<ResponseFormatKind> responseFormats, boolean streaming, Duration timeout) {
            this(strategy, responseFormats, streaming, false, timeout);
        }

        public BindingPlan {
            Objects.requireNonNull(strategy); responseFormats = Set.copyOf(responseFormats);
            Objects.requireNonNull(timeout);
            Objects.requireNonNull(imagePolicy);
            Objects.requireNonNull(prefixCapabilities);
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("model binding timeout must be positive");
            }
        }
    }

    /** tokenizer 对三种模型消息角色的固定开销。 */
    public record RoleCosts(int system, int user, int assistant) {
        public RoleCosts {
            if (system < 0 || user < 0 || assistant < 0) {
                throw new IllegalArgumentException("tokenizer role costs must not be negative");
            }
        }
    }

    /** 绑定到精确模型 revision 的 tokenizer 配置。 */
    public record TokenizerPlan(ResultRef reference, String version,
            String tokenizerVersion, String encoding, FrozenModelTarget target,
            RoleCosts roleCosts, int perMessageFramingTokens, int replyPrimingTokens,
            int maxMessages, int maxContentCodeUnits) {
        public TokenizerPlan {
            Objects.requireNonNull(reference); requireText(version, "version");
            requireText(tokenizerVersion, "tokenizerVersion"); requireText(encoding, "encoding");
            Objects.requireNonNull(target); Objects.requireNonNull(roleCosts);
            if (perMessageFramingTokens < 0 || replyPrimingTokens < 0
                    || maxMessages <= 0 || maxContentCodeUnits <= 0) {
                throw new IllegalArgumentException("tokenizer limits are invalid");
            }
        }
    }

    /** Agent 输入渲染、模型决策与历史预算的冻结准备配置。 */
    public record PreparationPlan(ResultRef reference, String version,
            String rendererVersion, com.skillforge.runtime.session.spi.SessionPrompt systemPrompt, ModelBindingRef binding,
            RequestedModelStrategy strategy, ResponseContract response,
            GenerationOptions generation, ReasoningPolicy reasoning,
            PortalCallKind callKind, ResultRef tokenizerProfileRef,
            SessionContextPolicy contextPolicy,
            long maxTokens, long maxCharacters, int maxHistoryEvents,
            boolean delegationEnabled, Set<InboundKind> allowedInputs) {
        public PreparationPlan {
            Objects.requireNonNull(reference); requireText(version, "version");
            requireText(rendererVersion, "rendererVersion");
            Objects.requireNonNull(systemPrompt); Objects.requireNonNull(binding);
            Objects.requireNonNull(strategy); Objects.requireNonNull(response);
            Objects.requireNonNull(generation); Objects.requireNonNull(reasoning);
            Objects.requireNonNull(callKind); Objects.requireNonNull(tokenizerProfileRef);
            Objects.requireNonNull(contextPolicy);
            allowedInputs = Set.copyOf(allowedInputs);
            if (maxTokens <= 0 || maxCharacters <= 0 || maxHistoryEvents < 0
                    || contextPolicy.immediateToolTokens() >= maxTokens) {
                throw new IllegalArgumentException("preparation limits are invalid");
            }
        }
    }

    @FunctionalInterface
    interface BindingPublisher {
        JdbcModelBindings.Binding publish(TenantId tenantId, String bindingId,
                Optional<AgentId> agentId, JdbcModelBindings.Profile profile, JdbcModelBindings.Source source);
    }

    @FunctionalInterface
    interface BindingLookup {
        Optional<JdbcModelBindings.Binding> find(ModelBindingRef reference);
    }

    interface TokenProfiles {
        SessionTokenCounter.Profile register(
                TenantId tenantId, SessionTokenCounter.Profile profile);
        Optional<SessionTokenCounter.Profile> read(TenantId tenantId, ResultRef reference);
    }

    interface PreparationProfiles {
        SessionPreparations.Profile register(
                TenantId tenantId, SessionPreparations.Profile profile);
        Optional<SessionPreparations.Profile> read(TenantId tenantId, ResultRef reference);
    }

    private static IllegalStateException failure(String reason) {
        return new IllegalStateException(reason);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
