package com.skillforge.core.knowledge.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.skillforge.core.knowledge.typehandler.VectorTypeHandler;
import java.time.Instant;
import lombok.Data;

/**
 * 知识版本分块只读投影，对应 {@code knowledge_chunk_values}。
 *
 * <p>正文与向量分别由所属知识内容表持有，此投影解析精确集合成员与source_version。
 * 写入通过知识存储服务创建正文及引用，不使用此view的BaseMapper写方法。
 */
@Data
@TableName(value = "knowledge_chunk_values", autoResultMap = true)
public class KnowledgeChunkDO {

    /** 主键雪花 ID。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 所属租户 ID。 */
    private Long tenantId;

    /** 关联知识库 ID。 */
    private Long kbId;

    /** 关联知识文档 ID。 */
    private Long documentId;

    /** 分块正文所有者身份，复用只发生在同一租户与文档内。 */
    private Long contentId;

    /** 精确模型空间中的不可变向量身份；NULL 表示仅可关键词检索。 */
    private Long embeddingId;

    /** 所属不可变分块集合。 */
    private Long chunkSetId;

    /** 分块正文。 */
    private String content;

    /** 分块在文档内的顺序下标，从 0 开始。 */
    private Integer chunkIndex;

    /** 与文档一致的不可变 source_version，供检索版本钉扎。 */
    private String sourceVersion;

    /** embedding 向量文本，形如 [0.1,0.2,...]，通过 pgvector 类型处理器读写。 */
    @TableField(typeHandler = VectorTypeHandler.class)
    private String embedding;

    /** 创建时间。 */
    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
}
