package com.skillforge.core.billing.export;

import lombok.Data;

/** 仅从原机构 Run 绑定或系统宿主输入快照读取；系统 prompt 来源不冒充机构定义/发布 ID。 */
@Data
public class PlatformUsageFrozenSource {
    private String sourceOwnerKind;
    private Long sourceOwnerTenantId;
    private Long agentDefinitionId;
    private String agentDefinitionVersion;
    private Long agentRevisionId;
    private Integer agentRevision;
    private Long deploymentId;
    private Long deploymentRevision;
    private Long grantId;
    private Long grantRevision;
}
