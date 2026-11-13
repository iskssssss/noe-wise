package com.skillforge.core.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skillforge.core.knowledge.model.KnowledgeDocumentVersionDO;
import com.skillforge.core.knowledge.model.KnowledgeDocumentSummary;
import java.util.List;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Param;

/** 知识文档不可变来源版本映射器。 */
public interface KnowledgeDocumentVersionMapper extends BaseMapper<KnowledgeDocumentVersionDO> {
    /** 当前未删除容器中的精确来源；不改变历史版本或发布清单。 */
    List<String> liveSourceVersions(@Param("tenantId") long tenantId, @Param("kbId") long kbId,
            @Param("sourceVersions") List<String> sourceVersions);

    /** 分页读取未删除文档的当前版本。 */
    Page<KnowledgeDocumentSummary> selectCurrentPage(
            Page<KnowledgeDocumentSummary> page,
            @Param("tenantId") long tenantId,
            @Param("kbId") long kbId,
            @Param("status") String status,
            @Param("titleQuery") String titleQuery);

    /** 按文档创建顺序读取最多 2000 个当前就绪来源版本。 */
    List<String> selectLatestReadySources(@Param("tenantId") long tenantId, @Param("kbId") long kbId);

    /** 返回 manifest 中完整且 ready 的版本数。 */
    int countCompleteReadyVersions(
            @Param("tenantId") long tenantId,
            @Param("kbId") long kbId,
            @Param("sourceVersions") List<String> sourceVersions);
    /** 发布前再验完整向量集合，ready标签不能替代实际块数/维度。 */
    int countCompleteVectorVersion(@Param("tenantId") long tenantId,@Param("kbId") long kbId,
            @Param("sourceVersion") String sourceVersion,@Param("dimensions") int dimensions);
}
