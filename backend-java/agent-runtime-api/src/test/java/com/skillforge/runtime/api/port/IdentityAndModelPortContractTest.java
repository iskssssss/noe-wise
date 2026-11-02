package com.skillforge.runtime.api.port;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.runtime.api.PrincipalRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.AuthenticationRef;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.RequestId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.SessionTarget;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** 验证身份与模型 Port 的字段级 ABI、封闭结果及构造期 fail-closed 不变量。 */
class IdentityAndModelPortContractTest {

    private static final TenantId TENANT = new TenantId("tenant-1");
    private static final EndUserId USER = new EndUserId(7);
    private static final SessionRef SESSION = new SessionRef(TENANT, "session-1");
    private static final AgentId AGENT = new AgentId("agent-1");
    private static final SessionTarget TARGET = SessionTarget.agent(AGENT);
    private static final SessionOwner OWNER = SessionOwner.user(SESSION, TARGET, USER);
    private static final InputId INPUT = new InputId("input-1");
    private static final RunRef RUN = new RunRef(TENANT, "run-1");
    private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");

    /** 验证 IdentityPolicyPort 方法、record 字段顺序和 sealed permits 与主文档精确一致。 */
    @Test
    void freezesIdentityPolicyPortAbi() throws ReflectiveOperationException {
        assertEquals(Set.of("verify", "verifyAccess", "authorize"),
                publicDeclaredMethodNames(IdentityPolicyPort.class));
        assertEquals(
                List.of("kind:SessionTarget.Kind", "id:String"),
                componentSignatures(SessionTarget.class));
        assertEquals(
                List.of("sessionRef:SessionRef", "subject:SessionSubject", "target:SessionTarget"),
                componentSignatures(SessionOwner.class));
        assertEquals(IdentityPolicyPort.IdentityResult.class,
                IdentityPolicyPort.class
                        .getDeclaredMethod("verify", IdentityPolicyPort.IdentityRequest.class)
                        .getReturnType());
        assertEquals(IdentityPolicyPort.IdentityResult.class,
                IdentityPolicyPort.class
                        .getDeclaredMethod(
                                "verifyAccess", IdentityPolicyPort.AccessIdentityRequest.class)
                        .getReturnType());
        assertEquals(IdentityPolicyPort.AuthorizationResult.class,
                IdentityPolicyPort.class
                        .getDeclaredMethod(
                                "authorize", IdentityPolicyPort.AuthorizationRequest.class)
                        .getReturnType());
        assertEquals(
                List.of(
                        "authenticationRef:AuthenticationRef", "claimedOwner:SessionOwner",
                        "channel:String", "source:String", "requestId:RequestId",
                        "inputId:java.util.Optional<InputId>", "traceId:String", "spanId:String"),
                componentSignatures(IdentityPolicyPort.IdentityRequest.class));
        assertEquals(
                List.of(
                        "authenticationRef:AuthenticationRef", "claimedTenantId:TenantId",
                        "claimedTarget:SessionTarget", "claimedAgentId:java.util.Optional<AgentId>", "claimedSubject:SessionSubject",
                        "channel:String", "source:String", "requestId:RequestId",
                        "traceId:String", "spanId:String"),
                componentSignatures(IdentityPolicyPort.AccessIdentityRequest.class));
        assertEquals(
                List.of(
                        "identity:VerifiedIdentity", "action:String", "resource:TypedPayload",
                        "traceId:String", "spanId:String"),
                componentSignatures(IdentityPolicyPort.AuthorizationRequest.class));
        assertEquals(
                List.of("identity:VerifiedIdentity"),
                componentSignatures(IdentityPolicyPort.IdentityResult.Verified.class));
        assertEquals(
                List.of("failure:PortFailure"),
                componentSignatures(IdentityPolicyPort.IdentityResult.Rejected.class));
        assertEquals(
                List.of("policyRevision:long", "auditRef:ResultRef"),
                componentSignatures(IdentityPolicyPort.AuthorizationResult.Allowed.class));
        assertEquals(
                List.of("reasonCode:String", "policyRevision:long", "auditRef:ResultRef"),
                componentSignatures(IdentityPolicyPort.AuthorizationResult.Denied.class));
        assertEquals(
                List.of("failure:PortFailure"),
                componentSignatures(IdentityPolicyPort.AuthorizationResult.Failed.class));
        assertEquals(
                Set.of(
                        IdentityPolicyPort.IdentityResult.Verified.class,
                        IdentityPolicyPort.IdentityResult.Rejected.class),
                Set.of(IdentityPolicyPort.IdentityResult.class.getPermittedSubclasses()));
        assertEquals(
                Set.of(
                        IdentityPolicyPort.AuthorizationResult.Allowed.class,
                        IdentityPolicyPort.AuthorizationResult.Denied.class,
                        IdentityPolicyPort.AuthorizationResult.Failed.class),
                Set.of(IdentityPolicyPort.AuthorizationResult.class.getPermittedSubclasses()));
    }

    /** 验证身份请求、结果和授权评估拒绝 null、空白文本及非正策略 revision。 */
    @Test
    void validatesIdentityPolicyConstruction() {
        IdentityPolicyPort.IdentityRequest request = identityRequest(Optional.of(INPUT));
        IdentityPolicyPort.AccessIdentityRequest access =
                new IdentityPolicyPort.AccessIdentityRequest(new AuthenticationRef("auth-1"), TENANT, com.skillforge.runtime.api.SessionTarget.agent(OWNER.target().requireAgent()), java.util.Optional.of(OWNER.target().requireAgent()), com.skillforge.runtime.api.SessionSubject.user(USER), "web", "chat", new RequestId("request-1"), "trace-1", "span-1");
        VerifiedIdentity identity = identity(OWNER);
        IdentityPolicyPort.AuthorizationRequest authorization =
                new IdentityPolicyPort.AuthorizationRequest(
                        identity, "runtime:model", payload(), "trace-1", "span-1");

        assertEquals(Optional.of(INPUT), request.inputId());
        assertEquals(TENANT, access.claimedTenantId());
        assertEquals(identity, authorization.identity());
        assertEquals(3,
                new IdentityPolicyPort.AuthorizationResult.Allowed(
                        3, new ResultRef("audit-1")).policyRevision());
        assertThrows(IllegalArgumentException.class, () -> identityRequest(null));
        assertThrows(IllegalArgumentException.class,
                () -> new IdentityPolicyPort.IdentityRequest(
                        new AuthenticationRef("auth-1"), OWNER, " ", "chat",
                        new RequestId("request-1"), Optional.empty(), "trace-1", "span-1"));
        assertThrows(IllegalArgumentException.class,
                () -> new IdentityPolicyPort.AccessIdentityRequest(new AuthenticationRef("auth-1"), TENANT, com.skillforge.runtime.api.SessionTarget.agent(OWNER.target().requireAgent()), java.util.Optional.of(OWNER.target().requireAgent()), com.skillforge.runtime.api.SessionSubject.user(USER), " ", "chat", new RequestId("request-1"), "trace-1", "span-1"));
        assertThrows(IllegalArgumentException.class,
                () -> new IdentityPolicyPort.IdentityResult.Verified(null));
        assertThrows(IllegalArgumentException.class,
                () -> new IdentityPolicyPort.AuthorizationRequest(
                        identity, "runtime:model", null, "trace-1", "span-1"));
        assertThrows(IllegalArgumentException.class,
                () -> new IdentityPolicyPort.AuthorizationResult.Allowed(
                        0, new ResultRef("audit-1")));
        assertThrows(IllegalArgumentException.class,
                () -> new IdentityPolicyPort.AuthorizationResult.Denied(
                        " ", 1, new ResultRef("audit-1")));
        assertThrows(IllegalArgumentException.class,
                () -> new IdentityPolicyPort.AuthorizationResult.Failed(null));
    }

    /** 验证 ModelResolutionPort 与 ModelPort 的方法签名、字段闭包和枚举值。 */
    @Test
    void freezesModelPortAbi() throws ReflectiveOperationException {
        assertEquals(Set.of("resolve"), publicDeclaredMethodNames(ModelResolutionPort.class));
        assertEquals(Set.of("generate", "stream", "validateConversation"), publicDeclaredMethodNames(ModelPort.class));
        assertEquals(
                List.of(
                        "owner:SessionOwner", "agentId:AgentId", "causationInputId:InputId",
                        "runRef:RunRef", "trace:TraceEnvelope",
                        "causationIdentityRef:ResultRef", "requester:RunRequester", "sourceMessageRef:java.util.Optional<SourceMessageRef>", "originRef:RunOriginRef"),
                componentSignatures(RunIdentity.class));
        assertEquals(ModelResolutionPort.ResolutionResult.class,
                ModelResolutionPort.class
                        .getDeclaredMethod(
                                "resolve", ModelResolutionPort.ModelResolutionRequest.class)
                        .getReturnType());
        assertEquals(ModelPort.ModelResult.class,
                ModelPort.class.getDeclaredMethod("generate", ModelPort.ModelCall.class)
                        .getReturnType());
        Method stream = ModelPort.class.getDeclaredMethod("stream", ModelPort.ModelCall.class);
        assertEquals(Flow.Publisher.class, stream.getReturnType());
        ParameterizedType streamType = (ParameterizedType) stream.getGenericReturnType();
        assertEquals(ModelPort.ModelStreamEvent.class, streamType.getActualTypeArguments()[0]);

        assertEquals(
                List.of(
                        "runIdentity:RunIdentity", "identity:VerifiedIdentity",
                        "binding:ModelBindingRef", "requestedStrategy:RequestedModelStrategy"),
                componentSignatures(ModelResolutionPort.ModelResolutionRequest.class));
        assertEquals(
                List.of("target:FrozenModelTarget"),
                componentSignatures(ModelResolutionPort.ResolutionResult.Resolved.class));
        assertEquals(
                List.of("failure:PortFailure"),
                componentSignatures(ModelResolutionPort.ResolutionResult.Rejected.class));
        assertEquals(
                List.of("tenantId:TenantId", "bindingId:String", "revision:long"),
                componentSignatures(ModelPort.ModelBindingRef.class));
        assertEquals(
                List.of("strategyId:String", "strategyVersion:String"),
                componentSignatures(ModelPort.RequestedModelStrategy.class));
        assertEquals(
                List.of(
                        "responseFormats:java.util.Set<ResponseFormatKind>",
                        "streaming:boolean", "reasoning:boolean", "capabilityFactRef:ResultRef",
                        "imagePolicy:media.ModelImagePolicy", "prefixCapabilities:PromptPrefixCapabilities"),
                componentSignatures(ModelPort.ModelCapabilitySnapshot.class));
        assertEquals(
                List.of(
                        "binding:ModelBindingRef", "effectiveModelId:String",
                        "capabilities:ModelCapabilitySnapshot", "timeout:java.time.Duration",
                        "resolutionRef:ResultRef"),
                componentSignatures(ModelPort.FrozenModelTarget.class));
        assertEquals(
                List.of(
                        "context:ProviderCallContext", "requestedStrategy:RequestedModelStrategy",
                        "target:FrozenModelTarget", "invocationSnapshotRef:ResultRef",
                        "messages:java.util.List<ModelMessage>", "tools:java.util.List<ModelTool>",
                        "toolChoice:ToolChoice", "response:ResponseContract",
                        "generation:GenerationOptions", "reasoning:ReasoningPolicy",
                        "budgetCategory:ExecutionBudgetPort.Category"),
                componentSignatures(ModelPort.ModelCall.class));
        assertEquals(
                List.of("messageId:String", "role:ModelRole", "contents:java.util.List<media.ModelContent>",
                        "toolCalls:java.util.List<ModelToolCall>", "toolCallId:java.util.Optional<String>",
                        "reasoningContent:java.util.Optional<String>", "protocolState:java.util.Optional<NativeProtocolState>"),
                componentSignatures(ModelPort.ModelMessage.class));
        assertEquals(List.of("protocol:NativeProtocol", "dialectVersion:String", "modelId:String", "items:java.util.List<TypedPayload>"),
                componentSignatures(ModelPort.NativeProtocolState.class));
        assertEquals(
                List.of("schema:TypedPayload"),
                componentSignatures(ModelPort.ResponseFormat.JsonSchema.class));
        assertEquals(
                List.of(
                        "format:ResponseFormat", "enforcement:EnforcementLevel",
                        "negotiationReasonCode:String"),
                componentSignatures(ModelPort.ResponseContract.class));
        assertEquals(
                List.of(
                        "maxOutputTokens:java.util.OptionalInt",
                        "temperature:java.util.OptionalDouble"),
                componentSignatures(ModelPort.GenerationOptions.class));
        assertEquals(
                List.of("inputTokens:java.util.OptionalLong", "outputTokens:java.util.OptionalLong",
                        "cachedInputTokens:java.util.OptionalLong",
                        "cacheWriteInputTokens:java.util.OptionalLong",
                        "reasoningOutputTokens:java.util.OptionalLong"),
                componentSignatures(ModelPort.ModelUsage.class));
        assertEquals(
                List.of(
                        "output:TypedPayload", "effectiveModelId:String", "usage:ModelUsage",
                        "finishReasonCode:String", "resultRef:ResultRef",
                        "reasoningContent:java.util.Optional<String>",
                        "assistantTurn:java.util.Optional<AssistantTurn>", "protocolState:java.util.Optional<NativeProtocolState>"),
                componentSignatures(ModelPort.ModelResult.Completed.class));
        assertEquals(List.of("contents:java.util.List<AssistantContent>"),
                componentSignatures(ModelPort.AssistantTurn.class));
        assertEquals(List.of("text:String"),
                componentSignatures(ModelPort.AssistantContent.Text.class));
        assertEquals(List.of("callId:String", "wireName:String", "argumentsJson:String"),
                componentSignatures(ModelPort.AssistantContent.ToolCall.class));
        assertEquals(Set.of("contents", "content", "toolCalls", "kind", "equals", "hashCode", "toString"),
                publicDeclaredMethodNames(ModelPort.AssistantTurn.class));
        assertEquals(List.of(), componentSignatures(ModelPort.ResponseFormat.NativeConversation.class));
        assertEquals(
                List.of("failure:PortFailure", "retryAt:java.util.Optional<java.time.Instant>"),
                componentSignatures(ModelPort.ModelResult.Failed.class));
        assertEquals(
                List.of("failure:PortFailure"),
                componentSignatures(ModelPort.ModelResult.OutcomeUnknown.class));
        assertEquals(
                List.of("sequence:long", "content:String"),
                componentSignatures(ModelPort.ModelStreamEvent.ContentDelta.class));
        assertEquals(
                List.of("sequence:long", "content:String"),
                componentSignatures(ModelPort.ModelStreamEvent.ReasoningDelta.class));
        assertEquals(
                List.of("sequence:long", "index:int", "callId:String", "toolHandle:String",
                        "argumentsDelta:String"),
                componentSignatures(ModelPort.ModelStreamEvent.ToolCallDelta.class));
        assertEquals(
                List.of("sequence:long", "usage:ModelUsage"),
                componentSignatures(ModelPort.ModelStreamEvent.UsageDelta.class));
        assertEquals(
                List.of("result:ModelResult.Completed"),
                componentSignatures(ModelPort.ModelStreamEvent.Completed.class));
        assertEquals(
                List.of("failure:PortFailure", "retryAt:java.util.Optional<java.time.Instant>"),
                componentSignatures(ModelPort.ModelStreamEvent.Failed.class));
        assertEquals(
                List.of("failure:PortFailure"),
                componentSignatures(ModelPort.ModelStreamEvent.OutcomeUnknown.class));
        assertEquals(
                List.of("TEXT", "JSON_OBJECT", "JSON_SCHEMA"),
                Arrays.stream(ModelPort.ResponseFormatKind.values()).map(Enum::name).toList());
        assertEquals(
                List.of("SYSTEM", "USER", "ASSISTANT", "TOOL"),
                Arrays.stream(ModelPort.ModelRole.values()).map(Enum::name).toList());
        assertEquals(
                List.of("STRICT", "BEST_EFFORT"),
                Arrays.stream(ModelPort.EnforcementLevel.values()).map(Enum::name).toList());
        assertEquals(
                List.of("DEFAULT", "DISABLED", "ENABLED", "MINIMAL", "LOW", "MEDIUM", "HIGH", "XHIGH", "MAX"),
                Arrays.stream(ModelPort.ReasoningPolicy.values()).map(Enum::name).toList());
        assertEquals(List.of("CHAT_COMPLETIONS", "RESPONSES", "MESSAGES"),
                Arrays.stream(ModelPort.NativeProtocol.values()).map(Enum::name).toList());
        assertEquals(Set.of(ModelPort.ResponseFormat.Text.class, ModelPort.ResponseFormat.JsonObject.class,
                        ModelPort.ResponseFormat.JsonSchema.class, ModelPort.ResponseFormat.NativeConversation.class),
                Set.of(ModelPort.ResponseFormat.class.getPermittedSubclasses()));
        assertEquals(Set.of("Text", "JsonObject", "JsonSchema", "NativeConversation"),
                Arrays.stream(ModelPort.ResponseFormat.class.getDeclaredClasses())
                        .map(Class::getSimpleName).collect(Collectors.toSet()));
        assertEquals(Set.of(ModelPort.AssistantContent.Text.class, ModelPort.AssistantContent.ToolCall.class),
                Set.of(ModelPort.AssistantContent.class.getPermittedSubclasses()));
        assertEquals(Set.of("Text", "ToolCall"),
                Arrays.stream(ModelPort.AssistantContent.class.getDeclaredClasses())
                        .map(Class::getSimpleName).collect(Collectors.toSet()));
        assertEquals(3, ModelPort.ModelResult.class.getPermittedSubclasses().length);
        assertEquals(7, ModelPort.ModelStreamEvent.class.getPermittedSubclasses().length);
        assertEquals(Set.of(
                        ModelResolutionPort.ResolutionResult.Resolved.class,
                        ModelResolutionPort.ResolutionResult.Rejected.class),
                Set.of(ModelResolutionPort.ResolutionResult.class.getPermittedSubclasses()));
    }

    /** 验证模型解析请求封闭 owner 与 tenant，并限制本地拒绝为 RetryMode.NONE。 */
    @Test
    void validatesModelResolutionIdentityAndRetryMode() {
        ModelPort.ModelBindingRef binding = binding(TENANT);
        ModelResolutionPort.ModelResolutionRequest request =
                new ModelResolutionPort.ModelResolutionRequest(
                        runIdentity(OWNER, RUN), identity(OWNER), binding, strategy());

        assertEquals(binding, request.binding());
        assertThrows(IllegalArgumentException.class,
                () -> new ModelResolutionPort.ModelResolutionRequest(
                        runIdentity(OWNER, RUN),
                        identity(SessionOwner.user(
                                new SessionRef(new TenantId("tenant-2"), "session-2"),
                                TARGET, USER)),
                        binding,
                        strategy()));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelResolutionPort.ModelResolutionRequest(
                        runIdentity(OWNER, RUN), identity(OWNER),
                        binding(new TenantId("tenant-2")), strategy()));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelResolutionPort.ResolutionResult.Rejected(
                        failure(RetryMode.IDEMPOTENT_BY_STABLE_KEY)));
        assertEquals(
                RetryMode.NONE,
                new ModelResolutionPort.ResolutionResult.Rejected(failure(RetryMode.NONE))
                        .failure().retryMode());
    }

    /** 验证模型能力集合防御复制，且 target、生成参数和 usage 使用严格数值边界。 */
    @Test
    void validatesFrozenTargetOptionsAndUsage() {
        HashSet<ModelPort.ResponseFormatKind> mutableFormats = new HashSet<>(Set.of(
                ModelPort.ResponseFormatKind.TEXT, ModelPort.ResponseFormatKind.JSON_OBJECT));
        ModelPort.ModelCapabilitySnapshot capabilities = new ModelPort.ModelCapabilitySnapshot(
                mutableFormats, true, true, new ResultRef("capability-1"));
        mutableFormats.remove(ModelPort.ResponseFormatKind.JSON_OBJECT);

        assertEquals(
                Set.of(ModelPort.ResponseFormatKind.TEXT, ModelPort.ResponseFormatKind.JSON_OBJECT),
                capabilities.responseFormats());
        assertNotSame(mutableFormats, capabilities.responseFormats());
        assertThrows(UnsupportedOperationException.class,
                () -> capabilities.responseFormats().add(ModelPort.ResponseFormatKind.JSON_SCHEMA));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelPort.ModelCapabilitySnapshot(
                        Set.of(ModelPort.ResponseFormatKind.JSON_OBJECT), true, true,
                        new ResultRef("capability-1")));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelPort.ModelCapabilitySnapshot(
                        null, true, true, new ResultRef("capability-1")));
        assertThrows(IllegalArgumentException.class,
                () -> target(TENANT, capabilities, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelPort.GenerationOptions(OptionalInt.of(0), OptionalDouble.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelPort.GenerationOptions(
                        OptionalInt.empty(), OptionalDouble.of(Double.NaN)));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelPort.GenerationOptions(
                        OptionalInt.empty(), OptionalDouble.of(Double.POSITIVE_INFINITY)));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelPort.GenerationOptions(
                        OptionalInt.empty(), OptionalDouble.of(2.01)));
        assertEquals(2.0,
                new ModelPort.GenerationOptions(OptionalInt.of(1), OptionalDouble.of(2.0))
                        .temperature().orElseThrow());
        assertThrows(IllegalArgumentException.class,
                () -> new ModelPort.ModelUsage(OptionalLong.of(-1), OptionalLong.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelPort.ModelUsage(null, OptionalLong.empty()));
        var twoField = new ModelPort.ModelUsage(OptionalLong.of(10), OptionalLong.of(2));
        assertTrue(twoField.cachedInputTokens().isEmpty());
        assertTrue(twoField.cacheWriteInputTokens().isEmpty());
        assertTrue(twoField.reasoningOutputTokens().isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> new ModelPort.ModelUsage(OptionalLong.empty(), OptionalLong.empty(),
                        OptionalLong.of(-1), OptionalLong.empty(), OptionalLong.empty()));
    }

    /** 验证调用目标同租户、消息不可变、响应格式已支持且 reasoning 不会静默降级。 */
    @Test
    void validatesModelCallNegotiationClosure() {
        ArrayList<ModelPort.ModelMessage> messages = new ArrayList<>(List.of(
                new ModelPort.ModelMessage("message-1", ModelPort.ModelRole.USER, "question")));
        ModelPort.ModelCapabilitySnapshot textOnly = capabilities(false, false);
        ModelPort.ModelCall call = modelCall(
                TENANT, messages, textOnly,
                new ModelPort.ResponseFormat.Text(), ModelPort.ReasoningPolicy.DISABLED);
        messages.clear();

        assertEquals(1, call.messages().size());
        assertThrows(UnsupportedOperationException.class,
                () -> call.messages().add(
                        new ModelPort.ModelMessage(
                                "message-2", ModelPort.ModelRole.USER, "more")));
        ModelPort.ModelMessage duplicate = new ModelPort.ModelMessage(
                "message-1", ModelPort.ModelRole.ASSISTANT, "answer");
        assertThrows(IllegalArgumentException.class,
                () -> modelCall(
                        TENANT,
                        List.of(
                                new ModelPort.ModelMessage(
                                        "message-1", ModelPort.ModelRole.USER, "question"),
                                duplicate),
                        textOnly,
                        new ModelPort.ResponseFormat.Text(),
                        ModelPort.ReasoningPolicy.DISABLED));
        assertThrows(IllegalArgumentException.class,
                () -> modelCall(
                        TENANT, null, textOnly,
                        new ModelPort.ResponseFormat.Text(), ModelPort.ReasoningPolicy.DISABLED));
        assertThrows(IllegalArgumentException.class,
                () -> modelCall(
                        new TenantId("tenant-2"), List.of(), textOnly,
                        new ModelPort.ResponseFormat.Text(), ModelPort.ReasoningPolicy.DISABLED));
        assertThrows(IllegalArgumentException.class,
                () -> modelCall(
                        TENANT, List.of(), textOnly,
                        new ModelPort.ResponseFormat.JsonObject(), ModelPort.ReasoningPolicy.DISABLED));
        assertThrows(IllegalArgumentException.class,
                () -> modelCall(
                        TENANT, List.of(), textOnly,
                        new ModelPort.ResponseFormat.Text(), ModelPort.ReasoningPolicy.DEFAULT));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelPort.ResponseFormat.JsonSchema(null));
    }

    /** NONE 表示禁用执行，可保留声明；空目录仍不能请求 AUTO 或 REQUIRED。 */
    @Test void retainedToolDeclarationsDoNotRequireCallableChoice() {
        var base = modelCall(TENANT, List.of(new ModelPort.ModelMessage("m", ModelPort.ModelRole.USER, "q")),
                capabilities(false, false), new ModelPort.ResponseFormat.NativeConversation(), ModelPort.ReasoningPolicy.DISABLED);
        var tools = List.of(new ModelPort.ModelTool("wire", "handle", "声明", new TypedPayload("schema.v1", "{}"), false, ModelPort.ModelToolKind.TOOL_CALL, false));
        var retained = new ModelPort.ModelCall(base.context(), base.requestedStrategy(), base.target(),
                base.invocationSnapshotRef(), base.messages(), tools, ModelPort.ToolChoice.NONE,
                base.response(), base.generation(), base.reasoning());
        assertEquals(tools, retained.tools());
        assertEquals(ModelPort.ToolChoice.NONE, retained.toolChoice());
        for (var choice : List.of(ModelPort.ToolChoice.AUTO, ModelPort.ToolChoice.REQUIRED))
            assertThrows(IllegalArgumentException.class, () -> new ModelPort.ModelCall(base.context(),
                    base.requestedStrategy(), base.target(), base.invocationSnapshotRef(), base.messages(), List.of(),
                    choice, base.response(), base.generation(), base.reasoning()));
    }

    /** 验证模型结果、stream 叶事件和 OutcomeUnknown 的完整重试模式白名单。 */
    @Test
    void validatesModelResultsAndStreamLeaves() {
        ModelPort.ModelResult.Completed completed = new ModelPort.ModelResult.Completed(
                payload(), "model-1", usage(), "stop", new ResultRef("result-1"));
        PortFailure rateLimited = new PortFailure(
                "model_rate_limited", RetryMode.NONE, new ResultRef("rate-limit-1"));
        ModelPort.ModelResult.Failed failed = new ModelPort.ModelResult.Failed(
                rateLimited, Optional.of(NOW));
        ModelPort.ModelStreamEvent.Failed streamFailed = new ModelPort.ModelStreamEvent.Failed(
                rateLimited, Optional.of(NOW));

        assertEquals("model-1", completed.effectiveModelId());
        assertTrue(completed.assistantTurn().isEmpty());
        var turn = new ModelPort.AssistantTurn(List.of(new ModelPort.AssistantContent.Text("公开正文😀")));
        assertEquals("公开正文😀", turn.content());
        assertEquals("AssistantTurn[REDACTED]", turn.toString());
        var call = new ModelPort.AssistantContent.ToolCall("call-1", "read_file", "{\"path\":");
        var tools = new ModelPort.AssistantTurn(List.of(call));
        assertEquals("", tools.content());
        assertEquals(ModelPort.AssistantTurnKind.TOOL_CALLS, tools.kind());
        assertEquals(List.of(call), tools.toolCalls());
        assertFalse(call.toString().contains("path"));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelPort.AssistantContent.Text("\ud800"));
        assertThrows(IllegalArgumentException.class, () -> new ModelPort.AssistantContent.Text(null));
        assertThrows(IllegalArgumentException.class, () -> new ModelPort.AssistantTurn(null));
        assertEquals(Optional.of(NOW), failed.retryAt());
        assertEquals(Optional.of(NOW), streamFailed.retryAt());
        assertEquals(1, new ModelPort.ModelStreamEvent.ContentDelta(1, "a").sequence());
        assertEquals("reasoning",
                new ModelPort.ModelStreamEvent.ReasoningDelta(2, "reasoning").content());
        assertThrows(IllegalArgumentException.class,
                () -> new ModelPort.ModelStreamEvent.ContentDelta(0, "a"));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelPort.ModelStreamEvent.ReasoningDelta(1, "\ud800"));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelPort.ModelStreamEvent.UsageDelta(1, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelPort.ModelStreamEvent.Completed(null));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelPort.ModelResult.Failed(
                        failure(RetryMode.NONE), Optional.of(NOW)));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelPort.ModelStreamEvent.Failed(
                        failure(RetryMode.NONE), Optional.of(NOW)));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelPort.ModelResult.Failed(
                        new PortFailure("model_rate_limited", RetryMode.IDEMPOTENT_BY_STABLE_KEY,
                                new ResultRef("rate-limit-2")), Optional.of(NOW)));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelPort.ModelStreamEvent.Failed(
                        new PortFailure("model_rate_limited", RetryMode.IDEMPOTENT_BY_STABLE_KEY,
                                new ResultRef("rate-limit-3")), Optional.of(NOW)));
        for (RetryMode retryMode : RetryMode.values()) {
            if (retryMode == RetryMode.NONE) {
                assertEquals(retryMode,
                        new ModelPort.ModelResult.OutcomeUnknown(failure(retryMode))
                                .failure().retryMode());
                assertEquals(retryMode,
                        new ModelPort.ModelStreamEvent.OutcomeUnknown(failure(retryMode))
                                .failure().retryMode());
            } else {
                assertThrows(IllegalArgumentException.class,
                        () -> new ModelPort.ModelResult.OutcomeUnknown(failure(retryMode)),
                        retryMode.name());
                assertThrows(IllegalArgumentException.class,
                        () -> new ModelPort.ModelStreamEvent.OutcomeUnknown(failure(retryMode)),
                        retryMode.name());
            }
        }
    }

    /** 构造完整身份验证请求，并允许测试替换 input Optional。 */
    private static IdentityPolicyPort.IdentityRequest identityRequest(Optional<InputId> inputId) {
        return new IdentityPolicyPort.IdentityRequest(
                new AuthenticationRef("auth-1"), OWNER, "api", "chat",
                new RequestId("request-1"), inputId, "trace-1", "span-1");
    }

    /** 构造与给定 owner 一致的已验证终端用户身份。 */
    private static VerifiedIdentity identity(SessionOwner owner) {
        return new VerifiedIdentity(
                new PrincipalRef.EndUserPrincipal(owner.sessionRef().tenantId(), owner.requireUser()),
                owner, Set.of("runtime:model"), 1, new ResultRef("verification-1"));
    }

    /** 构造当前 tenant 的正 revision 模型绑定。 */
    private static ModelPort.ModelBindingRef binding(TenantId tenantId) {
        return new ModelPort.ModelBindingRef(tenantId, "binding-1", 1);
    }

    /** 构造稳定的请求模型策略。 */
    private static ModelPort.RequestedModelStrategy strategy() {
        return new ModelPort.RequestedModelStrategy("balanced", "v1");
    }

    /** 构造指定能力开关且至少支持 TEXT 的冻结能力快照。 */
    private static ModelPort.ModelCapabilitySnapshot capabilities(
            boolean streaming, boolean reasoning) {
        return new ModelPort.ModelCapabilitySnapshot(
                Set.of(ModelPort.ResponseFormatKind.TEXT), streaming, reasoning,
                new ResultRef("capability-1"));
    }

    /** 构造指定租户、能力和超时的冻结模型目标。 */
    private static ModelPort.FrozenModelTarget target(
            TenantId tenantId,
            ModelPort.ModelCapabilitySnapshot capabilities,
            Duration timeout) {
        return new ModelPort.FrozenModelTarget(
                binding(tenantId), "model-1", capabilities, timeout,
                new ResultRef("resolution-1"));
    }

    /** 构造完整模型调用，便于单项替换目标租户、消息与协商契约。 */
    private static ModelPort.ModelCall modelCall(
            TenantId targetTenant,
            List<ModelPort.ModelMessage> messages,
            ModelPort.ModelCapabilitySnapshot capabilities,
            ModelPort.ResponseFormat responseFormat,
            ModelPort.ReasoningPolicy reasoning) {
        return new ModelPort.ModelCall(
                ProviderDispatchTestFixtures.providerContext(
                        context(), PortalCallKind.MODEL_GENERATE),
                strategy(), target(targetTenant, capabilities, Duration.ofSeconds(30)),
                new ResultRef("snapshot-1"), messages,
                new ModelPort.ResponseContract(
                        responseFormat, ModelPort.EnforcementLevel.STRICT, "requested"),
                new ModelPort.GenerationOptions(OptionalInt.of(100), OptionalDouble.of(0.5)),
                reasoning);
    }

    /** 构造不携带外调权限的普通运行、身份与 deadline 上下文。 */
    private static PortCallContext context() {
        return new PortCallContext(
                runIdentity(OWNER, RUN), identity(OWNER),
                new InvocationControl(NOW.plusSeconds(30), () -> false));
    }

    /** 构造与给定 owner 和 Run 引用一致的完整运行身份。 */
    private static RunIdentity runIdentity(SessionOwner owner, RunRef runRef) {
        TraceEnvelope trace = new TraceEnvelope(
                "trace-1", "span-1", INPUT, Optional.of(new EventId("event-1")));
        return new RunIdentity(owner, owner.target().requireAgent(), INPUT, runRef, trace, new ResultRef("causation-identity-1"), new com.skillforge.runtime.api.RunRequester.EndUser(owner.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(owner.sessionRef().tenantId(), INPUT.value()));
    }

    /** 构造指定重试模式且不含供应商细节的稳定失败事实。 */
    private static PortFailure failure(RetryMode retryMode) {
        return new PortFailure("model_failed", retryMode, new ResultRef("failure-1"));
    }

    /** 构造有正 token 计量的规范模型 usage。 */
    private static ModelPort.ModelUsage usage() {
        return new ModelPort.ModelUsage(OptionalLong.of(10), OptionalLong.of(2));
    }

    /** 构造测试使用的 schema 化动态载荷。 */
    private static TypedPayload payload() {
        return new TypedPayload("schema-1", "{}");
    }

    /** 返回 record component 的名称与完整泛型类型，冻结字段顺序和 ABI。 */
    private static List<String> componentSignatures(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(component -> component.getName() + ":" + stableTypeName(component))
                .toList();
    }

    /** 收集顶层 Port 直接声明的公开方法名，拒绝未来无登记扩张。 */
    private static Set<String> publicDeclaredMethodNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .collect(Collectors.toSet());
    }

    /** 压缩稳定 SDK 与 java.lang 前缀，并保留集合和 Optional 的泛型参数。 */
    private static String stableTypeName(RecordComponent component) {
        return component.getGenericType().getTypeName()
                .replace("com.skillforge.runtime.api.port.ModelPort$", "")
                .replace("com.skillforge.runtime.api.port.", "")
                .replace("com.skillforge.runtime.api.", "")
                .replace("java.lang.", "")
                .replace('$', '.');
    }
}
