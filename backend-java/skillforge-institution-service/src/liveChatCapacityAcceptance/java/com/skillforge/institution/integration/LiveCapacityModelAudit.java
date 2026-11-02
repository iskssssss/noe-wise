package com.skillforge.institution.integration;

import com.skillforge.runtime.adapter.model.ModelInvocationAudit;
import com.skillforge.runtime.adapter.model.ModelInvocationAudit.Provider;
import com.skillforge.runtime.adapter.jdbc.model.JdbcModelBindings;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.port.ModelPort.ModelCall;
import com.skillforge.runtime.api.port.ModelPort.ModelUsage;

/** 保留生产计量及供应商事实，验证来源绑定；发送门禁只属于 ExecutionBudgetPort。 */
final class LiveCapacityModelAudit implements LiveDispatchedModelAudit.Callbacks {
    private final JdbcModelBindings bindings;
    private final LiveCapacityBudgets budgets;
    private volatile LiveCapacitySourceSnapshot.Snapshot source;
    LiveCapacityModelAudit(JdbcModelBindings bindings, LiveCapacityBudgets budgets) {
        this.bindings = bindings; this.budgets = budgets;
    }
    void freeze(LiveCapacitySourceSnapshot.Snapshot source) { this.source = source; }
    @Override public boolean start(ModelInvocationAudit delegate, ModelCall call, Provider provider) {
        var frozen = source;
        var run = call.context().callContext().runIdentity().runRef();
        if (frozen == null || budgets.find(run).isEmpty()
                || !bindings.find(call.target().binding()).map(JdbcModelBindings.Binding::sourceReference)
                    .filter(("platform-model:" + frozen.model().id() + ":" + frozen.model().revision())::equals).isPresent()
                || !provider.providerId().equals(frozen.model().providerId())
                || !provider.serviceId().equals(frozen.model().serviceId())
                || !provider.modelId().equals(frozen.model().modelId())
                || call.generation().maxOutputTokens().isEmpty()
                || call.generation().maxOutputTokens().getAsInt() > frozen.model().maxOutputTokens()) {
            budgets.stop(run, "MODEL_CONFIGURATION_DRIFT"); return false;
        }
        return delegate.start(call, provider);
    }
    @Override public void usageReported(ModelInvocationAudit delegate, ModelCall call, Provider provider, long revision, ModelUsage usage) {
        delegate.usageReported(call, provider, revision, usage);
    }
    @Override public void succeeded(ModelInvocationAudit delegate, ModelCall call, Provider provider, ModelUsage usage) {
        delegate.succeeded(call, provider, usage);
        budgets.providerUsage(call.context().callContext().runIdentity().runRef(),
                call.context().dispatch().attemptId(),
                ExecutionBudgetPort.Usage.reported(usage.inputTokens(), usage.outputTokens()));
    }
    @Override public void failed(ModelInvocationAudit delegate, ModelCall call, Provider provider, String reason) {
        budgets.stop(call.context().callContext().runIdentity().runRef(), "MODEL_FAILED");
        delegate.failed(call, provider, reason);
    }
    @Override public void unresolved(ModelInvocationAudit delegate, ModelCall call, Provider provider, String reason) {
        budgets.stop(call.context().callContext().runIdentity().runRef(), "MODEL_OUTCOME_UNKNOWN");
        delegate.unresolved(call, provider, reason);
    }
}
