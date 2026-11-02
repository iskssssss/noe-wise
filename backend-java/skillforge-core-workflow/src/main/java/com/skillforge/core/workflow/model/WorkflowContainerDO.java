package com.skillforge.core.workflow.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Data;

/** Builder 工作流容器；保存草稿游标和当前发布版本，不存放可变画布定义。 */
@Data
@TableName("nw_workflow_containers")
public class WorkflowContainerDO {

    @TableId
    private Long id;
    private Long tenantId;
    private String workflowId;
    private String status;
    private Long currentDraftRevision;
    private Long activeDefinitionVersionId;
    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
