package com.skillforge.core.workflow.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.skillforge.core.shared.config.JsonbTypeHandler;
import java.time.Instant;
import java.util.Map;
import lombok.Data;

/** 发布后不可变的 Workflow DefinitionVersion。 */
@Data
@TableName(value = "nw_workflow_definition_versions", autoResultMap = true)
public class WorkflowDefinitionVersionDO {

    @TableId
    private Long id;
    private Long containerId;
    private Long tenantId;
    private Long definitionVersion;
    private Long sourceDraftId;
    /** 与已发布 WORKFLOW CapabilityVersion 的不可变 FK 关联。 */
    private Long capabilityVersionId;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> definition;
    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
}
