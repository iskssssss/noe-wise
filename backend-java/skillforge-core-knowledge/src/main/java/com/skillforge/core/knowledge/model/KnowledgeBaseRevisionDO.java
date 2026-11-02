package com.skillforge.core.knowledge.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.skillforge.core.shared.config.JsonbTypeHandler;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.Data;

/** Knowledge Base 不可变内容 revision。 */
@Data
@TableName(value = "nw_knowledge_base_revisions", autoResultMap = true)
public class KnowledgeBaseRevisionDO {

    @TableId
    private Long id;
    private Long tenantId;
    /** 此版本冻结的向量空间身份；同维度不同模型仍不可混用。 */
    private Long embeddingModelConfigId;
    private Long embeddingModelRevision;
    private Integer embeddingDimensions;
    private Long kbId;
    private Long revision;
    private String status;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private List<String> sourceVersions;
    /** ready 边界冻结的完整 Knowledge Source 治理策略。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> capabilityPolicy;
    /** 发布时与 Catalog PUBLISHED KNOWLEDGE_SOURCE CapabilityVersion 的不可变 FK。 */
    private Long capabilityVersionId;
    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
    private Instant readyAt;
    private Instant publishedAt;
}
