package com.skillforge.core.billing.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Data;

/**
 * 套餐实体，对应数据库表 {@code plans}。
 *
 * <p>套餐是配额与速率限制的唯一事实源（ADR-004），业务代码不得硬编码配额数值，
 * 必须通过本实体读取数据库中的套餐配置。
 */
@Data
@TableName(value = "nw_billing_plans", autoResultMap = true)
public class PlanDO {

    /** 主键雪花 ID。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 套餐编码：free / pro / enterprise。 */
    private String planCode;

    /** 套餐显示名称。 */
    private String displayName;

    /** 每月 API 调用配额，null 表示不限。 */
    private Integer quotaApiCallsPerMonth;

    /** 套餐包含的 Token 配额。 */
    private Integer quotaTokensIncluded;


    /** 最大知识库数，null 表示不限。 */
    @TableField(updateStrategy=FieldStrategy.NEVER)
    private Integer quotaMaxKnowledgeBases;

    /** 最大成员数，null 表示不限。 */
    @TableField(updateStrategy=FieldStrategy.NEVER)
    private Integer quotaMaxMembers;

    /** 最大渠道数，null 表示不限。 */
    @TableField(updateStrategy=FieldStrategy.NEVER)
    private Integer quotaMaxChannels;

    /** 最大智能体数，null 表示不限；catalog 配额服务拥有写权。 */
    @TableField(updateStrategy=FieldStrategy.NEVER)
    private Integer quotaMaxAgents;

    /** 最大 Builder 工作流容器数，null 表示不限。 */
    @TableField(updateStrategy=FieldStrategy.NEVER)
    private Integer quotaMaxWorkflows;

    /** 公共文件占用字节上限，null 表示不限。 */
    @TableField(updateStrategy=FieldStrategy.NEVER)
    private Long quotaFileBytes;

    /** 排队和运行中的管理任务上限，null 表示不限。 */
    @TableField(updateStrategy=FieldStrategy.NEVER)
    private Integer quotaMaxAdminTasks;

    /** 当前直属子机构数上限，null 表示不限，0 禁止新增；catalog 配额服务拥有写权。 */
    @TableField(updateStrategy=FieldStrategy.NEVER)
    private Integer quotaMaxSubInstitutions;

    /** catalog 资源配额 CAS 修订；数据库创建默认 1。 */
    @TableField(updateStrategy=FieldStrategy.NEVER)
    private Long foundationQuotaRevision;

    /** 每分钟速率限制，null 表示按合同处理。 */
    private Integer rateLimitPerMinute;

    /** 内部 SLO 可用性目标。 */
    private BigDecimal sloAvailabilityTarget;

    /** 套餐是否启用。 */
    private Boolean isActive;

    /** 创建时间。 */
    private Instant createdAt;

    /** 更新时间。 */
    private Instant updatedAt;
}
