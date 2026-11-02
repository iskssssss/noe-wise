package com.skillforge.core.workflow.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.skillforge.core.shared.config.JsonbTypeHandler;
import java.time.Instant;
import java.util.Map;
import lombok.Data;

/** 不可变安装修订；唯一键是租户、实例和 installationRevision。 */
@Data
@TableName(value = "nw_workflow_installation_revisions", autoResultMap = true)
public class WorkflowInstallationDO {
    private Long tenantId;
    private Long instanceId;
    private Long installationRevision;
    private Long definitionVersionId;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> config;
    private String customPrompt;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> bindings;
    private Instant createdAt;
}
