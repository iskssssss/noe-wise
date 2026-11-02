package com.skillforge.core.knowledge.model;
import com.baomidou.mybatisplus.annotation.*;
import com.skillforge.core.shared.config.JsonbTypeHandler;
import java.time.Instant;
import java.util.List;
import lombok.Data;
/** 知识库重建的持久事实，运行写权由 job fence 决定。 */
@Data @TableName(value="nw_knowledge_rebuild_items", autoResultMap=true)
public class KnowledgeRebuildItemDO {
@TableId(type=IdType.INPUT) private Long id; private Long tenantId; private Long jobId; private Long documentId;
private String sourceVersion; private String targetSourceVersion; private String status; private Integer chunkCount;
}
