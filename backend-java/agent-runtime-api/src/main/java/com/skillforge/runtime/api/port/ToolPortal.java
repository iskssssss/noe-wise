package com.skillforge.runtime.api.port;

import com.skillforge.runtime.api.CapabilityRef;
import com.skillforge.runtime.api.CapabilityType;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.ToolEffect;
import com.skillforge.runtime.api.TypedPayload;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 隔离 Core 与具体 Tool provider，并只接受一次模型选择绑定的精确调用目标。 */
public interface ToolPortal {

    /** Tool Provider 路由身份；名称只用于目录发现，不能替代 opaque handle 调用。 */
    record ToolBinding(ProviderType providerType,String providerRef,String toolName) {
        public enum ProviderType { PLATFORM, CONNECTOR, MCP, TEST_MOCK }
        public ToolBinding {
            providerType=PortContracts.requireNonNull(providerType,"providerType");
            providerRef=PortContracts.requireText(providerRef,"providerRef");
            toolName=PortContracts.requireText(toolName,"toolName");
        }
        public String serverIdentity(){return providerType.name().toLowerCase(Locale.ROOT)+":"+providerRef;}
    }

    /** 调用一个精确 Tool 目标并返回确定性明确的结果。 */
    ToolOutcome invoke(ToolCall call);

    /** 使用同一逻辑 Action 的精确目标和服务端 opaque 状态继续一次 Tool 调用。 */
    ToolOutcome resume(ToolContinuationCall call);

    /** 表示 `tool:<mountId>` 格式的模型可见挂载句柄。 */
    record ToolHandle(String value) {

        /** 拒绝非 Tool 前缀或缺失 mountId 的句柄。 */
        public ToolHandle {
            value = requireHandle(value, "tool:");
        }
    }

    /** 固定 Tool 挂载、发布版本、schema、副作用、幂等性与超时。 */
    record FrozenToolTarget(
            ToolHandle handle,
            CapabilityRef capability,
            String deploymentId,
            long deploymentRevision,
            TypedPayload inputSchema,
            TypedPayload outputSchema,
            ToolEffect effect,
            boolean idempotent,
            Duration timeout,
            boolean modelInvocable,
            ToolConfirmationPolicy confirmation,
            Set<String> sensitiveFields,
            Set<String> providerRequirements,
            boolean outputSchemaDeclared,
            ToolCallIdentityPolicy callIdentityPolicy) {

        /** 普通注册按已核证的副作用选择身份政策；特殊能力须显式声明并冻结。 */
        public FrozenToolTarget(ToolHandle handle, CapabilityRef capability, String deploymentId, long deploymentRevision,
                TypedPayload inputSchema, TypedPayload outputSchema, ToolEffect effect, boolean idempotent, Duration timeout,
                boolean modelInvocable, ToolConfirmationPolicy confirmation, Set<String> sensitiveFields,
                Set<String> providerRequirements, boolean outputSchemaDeclared) {
            this(handle, capability, deploymentId, deploymentRevision, inputSchema, outputSchema, effect, idempotent,
                    timeout, modelInvocable, confirmation, sensitiveFields, providerRequirements, outputSchemaDeclared,
                    ToolCallIdentityPolicy.forEffect(effect));
        }

        /** 拒绝错类型能力、不完整发布事实与非正超时。 */
        public FrozenToolTarget {
            handle = PortContracts.requireNonNull(handle, "handle");
            capability = requireCapability(capability, CapabilityType.TOOL);
            deploymentId = PortContracts.requireText(deploymentId, "deploymentId");
            deploymentRevision = PortContracts.requirePositive(deploymentRevision, "deploymentRevision");
            inputSchema = PortContracts.requireNonNull(inputSchema, "inputSchema");
            outputSchema = PortContracts.requireNonNull(outputSchema, "outputSchema");
            effect = PortContracts.requireNonNull(effect, "effect");
            callIdentityPolicy = PortContracts.requireNonNull(callIdentityPolicy, "callIdentityPolicy");
            timeout = PortContracts.requirePositive(timeout, "timeout");
            confirmation = PortContracts.requireNonNull(confirmation, "confirmation");
            sensitiveFields = immutableTextSet(sensitiveFields, "sensitiveFields");
            providerRequirements = immutableTextSet(
                    providerRequirements, "providerRequirements");
            if (effect == ToolEffect.READ_ONLY && confirmation == ToolConfirmationPolicy.ALWAYS) {
                throw new IllegalArgumentException(
                        "read-only tool must not require unconditional confirmation");
            }
            if (effect == ToolEffect.IDEMPOTENT_WRITE && !idempotent) {
                throw new IllegalArgumentException(
                        "IDEMPOTENT_WRITE requires idempotent=true");
            }
            if (!outputSchemaDeclared
                    && (!"json-schema".equals(outputSchema.schemaId())
                    || !"{}".equals(outputSchema.canonicalJson()))) {
                throw new IllegalArgumentException(
                        "undeclared output schema must use canonical empty JSON Schema");
            }
        }
    }

    /** 可信发布事实，只影响逻辑调用身份，不授予重试、并行或执行权限。 */
    enum ToolCallIdentityPolicy {
        HANDLE, ARGUMENTS;
        public static final String DESCRIPTOR_FIELD = "call_identity_policy";

        public static ToolCallIdentityPolicy forEffect(ToolEffect effect) {
            return switch (PortContracts.requireNonNull(effect, "effect")) {
                case READ_ONLY, IDEMPOTENT_WRITE -> ARGUMENTS;
                case NON_IDEMPOTENT_WRITE, DESTRUCTIVE -> HANDLE;
            };
        }
    }

    /** ToolVersion 冻结的调用前确认策略；Runtime 不从当前目录补写确认事实。 */
    enum ToolConfirmationPolicy { NEVER, POLICY, ALWAYS }

    /** 描述一次使用稳定逻辑动作与幂等键的 Tool 尝试。 */
    record ToolCall(
            ProviderCallContext context,
            ToolBinding binding,
            FrozenToolTarget target,
            TypedPayload arguments,
            IdempotencyKey idempotencyKey) {

        /** 拒绝缺失的调用上下文、目标、参数或幂等键。 */
        public ToolCall {
            context = PortContracts.requireNonNull(context, "context");
            binding = PortContracts.requireNonNull(binding, "binding");
            target = PortContracts.requireNonNull(target, "target");
            arguments = PortContracts.requireNonNull(arguments, "arguments");
            idempotencyKey = PortContracts.requireNonNull(idempotencyKey, "idempotencyKey");
        }

        /** Tool 参数可能含业务敏感值，普通日志固定脱敏。 */
        @Override
        public String toString() {
            return "ToolCall[REDACTED]";
        }
    }

    /** 使用新的 Provider attempt 继续原 Tool 调用；requestState 只允许原样传递。 */
    record ToolContinuationCall(
            ProviderCallContext context,
            ToolBinding binding,
            FrozenToolTarget target,
            TypedPayload arguments,
            IdempotencyKey idempotencyKey,
            Map<String, ToolInputResponse> inputResponses,
            Optional<String> requestState) {

        /** 拒绝跨 Session、空响应或缺失 continuation 状态。 */
        public ToolContinuationCall {
            context = PortContracts.requireNonNull(context, "context");
            binding = PortContracts.requireNonNull(binding, "binding");
            target = PortContracts.requireNonNull(target, "target");
            arguments = PortContracts.requireNonNull(arguments, "arguments");
            idempotencyKey = PortContracts.requireNonNull(idempotencyKey, "idempotencyKey");
            inputResponses = immutableTextMap(inputResponses, "inputResponses");
            requestState = PortContracts.requireOptional(requestState, "requestState")
                    .map(value -> PortContracts.requireText(value, "requestState"));
            if (inputResponses.isEmpty() && requestState.isEmpty()) {
                throw new IllegalArgumentException(
                        "Tool continuation requires inputResponses or requestState");
            }
        }

        /** continuation 可能含用户输入与服务端私有状态，普通日志固定脱敏。 */
        @Override
        public String toString() {
            return "ToolContinuationCall[REDACTED]";
        }
    }

    /** MCP multi-round-trip 允许的三种服务端输入请求方法。 */
    enum ToolInputMethod {
        ELICITATION_CREATE("elicitation/create"),
        SAMPLING_CREATE_MESSAGE("sampling/createMessage"),
        ROOTS_LIST("roots/list");

        private final String wireName;

        ToolInputMethod(String wireName) {
            this.wireName = wireName;
        }

        /** 返回 MCP 规范中的精确 method 名称。 */
        public String wireName() {
            return wireName;
        }

        /** 只接受规范登记的方法，不提供未知方法回退。 */
        public static ToolInputMethod fromWireName(String value) {
            value = PortContracts.requireText(value, "method");
            for (ToolInputMethod method : values()) {
                if (method.wireName.equals(value)) {
                    return method;
                }
            }
            throw new IllegalArgumentException("unsupported tool input method");
        }
    }

    /** 保存一个 inputRequests map value；请求身份由所属 map key 唯一确定。 */
    record ToolInputRequest(ToolInputMethod method, TypedPayload params) {

        /** 请求 method 与参数必须完整，参数 schema 由对应执行边界验证。 */
        public ToolInputRequest {
            method = PortContracts.requireNonNull(method, "method");
            params = PortContracts.requireNonNull(params, "params");
        }

        /** 服务端请求参数可能含提示、URL 或其他私有内容，普通日志只显示方法。 */
        @Override
        public String toString() {
            return "ToolInputRequest[method=" + method + ",params=REDACTED]";
        }
    }

    /** 保存一个 inputResponses map value，并绑定原请求 method 防止跨请求替换。 */
    record ToolInputResponse(ToolInputMethod method, TypedPayload result) {

        /** 响应 method 与结果必须完整，业务内容不得作为授权事实。 */
        public ToolInputResponse {
            method = PortContracts.requireNonNull(method, "method");
            result = PortContracts.requireNonNull(result, "result");
        }

        /** 客户端响应可能含用户输入、模型输出或本地资源，普通日志不得展开。 */
        @Override
        public String toString() {
            return "ToolInputResponse[method=" + method + ",result=REDACTED]";
        }
    }

    /** 表达 Tool 已成功、等待协议输入、确定失败或结果未知四种结果确定性。 */
    sealed interface ToolOutcome
            permits ToolOutcome.Succeeded, ToolOutcome.InputRequired,
                    ToolOutcome.Failed, ToolOutcome.OutcomeUnknown {

        /** 返回已提交 observation、结果引用与去重后的用量引用。 */
        record Succeeded(
                TypedPayload observation,
                ResultRef resultRef,
                List<ResultRef> usageRefs) implements ToolOutcome {

            /** 拒绝缺失结果并防御性复制用量引用。 */
            public Succeeded {
                observation = PortContracts.requireNonNull(observation, "observation");
                resultRef = PortContracts.requireNonNull(resultRef, "resultRef");
                usageRefs = PortContracts.immutableDistinctList(usageRefs, "usageRefs");
            }

            /** Provider observation 仅能经安全投影消费，普通日志不得展开。 */
            @Override
            public String toString() {
                return "ToolSucceeded[REDACTED]";
            }
        }

        /** 表示本轮 Tool 调用需要客户端完成请求或原样保留服务端状态后再继续。 */
        record InputRequired(
                Map<String, ToolInputRequest> inputRequests,
                Optional<String> requestState) implements ToolOutcome {

            /** 每个结果至少携带 inputRequests 或 requestState 之一。 */
            public InputRequired {
                inputRequests = immutableTextMap(inputRequests, "inputRequests");
                requestState = PortContracts.requireOptional(requestState, "requestState")
                        .map(value -> PortContracts.requireText(value, "requestState"));
                if (inputRequests.isEmpty() && requestState.isEmpty()) {
                    throw new IllegalArgumentException(
                            "InputRequired requires inputRequests or requestState");
                }
            }

            /** 请求参数与 opaque 状态不进入普通日志。 */
            @Override
            public String toString() {
                return "ToolInputRequired[REDACTED]";
            }
        }

        /** 确定失败及可选的完整私有比较事实；空表示事实不可得，不代表没有详情。 */
        record Failed(PortFailure failure, Optional<TypedPayload> comparisonFact) implements ToolOutcome {
            public Failed {
                failure = PortContracts.requireNonNull(failure, "failure");
                comparisonFact = PortContracts.requireOptional(comparisonFact, "comparisonFact");
            }

            /** 未提供完整事实的失败不能与不同来源按原因码合并。 */
            public Failed(PortFailure failure) { this(failure, Optional.empty()); }

            /** 私有事实不得进入普通日志。 */
            @Override public String toString() { return "ToolFailed[REDACTED]"; }
        }

        /** 表示结果未知且只允许不重试或稳定键幂等重试。 */
        record OutcomeUnknown(PortFailure failure) implements ToolOutcome {

            /** 拒绝 Tool 无法收敛的查询或协调重试模式。 */
            public OutcomeUnknown {
                failure = requireRetryMode(
                        failure,
                        Set.of(RetryMode.NONE, RetryMode.IDEMPOTENT_BY_STABLE_KEY),
                        "Tool OutcomeUnknown");
            }
        }
    }

    /** 防御性复制以文本为身份的协议 map，并拒绝空白键、null 或重复规范键。 */
    private static <T> Map<String, T> immutableTextMap(Map<String, T> values, String field) {
        PortContracts.requireNonNull(values, field);
        var copy = new LinkedHashMap<String, T>();
        values.forEach((key, value) -> {
            String canonicalKey = PortContracts.requireText(key, field + " key");
            T previous = copy.put(canonicalKey,
                    PortContracts.requireNonNull(value, field + " value"));
            if (previous != null) {
                throw new IllegalArgumentException(field + " must not contain duplicate keys");
            }
        });
        return Map.copyOf(copy);
    }

    /** 防御性复制冻结策略集合，并拒绝空白或未规范化 requirement/字段名。 */
    private static Set<String> immutableTextSet(Set<String> values, String field) {
        PortContracts.requireNonNull(values, field);
        var copy = new java.util.LinkedHashSet<String>();
        for (String value : values) {
            String normalized = PortContracts.requireText(value, field + " value");
            if (!normalized.equals(normalized.trim()) || !copy.add(normalized)) {
                throw new IllegalArgumentException(field + " must contain distinct normalized text");
            }
        }
        return Set.copyOf(copy);
    }

    /** 校验能力引用与 Portal 的封闭类型相符。 */
    private static CapabilityRef requireCapability(CapabilityRef capability, CapabilityType expectedType) {
        capability = PortContracts.requireNonNull(capability, "capability");
        if (capability.type() != expectedType) {
            throw new IllegalArgumentException("capability type must be " + expectedType);
        }
        return capability;
    }

    /** 校验挂载句柄有精确前缀和非空白 mountId。 */
    private static String requireHandle(String value, String prefix) {
        value = PortContracts.requireText(value, "value");
        if (!value.startsWith(prefix) || value.length() == prefix.length()
                || value.substring(prefix.length()).isBlank()) {
            throw new IllegalArgumentException("handle must use " + prefix + "<mountId>");
        }
        return value;
    }

    /** 校验结果未知分支只声明 Portal 能安全承接的重试方式。 */
    private static PortFailure requireRetryMode(
            PortFailure failure,
            Set<RetryMode> allowedModes,
            String outcome) {
        failure = PortContracts.requireNonNull(failure, "failure");
        if (!allowedModes.contains(failure.retryMode())) {
            throw new IllegalArgumentException(outcome + " retry mode is not supported");
        }
        return failure;
    }
}
