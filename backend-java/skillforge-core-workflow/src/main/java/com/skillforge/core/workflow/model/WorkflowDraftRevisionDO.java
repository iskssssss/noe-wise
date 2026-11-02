package com.skillforge.core.workflow.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.skillforge.core.shared.config.JsonbTypeHandler;
import java.time.Instant;
import java.util.Map;
import lombok.Data;

/** Builder 唯一工作草稿；revision 仅用于并发保存的 CAS 游标。 */
@Data
@TableName(value = "nw_workflow_draft_revisions", autoResultMap = true)
public class WorkflowDraftRevisionDO {

    @TableId
    private Long id;
    private Long containerId;
    private Long tenantId;
    private Long revision;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> definition;
    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
}
