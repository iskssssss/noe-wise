package com.skillforge.runtime.api.port;

import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.SessionRef;

/** 引用一次输入准入时冻结的能力挂载快照。 */
public record CapabilitySnapshotRef(
        SessionRef sessionRef,
        InputId inputId,
        String snapshotId,
        long generation) {
    /** 拒绝缺失快照身份与非正 generation。 */
    public CapabilitySnapshotRef {
        sessionRef = PortContracts.requireNonNull(sessionRef, "sessionRef");
        inputId = PortContracts.requireNonNull(inputId, "inputId");
        snapshotId = PortContracts.requireText(snapshotId, "snapshotId");
        generation = PortContracts.requirePositive(generation, "generation");
    }
}
