package com.skillforge.core.foundation.integration;

import java.time.Instant;
import java.util.List;
import lombok.Data;

/** 管理身份所属 SQL 投影，仅用于该能力持久化，不作为 HTTP 返回值。 */
public final class ManagementRows {
    /** 不创建持久化类型容器实例。 */
    private ManagementRows() { }

    /** 当前管理主体和规范化授权关系的聚合投影；秘密只在验签时受控解密。 */
    @Data
    public static class Principal {
        /** 稳定主体主键。 */ private Long principalId;
        /** 展示名称。 */ private String name;
        /** 当前 active/revoked 状态。 */ private String status;
        /** CAS 授权修订。 */ private Long revision;
        /** 已轮换凭据版本。 */ private Long credentialVersion;
        /** 明文 Key 的 SHA-256，不可用于替代明文验签。 */ private String keyHash;
        /** 密钥的安全展示前缀。 */ private String keyPrefix;
        /** 加密 HMAC secret；撤销时清空。 */ private String signingSecretEncrypted;
        /** 当前精确动作聚合。 */ private List<String> scopes;
        /** 当前固定机构目标聚合。 */ private List<String> targetTenantIds;
        /** 当前固定定义目标聚合。 */ private List<String> targetDefinitionIds;
        /** 当前固定命名空间目标聚合。 */ private List<String> targetNamespaces;
        /** 创建人员的稳定引用。 */ private String createdByRef;
        /** 创建时间。 */ private Instant createdAt;
        /** 最后状态/凭据变更时间。 */ private Instant updatedAt;
    }

    /** 同一主体、操作、对象范围与幂等键下的秘密无关安全回执。 */
    @Data
    public static class Receipt {
        /** 参数的稳定摘要。 */ private String fingerprint;
        /** 原操作安全结果 JSON；事务内完成前为空，未完成事务不得提交。 */ private String resultJson;
    }
}
