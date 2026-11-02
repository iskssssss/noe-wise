package com.skillforge.runtime.api;

import java.time.Instant;
import java.util.List;

/** 表示 Session 的 revision、事件序列游标和活动运行身份快照。 */
public record SessionSnapshot(
        SessionRef sessionRef,
        long sessionRevision,
        long lastSequence,
        List<RunRef> activeRuns,
        Instant updatedAt) {

    /** 防御性复制活动运行，并拒绝无效 revision、重复身份和跨租户引用。 */
    public SessionSnapshot {
        sessionRef = ApiContracts.requireNonNull(sessionRef, "sessionRef");
        sessionRevision = ApiContracts.requirePositive(sessionRevision, "sessionRevision");
        lastSequence = ApiContracts.requireNonNegative(lastSequence, "lastSequence");
        activeRuns = ApiContracts.immutableDistinctList(activeRuns, "activeRuns");
        updatedAt = ApiContracts.requireNonNull(updatedAt, "updatedAt");

        // activeRuns 是非终态运行的身份投影；构造器同时封闭其中可验证的 tenant 边界。
        for (RunRef activeRun : activeRuns) {
            ApiContracts.requireSameTenant(sessionRef, activeRun);
        }
    }
}
