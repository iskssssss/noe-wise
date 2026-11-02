package com.skillforge.runtime.api.port;

import com.skillforge.runtime.api.ResultRef;

/** 表示资源身份或幂等内容冲突及其安全审计引用。 */
public record PortConflict(String resourceKind, String resourceId, ResultRef auditRef) {
    /** 拒绝空白资源身份或缺失审计引用。 */
    public PortConflict {
        resourceKind = PortContracts.requireText(resourceKind, "resourceKind");
        resourceId = PortContracts.requireText(resourceId, "resourceId");
        auditRef = PortContracts.requireNonNull(auditRef, "auditRef");
    }
}
