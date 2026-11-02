package com.skillforge.runtime.adapter.model;

import com.skillforge.runtime.api.port.ModelPort.ModelCall;
import com.skillforge.runtime.api.port.ModelPort.ModelUsage;
import java.util.Objects;
import java.util.Optional;

/** 每个模型 attempt 创建一个实例，观察已成功委托的供应商用量。 */
public final class ObservedModelInvocationAudit implements ModelInvocationAudit {
    private final ModelInvocationAudit delegate;
    private volatile ObservedUsage observed;

    public ObservedModelInvocationAudit(ModelInvocationAudit delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /** 返回最后一次成功委托的供应商快照；没有报告时为空。 */
    public Optional<ObservedUsage> observed() { return Optional.ofNullable(observed); }

    @Override public boolean start(ModelCall call, Provider provider) { return delegate.start(call, provider); }
    @Override public void succeeded(ModelCall call, Provider provider, ModelUsage usage) {
        delegate.succeeded(call, provider, usage);
    }
    @Override public void usageReported(ModelCall call, Provider provider, long revision, ModelUsage usage) {
        delegate.usageReported(call, provider, revision, usage);
        observed = new ObservedUsage(call, provider, revision, usage);
    }
    @Override public void failed(ModelCall call, Provider provider, String reasonCode) {
        delegate.failed(call, provider, reasonCode);
    }
    @Override public void unresolved(ModelCall call, Provider provider, String reasonCode) {
        delegate.unresolved(call, provider, reasonCode);
    }

    /** 原始供应商快照及其 attempt 和修订身份；不填补未报告的字段。 */
    public record ObservedUsage(ModelCall call, Provider provider, long revision, ModelUsage usage) { }
}
