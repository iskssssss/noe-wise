package com.skillforge.core.foundation.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.skillforge.core.shared.config.JsonbTypeHandler;
import java.time.Instant;
import java.util.List;
import lombok.Data;

/** Tenant Open Platform 的服务身份；仅保存 API key 哈希和签名密文。 */
@Data
@TableName(value = "nw_service_principals", autoResultMap = true)
public class ServicePrincipalDO {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private String principalId;
    private String integrationId;
    private String apiKeyHash;
    private String apiKeyPrefix;
    private String signingSecretEncrypted;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private List<String> scopes;
    private String status;
    private Long credentialVersion;
    private Instant createdAt;
    private Instant updatedAt;
}
