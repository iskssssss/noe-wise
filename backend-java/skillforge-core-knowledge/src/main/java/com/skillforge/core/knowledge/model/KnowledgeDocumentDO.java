package com.skillforge.core.knowledge.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Data;

/**
 * 知识文档实体，对应数据库表 {@code knowledge_documents}。
 *
 * <p>文档只保存稳定身份与当前不可变来源版本指针。标题、正文和来源元数据归属
 * {@link KnowledgeDocumentVersionDO}，删除使用软删除以保留已发布版本的冻结来源。
 */
@Data
@TableName(value = "nw_knowledge_documents", autoResultMap = true)
public class KnowledgeDocumentDO {

    /** 主键雪花 ID。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 所属租户 ID。 */
    private Long tenantId;

    /** 关联知识库 ID。 */
    private Long kbId;

    /** 当前不可变来源版本指针；通过 expected_source_version CAS 切换。 */
    private String currentSourceVersion;

    /** 管理面软删除时间；历史版本与分块继续保留。 */
    private Instant deletedAt;

    /** 创建时间。 */
    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
}
