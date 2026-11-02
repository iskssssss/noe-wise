package com.skillforge.institution.integration;

import com.skillforge.core.billing.invocation.ExternalInvocationLedger;
import com.skillforge.core.billing.metering.CostRecorder;
import com.skillforge.institution.runtime.AgentRuntimeModelInvocationAudit;
import com.skillforge.runtime.adapter.jdbc.model.JdbcModelBindings;
import com.skillforge.runtime.adapter.model.ModelInvocationAudit;
import com.skillforge.runtime.api.port.ModelPort.ModelCall;
import com.skillforge.runtime.api.port.ModelPort.ModelUsage;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.util.Objects;
import java.util.function.UnaryOperator;
import org.springframework.transaction.PlatformTransactionManager;

/** Live suite 的实际工厂装饰：先绑定精确派发，再接共享发送计量和 suite 状态。 */
final class LiveDispatchedModelAudit extends AgentRuntimeModelInvocationAudit {
    private final AgentRuntimeModelInvocationAudit original;
    private final UnaryOperator<ModelInvocationAudit> meter;
    private final Callbacks callbacks;

    LiveDispatchedModelAudit(AgentRuntimeModelInvocationAudit original, ExternalInvocationLedger ledger,
            CostRecorder recorder, PlatformTransactionManager transactions,
            org.springframework.beans.factory.ObjectProvider<com.skillforge.institution.runtime.AgentRuntimeWorkflowNodeExecutions> workflows,
            UnaryOperator<ModelInvocationAudit> meter, Callbacks callbacks) {
        super(ledger, recorder, transactions, workflows);
        this.original = Objects.requireNonNull(original);
        this.meter = Objects.requireNonNull(meter);
        this.callbacks = Objects.requireNonNull(callbacks);
    }

    @Override public ModelInvocationAudit dispatched(ExecutionStore.DispatchResult.Registered registered,
            ExecutionStore.Prepared prepared, JdbcModelBindings.Binding frozenBinding) {
        var delegate = Objects.requireNonNull(meter.apply(original.dispatched(registered, prepared, frozenBinding)));
        return new ModelInvocationAudit() {
            @Override public boolean start(ModelCall call, Provider provider) { return callbacks.start(delegate, call, provider); }
            @Override public void usageReported(ModelCall call, Provider provider, long revision, ModelUsage usage) { callbacks.usageReported(delegate, call, provider, revision, usage); }
            @Override public void succeeded(ModelCall call, Provider provider, ModelUsage usage) { callbacks.succeeded(delegate, call, provider, usage); }
            @Override public void failed(ModelCall call, Provider provider, String reason) { callbacks.failed(delegate, call, provider, reason); }
            @Override public void unresolved(ModelCall call, Provider provider, String reason) { callbacks.unresolved(delegate, call, provider, reason); }
        };
    }

    /** 状态属于整个 suite，delegate 仅属于本次实际派发，不能保存为跨调用全局实例。 */
    interface Callbacks {
        boolean start(ModelInvocationAudit delegate, ModelCall call, ModelInvocationAudit.Provider provider);
        void usageReported(ModelInvocationAudit delegate, ModelCall call, ModelInvocationAudit.Provider provider, long revision, ModelUsage usage);
        void succeeded(ModelInvocationAudit delegate, ModelCall call, ModelInvocationAudit.Provider provider, ModelUsage usage);
        void failed(ModelInvocationAudit delegate, ModelCall call, ModelInvocationAudit.Provider provider, String reason);
        void unresolved(ModelInvocationAudit delegate, ModelCall call, ModelInvocationAudit.Provider provider, String reason);
    }
}
