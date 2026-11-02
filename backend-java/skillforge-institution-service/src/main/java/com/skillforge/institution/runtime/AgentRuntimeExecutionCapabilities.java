package com.skillforge.institution.runtime;

import com.skillforge.core.capability.integration.runtime.tool.JdbcMcpBindings;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionLedgerPort;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.port.InvocationControl;
import com.skillforge.runtime.api.port.ModelPort.ResponseFormat;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.port.ToolPortal.ToolBinding.ProviderType;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.ExecutionCoordinator;
import com.skillforge.runtime.session.SessionExecutionWorkflows;
import com.skillforge.core.workflow.runtime.SdkWorkflowPortal;
import org.springframework.beans.factory.ObjectProvider;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.spi.WorkflowInteractionView;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Agent已加载材料与共享真实Provider边界之间的薄适配；本身不持有执行循环或事务。
 * Workflow节点使用同一Provider组件与Session协调器，避免维护另一套调用及结果转换实现。
 */
public final class AgentRuntimeExecutionCapabilities implements ExecutionCoordinator.Capabilities {
    private final AgentRuntimeExecutionSources sources;
    private final RegisteredExecutionProviderCalls calls;
    private final AgentRuntimeBodyPreview previews;
    private final AgentExecutionCodec codec;
    private final SessionExecutionWorkflows workflowCodec;
    private final ObjectProvider<SdkWorkflowPortal> workflows;
    private final JdbcExecutionLedgerPort executions;
    private final WorkflowInteractionView interactions;

    /** 接收唯一drive材料来源、共享真实调用组件及明确协议codec。 */
    public AgentRuntimeExecutionCapabilities(AgentRuntimeExecutionSources sources,
            RegisteredExecutionProviderCalls calls, AgentExecutionCodec codec, SessionExecutionWorkflows workflowCodec,
            ObjectProvider<SdkWorkflowPortal> workflows, JdbcExecutionLedgerPort executions, WorkflowInteractionView interactions, AgentRuntimeBodyPreview previews) {
        this.sources = Objects.requireNonNull(sources); this.calls = Objects.requireNonNull(calls);
        this.codec = Objects.requireNonNull(codec);
        this.workflowCodec = Objects.requireNonNull(workflowCodec); this.workflows = Objects.requireNonNull(workflows);
        this.executions = Objects.requireNonNull(executions);
        this.interactions = Objects.requireNonNull(interactions);
        this.previews = Objects.requireNonNull(previews);
    }

    /** 每个请求都来自本次真实派发回执；Existing结果只能由协调器采纳，不能进入本入口。 */
    @Override public ExecutionCoordinator.CapabilityResult invoke(ExecutionStore.DispatchResult.Registered registered,
            ExecutionStore.Prepared prepared) {
        var run = registered.run();
        return switch (prepared.kind()) {
            case MODEL -> {
                var original = codec.decodeModelRequest(prepared, refs -> sources.materials(run, refs));
                var request = original.request();
                var configuration = sources.configuration(run, new ResultRef("execution-input:" + run.identity().runRef().runId()));
                var control=control(run,request.target().timeout(),ExecutionBudgetPort.Category.BUSINESS_MODEL);
                yield sources.modelActivity(run,registered.attempt().id(),original.output().map(value->value.policy().closing()).orElse(false),
                        () -> {
                            if (!(request.response().format() instanceof ResponseFormat.NativeConversation))
                                return calls.model(registered, prepared, configuration.profile().callKind(), request,
                                        sources.modelBinding(run), control);
                            var preview = previews.begin(registered, prepared);
                            try {
                                var result = calls.model(registered, prepared, configuration.profile().callKind(), request,
                                        sources.modelBinding(run), control, preview);
                                if (result.kind() == ExecutionStore.ResultKind.SUCCEEDED) preview.flush();
                                else preview.unresolved(result.kind() == ExecutionStore.ResultKind.UNKNOWN ? "unknown" : "failed");
                                return result;
                            } catch (RuntimeException unresolved) {
                                preview.unresolved("unknown");
                                throw unresolved;
                            }
                        });
            }
            case TOOL -> {
                var request = codec.decodeTool(prepared.request().content());
                Optional<JdbcMcpBindings.Binding> binding = request.binding().providerType() == ProviderType.MCP
                        ? Optional.of(sources.mcpBinding(run, request.target())) : Optional.empty();
                yield calls.tool(registered, prepared, request, binding, sources.extraParameters(run), control(run, request.target().timeout(),ExecutionBudgetPort.Category.TOOL));
            }
            case KNOWLEDGE -> {
                var request = codec.decodeKnowledgeRequest(prepared.request().content());
                yield calls.knowledge(registered, prepared, request, sources.knowledgeBinding(run, request.target()),
                        control(run, request.target().timeout(),ExecutionBudgetPort.Category.KNOWLEDGE));
            }
            case WORKFLOW -> {
                var request = workflowCodec.decode(prepared.request().content());
                sources.workflowBinding(run, request.target());
                yield calls.workflow(registered, prepared, request, workflows.getObject(), executions, interactions, control(run, request.target().timeout(),ExecutionBudgetPort.Category.TOOL));
            }
            default -> throw new IllegalArgumentException("execution_capability_unsupported");
        };
    }

    /** 同drive取消及失租观察只读内存；原派发事务已核验当前IAM及冻结挂载。 */
    private InvocationControl control(ExecutionStore.Run run, Duration timeout,ExecutionBudgetPort.Category category) {
        return new InvocationControl(Instant.now().plus(sources.effectiveTimeout(run,category,timeout)), sources.cancellation(run));
    }
}
