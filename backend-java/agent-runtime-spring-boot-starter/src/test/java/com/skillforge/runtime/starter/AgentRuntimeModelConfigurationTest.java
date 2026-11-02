package com.skillforge.runtime.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skillforge.runtime.adapter.jdbc.model.JdbcModelBindings;
import com.skillforge.runtime.api.InboundKind;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.ModelPort.EnforcementLevel;
import com.skillforge.runtime.api.port.ModelPort.FrozenModelTarget;
import com.skillforge.runtime.api.port.ModelPort.GenerationOptions;
import com.skillforge.runtime.api.port.ModelPort.ModelBindingRef;
import com.skillforge.runtime.api.port.ModelPort.ModelCapabilitySnapshot;
import com.skillforge.runtime.api.port.ModelPort.CapabilitySupportStatus;
import com.skillforge.runtime.api.port.ModelPort.PromptPrefixCapabilities;
import com.skillforge.runtime.api.port.ModelPort.ToolUpdateMode;
import com.skillforge.runtime.api.port.ModelPort.ReasoningPolicy;
import com.skillforge.runtime.api.port.ModelPort.RequestedModelStrategy;
import com.skillforge.runtime.api.port.ModelPort.ResponseContract;
import com.skillforge.runtime.api.port.ModelPort.ResponseFormat;
import com.skillforge.runtime.api.port.ModelPort.ResponseFormatKind;
import com.skillforge.runtime.api.port.PortalCallKind;
import com.skillforge.runtime.session.spi.SessionContextPolicy;
import com.skillforge.runtime.session.spi.SessionPreparations;
import com.skillforge.runtime.session.spi.SessionTokenCounter;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentRuntimeModelConfigurationTest {
    private static final TenantId TENANT = new TenantId("1");
    private static final RequestedModelStrategy STRATEGY =
            new RequestedModelStrategy("chat", "v1");
    private static final FrozenModelTarget TARGET = new FrozenModelTarget(
            new ModelBindingRef(TENANT, "binding", 1), "model-1",
            new ModelCapabilitySnapshot(Set.of(ResponseFormatKind.TEXT), false, false,
                    new ResultRef("capability")), Duration.ofSeconds(30),
            new ResultRef("resolution"));
    private static final JdbcModelBindings.ProviderProfile PROVIDER_PROFILE =
            new JdbcModelBindings.ProviderProfile("custom", "custom-openai-compatible",
                    "custom-v1", "openai-chat-completions", "bearer-api-key",
                    "openai-compatible-chat-v1");

    @Test
    void publishesBindingWithoutAcceptingEndpointOrCredential() {
        var publisher = mock(AgentRuntimeModelConfiguration.BindingPublisher.class);
        var bindings = mock(AgentRuntimeModelConfiguration.BindingLookup.class);
        var tokens = mock(AgentRuntimeModelConfiguration.TokenProfiles.class);
        var preparations = mock(AgentRuntimeModelConfiguration.PreparationProfiles.class);
        var stored = new JdbcModelBindings.Binding(
                TARGET, STRATEGY, "https://model.test/v1", "encrypted", PROVIDER_PROFILE,
                "platform-model:test:1");
        when(publisher.publish(any(), any(), any(), any(), any())).thenReturn(stored);
        var configuration = new AgentRuntimeModelConfiguration(
                publisher, bindings, tokens, preparations);

        assertThat(configuration.publishBinding(TENANT, "binding", Optional.empty(),
                new AgentRuntimeModelConfiguration.BindingPlan(STRATEGY,
                        Set.of(ResponseFormatKind.TEXT), false, Duration.ofSeconds(30)), source()))
                .isEqualTo(TARGET);
    }

    @Test void publishesImagePolicyAsPartOfExactBindingRatherThanInferringFromModelName() {
        var publisher = mock(AgentRuntimeModelConfiguration.BindingPublisher.class);
        var policy = new com.skillforge.runtime.api.media.ModelImagePolicy(true, true, 4, 1024,
                8192, 2048, 512, 85, 170, "vision.v1");
        when(publisher.publish(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            JdbcModelBindings.Profile profile = invocation.getArgument(3);
            assertThat(profile.imagePolicy()).isEqualTo(policy);
            return new JdbcModelBindings.Binding(TARGET, STRATEGY, "https://model.test/v1", "encrypted",
                    PROVIDER_PROFILE, "platform-model:test:1");
        });
        var configuration = new AgentRuntimeModelConfiguration(publisher,
                mock(AgentRuntimeModelConfiguration.BindingLookup.class),
                mock(AgentRuntimeModelConfiguration.TokenProfiles.class),
                mock(AgentRuntimeModelConfiguration.PreparationProfiles.class));
        configuration.publishBinding(TENANT, "binding", Optional.empty(),
                new AgentRuntimeModelConfiguration.BindingPlan(STRATEGY, Set.of(ResponseFormatKind.TEXT),
                        false, false, Duration.ofSeconds(30), policy), source());
        verify(publisher).publish(any(), any(), any(), any(), any());
    }

    /** Starter 原样传递受信宿主解析的能力，不按品牌或当前 Provider 再推断。 */
    @Test void publishesExactResolvedPrefixCapabilitiesInBindingProfile() {
        var publisher = mock(AgentRuntimeModelConfiguration.BindingPublisher.class);
        var prefix = new PromptPrefixCapabilities(CapabilitySupportStatus.UNVERIFIED,
                ToolUpdateMode.NONE, CapabilitySupportStatus.SUPPORTED);
        when(publisher.publish(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            JdbcModelBindings.Profile profile = invocation.getArgument(3);
            assertThat(profile.prefixCapabilities()).isEqualTo(prefix);
            return new JdbcModelBindings.Binding(TARGET, STRATEGY, "https://model.test/v1", "encrypted",
                    PROVIDER_PROFILE, "platform-model:test:1");
        });
        var configuration = new AgentRuntimeModelConfiguration(publisher,
                mock(AgentRuntimeModelConfiguration.BindingLookup.class),
                mock(AgentRuntimeModelConfiguration.TokenProfiles.class),
                mock(AgentRuntimeModelConfiguration.PreparationProfiles.class));
        configuration.publishBinding(TENANT, "binding", Optional.empty(),
                new AgentRuntimeModelConfiguration.BindingPlan(STRATEGY, Set.of(ResponseFormatKind.TEXT),
                        false, false, Duration.ofSeconds(30),
                        com.skillforge.runtime.api.media.ModelImagePolicy.textOnly(), prefix), source());
        verify(publisher).publish(any(), any(), any(), any(), any());
        assertThat(new AgentRuntimeModelConfiguration.BindingPlan(STRATEGY,
                Set.of(ResponseFormatKind.TEXT), false, Duration.ofSeconds(30)).prefixCapabilities())
                .isEqualTo(PromptPrefixCapabilities.unverified());
    }

    @Test
    void registersTokenizerAndPreparationOnlyAgainstExactFrozenSources() {
        var publisher = mock(AgentRuntimeModelConfiguration.BindingPublisher.class);
        var bindings = mock(AgentRuntimeModelConfiguration.BindingLookup.class);
        var tokens = mock(AgentRuntimeModelConfiguration.TokenProfiles.class);
        var preparations = mock(AgentRuntimeModelConfiguration.PreparationProfiles.class);
        var stored = new JdbcModelBindings.Binding(
                TARGET, STRATEGY, "https://model.test/v1", "encrypted", PROVIDER_PROFILE,
                "platform-model:test:1");
        when(bindings.find(TARGET.binding())).thenReturn(Optional.of(stored));
        when(tokens.register(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));
        when(tokens.read(any(), any())).thenAnswer(invocation -> Optional.of(tokenProfile()));
        when(preparations.register(any(), any())).thenAnswer(
                invocation -> {
                    com.skillforge.runtime.session.spi.SessionPreparations.Profile profile = invocation.getArgument(1);
                    assertThat(profile.delegationEnabled()).isTrue();
                    return profile;
                });
        var configuration = new AgentRuntimeModelConfiguration(
                publisher, bindings, tokens, preparations);

        assertThat(configuration.registerTokenizer(tokenPlan()))
                .isEqualTo(new ResultRef("tokenizer"));
        assertThat(configuration.registerPreparation(preparationPlan()))
                .isEqualTo(new ResultRef("preparation"));
        verify(tokens).register(any(), any());
        verify(preparations).register(any(), any());
    }

    @Test
    void preparationLookupUsesExplicitTenantOutsideSessionWorker() {
        var preparations = mock(AgentRuntimeModelConfiguration.PreparationProfiles.class);
        var reference = new ResultRef("workflow-profile");
        var tenant = new TenantId("workflow-tenant");
        var profile = mock(com.skillforge.runtime.session.spi.SessionPreparations.Profile.class);
        when(preparations.read(tenant, reference)).thenReturn(Optional.of(profile));
        var configuration = new AgentRuntimeModelConfiguration(
                mock(AgentRuntimeModelConfiguration.BindingPublisher.class),
                mock(AgentRuntimeModelConfiguration.BindingLookup.class),
                mock(AgentRuntimeModelConfiguration.TokenProfiles.class), preparations);
        assertThat(configuration.preparationProfile(tenant, reference)).contains(profile);
        assertThat(configuration.preparationProfile(new TenantId("other-tenant"), reference)).isEmpty();
    }

    @Test
    void rejectsPreparationStrategyDriftBeforeWritingProfile() {
        var publisher = mock(AgentRuntimeModelConfiguration.BindingPublisher.class);
        var bindings = mock(AgentRuntimeModelConfiguration.BindingLookup.class);
        var tokens = mock(AgentRuntimeModelConfiguration.TokenProfiles.class);
        var preparations = mock(AgentRuntimeModelConfiguration.PreparationProfiles.class);
        when(bindings.find(TARGET.binding())).thenReturn(Optional.of(
                new JdbcModelBindings.Binding(TARGET, STRATEGY,
                        "https://model.test/v1", "encrypted", PROVIDER_PROFILE,
                        "platform-model:test:1")));
        var drift = new AgentRuntimeModelConfiguration.PreparationPlan(
                new ResultRef("preparation"), "v1", "input-log.v1", com.skillforge.runtime.session.spi.SessionPrompt.single("test-instructions", com.skillforge.runtime.session.spi.SessionPrompt.Type.AGENT, "test-v1", "system"),
                TARGET.binding(), new RequestedModelStrategy("other", "v1"),
                response(), generation(), ReasoningPolicy.DISABLED,
                PortalCallKind.MODEL_GENERATE, new ResultRef("tokenizer"),
                testPolicy(), 1000, 10000, 10, false, Set.of(InboundKind.MESSAGE));

        assertThatThrownBy(() -> new AgentRuntimeModelConfiguration(
                publisher, bindings, tokens, preparations).registerPreparation(drift))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("agent_runtime_model_strategy_mismatch");
        verify(tokens, never()).read(any(), any());
        verify(preparations, never()).register(any(), any());
    }

    private static AgentRuntimeModelConfiguration.TokenizerPlan tokenPlan() {
        return new AgentRuntimeModelConfiguration.TokenizerPlan(
                new ResultRef("tokenizer"), "v1", "jtokkit:1.1.0", "cl100k_base",
                TARGET, new AgentRuntimeModelConfiguration.RoleCosts(1, 2, 3),
                3, 4, 20, 100000);
    }

    @Test
    void rejectsImmediateToolThresholdOutsideEffectiveInputBudget() {
        assertThatThrownBy(() -> new AgentRuntimeModelConfiguration.PreparationPlan(
                new ResultRef("preparation"), "v2", "input-log.v1", com.skillforge.runtime.session.spi.SessionPrompt.single("test-instructions", com.skillforge.runtime.session.spi.SessionPrompt.Type.AGENT, "test-v1", "system"),
                TARGET.binding(), STRATEGY, response(), generation(), ReasoningPolicy.DISABLED,
                PortalCallKind.MODEL_GENERATE, new ResultRef("tokenizer"),
                new SessionContextPolicy("context-policy.small.v1", 1_000, 512, 256, 64,
                        80, 60, 16),
                1_000, 10_000, 10, false, Set.of(InboundKind.MESSAGE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("preparation limits are invalid");
    }
    private static AgentRuntimeModelConfiguration.ModelSource source() {
        return new AgentRuntimeModelConfiguration.ModelSource("platform-model:test", "frozen-model",
                "https://model.test/v1", "encrypted", "custom", "custom-openai-compatible", "custom-v1",
                "openai-chat-completions", "bearer-api-key", "openai-compatible-chat-v1");
    }

    private static SessionTokenCounter.Profile tokenProfile() {
        var plan = tokenPlan();
        return new SessionTokenCounter.Profile(plan.reference(), plan.version(),
                plan.tokenizerVersion(), plan.encoding(), plan.target(),
                new SessionTokenCounter.RoleCosts(1, 2, 3),
                plan.perMessageFramingTokens(), plan.replyPrimingTokens(),
                plan.maxMessages(), plan.maxContentCodeUnits());
    }

    private static AgentRuntimeModelConfiguration.PreparationPlan preparationPlan() {
        return new AgentRuntimeModelConfiguration.PreparationPlan(
                new ResultRef("preparation"), SessionPreparations.PROFILE_VERSION, SessionPreparations.AGENT_INPUT_RENDERER_VERSION,
                com.skillforge.runtime.session.spi.SessionPrompt.single("test-instructions", com.skillforge.runtime.session.spi.SessionPrompt.Type.AGENT, "test-v1", "system"),
                TARGET.binding(), STRATEGY, response(), generation(),
                ReasoningPolicy.DISABLED, PortalCallKind.MODEL_GENERATE,
                new ResultRef("tokenizer"), testPolicy(), 1000, 10000, 10, true,
                Set.of(InboundKind.MESSAGE));
    }

    private static SessionContextPolicy testPolicy() {
        return new SessionContextPolicy("context-policy.test.v1", 500, 512, 256, 64,
                80, 60, 16);
    }

    private static ResponseContract response() {
        return new ResponseContract(new ResponseFormat.Text(),
                EnforcementLevel.STRICT, "supported");
    }

    private static GenerationOptions generation() {
        return new GenerationOptions(OptionalInt.of(500), OptionalDouble.of(0.2));
    }
}
