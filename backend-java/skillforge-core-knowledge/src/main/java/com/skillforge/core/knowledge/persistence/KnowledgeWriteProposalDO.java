package com.skillforge.core.knowledge.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.skillforge.core.shared.config.JsonbTypeHandler;
import java.time.Instant;
import java.util.Map;
import lombok.Data;

/** Knowledge Runtime 写回提案持久化实体。 */
@Data
@TableName(value = "nw_knowledge_write_proposals", autoResultMap = true)
public class KnowledgeWriteProposalDO {

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long tenantId;
    private String proposalId;
    private String target;
    private String sourceEventId;
    private String bundleId;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> payload;

    private String status;
    private String projectionStatus;
    private Long projectionRef;
    private String projectionError;
    private String reviewerId;
    private String reviewNote;
    private Instant createdAt;
    private Instant reviewedAt;
    private Instant projectedAt;
}
