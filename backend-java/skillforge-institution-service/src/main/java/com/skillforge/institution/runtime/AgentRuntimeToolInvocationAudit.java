package com.skillforge.institution.runtime;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.invocation.ExternalInvocationLedger;
import com.skillforge.core.billing.invocation.ExternalInvocationGuard.DuplicateAttemptException;
import com.skillforge.core.billing.invocation.ExternalInvocationRequest;
import com.skillforge.core.billing.invocation.ExternalInvocationScope;
import com.skillforge.core.billing.invocation.ExternalInvocationStatus;
import com.skillforge.core.billing.invocation.ExternalOperationType;
import com.skillforge.core.billing.metering.CostContext;
import com.skillforge.core.billing.metering.CostRecorder;
import com.skillforge.core.billing.metering.CostType;
import com.skillforge.core.billing.metering.MeterScope;
import com.skillforge.core.shared.tracing.TraceContext;
import com.skillforge.runtime.adapter.mcp.McpToolPortal.BusinessSendAudit;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.runtime.api.port.ToolPortal.ToolCall;
import com.skillforge.runtime.api.port.ToolPortal.ToolOutcome;
import com.skillforge.runtime.session.RegisteredExecutionCall;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 真实工具 Attempt 的宿主计量桥；不创建预算、重放或第二套执行状态。 */
@Component
public final class AgentRuntimeToolInvocationAudit {
    private static final Logger log = LoggerFactory.getLogger(AgentRuntimeToolInvocationAudit.class);
    private final ExternalInvocationLedger ledger;
    private final CostRecorder costs;
    private final TransactionTemplate terminals;
    private final org.springframework.beans.factory.ObjectProvider<AgentRuntimeProgramTools> programs;
    private final org.springframework.beans.factory.ObjectProvider<AgentRuntimeWorkflowNodeExecutions> workflows;

    public AgentRuntimeToolInvocationAudit(ExternalInvocationLedger ledger, CostRecorder costs,
            PlatformTransactionManager transactions,
            org.springframework.beans.factory.ObjectProvider<AgentRuntimeProgramTools> programs,
            org.springframework.beans.factory.ObjectProvider<AgentRuntimeWorkflowNodeExecutions> workflows) {
        this.ledger = Objects.requireNonNull(ledger);
        this.costs = Objects.requireNonNull(costs);
        this.programs = Objects.requireNonNull(programs);this.workflows=Objects.requireNonNull(workflows);
        terminals = new TransactionTemplate(Objects.requireNonNull(transactions));
        terminals.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 平台工具已经由 Guard 记账，只补充可信 Run 来源，不重复登记或结算。 */
    ToolOutcome platform(ExecutionStore.DispatchResult.Registered registered, ExecutionStore.Prepared prepared,
            ToolCall call, Supplier<ToolOutcome> invoke) {
        require(registered, prepared, call);
        return TenantContext.callWithTenant(tenant(call), () -> {
            try (var meter = MeterScope.openIsolated(context(call,registered.run()))) { return invoke.get(); }
        });
    }

    /** 只在真实业务发送前提交开始事实；握手与当前授权仍由原 transport/Store 处理。 */
    ToolOutcome mcp(ExecutionStore.DispatchResult.Registered registered, ExecutionStore.Prepared prepared,
            ToolCall call, Function<BusinessSendAudit, ToolOutcome> invoke) {
        require(registered, prepared, call);
        var request = request(call);
        var started = new AtomicBoolean();
        var attempted = new AtomicBoolean();
        return TenantContext.callWithTenant(tenant(call), () -> {
            try (var meter = MeterScope.openIsolated(context(call,registered.run()))) {
                var businessSendAudit = new BusinessSendAudit() {
                    @Override public void beforeSend() {
                        if (started.get() || !ledger.startAuxiliary(tenant(call), request, Instant.now()))
                            throw new DuplicateAttemptException(request.attemptId());
                        started.set(true);
                    }
                    @Override public void sendAttempted() { attempted.set(true); }
                };
                ToolOutcome actual;
                try { actual = invoke.apply(businessSendAudit); }
                catch (RuntimeException failure) {
                    if (started.get()) finish(call, request, ExternalInvocationStatus.UNRESOLVED,
                            "TOOL_TRANSPORT_UNRESOLVED", "unknown", attempted.get());
                    throw failure;
                }
                if (started.get()) {
                    switch (actual) {
                        case ToolOutcome.Succeeded ignored -> finish(call, request, ExternalInvocationStatus.SUCCEEDED, null, "succeeded", attempted.get());
                        // 等待输入也是一次已发生的供应商响应，后续继续使用原 Action 的新 Attempt。
                        case ToolOutcome.InputRequired ignored -> finish(call, request, ExternalInvocationStatus.SUCCEEDED, null, "input_required", attempted.get());
                        case ToolOutcome.Failed failed -> finish(call, request, ExternalInvocationStatus.FAILED, failed.failure().reasonCode(), "failed", attempted.get());
                        case ToolOutcome.OutcomeUnknown unknown -> finish(call, request, ExternalInvocationStatus.UNRESOLVED, unknown.failure().reasonCode(), "unknown", attempted.get());
                    }
                }
                return actual;
            }
        });
    }

    /** 审计事务失败不能覆盖真实工具结果；保留开始事实和 UNKNOWN，交由原账本对账。 */
    private void finish(ToolCall call, ExternalInvocationRequest request, ExternalInvocationStatus status,
            String reason, String responseKind, boolean attempted) {
        try {
            terminals.executeWithoutResult(transaction -> {
                if (attempted) {
                    try (var scope = ExternalInvocationScope.open(request)) {
                        costs.recordExternal(tenant(call), CostType.TOOL_CALL, "mcp", request.service(), Objects.requireNonNull(MeterScope.current()),
                                Map.of("runtime_run_id", call.context().dispatch().run().runId(),
                                        "tool_outcome", responseKind, "unit", "call"));
                    }
                }
                if (!ledger.finish(tenant(call), request.attemptId(), status, null, reason, Instant.now()))
                    throw new IllegalStateException("tool_attempt_terminal_conflict");
            });
        } catch (RuntimeException failure) {
            log.error("工具审计终结失败 tenant={} run={} attempt={}，保留原结果与未知计量", tenant(call),
                    call.context().dispatch().run().runId(), request.attemptId(), failure);
        }
    }

    private static void require(ExecutionStore.DispatchResult.Registered registered,
            ExecutionStore.Prepared prepared, ToolCall call) {
        RegisteredExecutionCall.require(registered, prepared, call.context(), ExecutionStore.Kind.TOOL);
    }

    private static ExternalInvocationRequest request(ToolCall call) {
        var dispatch = call.context().dispatch();
        return new ExternalInvocationRequest(dispatch.invocationId(), dispatch.attemptId(), ExternalOperationType.TOOL_CALL,
                "mcp", call.binding().serverIdentity(), call.idempotencyKey().value(), trace(call),
                Map.of("deploymentId", call.target().deploymentId(), "revision", call.target().deploymentRevision(),
                        "toolHandle", call.target().handle().value()));
    }

    private CostContext context(ToolCall call,ExecutionStore.Run registered) {
        var run = call.context().callContext().runIdentity();
        var programOrigin=programs.getObject().resourceOrigin(registered);
        var origin=programOrigin.equals(run)?workflows.getObject().resourceOrigin(registered):workflows.getObject().resourceOrigin(programOrigin);
        return CostContext.builder().requestId(run.causationInputId().value()).rootInputId(run.trace().rootInputId().value())
                .runId(run.runRef().runId()).sessionId(run.owner().sessionRef().sessionId())
                .attributionRunId(origin.runRef().runId())
                .endUserId(run.requester() instanceof RunRequester.EndUser user ? user.endUserId().value() : null)
                .agentId(Long.parseLong(run.agentId().value())).callPoint("agent-runtime.tool").traceContext(trace(call)).build();
    }

    private static TraceContext trace(ToolCall call) {
        var run = call.context().callContext().runIdentity();
        var trace = run.trace();
        return new TraceContext(run.causationInputId().value(), trace.rootInputId().value(), trace.traceId(), trace.spanId(),
                call.context().dispatch().requestRef().value(), trace.causedByEventId().map(value -> value.value()).orElse(null));
    }

    private static String tenant(ToolCall call) { return call.context().dispatch().run().tenantId().value(); }
}
