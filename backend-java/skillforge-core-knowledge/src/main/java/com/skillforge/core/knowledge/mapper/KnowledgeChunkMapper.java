package com.skillforge.core.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skillforge.core.knowledge.model.KnowledgeChunkDO;
import com.skillforge.core.knowledge.model.KnowledgeBaseCount;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * 知识分块 MyBatis-Plus 映射接口。
 *
 * <p>提供基于 pgvector 的余弦距离检索（V6 后 embedding 列无固定维度，
 * 检索按 vector_dims 过滤同维度向量）与 SQL 字面子串检索；均按租户、
 * 活动知识库过滤。
 */
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunkDO> {

    /** 按字面子串检索；null 来源集合表示不限版本，非空集合限制来源范围。 */
    List<KnowledgeChunkDO> searchKeyword(
            @Param("tenantId") String tenantId, @Param("kbId") String kbId,
            @Param("query") String query, @Param("topK") int topK,
            @Param("sourceVersions") List<String> sourceVersions);

    /** 仅在调用方已读取的非空知识库集合内统计未删除文档当前来源版本的分块。 */
    List<KnowledgeBaseCount> countCurrentByBase(@Param("tenantId") long tenantId, @Param("kbIds") List<Long> kbIds);

    /**
     * 执行向量相似度检索（仅活动知识库，同维度向量）。
     *
     * @param tenantId  租户 ID
     * @param kbId      知识库 ID
     * @param queryVec  查询向量文本
     * @param topK      返回条数上限
     * @param threshold 相关度阈值，null 表示不过滤
     * @return 分块内容、相似度得分、文档 ID 与分块 ID
     */
    List<Map<String, Object>> vectorSearch(
            @Param("tenantId") String tenantId,
            @Param("kbId") String kbId,
            @Param("queryVec") String queryVec,
            @Param("topK") int topK,
            @Param("threshold") Double threshold);

    /** 带 source_version 钉扎的向量检索。 */
    List<Map<String, Object>> vectorSearchPinned(
            @Param("tenantId") String tenantId,
            @Param("kbId") String kbId,
            @Param("queryVec") String queryVec,
            @Param("topK") int topK,
            @Param("threshold") Double threshold,
            @Param("sourceVersion") String sourceVersion);

    /** 完整授权 manifest 的一次向量检索，过滤在 topK 之前，空集合 fail closed。 */
    List<Map<String, Object>> vectorSearchVersions(
            @Param("tenantId") String tenantId, @Param("kbId") String kbId,
            @Param("queryVec") String queryVec, @Param("topK") int topK,
            @Param("threshold") Double threshold, @Param("sourceVersions") List<String> sourceVersions);

}
