package com.skillforge.core.knowledge.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Data;

/**
 * 文档不可变来源版本。
 *
 * <p>正文与分块集合按精确身份复用，ready后禁止原地更新；
 * 仅在明确保留边界内且没有当前/发布/运行/重建引用时允许锁内回收。
 */
@Data
@TableName("nw_knowledge_document_versions")
public class KnowledgeDocumentVersionDO {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    /** 此版本冻结的向量空间身份；同维度不同模型仍不可混用。 */
    private Long embeddingModelConfigId;
    private Long embeddingModelRevision;
    private Integer embeddingDimensions;
    private Long kbId;
    private Long documentId;
    private String sourceVersion;
    private String title;
    private String sourceType;
    /** 所属文档的不可变原始正文身份；标题修订复用该身份。 */
    private Long contentId;
    /** 不可变分块集合身份；同一正文/向量集合可以被多个元数据版本引用。 */
    private Long chunkSetId;
    /** 读取详情或准备新正文时使用，由知识正文所有者解析，不重复写入版本表。 */
    @TableField(exist = false)
    private String content;
    private String originalFilename;
    private String extension;
    private Long sizeBytes;
    private String parsedBy;
    private String status;
    private Integer chunkCount;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
}
