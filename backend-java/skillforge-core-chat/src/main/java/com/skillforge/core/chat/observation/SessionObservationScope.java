package com.skillforge.core.chat.observation;

/** 会话观测的显式授权作用域；实例只能由同包授权器创建。 */
public final class SessionObservationScope {

    public enum Kind {
        PLATFORM_ADMIN,
        TENANT
    }

    private final Kind kind;
    private final Long tenantId;
    private final String actorId;

    SessionObservationScope(Kind kind, Long tenantId, String actorId) {
        if (kind == null || actorId == null || actorId.isBlank()) {
            throw new IllegalArgumentException("session observation scope is incomplete");
        }
        if (kind == Kind.PLATFORM_ADMIN && tenantId != null) {
            throw new IllegalArgumentException("platform scope must not contain tenant id");
        }
        if (kind == Kind.TENANT && (tenantId == null || tenantId <= 0)) {
            throw new IllegalArgumentException("tenant scope requires a positive tenant id");
        }
        this.kind = kind;
        this.tenantId = tenantId;
        this.actorId = actorId;
    }

    public Kind kind() {
        return kind;
    }

    public Long tenantId() {
        return tenantId;
    }

    public String actorId() {
        return actorId;
    }

    public void requireTenant(long requestedTenantId) {
        if (requestedTenantId <= 0
                || (kind == Kind.TENANT && tenantId.longValue() != requestedTenantId)) {
            throw new IllegalArgumentException("session observation tenant is outside the authorized scope");
        }
    }
}
