package com.skillforge.core.workflow.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Data;

/**
 * 工作流资源资产实体。
 *
 * <p>工作流包资源（workflow.yaml / scripts/*）权威入库，磁盘为缓存；
 * 平台级工作流 tenant_id 为 NULL。
 */
@Data
@TableName("nw_workflow_assets")
public class WorkflowAssetDO {

    /** 主键雪花 ID。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 所属租户 ID（平台级工作流为 NULL）。 */
    private Long tenantId;

    /** 工作流业务 ID。 */
    private String workflowId;

    /** 资源文件名（workflow.yaml / scripts/x.py）。 */
    private String assetName;


    /** 资源字节内容。 */
    private byte[] content;


    /** 创建时间。 */
    private Instant createdAt;

    /** 更新时间。 */
    private Instant updatedAt;
}
