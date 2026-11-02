package com.skillforge.core.foundation.integration;

import java.time.Instant;
import lombok.Data;

/** 平台专用仓储中的外部机构映射；外部键在当前部署中唯一，创建内容不可重绑。 */
@Data
public class InstitutionMappingRow {
    /** 映射雪花身份。 */ private Long id;
    /** 映射所属机构。 */ private Long tenantId;
    /** 获准的外部命名空间。 */ private String namespace;
    /** 命名空间中的外部机构稳定键。 */ private String externalInstitutionRef;
    /** 该接入的稳定执行域。 */ private String integrationId;
    /** 只读机构显示名称。 */ private String tenantName;
    /** 首次开通内容摘要，associate 映射为空。 */ private String provisionFingerprint;
    /** 当前映射修订。 */ private Long revision;
    /** 数据库记录时间。 */ private Instant createdAt;
    /** 创建来源的稳定认证主体。 */ private String createdBy;
}
