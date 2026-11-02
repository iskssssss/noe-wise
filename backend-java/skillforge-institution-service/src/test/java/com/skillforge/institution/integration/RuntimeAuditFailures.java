package com.skillforge.institution.integration;

import com.skillforge.runtime.adapter.model.ModelInvocationAudit;
import com.skillforge.runtime.api.port.ModelPort.ModelCall;
import com.skillforge.runtime.api.port.ModelPort.ModelUsage;
import java.util.Queue;
import java.util.function.Supplier;

/** 观察真实派发审计实例的失败，保持全部调用和返回值，不伪造审计或Provider结果。 */
final class RuntimeAuditFailures {
    private RuntimeAuditFailures() { }
    static ModelInvocationAudit capture(ModelInvocationAudit actual, Queue<Throwable> failures) {
        return new ModelInvocationAudit() {
            private <T> T observedValue(Supplier<T> work) {
                try { return work.get(); }
                catch (RuntimeException | Error failure) { failures.add(failure); throw failure; }
            }
            private void observed(Runnable work) { observedValue(() -> { work.run(); return null; }); }
            @Override public boolean start(ModelCall call, Provider provider) { return observedValue(() -> actual.start(call, provider)); }
            @Override public void usageReported(ModelCall call, Provider provider, long revision, ModelUsage usage) {
                observed(() -> actual.usageReported(call, provider, revision, usage));
            }
            @Override public void succeeded(ModelCall call, Provider provider, ModelUsage usage) {
                observed(() -> actual.succeeded(call, provider, usage));
            }
            @Override public void failed(ModelCall call, Provider provider, String reason) { observed(() -> actual.failed(call, provider, reason)); }
            @Override public void unresolved(ModelCall call, Provider provider, String reason) { observed(() -> actual.unresolved(call, provider, reason)); }
        };
    }
}
