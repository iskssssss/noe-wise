package com.skillforge.core.foundation.integration;

import lombok.Data;
import java.time.Instant;
import java.util.List;

/** 已提交管理或范围操作的幂等安全回执；不存储凭据明文、哈希或密文。 */
@Data
public class InstitutionOperationRow {
    /** 主体稳定身份，独立于凭据版本。 */ private String actorRef;
    /** 精确操作种类。 */ private String operationKind;
    /** 调用者幂等键。 */ private String idempotencyKey;
    /** 类型化操作参数的规范摘要。 */ private String fingerprint;
    /** 操作所属机构。 */ private Long tenantId;
    /** 安全回执的映射、范围或主体主键。 */ private String resultRef;
    /** 已提交结果修订。 */ private Long resultRevision;
    /** 已提交范围或凭证状态，避免重读新状态冒充原回执。 */ private String resultStatus;
    /** 凭证回执中的固定执行域；其他操作为空。 */ private String integrationId;
    /** 凭证稳定主体引用；其他操作为空。 */ private String principalRef;
    /** 原凭证安全前缀；绝不包含完整API key。 */ private String apiKeyPrefix;
    /** 原凭证执行scope集合；其他操作为空。 */ private List<String> scopes;
    /** 原凭证创建时间；其他操作为空。 */ private Instant principalCreatedAt;
}
