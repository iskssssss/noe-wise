package com.skillforge.runtime.api;

/** 描述一次带授权身份和 Session 游标的已提交事件订阅。 */
public record EventStreamRequest(
        AccessEnvelope access,
        SessionRef sessionRef,
        long afterSequence,
        int limit) {

    /** 拒绝跨租户订阅和负事件游标，同时允许从序列零开始消费。 */
    public EventStreamRequest {
        access = ApiContracts.requireNonNull(access, "access");
        sessionRef = ApiContracts.requireNonNull(sessionRef, "sessionRef");
        afterSequence = ApiContracts.requireNonNegative(afterSequence, "afterSequence");
        limit = ApiContracts.requirePositive(limit, "limit");
        if (!access.tenantId().equals(sessionRef.tenantId())) {
            throw new IllegalArgumentException("access and sessionRef tenantId must match");
        }
    }
}
