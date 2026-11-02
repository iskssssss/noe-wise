package com.skillforge.runtime.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.runtime.api.CapabilityRef;
import com.skillforge.runtime.api.CapabilityType;
import com.skillforge.runtime.api.InboundKind;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.ToolEffect;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.CapabilitySnapshotRef;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.port.ModelPort;
import com.skillforge.runtime.api.port.PlanPort;
import com.skillforge.runtime.api.port.PortalCallKind;
import com.skillforge.runtime.api.port.ToolPortal;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.skillforge.runtime.core.LoopBudget;
import com.skillforge.runtime.session.AgentExecutionProgram;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.spi.SessionAdmissionFacts;
import com.skillforge.runtime.session.spi.SessionContextPolicy;
import com.skillforge.runtime.session.spi.SessionPreparations;
import com.skillforge.runtime.session.spi.SessionToolDirectory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;

/** 本地受理点冻结模型、工具、预算与前一任务引用；不保存凭据或当前活动对象。 */
final class DesktopConfiguration {
    static final String SCHEMA = "noewise.desktop.configuration.v5";
    static final String RESPONSE_PROTOCOL = "native-conversation.v1";
    private static final String BUDGET_POLICY = "observe-only";
    private DesktopConfiguration() { }

    static ObjectNode freeze(DesktopModels.Settings model, DesktopPolicy policy, String workspace,
            Optional<String> predecessor, List<DesktopTools.Definition> tools, DesktopPrompts.Prepared prompt) {
        var value = DesktopJson.object();
        value.put("responseProtocol", RESPONSE_PROTOCOL);
        value.put("budgetPolicy", BUDGET_POLICY);
        value.putObject("prompt").put("resource", prompt.resource()).put("fixedBodySha256", prompt.fixedBodySha256())
                .put("effectiveBodySha256", prompt.effectiveBodySha256());
        value.set("model", model.json()); value.put("workspace", workspace);
        predecessor.ifPresentOrElse(id -> value.put("predecessor", id), () -> value.putNull("predecessor"));
        var limits = value.putObject("policy");
        limits.put("maxActiveTasks", policy.maxActiveTasks()).put("maxQueuedTasks", policy.maxQueuedTasks())
                .put("outputTokens", policy.outputTokens()).put("inputTokens", policy.inputTokens())
                .put("modelTimeoutMillis", policy.modelTimeout().toMillis())
                .put("leaseDurationMillis", policy.leaseDuration().toMillis());
        var definitions = value.putArray("tools");
        for (var tool : tools) definitions.addObject().put("name", tool.name()).put("description", tool.description())
                .put("inputSchemaJson", tool.inputSchemaJson()).put("readOnly", tool.readOnly())
                .put("bindingToolName", tool.name().equals("run_command") ? "workspace_exec"
                        : tool.name().equals("read_context_reference") ? "read_context_reference" : "workspace_files")
                .put("callIdentityPolicy", ToolPortal.ToolCallIdentityPolicy.ARGUMENTS.name());
        return value;
    }

    static DesktopPolicy policy(JsonNode frozen) {
        var value = frozen.required("policy");
        if (!BUDGET_POLICY.equals(frozen.path("budgetPolicy").asText()))
            throw new IllegalArgumentException("desktop_budget_policy_unsupported");
        var fields = new HashSet<String>(); value.fieldNames().forEachRemaining(fields::add);
        if (!fields.equals(Set.of("maxActiveTasks", "maxQueuedTasks", "outputTokens", "inputTokens", "modelTimeoutMillis", "leaseDurationMillis")))
            throw new IllegalArgumentException("desktop_budget_policy_invalid");
        return new DesktopPolicy(value.required("maxActiveTasks").intValue(), value.required("maxQueuedTasks").intValue(), value.required("outputTokens").intValue(),
                value.required("inputTokens").longValue(), Duration.ofMillis(value.required("modelTimeoutMillis").longValue()),
                Duration.ofMillis(value.required("leaseDurationMillis").longValue()));
    }

    static ExecutionBudgetPort.Policy budget(ExecutionStore.Run run, JsonNode frozen) {
        policy(frozen);
        return new ExecutionBudgetPort.Policy.ObserveOnly(ExecutionBudgetPort.Scenario.STANDARD, run.configuration().value());
    }

    static List<SessionToolDirectory.Entry> tools(JsonNode frozen) {
        var entries = new ArrayList<SessionToolDirectory.Entry>();
        for (var definition : frozen.required("tools")) {
            var name = definition.required("name").textValue();
            boolean readOnly = definition.required("readOnly").booleanValue();
            var target = new ToolPortal.FrozenToolTarget(new ToolPortal.ToolHandle("tool:desktop:" + name),
                    new CapabilityRef(CapabilityType.TOOL, "desktop:" + name, "1"), "desktop-tools", 1,
                    new TypedPayload("json-schema", definition.required("inputSchemaJson").textValue()),
                    new TypedPayload("json-schema", "{}"), readOnly ? ToolEffect.READ_ONLY : ToolEffect.NON_IDEMPOTENT_WRITE,
                    readOnly, Duration.ofMinutes(11), true, ToolPortal.ToolConfirmationPolicy.NEVER, Set.of(), Set.of(), false,
                    ToolPortal.ToolCallIdentityPolicy.valueOf(definition.required("callIdentityPolicy").textValue()));
            String handler = definition.required("bindingToolName").textValue();
            var title = Optional.of(name);
            var binding = new ToolPortal.ToolBinding(ToolPortal.ToolBinding.ProviderType.PLATFORM, "desktop", handler);
            entries.add(new SessionToolDirectory.Entry(target, binding, new SessionToolDirectory.Discovery(binding.serverIdentity(),
                    binding.serverIdentity() + "/" + handler, handler, title, definition.required("description").textValue()), readOnly));
        }
        return List.copyOf(entries);
    }

    static String toolName(JsonNode frozen, AgentExecutionCodec.ToolRequest request) {
        var entries = tools(frozen);
        for (int index = 0; index < entries.size(); index++) {
            var entry = entries.get(index);
            if (entry.target().equals(request.target()) && entry.binding().equals(request.binding()))
                return frozen.required("tools").get(index).required("name").textValue();
        }
        throw new IllegalArgumentException("desktop_tool_target_rejected");
    }

    /** 历史展示只从原冻结目录识别标签，不构造或恢复旧执行绑定。 */
    static String projectedToolName(JsonNode frozen, AgentExecutionCodec.ToolRequest request) {
        for (var definition : frozen.required("tools")) {
            var name = definition.required("name").textValue();
            if (request.target().handle().value().equals("tool:desktop:" + name)) return name;
        }
        throw new IllegalArgumentException("desktop_tool_target_rejected");
    }

    static boolean supports(ExecutionStore.Material material) {
        if (!SCHEMA.equals(material.content().schemaId())) return false;
        var frozen = DesktopJson.parse(material.content().canonicalJson());
        if (!RESPONSE_PROTOCOL.equals(frozen.path("responseProtocol").asText())
                || !BUDGET_POLICY.equals(frozen.path("budgetPolicy").asText()) || !frozen.path("tools").isArray()
                || !frozen.path("model").path("streaming").isBoolean()) return false;
        for (var tool : frozen.path("tools")) {
            var identity = tool.path("callIdentityPolicy");
            if (!identity.isTextual() || !(ToolPortal.ToolCallIdentityPolicy.HANDLE.name().equals(identity.textValue())
                    || ToolPortal.ToolCallIdentityPolicy.ARGUMENTS.name().equals(identity.textValue()))) return false;
        }
        return true;
    }

    static AgentExecutionProgram.Configuration configuration(ExecutionStore.Run run, JsonNode frozen, DesktopModels models,
            List<ModelPort.ModelMessage> initialMessages, VerifiedIdentity actor) {
        var policy = policy(frozen);
        var target = models.target(DesktopModels.Settings.read(frozen.required("model")), run.identity().runRef().tenantId(), policy);
        var system = DesktopPrompts.frozen(frozen, initialMessages);
        var profile = new SessionPreparations.Profile(run.configuration(), SessionPreparations.PROFILE_VERSION,
                SessionPreparations.AGENT_INPUT_RENDERER_VERSION, SessionAdmissionFacts.ContextKind.AGENT_INPUT,
                system, target.binding(), DesktopModels.STRATEGY,
                new ModelPort.ResponseContract(new ModelPort.ResponseFormat.NativeConversation(), ModelPort.EnforcementLevel.STRICT, "desktop-native"),
                new ModelPort.GenerationOptions(OptionalInt.of(policy.outputTokens()), OptionalDouble.empty()), ModelPort.ReasoningPolicy.DISABLED,
                (target.capabilities().streaming() ? PortalCallKind.MODEL_STREAM : PortalCallKind.MODEL_GENERATE), new ResultRef("desktop-tokenizer:" + target.binding().bindingId() + ":" + policy.modelTimeout().toMillis()),
                SessionContextPolicy.forInputBudget(policy.inputTokens()), policy.inputTokens(), 2_000_000, 200, false, Set.of(InboundKind.MESSAGE));
        var entries = tools(frozen); var identities = new LinkedHashMap<String, String>();
        for (int index = 0; index < entries.size(); index++) identities.put(entries.get(index).target().handle().value(),
                frozen.required("tools").get(index).required("name").textValue());
        // 通用目录还含 Plan 控制工具；本宿主没有 Plan/Workflow/委派能力，不能向模型声明。
        var declarations = AgentExecutionProgram.modelTools(profile, entries, identities, List.of(), List.of(), List.of(), List.of()).stream()
                .filter(tool -> tool.kind() == ModelPort.ModelToolKind.TOOL_CALL)
                // 自定义兼容端点不承诺严格函数模式；保留可选参数，实际入参仍由Runtime按原schema校验。
                .map(tool -> new ModelPort.ModelTool(tool.wireName(), tool.toolHandle(), tool.description(), tool.inputSchema(),
                        false, tool.kind(), tool.batchEligible())).toList();
        return new AgentExecutionProgram.Configuration(profile, target, declarations, entries,
                LoopBudget.from(budget(run, frozen)),
                new CapabilitySnapshotRef(run.identity().owner().sessionRef(), run.identity().causationInputId(), "desktop-tools", 1),
                run.configuration(), initialMessages, actor, List.of(), List.of(), Set.of(), List.of(), PlanPort.State.absent(), false);
    }
}
