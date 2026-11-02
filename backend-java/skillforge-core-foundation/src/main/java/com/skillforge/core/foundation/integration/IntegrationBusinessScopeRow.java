package com.skillforge.core.foundation.integration;

import lombok.Data;

/** 机构执行域内显式声明的范围，授权状态用 revision CAS；外部键永不扩大 Tenant/integration。 */
@Data
public class IntegrationBusinessScopeRow {
    /** 范围雪花身份。 */ private String scopeId;
    /** 所属机构。 */ private Long tenantId;
    /** 创建时固定的执行域。 */ private String integrationId;
    /** 例如 store 的范围类型。 */ private String scopeType;
    /** 执行域内的外部范围键。 */ private String externalRef;
    /** 仅展示的名称。 */ private String displayName;
    /** active/suspended/revoked，revoked 为终态。 */ private String status;
    /** 当前状态修订，从 1 开始。 */ private Long revision;
    /** 创建或最后写入的可信主体。 */ private String actorRef;
}
