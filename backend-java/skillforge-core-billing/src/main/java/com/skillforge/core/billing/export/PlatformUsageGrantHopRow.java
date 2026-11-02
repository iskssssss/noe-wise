package com.skillforge.core.billing.export;

import lombok.Data;

/** 单页批量读取的授权子事实；usageId 只负责关联原用量正文。 */
@Data
public class PlatformUsageGrantHopRow {
    private Long usageId;
    private Integer ordinal;
    private Long grantId;
    private Long revision;
    private String grantorKind;
    private Long grantorTenantId;
    private Long targetTenantId;
    private Long parentGrantId;
    private Long parentGrantRevision;
    private Long relationRef;
    private Long relationRevision;

    /** 丢弃查询关联键，形成不可变授权链一跳。 */
    public PlatformUsageGrantHop toHop() {
        return new PlatformUsageGrantHop(ordinal,grantId,revision,grantorKind,grantorTenantId,
                targetTenantId,parentGrantId,parentGrantRevision,relationRef,relationRevision);
    }
}
