package com.skillforge.core.foundation.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.skillforge.core.shared.config.JsonbTypeHandler;
import java.time.Instant;
import java.util.Map;
import lombok.Data;

/**
 * 租户实体，对应数据库表 {@code tenants}。
 *
 * <p>租户是多租户隔离与计费的基本单位，状态与欠费/运营状态机对齐：
 * active / warning / suspended / terminated。
 */
@Data
@TableName(value = "nw_tenants", autoResultMap = true)
public class TenantDO {

    /** 主键雪花 ID。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 租户显示名称。 */
    private String name;

    /** 租户唯一标识（URL 友好），全局唯一。 */
    private String slug;

    /** 关联套餐 ID。 */
    private Long planId;

    /** 租户状态：active / warning / suspended / terminated。 */
    private String status;

    /** 部署模式：cloud / hybrid / on_premise。 */
    private String deploymentMode;


    /** 覆盖的每月 API 配额，null 表示继承套餐。 */
    private Integer overrideQuotaApiCallsPerMonth;

    /** 覆盖的最大工作流数，null 表示继承套餐。 */
    @TableField(updateStrategy=FieldStrategy.NEVER)
    private Integer overrideQuotaMaxWorkflows;

    /** 覆盖的最大知识库数，null 表示继承套餐。 */
    @TableField(updateStrategy=FieldStrategy.NEVER)
    private Integer overrideQuotaMaxKnowledgeBases;

    /** 覆盖的最大成员数，null 表示继承套餐。 */
    @TableField(updateStrategy=FieldStrategy.NEVER)
    private Integer overrideQuotaMaxMembers;

    /** 覆盖的最大渠道数，null 表示继承套餐。 */
    @TableField(updateStrategy=FieldStrategy.NEVER)
    private Integer overrideQuotaMaxChannels;

    /** 智能体数量覆盖，null 表示继承当前套餐。 */
    @TableField(updateStrategy=FieldStrategy.NEVER)
    private Integer overrideQuotaMaxAgents;

    /** 公共文件字节覆盖，null 表示继承当前套餐。 */
    @TableField(updateStrategy=FieldStrategy.NEVER)
    private Long overrideQuotaFileBytes;

    /** 并行管理任务数量覆盖，null 表示继承当前套餐。 */
    @TableField(updateStrategy=FieldStrategy.NEVER)
    private Integer overrideQuotaMaxAdminTasks;

    /** catalog 资源配额覆盖 CAS 修订；数据库创建默认 1。 */
    @TableField(updateStrategy=FieldStrategy.NEVER)
    private Long foundationQuotaRevision;

    /** 覆盖的每分钟速率限制，null 表示继承套餐。 */
    private Integer overrideRateLimitPerMinute;

    /** 租户第三方账单 Provider ID；实现由共享 Provider 注册表选择。 */
    private String usageReconciliationProvider;

    /** 租户第三方账单 endpoint。 */
    private String usageReconciliationEndpoint;

    /** 租户第三方账单 API key（AES-GCM 密文）。 */
    private String usageReconciliationApiKeyEncrypted;

    /** 租户第三方账单 API key 请求头。 */
    private String usageReconciliationApiKeyHeader;

    /** 租户第三方账单响应字段 schema（受限 JSON path/别名）。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> usageReconciliationResponseSchema;

    /** 上级租户 ID（分支机构场景），null 表示顶级租户。 */
    @TableField(updateStrategy=FieldStrategy.NEVER)
    private Long parentTenantId;

    /** 当前机构直属关系CAS修订；专用树服务更新，普通租户编辑不覆写。 */
    @TableField(updateStrategy=FieldStrategy.NEVER)
    private Long relationRevision;

    /** 直属下级容量覆盖；NULL继承套餐，0明确禁止。 */
    @TableField(updateStrategy=FieldStrategy.NEVER)
    private Integer overrideQuotaMaxSubInstitutions;

    /** 租户级设置 JSON，通过 Jackson 类型处理器读写。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> settings;

    /** 创建时间。 */
    private Instant createdAt;

    /** 更新时间。 */
    private Instant updatedAt;
}
