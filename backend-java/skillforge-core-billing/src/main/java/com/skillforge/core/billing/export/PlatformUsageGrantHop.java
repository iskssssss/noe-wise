package com.skillforge.core.billing.export;

/** 原受理到末端的有序授权事实，费用确认与修订继承原链，不查询当前授权。 */
public record PlatformUsageGrantHop(int ordinal, Long grantId, long revision, String grantorKind,
        Long grantorTenantId, Long targetTenantId, Long parentGrantId, Long parentGrantRevision,
        Long relationRef, Long relationRevision) { }
