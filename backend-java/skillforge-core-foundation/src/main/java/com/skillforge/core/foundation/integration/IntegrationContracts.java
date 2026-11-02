package com.skillforge.core.foundation.integration;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.skillforge.core.shared.web.StrictStringDeserializer;
import com.skillforge.core.shared.web.StrictLongDeserializer;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** 平台机构接入和固定机构执行范围的公开传输契约；不承载认证身份或凭据密文。 */
public final class IntegrationContracts {
    private IntegrationContracts() {}
    /** 有权目标先过滤再分页；page 从 1 开始，pageSize 为 1 至 200。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Page<T>(List<T> items, long total, int page, int pageSize) {}
    /** 外部机构的稳定映射；integrationId 在凭据轮换后保持不变。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Institution(String mappingId, String tenantId, String namespace,
            String externalInstitutionRef, String integrationId, String tenantName,
            long revision, Instant createdAt) {}
    /** 开通时明确套餐；可空配额继承该套餐，不存在应用策略或默认应用。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ProvisionInstitution(@JsonDeserialize(using=StrictStringDeserializer.class) String namespace, @JsonDeserialize(using=StrictStringDeserializer.class) String externalInstitutionRef, @JsonDeserialize(using=StrictStringDeserializer.class) String name,
            @JsonDeserialize(using=StrictStringDeserializer.class) String defaultPlanCode, Integer quotaApiCallsPerMonth, Integer quotaMaxKnowledgeBases,
            Integer quotaMaxMembers, Integer quotaMaxChannels, Integer rateLimitPerMinute) {}
    /** 认领已有机构同时需要 namespace 创建权及目标 Tenant 管理权。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AssociateInstitution(@JsonDeserialize(using=StrictStringDeserializer.class) String tenantId, @JsonDeserialize(using=StrictStringDeserializer.class) String namespace, @JsonDeserialize(using=StrictStringDeserializer.class) String externalInstitutionRef) {}
    /** 固定 Tenant/integration 的执行身份安全视图；id 为数据库主键，principalId 为稳定主体身份。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ExecutionCredential(String id, String principalId, String tenantId, String integrationId,
            String apiKeyPrefix, List<String> scopes, String status, long credentialVersion, Instant createdAt) {}
    /** 一次秘密仅原始签发或轮换返回；幂等重读的两个秘密字段为 null。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record IssuedExecutionCredential(ExecutionCredential principal, String apiKey, String signingSecret) {}
    /** 管理入口显式指定固定执行域，scopes 只允许机构执行白名单。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record IssueExecutionCredential(@JsonDeserialize(using=StrictStringDeserializer.class) String tenantId, @JsonDeserialize(using=StrictStringDeserializer.class) String integrationId, @JsonDeserialize(contentUsing=StrictStringDeserializer.class) Set<String> scopes) {}
    /** principalId 使用内部数字主键；expectedRevision 对应当前 credentialVersion。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ExecutionCredentialCommand(@JsonDeserialize(using=StrictStringDeserializer.class) String tenantId, @JsonDeserialize(using=StrictStringDeserializer.class) String integrationId, @JsonDeserialize(using=StrictStringDeserializer.class) String principalId,
            @JsonDeserialize(using=StrictLongDeserializer.class) Long expectedRevision) {}
    /** 业务范围始终固定可信 Tenant/integration；revision 为当前授权状态修订。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record BusinessScope(String businessScopeId, String tenantId, String integrationId,
            String scopeType, String externalRef, String displayName, String status, long revision) {}
    /** 范围声明只能收窄当前认证执行域，不接受正文指定 Tenant 或 integration。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EnsureBusinessScope(@JsonDeserialize(using=StrictStringDeserializer.class) String scopeType, @JsonDeserialize(using=StrictStringDeserializer.class) String externalRef, @JsonDeserialize(using=StrictStringDeserializer.class) String displayName) {}
    /** scopeId 固定原范围；CAS 竞争及撤销终态均拒绝覆写。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record BusinessScopeStatus(@JsonDeserialize(using=StrictStringDeserializer.class) String scopeId, @JsonDeserialize(using=StrictStringDeserializer.class) String status, @JsonDeserialize(using=StrictLongDeserializer.class) Long expectedRevision) {}
}
