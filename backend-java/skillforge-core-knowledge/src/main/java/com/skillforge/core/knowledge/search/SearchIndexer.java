package com.skillforge.core.knowledge.search;

import java.util.List;
import java.util.Map;

/**
 * 检索索引器抽象。
 *
 * <p>负责将知识分块写入外部检索引擎并按租户/知识库检索；
 * 当前实现为 Elasticsearch，后续可扩展向量数据库等实现。
 */
public interface SearchIndexer {

    /**
     * 写入分块。
     *
     * @param tenantId 租户 ID
     * @param chunks   分块数据列表
     */
    /** 只有外部引擎确认全部逐项写入才返回true；停用或失败返回false。 */
    boolean indexChunks(String tenantId, List<Map<String, Object>> chunks);

    /**
     * 检索分块。
     *
     * @param tenantId 租户 ID
     * @param kbId     知识库 ID
     * @param query    查询文本
     * @param topK     返回条数上限
     * @param sourceVersion 精确 source_version；为空时明确表示不钉扎版本
     * @return 检索结果
     */
    List<Map<String, Object>> search(
            String tenantId, String kbId, String query, int topK, String sourceVersion);

    /**
     * 按查询向量做语义检索（kNN）。
     *
     * @param tenantId       租户 ID
     * @param kbId           知识库 ID
     * @param queryVectorJson 查询向量 JSON 数组文本（如 "[0.1,0.2,...]"）
     * @param topK           返回条数上限
     * @param sourceVersion  精确 source_version；为空时明确表示不钉扎版本
     * @return 检索结果；不支持或失败时返回空列表
     */
    List<Map<String, Object>> searchKnn(
            String tenantId, String kbId, String queryVectorJson, int topK, String sourceVersion);

    /** 按完整授权 manifest 批量检索；null 非法，空集合返回空，过滤必须在 topK 之前。 */
    List<Map<String, Object>> searchVersions(
            String tenantId, String kbId, String query, int topK, List<String> sourceVersions);

    /** 按完整授权 manifest 批量 kNN 检索；null 非法，空集合返回空。 */
    List<Map<String, Object>> searchKnnVersions(
            String tenantId, String kbId, String queryVectorJson, int topK, List<String> sourceVersions);

    /**
     * 删除同租户中已确认无任何受保护版本/冻结引用的孤立分块全部投影。
     *
     * <p>默认空实现：外部索引为可选项，未接入的索引器无需覆盖。
     *
     * @param tenantId 租户 ID
     * @param chunkIds 分块 ID 列表
     */
    default void deleteChunks(String tenantId, List<String> chunkIds) {
        // 默认不清理外部索引
    }

    /**
     * 删除精确租户/知识库/来源版本的派生投影；调用方须已在所属事务确认来源退役且无引用。
     * true仅表示外部引擎确认该次精确删除，无响应或失败保持可重试意图。
     */
    default boolean deleteSourceVersion(String tenantId,String kbId,String sourceVersion) { return false; }

    /** 当前部署是否实际启用外部索引；停用不会被当作已清理旧索引。 */
    default boolean indexingEnabled() { return true; }
}
