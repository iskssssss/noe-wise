package com.skillforge.core.foundation.workflowtest;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.skillforge.core.shared.config.JsonbTypeHandler;
import java.time.Instant;
import java.util.Map;
import lombok.Data;

/** 后台拥有的隔离测试资源与原请求回执；状态不代替唯一 Runtime Run。 */
@Data
@TableName(value="nw_workflow_test_sessions",autoResultMap=true)
public class WorkflowTestSessionDO {
    @TableId private Long id;
    private Long tenantId;
    private Long initiatorMemberId;
    private String requestId;
    private String requestFingerprint;
    private String sourceKind;
    private Long sourceContainerId;
    private String sourceWorkflowId;
    private Long sourceDraftRevision;
    private Long sourceDefinitionVersionId;
    private String sourceHash;
    private Long containerId;
    private Long definitionVersionId;
    private Long capabilityDefinitionId;
    private Long capabilityVersionId;
    private Long instanceId;
    private Long installationRevision;
    private Long agentId;
    private Long endUserId;
    private String sessionId;
    private String initialInputId;
    private String initialMessage;
    @TableField(typeHandler=JsonbTypeHandler.class) private Map<String,Object> parameters;
    private Integer maxBusinessModelCalls;
    private Integer maxToolCalls;
    private Integer maxKnowledgeCalls;
    private Long maxTotalTokens;
    private Long maxWallTimeSeconds;
    private String status;
    private Instant expiresAt;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant closedAt;
}
