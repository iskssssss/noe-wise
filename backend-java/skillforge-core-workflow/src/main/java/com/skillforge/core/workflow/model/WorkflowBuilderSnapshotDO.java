package com.skillforge.core.workflow.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.skillforge.core.shared.config.JsonbTypeHandler;
import java.time.Instant;
import java.util.Map;
import lombok.Data;

/** 一个 draft revision 的 Builder 布局和视口快照；它不是运行定义的一部分。 */
@Data
@TableName(value = "nw_workflow_builder_snapshots", autoResultMap = true)
public class WorkflowBuilderSnapshotDO {

    @TableId
    private Long id;
    private Long tenantId;
    private Long containerId;
    private Long draftRevision;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> layout;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> viewport;
    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
