package com.skillforge.core.knowledge.mapper;

import com.skillforge.core.knowledge.model.KnowledgeChunkDO;
import com.skillforge.core.knowledge.model.KnowledgeStorageCapacity;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 知识领域不可变正文、向量、精确引用与容量的持久入口；写入加入调用方原子事务。 */
public interface KnowledgeStorageMapper {
    /** 创建租户容量锁；未配置预算时保留 NULL，不擅设运营额度。 */
    int ensureBudget(@Param("tenantId") long tenantId);
    /** 串行化同租户正文新增和回收，使容量受理和释放不会竞态。 */
    Long lockBudget(@Param("tenantId") long tenantId);
    /** 查询仅属于该租户的实际容量与已配置预算。 */
    KnowledgeStorageCapacity capacity(@Param("tenantId") long tenantId);
    /** 修改运营预算，不回收或改写现有正文；已有超额只限制后续增长。 */
    int configureBudget(@Param("tenantId") long tenantId, @Param("bytes") Long bytes, @Param("chunks") Long chunks);
    /** 摘要只缩小查找集合，正文全值相等才允许复用。 */
    Long findDocumentContent(@Param("tenantId") long tenantId, @Param("documentId") long documentId,
            @Param("digest") String digest, @Param("content") String content);
    /** 插入属于该文档的原始正文。 */
    int insertDocumentContent(@Param("tenantId") long tenantId, @Param("documentId") long documentId,
            @Param("id") long id, @Param("digest") String digest, @Param("content") String content);
    /** 精确解析文档正文，拒绝跨文档或租户身份。 */
    String documentContent(@Param("tenantId") long tenantId, @Param("documentId") long documentId, @Param("id") long id);
    /** 摘要命中后同时验证同租户、同文档与完整分块正文。 */
    Long findChunkContent(@Param("tenantId") long tenantId, @Param("documentId") long documentId,
            @Param("digest") String digest, @Param("content") String content);
    /** 新增不可变分块正文，供所有版本引用。 */
    int insertChunkContent(@Param("tenantId") long tenantId, @Param("documentId") long documentId,
            @Param("id") long id, @Param("digest") String digest, @Param("content") String content);
    /** 完整向量、来源正文与模型空间均相同才复用。 */
    Long findEmbedding(@Param("tenantId") long tenantId, @Param("contentId") long contentId,
            @Param("modelId") long modelId, @Param("revision") long revision,
            @Param("dimensions") int dimensions, @Param("vector") String vector);
    /** 向量正文的唯一所属写入入口，重建批次和版本均只引用该身份。 */
    int insertEmbedding(@Param("tenantId") long tenantId, @Param("id") long id, @Param("contentId") long contentId,
            @Param("modelId") long modelId, @Param("revision") long revision,
            @Param("dimensions") int dimensions, @Param("vector") String vector);
    /** 创建不可变分块集合容器。 */
    int insertSet(@Param("tenantId") long tenantId, @Param("kbId") long kbId,
            @Param("documentId") long documentId, @Param("id") long id);
    /** 插入集合成员，仅保存精确正文、向量与顺序引用。 */
    int insertChunk(@Param("chunk") KnowledgeChunkDO chunk);
    /** 原子保存成功批次的顺序向量引用；回执正文不携带大向量。 */
    int insertBatchEmbedding(@Param("tenantId") long tenantId, @Param("batchId") long batchId,
            @Param("position") int position, @Param("embeddingId") long embeddingId);
    /** 恢复成功批次时读取精确向量身份，不重新调用供应商。 */
    List<Long> batchEmbeddings(@Param("tenantId") long tenantId, @Param("batchId") long batchId);
    /** 成功发布后解除该作业的暂存向量引用；目标集合仍保护向量。 */
    int releasePublishedStaging(@Param("tenantId") long tenantId, @Param("jobId") long jobId);
    /** 锁内筛选明确保留边界以前且无任何版本/运行/重建引用的历史来源。 */
    List<Long> recyclableVersions(@Param("tenantId") long tenantId, @Param("kbId") long kbId,
            @Param("before") Instant before, @Param("limit") int limit);
    /** 删除前由数据库守卫再次核对精确引用，保护并发冻结/发布/重建。 */
    int deleteVersion(@Param("tenantId") long tenantId, @Param("id") long id);
    /** 回收没有版本所有者的集合及其成员，批次/Runtime 引用通过外键保护。 */
    int deleteOrphanSets(@Param("tenantId") long tenantId, @Param("kbId") long kbId, @Param("limit") int limit);
    /** 有界回收没有集合或暂存引用的向量。 */
    int deleteOrphanEmbeddings(@Param("tenantId") long tenantId, @Param("kbId") long kbId, @Param("limit") int limit);
    /** 有界回收没有集合或向量引用的分块正文。 */
    int deleteOrphanChunkContents(@Param("tenantId") long tenantId, @Param("kbId") long kbId, @Param("limit") int limit);
    /** 有界回收没有来源版本引用的原始文档正文。 */
    int deleteOrphanDocumentContents(@Param("tenantId") long tenantId, @Param("kbId") long kbId, @Param("limit") int limit);
}
