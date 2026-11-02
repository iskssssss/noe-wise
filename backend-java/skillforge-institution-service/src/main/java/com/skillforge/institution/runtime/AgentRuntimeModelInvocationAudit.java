package com.skillforge.institution.runtime;

import com.skillforge.runtime.session.RegisteredExecutionCall;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.invocation.ExternalInvocationLedger;
import com.skillforge.core.billing.invocation.ExternalInvocationRequest;
import com.skillforge.core.billing.invocation.ExternalInvocationScope;
import com.skillforge.core.billing.invocation.ExternalInvocationStatus;
import com.skillforge.core.billing.invocation.ExternalOperationType;
import com.skillforge.core.billing.metering.CostContext;
import com.skillforge.core.billing.metering.CostRecorder;
import com.skillforge.core.billing.metering.CostType;
import com.skillforge.core.billing.metering.MeterScope;
import com.skillforge.core.billing.metering.LlmUsage;
import com.skillforge.core.model.invocation.LlmUsageMapper;
import com.skillforge.core.billing.pricing.ModelPriceSource.ModelRevisionRef;
import com.skillforge.core.shared.tracing.TraceContext;
import com.skillforge.runtime.adapter.jdbc.model.JdbcModelBindings;
import com.skillforge.runtime.adapter.model.ModelInvocationAudit;
import com.skillforge.runtime.api.port.ModelPort.ModelCall;
import com.skillforge.runtime.api.port.ModelPort.ModelUsage;
import com.skillforge.runtime.api.port.PortalCallKind;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Objects;

/** 把 Runtime 模型 attempt 投影到外调账本与用量事实，每个异步入口按可信调用身份绑定宿主租户。 */
@Component
public class AgentRuntimeModelInvocationAudit {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AgentRuntimeModelInvocationAudit.class);

    private final ExternalInvocationLedger ledger;
    private final CostRecorder costRecorder;
    /** 派生审计实例不是 Spring Bean，发送前开始事实仍显式共用一个独立事务。 */
    private final TransactionTemplate directStarts;
    private final org.springframework.beans.factory.ObjectProvider<AgentRuntimeWorkflowNodeExecutions> workflows;

    public AgentRuntimeModelInvocationAudit(
            ExternalInvocationLedger ledger, CostRecorder costRecorder,
            PlatformTransactionManager transactions,
            org.springframework.beans.factory.ObjectProvider<AgentRuntimeWorkflowNodeExecutions> workflows) {
        this.ledger = Objects.requireNonNull(ledger);
        this.costRecorder = Objects.requireNonNull(costRecorder);
        this.workflows=Objects.requireNonNull(workflows);
        this.directStarts = new TransactionTemplate(Objects.requireNonNull(transactions));
        this.directStarts.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 为已登记的主 Agent 模型调用绑定原始回执和本次已读精确模型来源，不建立新查询或隐式作用域。
     * 返回值只能审计同一 Run/Action/Attempt/actor/request/target；Store 已核实当前权限并登记预算，
     * 本实例不重复 IAM、预算 markSent、binding 读取或旧来源图恢复。
     */
    public ModelInvocationAudit dispatched(ExecutionStore.DispatchResult.Registered registered,
            ExecutionStore.Prepared prepared, JdbcModelBindings.Binding frozenBinding) {
        Objects.requireNonNull(registered); Objects.requireNonNull(prepared); Objects.requireNonNull(frozenBinding);
        if (prepared.kind() != ExecutionStore.Kind.MODEL)
            throw new IllegalArgumentException("registered_model_kind_required");
        return new DirectAudit(registered, prepared, frozenBinding, pricingReference(frozenBinding.sourceReference()));
    }

    /** 本次已授权登记的受限审计实例；配置与回执不可变，无跨请求缓存。 */
    private final class DirectAudit implements ModelInvocationAudit {
        private final ExecutionStore.DispatchResult.Registered registered;
        private final ExecutionStore.Prepared prepared;
        private final JdbcModelBindings.Binding binding;
        private final ModelRevisionRef pricing;
        private final com.skillforge.runtime.api.port.RunIdentity origin;

        /** 来源修订在外调前按本次精确 Binding 固定，不在结果审计时重查。 */
        private DirectAudit(ExecutionStore.DispatchResult.Registered registered, ExecutionStore.Prepared prepared,
                            JdbcModelBindings.Binding binding, ModelRevisionRef pricing) {
            this.registered = registered; this.prepared = prepared; this.binding = binding; this.pricing = pricing;
            this.origin=workflows.getObject().resourceOrigin(registered.run());
        }

        /** 开始事实和未知用量占位共用独立事务，提交成功才允许 OpenAI 适配器发送。 */
        @Override public boolean start(ModelCall call, Provider provider) {
            require(call);
            return TenantContext.callWithTenant(tenantId(call), () -> Boolean.TRUE.equals(directStarts.execute(status -> {
                var request = request(call, provider);
                boolean started = ledger.start(tenantId(call), request, Instant.now());
                if (started) costRecorder.recordUsageReport(tenantId(call), context(call), request,
                        provider.modelId(), 0, LlmUsage.empty(), metadata(call));
                return started;
            })));
        }

        /** 真实供应商报告独立保留 revision；不以缺报或 UNKNOWN 补零。 */
        @Override public void usageReported(ModelCall call, Provider provider, long revision, ModelUsage usage) {
            require(call);
            TenantContext.runWithTenant(tenantId(call), () -> costRecorder.recordUsageReport(tenantId(call), context(call),
                    request(call, provider), provider.modelId(), revision, usage(usage), metadata(call)));
        }

        /** 完整供应商结果沿原 CostRecorder 与外调终态保存，不重复预算结算。 */
        @Override public void succeeded(ModelCall call, Provider provider, ModelUsage usage) {
            require(call);
            TenantContext.runWithTenant(tenantId(call), () -> {
                try (ExternalInvocationScope ignored = ExternalInvocationScope.open(request(call, provider));
                     MeterScope meter = MeterScope.open(context(call))) {
                    costRecorder.recordLlm(tenantId(call), CostType.LLM_CHAT, provider.providerId(), provider.modelId(),
                            usage(usage), 1, pricing, metadata(call));
                } catch (RuntimeException failure) {
                    finishDirect(call, ExternalInvocationStatus.UNRESOLVED, "USAGE_RECORDING_FAILED");
                    throw failure;
                }
                finishDirect(call, ExternalInvocationStatus.SUCCEEDED, null);
            });
        }

        /** 确定失败与原报告计费同事务落账，缺报不产生 measured。 */
        @Override public void failed(ModelCall call, Provider provider, String reasonCode) {
            require(call);
            TenantContext.runWithTenant(tenantId(call), () -> {
                try {
                    directStarts.executeWithoutResult(status -> {
                        finishDirect(call, ExternalInvocationStatus.FAILED, reasonCode);
                        costRecorder.recordFailedModelUsage(tenantId(call), context(call), request(call, provider),
                                provider.modelId(), pricing, metadata(call));
                    });
                } catch (RuntimeException failure) {
                    try {
                        boolean updated = Boolean.TRUE.equals(directStarts.execute(status -> ledger.finish(tenantId(call),
                                call.context().dispatch().attemptId(), ExternalInvocationStatus.UNRESOLVED,
                                null, "MODEL_TERMINAL_AUDIT_FAILED", Instant.now())));
                        if (!updated) log.warn("模型终态审计回退未更新 tenant={} attempt={}",
                                tenantId(call), call.context().dispatch().attemptId());
                    } catch (RuntimeException fallbackFailure) {
                        if (fallbackFailure != failure) failure.addSuppressed(fallbackFailure);
                        log.warn("模型终态审计回退失败 tenant={} attempt={} diagnostic={}", tenantId(call),
                                call.context().dispatch().attemptId(),
                                com.skillforge.core.shared.logging.SafeFailureDiagnostics.of(fallbackFailure));
                    }
                    throw failure;
                }
            });
        }

        /** 未知事实保留 UNRESOLVED，不重放、不改写成成功。 */
        @Override public void unresolved(ModelCall call, Provider provider, String reasonCode) {
            require(call);
            TenantContext.runWithTenant(tenantId(call), () -> finishDirect(call, ExternalInvocationStatus.UNRESOLVED, reasonCode));
        }

        /** 原登记、完整 actor 和精确模型契约必须对应同一实际调用。 */
        private void require(ModelCall call) {
            var dispatch = RegisteredExecutionCall.require(registered, prepared, call.context(), ExecutionStore.Kind.MODEL);
            if (dispatch.callKind() != PortalCallKind.MODEL_STREAM && dispatch.callKind() != PortalCallKind.MODEL_GENERATE
                    || !call.invocationSnapshotRef().equals(prepared.request().reference())
                    || !call.target().equals(binding.target()) || !call.requestedStrategy().equals(binding.strategy()))
                throw new IllegalArgumentException("registered_model_call_mismatch");
        }

        /** 审计全部身份来自本次实际边界，不读取旧 permit 或 Codec。 */
        private ExternalInvocationRequest request(ModelCall call, Provider provider) {
            var dispatch = call.context().dispatch();
            return new ExternalInvocationRequest(dispatch.invocationId(), dispatch.attemptId(), ExternalOperationType.MODEL_CHAT,
                    provider.providerId(), provider.serviceId(), dispatch.attemptId(), traceDirect(call),
                    Map.of("model", provider.modelId(), "stream", dispatch.callKind() == PortalCallKind.MODEL_STREAM));
        }

        /** 可信 Run 已含原始 trace，事件关联使用本次实际请求引用。 */
        private TraceContext traceDirect(ModelCall call) {
            var run = call.context().callContext().runIdentity();
            var trace = run.trace();
            return new TraceContext(run.causationInputId().value(), trace.rootInputId().value(), trace.traceId(), trace.spanId(),
                    call.context().dispatch().requestRef().value(), trace.causedByEventId().map(value -> value.value()).orElse(null));
        }

        /** 模型事件保留实际Run，Workflow节点只沿已冻结父来源取得计量归属。 */
        private CostContext context(ModelCall call) {
            var run = call.context().callContext().runIdentity();
            return CostContext.builder().requestId(run.causationInputId().value()).rootInputId(run.trace().rootInputId().value())
                    .runId(run.runRef().runId()).attributionRunId(origin.runRef().runId()).sessionId(run.owner().sessionRef().sessionId()).endUserId(run.requester() instanceof com.skillforge.runtime.api.RunRequester.EndUser user?user.endUserId().value():null)
                    .agentId(Long.parseLong(run.agentId().value())).callPoint("agent-runtime.model").traceContext(traceDirect(call)).build();
        }

        /** 非正文元数据只补本次实际 Run 关联。 */
        private Map<String, Object> metadata(ModelCall call) {
            return Map.of("runtime_run_id", call.context().callContext().runIdentity().runRef().runId());
        }

        /** 使用原 Attempt 终结账本，冲突不能被吞为成功。 */
        private void finishDirect(ModelCall call, ExternalInvocationStatus status, String reason) {
            if (!ledger.finish(tenantId(call), call.context().dispatch().attemptId(), status, null, reason, Instant.now()))
                throw new IllegalStateException("model_attempt_terminal_conflict");
        }
    }

    private LlmUsage usage(ModelUsage value) {
        return LlmUsageMapper.fromModel(value);
    }

    /** 平台模型修订必须为明确数字，当前指针或无法解析来源不能用作本次计价。 */
    private static ModelRevisionRef pricingReference(String reference) {
        String[] parts = reference.split(":", -1);
        if (parts.length != 3 || !"platform-model".equals(parts[0])) {
            throw new IllegalStateException("model_binding_source_invalid");
        }
        try {
            return new ModelRevisionRef(parts[1], Long.parseLong(parts[2]));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("model_binding_source_invalid", invalid);
        }
    }

    private static String tenantId(ModelCall call) {
        return call.context().callContext().runIdentity().owner()
                .sessionRef().tenantId().value();
    }

}
