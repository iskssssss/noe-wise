package com.skillforge.core.foundation.integration;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.skillforge.core.shared.web.StrictStringDeserializer;
import com.skillforge.core.shared.web.StrictLongDeserializer;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** 独立管理机器身份的公开请求和安全结果，不包含持久化凭据密文。 */
public final class ManagementContracts {
    /** 协议类型容器不持有运行状态。 */
    private ManagementContracts() { }

    /**
     * 创建固定权限与明确目标的管理机器，不能授予调用人员当前没有的动作。
     * @param name 展示名称，非空且最多 200 个码点
     * @param scopes 精确管理动作非空集合，禁止通配符
     * @param targetTenantIds 明确机构 ID；空集合不授权任何机构
     * @param targetDefinitionIds 明确定义 ID；空集合不授权任何定义
     * @param targetNamespaces 允许创建的外部命名空间；空集合不授权任何命名空间
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(name = "ManagementPrincipalCreateRequest", requiredProperties = {"name", "scopes", "target_tenant_ids", "target_definition_ids", "target_namespaces"})
    public record Create(@JsonDeserialize(using = StrictStringDeserializer.class) String name,
            @JsonDeserialize(contentUsing = StrictStringDeserializer.class) Set<String> scopes,
            @JsonDeserialize(contentUsing = StrictStringDeserializer.class) Set<String> targetTenantIds,
            @JsonDeserialize(contentUsing = StrictStringDeserializer.class) Set<String> targetDefinitionIds,
            @JsonDeserialize(contentUsing = StrictStringDeserializer.class) Set<String> targetNamespaces) { }

    /**
     * 对既有主体进行 CAS 轮换或撤销，不能顺带改动目标或 scope。
     * @param principalId 管理主体十进制 ID
     * @param expectedRevision 预期当前修订，必填且为正数
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(name = "ManagementPrincipalCommandRequest", requiredProperties = {"principal_id", "expected_revision"})
    public record Command(@JsonDeserialize(using = StrictStringDeserializer.class) String principalId,
            @JsonDeserialize(using = StrictLongDeserializer.class) Long expectedRevision) { }

    /**
     * 管理主体安全视图，查询和幂等回执均不返回秘密。
     * @param principalId 稳定十进制主体 ID
     * @param name 展示名称
     * @param status active 或 revoked，撤销为终态
     * @param revision 主体授权修订
     * @param credentialVersion 当前凭据版本
     * @param scopes 明确动作列表
     * @param targetTenantIds 明确机构列表
     * @param targetDefinitionIds 明确定义列表
     * @param targetNamespaces 明确命名空间列表
     * @param createdAt 创建时间
     * @param updatedAt 最近状态或凭据变更时间
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(name = "ManagementPrincipalView")
    public record Principal(String principalId, String name, String status, long revision, long credentialVersion,
            List<String> scopes, List<String> targetTenantIds, List<String> targetDefinitionIds,
            List<String> targetNamespaces, Instant createdAt, Instant updatedAt) {
        /** 保护结果集合，防止调用方改写安全回执。 */
        public Principal {
            scopes = List.copyOf(scopes); targetTenantIds = List.copyOf(targetTenantIds);
            targetDefinitionIds = List.copyOf(targetDefinitionIds); targetNamespaces = List.copyOf(targetNamespaces);
        }
    }

    /**
     * 首次创建或轮换的一次秘密交付；任何重读和撤销结果的秘密均为 null。
     * @param principal 原操作的稳定安全回执
     * @param managementKey 仅本次实际签发的明文管理 Key
     * @param signingSecret 仅本次实际签发的 HMAC secret
     * @param replayed 是否命中原持久回执
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(name = "ManagementPrincipalIssuedResponse")
    public record Issued(Principal principal, String managementKey, String signingSecret, boolean replayed) { }

    /**
     * 稳定创建时间及主体 ID 倒序的普通管理分页。
     * @param items 本页安全主体
     * @param total 当前授权可见总数
     * @param page 从 1 开始的页号
     * @param pageSize 每页条数，最大 200
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(name = "ManagementPrincipalPageResponse")
    public record Page(List<Principal> items, long total, int page, int pageSize) {
        /** 防止结果集合在响应前被修改。 */
        public Page { items = List.copyOf(items); }
    }
}
