package com.skillforge.runtime.adapter.model;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.adapter.model.openai.CustomOpenAiCompatibleModelProvider;
import com.skillforge.runtime.adapter.model.provider.ModelProvider;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import com.skillforge.runtime.adapter.model.provider.ModelProviderRuntime;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ModelPort.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 缓存路由跨轮次稳定，但不能跨 owner、Agent 或冻结模型绑定复用；仍从真实 Port 授权入口派生。 */
class ModelPromptCacheScopeTest {
    private static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test void differentRunsAndInputsOfSameSessionUseTheSameScope() {
        var binding = binding("tenant", "binding", 1, "model", "https://example.test/v1");
        var first = ModelPromptCacheScope.create(identity("tenant", "session", "agent", 1, "run-a", "input-a"), binding);
        var next = ModelPromptCacheScope.create(identity("tenant", "session", "agent", 1, "run-b", "input-b"), binding);
        assertEquals(first, next);
        assertTrue(first.key().matches("[a-f0-9]{64}"));
        assertFalse(first.toString().contains(first.key()));
        assertFalse(first.key().contains("session"));
    }

    @Test void ownerAndExecutingAgentAreSeparateCacheDomains() {
        var binding = binding("tenant", "binding", 1, "model", "https://example.test/v1");
        var base = ModelPromptCacheScope.create(identity("tenant", "session", "agent", 1, "run", "input"), binding);
        assertNotEquals(base, ModelPromptCacheScope.create(identity("tenant", "other-session", "agent", 1, "run", "input"), binding));
        assertNotEquals(base, ModelPromptCacheScope.create(identity("tenant", "session", "other-agent", 1, "run", "input"), binding));
        assertNotEquals(base, ModelPromptCacheScope.create(identity("tenant", "session", "agent", 2, "run", "input"), binding));
        assertNotEquals(base, ModelPromptCacheScope.create(identity("other-tenant", "session", "agent", 1, "run", "input"),
                binding("other-tenant", "binding", 1, "model", "https://example.test/v1")));
        assertThrows(IllegalArgumentException.class, () -> ModelPromptCacheScope.create(
                identity("other-tenant", "session", "agent", 1, "run", "input"), binding));
    }

    @Test void groupMembersShareOneGroupScopeWithoutAnOwnerEndUser() {
        var tenant = new TenantId("tenant"); var group = new GroupBindingRef(tenant, "group");
        var owner = new SessionOwner(new SessionRef(tenant, "group-session"), SessionSubject.group(group), SessionTarget.group(group));
        var binding = binding("tenant", "binding", 1, "model", "https://example.test/v1");
        var trace = new TraceEnvelope("trace", "span", new InputId("root"), Optional.empty());
        var first = new RunIdentity(owner, new AgentId("agent"), new InputId("input-1"), new RunRef(tenant, "run-1"), trace,
                new ResultRef("identity-1"), new RunRequester.EndUser(new EndUserId(1)),
                Optional.of(new SourceMessageRef(owner.sessionRef(), "message-1")), new RunOriginRef(tenant, "trigger-1"));
        var next = new RunIdentity(owner, first.agentId(), new InputId("input-2"), new RunRef(tenant, "run-2"), trace,
                new ResultRef("identity-2"), new RunRequester.EndUser(new EndUserId(2)),
                Optional.of(new SourceMessageRef(owner.sessionRef(), "message-2")), new RunOriginRef(tenant, "trigger-2"));
        assertEquals(ModelPromptCacheScope.create(first, binding), ModelPromptCacheScope.create(next, binding));
        var otherGroup = new GroupBindingRef(tenant, "other-group");
        var otherOwner = new SessionOwner(owner.sessionRef(), SessionSubject.group(otherGroup), SessionTarget.group(otherGroup));
        var other = new RunIdentity(otherOwner, first.agentId(), first.causationInputId(), first.runRef(), trace,
                first.causationIdentityRef(), first.requester(), first.sourceMessageRef(), first.originRef());
        assertNotEquals(ModelPromptCacheScope.create(first, binding), ModelPromptCacheScope.create(other, binding));
    }

    @Test void frozenBindingRevisionModelAndEndpointIsolateTheScope() {
        var identity = identity("tenant", "session", "agent", 1, "run", "input");
        var original = binding("tenant", "binding", 1, "model", "https://example.test/v1");
        var base = ModelPromptCacheScope.create(identity, original);
        for (var changed : List.of(
                binding("tenant", "other-binding", 1, "model", "https://example.test/v1"),
                binding("tenant", "binding", 2, "model", "https://example.test/v1"),
                binding("tenant", "binding", 1, "other-model", "https://example.test/v1"),
                binding("tenant", "binding", 1, "model", "https://other.test/v1"))) {
            assertNotEquals(base, ModelPromptCacheScope.create(identity, changed));
        }
        var otherProfile = new OpenAiModelPort.ProviderProfile("custom", "other-service", "v2",
                ModelProviderCatalog.ModelProtocol.OPENAI_CHAT_COMPLETIONS,
                ModelProviderCatalog.AuthenticationScheme.BEARER_API_KEY, "openai-compatible-chat-v1");
        assertNotEquals(base, ModelPromptCacheScope.create(identity, new OpenAiModelPort.Binding(
                original.target(), original.strategy(), original.endpoint(), original.encryptedCredential(), otherProfile)));
        // 凭据只用于认证，轮换凭据不会将秘密变成缓存路由字段。
        assertEquals(base, ModelPromptCacheScope.create(identity, new OpenAiModelPort.Binding(
                original.target(), original.strategy(), original.endpoint(), "rotated-secret", original.providerProfile())));
    }

    @Test void lengthFramingSeparatesAmbiguousAdjacentFields() {
        var binding = binding("tenant", "binding", 1, "model", "https://example.test/v1");
        assertNotEquals(
                ModelPromptCacheScope.create(identity("tenant", "a", "bc", 1, "run", "input"), binding),
                ModelPromptCacheScope.create(identity("tenant", "ab", "c", 1, "run", "input"), binding));
    }

    @Test void governedModelPortSuppliesScopeAfterAuthorizationAndNeverDispatchesADeniedCall() {
        var binding = binding("tenant", "binding", 1, "model", "https://example.test/v1");
        var identity = identity("tenant", "session", "agent", 1, "run", "input");
        var observed = new AtomicReference<ModelProvider.InvocationRequest>();
        ModelProvider provider = new ModelProvider() {
            public ModelProviderCatalog.ProviderPreset preset() { return new CustomOpenAiCompatibleModelProvider().preset(); }
            public InvocationResult generate(InvocationRequest request) {
                assertTrue(request.promptCacheScope().isPresent());
                observed.set(request);
                return new InvocationResult.Completed("answer", List.of(), request.modelId(),
                        new ModelUsage(OptionalLong.of(10), OptionalLong.of(2)), "stop");
            }
        };
        var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        var runtime = new ModelProviderRuntime(List.of(provider));
        var call = call(identity, binding);
        var allowed = new OpenAiModelPort(ref -> Optional.of(binding), encrypted -> "secret".getBytes(StandardCharsets.UTF_8),
                value -> true, (value, output) -> true, http, CLOCK, 100_000, runtime);
        assertInstanceOf(ModelResult.Completed.class, allowed.generate(call));
        assertEquals(ModelPromptCacheScope.create(identity, binding), observed.get().promptCacheScope().orElseThrow());
        observed.set(null);
        var denied = new OpenAiModelPort(ref -> Optional.of(binding), encrypted -> "secret".getBytes(StandardCharsets.UTF_8),
                value -> false, (value, output) -> true, http, CLOCK, 100_000, runtime);
        assertInstanceOf(ModelResult.Failed.class, denied.generate(call));
        assertNull(observed.get());
    }

    private static RunIdentity identity(String tenant, String session, String agent, long user, String run, String input) {
        var tenantId = new TenantId(tenant);
        var inputId = new InputId(input);
        var owner = SessionOwner.user(new SessionRef(tenantId, session), new AgentId(agent), new EndUserId(user));
        return new RunIdentity(owner, owner.target().requireAgent(), inputId, new RunRef(tenantId, run), new TraceEnvelope("trace", "span", inputId, Optional.empty()), new ResultRef("identity"), new com.skillforge.runtime.api.RunRequester.EndUser(owner.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(owner.sessionRef().tenantId(), inputId.value()));
    }

    private static OpenAiModelPort.Binding binding(String tenant, String id, long revision, String model, String endpoint) {
        var target = new FrozenModelTarget(new ModelBindingRef(new TenantId(tenant), id, revision), model,
                new ModelCapabilitySnapshot(Set.of(ResponseFormatKind.TEXT), true, false, new ResultRef("capabilities")),
                Duration.ofSeconds(10), new ResultRef("resolution"));
        return new OpenAiModelPort.Binding(target, new RequestedModelStrategy("strategy", "1"), endpoint, "encrypted",
                new OpenAiModelPort.ProviderProfile("custom", "custom-openai-compatible", "custom-v1",
                        ModelProviderCatalog.ModelProtocol.OPENAI_CHAT_COMPLETIONS,
                        ModelProviderCatalog.AuthenticationScheme.BEARER_API_KEY, "openai-compatible-chat-v1"));
    }

    private static ModelCall call(RunIdentity run, OpenAiModelPort.Binding binding) {
        var tenant = run.owner().sessionRef().tenantId();
        var action = new ActionRef(tenant, "action");
        var snapshotRef = new ResultRef("invocation");
        // 适配器纯值夹具只描述本次派发，不代替真实Store登记或IAM验收。
        var dispatch = new ProviderDispatch(run.runRef(), action, action.actionId(), "attempt", PortalCallKind.MODEL_GENERATE,
                snapshotRef, new ResultRef("capability"), "fingerprint", 1);
        var verified = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(tenant, run.owner().requireUser()), run.owner(),
                Set.of("runtime:invoke"), 1, new ResultRef("verified"));
        var context = ProviderCallContext.dispatched(new PortCallContext(run, verified,
                new InvocationControl(NOW.plusSeconds(10), () -> false)), dispatch);
        return new ModelCall(context, binding.strategy(), binding.target(), snapshotRef,
                List.of(new ModelMessage("system", ModelRole.SYSTEM, "system"), new ModelMessage("user", ModelRole.USER, "hello")),
                new ResponseContract(new ResponseFormat.Text(), EnforcementLevel.STRICT, "supported"),
                new GenerationOptions(OptionalInt.of(32), OptionalDouble.empty()), ReasoningPolicy.DISABLED);
    }
}
