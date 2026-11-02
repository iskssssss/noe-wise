package com.skillforge.core.workflow.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Data;

/**
 * 工作流实例实体，对应数据库表 {@code workflow_instances}。
 *
 * <p>表示租户安装工作流后的配置快照；仅保存业务参数，禁止存放 API Key 等凭证。
 */
@Data
@TableName(value = "nw_workflow_instances", autoResultMap = true)
public class WorkflowInstanceDO {

    /** 主键雪花 ID。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 实例业务 ID（对外展示）。 */
    private String instanceId;

    /**
     * 安装时冻结的不可变运行定义版本。
     *
     * <p>运行时只从该版本读取图定义。
     */
    private Long definitionVersionId;

    /** 当前不可变安装修订；配置和目标变化独立于 DefinitionVersion。 */
    private Long installationRevision;

    /** 所属租户 ID。 */
    private Long tenantId;

    /** 实例状态：draft / published / disabled / archived。 */
    private String status;

    /** 发布时间。 */
    private Instant publishedAt;

    /** 创建时间。 */
    private Instant createdAt;

    /** 更新时间。 */
    private Instant updatedAt;
}
