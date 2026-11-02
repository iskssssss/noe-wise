package com.skillforge.core.knowledge.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Data;

/**
 * 知识库实体，对应数据库表 {@code knowledge_bases}。
 *
 * <p>知识库是租户内 RAG 检索的顶层容器，保存名称、描述与检索设置，文档和分块
 * 均归属到某个知识库之下。
 */
@Data
@TableName(value = "nw_knowledge_bases", autoResultMap = true)
public class KnowledgeBaseDO {

    /** 主键雪花 ID。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 所属租户 ID。 */
    private Long tenantId;

    /** 知识库显示名称。 */
    private String name;

    /** 知识库描述。 */
    private String description;

    /** 知识库状态：active / archived。 */
    private String status;

    /** 当前已发布 revision 的主键；null 表示尚未发布。 */
    private Long activeRevisionId;
    /** 非空时禁止内容及普通发布写入；重建读取仍使用旧活动版本。 */
    @TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private Long rebuildJobId;
    /** 知识库控制修订；内容指针和配置变更均推进，staging不推进。 */
    private Long controlRevision;

    /** 建库时冻结的 Embedding 平台模型 ID。 */
    private Long embeddingModelConfigId;

    /** 建库时冻结的 Embedding 平台模型版本。 */
    private Long embeddingModelRevision;

    /** 冻结模型的向量维度。 */
    private Integer embeddingDimensions;

    /** 分段长度（字符），null 表示使用默认值。 */
    private Integer chunkSize;

    /** 分段重叠（字符），null 表示使用默认值。 */
    private Integer chunkOverlap;

    /** 索引方式：high_quality 高质量（向量）/ economical 经济（仅关键词）。 */
    private String indexingMode;

    /** 检索模式：semantic 向量 / full_text 全文 / hybrid 混合。 */
    private String retrievalMode;

    /** 检索默认条数。 */
    private Integer topK;

    /** 相关度阈值开关（仅 semantic / hybrid 生效）。 */
    private Boolean scoreThresholdEnabled;

    /** 相关度阈值（0-1）。 */
    private Double scoreThreshold;

    /** 混合检索向量权重（关键词权重 = 1 - 该值）。 */
    private Double vectorWeight;

    /** Rerank 重排开关。 */
    private Boolean rerankEnabled;

    /** 创建时间。 */
    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    /** 更新时间。 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
