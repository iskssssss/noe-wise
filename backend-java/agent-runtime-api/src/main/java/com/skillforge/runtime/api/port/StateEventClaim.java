package com.skillforge.runtime.api.port;

/** 表示一次携带当前 fencing lease 的领域事件领取。 */
public record StateEventClaim(StateEventPublication publication, LeaseFence fence) {
    /** 拒绝缺失或跨租户 publication/lease。 */
    public StateEventClaim {
        publication = PortContracts.requireNonNull(publication, "publication");
        fence = PortContracts.requireNonNull(fence, "fence");
        PortContracts.requireSameTenant(publication.tenantId(), fence.tenantId(), "fence.tenantId");
    }
}
