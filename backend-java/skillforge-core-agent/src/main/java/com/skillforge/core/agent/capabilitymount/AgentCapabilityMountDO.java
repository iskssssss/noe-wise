package com.skillforge.core.agent.capabilitymount;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Data;

/** 四类类型化 CapabilityMount 持久记录。 */
@Data
@TableName("nw_agent_capability_mounts")
public class AgentCapabilityMountDO {

    /** Snowflake mount identity。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 租户所有权。 */
    private Long tenantId;

    /** Agent 所有权。 */
    private Long agentId;

    /** 封闭四类 Capability 类型。 */
    private String capabilityType;

    /** Capability definition 外键。 */
    private Long capabilityDefinitionId;

    /** Capability version 外键。 */
    private Long capabilityVersionId;

    /** 装配优先级。 */
    private Integer priority;

    /** ACTIVE/DISABLED。 */
    private String status;

    /** mount CAS revision。 */
    private Long revision;

    /** 创建时间。 */
    private Instant createdAt;

    /** 更新时间。 */
    private Instant updatedAt;

    /** SKILL activation mode。 */
    private String activationMode;

    /** SKILL token budget。 */
    private Long tokenBudget;

    /** TOOL Deployment Snowflake FK。 */
    private Long toolDeploymentId;

    /** TOOL Deployment revision snapshot。 */
    private Long toolDeploymentRevision;

    /** WORKFLOW instance Snowflake FK。 */
    private Long workflowInstanceId;

    /** WORKFLOW instance revision snapshot。 */
    private Long workflowInstanceRevision;
    private Long workflowInstallationRevision;

    /** KNOWLEDGE_SOURCE binding mode。 */
    private String knowledgeBindingMode;

    /** PINNED_REVISION 的 Knowledge revision FK。 */
    private Long knowledgePinnedRevisionId;
}
