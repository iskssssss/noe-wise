package com.skillforge.core.knowledge.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.skillforge.core.shared.config.JsonbTypeHandler;
import java.time.Instant;
import java.util.Map;
import lombok.Data;

/** 审核提案到机构知识库事实源的幂等投影记录。 */
@Data
@TableName(value = "nw_knowledge_governance_projections", autoResultMap = true)
public class KnowledgeGovernanceProjectionDO {

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long tenantId;
    private String proposalId;
    private String projectionType;
    private String status;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> details;

    private String error;
    private Instant createdAt;
    private Instant updatedAt;
}
