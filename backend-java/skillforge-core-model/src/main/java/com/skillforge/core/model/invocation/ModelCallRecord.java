package com.skillforge.core.model.invocation;

import com.skillforge.runtime.api.TypedPayload;
import java.util.Objects;
import java.util.Optional;

/** 一次真实辅助模型调用已保存的材料；读取权由所属会话入口核验。 */
public record ModelCallRecord(long tenantId, String invocationId, String attemptId,
                              TypedPayload request, Optional<TypedPayload> outcome) {
    public ModelCallRecord {
        if (tenantId <= 0) throw new IllegalArgumentException("model_call_tenant_invalid");
        Objects.requireNonNull(invocationId);
        Objects.requireNonNull(attemptId);
        Objects.requireNonNull(request);
        Objects.requireNonNull(outcome);
    }

    @Override public String toString() { return "ModelCallRecord[REDACTED]"; }
}
