package com.skillforge.core.knowledge.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skillforge.core.knowledge.model.KnowledgeRebuildJobDO;
/** 重建账本映射器。 */
public interface KnowledgeRebuildJobMapper extends BaseMapper<KnowledgeRebuildJobDO> {
    record Progress(int documentCount, long readyDocuments, long unknownBatches) { }
    Progress progress(@org.apache.ibatis.annotations.Param("tenantId") long tenantId,
            @org.apache.ibatis.annotations.Param("jobId") long jobId);
    KnowledgeRebuildJobDO lock(@org.apache.ibatis.annotations.Param("tenantId") long tenantId,
            @org.apache.ibatis.annotations.Param("jobId") long jobId);
    java.util.List<KnowledgeRebuildJobDO> due(@org.apache.ibatis.annotations.Param("tenantId") long tenantId);
}
