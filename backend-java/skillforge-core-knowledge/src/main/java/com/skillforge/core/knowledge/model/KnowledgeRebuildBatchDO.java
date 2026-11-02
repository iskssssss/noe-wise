package com.skillforge.core.knowledge.model;
import com.baomidou.mybatisplus.annotation.*;
import com.skillforge.core.shared.config.JsonbTypeHandler;
import java.time.Instant;
import java.util.List;
import lombok.Data;
/** 知识库重建的持久事实，运行写权由 job fence 决定。 */
@Data @TableName(value="nw_knowledge_rebuild_batches", autoResultMap=true)
public class KnowledgeRebuildBatchDO {
@TableId(type=IdType.INPUT) private Long id; private Long tenantId; private Long jobId; private Long itemId; private Integer batchIndex;
private String status; private String invocationId; private String errorCode;
/** 向量正文由 knowledge_chunk_embeddings 持有；此字段仅供请求期校验。 */
@TableField(exist = false) private List<String> vectors;
@TableField(typeHandler = JsonbTypeHandler.class) private List<java.util.Map<String,Object>> receiptHistory;
}
