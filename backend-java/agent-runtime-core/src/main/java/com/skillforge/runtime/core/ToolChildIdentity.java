package com.skillforge.runtime.core;

import java.util.List;
import java.util.Objects;
import com.skillforge.runtime.api.port.ActionRef;

/** 工具逻辑身份：零为单调用，原生批次和持久程序请求分别使用所属契约范围。 */
public record ToolChildIdentity(ModelPreparationIdentity parent, int ordinal) {
    private static final String OPERATION_SCHEMA = "skillforge.runtime.tool-operation.v2";
    public ToolChildIdentity {
        Objects.requireNonNull(parent);
        if (ordinal < 0 || ordinal > com.skillforge.runtime.api.ToolCallLimits.MAX_CHILD_ORDINAL) throw new IllegalArgumentException("invalid_tool_child_ordinal");
    }
    CanonicalJson.Value canonicalValue() {
        return CanonicalJson.object(List.of(new CanonicalJson.Field("parent", parent.canonicalValue()),
                new CanonicalJson.Field("ordinal", CanonicalJson.integer(ordinal))));
    }
    /** 管理员定位与正式执行使用同一身份算法；仅构造引用，不证明调用存在或已授权。 */
    public ActionRef actionRef() {
        return new ActionRef(parent.runIdentity().runRef().tenantId(), "tool-action:" + key());
    }
    /** 同一子决策在不同 attempt 中保持逻辑 invocation，不使用供应商未保存的 callId。 */
    public String invocationId() { return "tool-invocation:" + key(); }
    /** 逻辑子身份拥有唯一摘要域，宿主不能复制或推测身份算法。 */
    private String key() { return CanonicalJson.hash(OPERATION_SCHEMA, canonicalValue()); }
    @Override public String toString() { return "ToolChildIdentity[REDACTED]"; }
}
