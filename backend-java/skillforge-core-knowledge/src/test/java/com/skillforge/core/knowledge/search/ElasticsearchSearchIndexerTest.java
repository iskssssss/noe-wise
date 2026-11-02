package com.skillforge.core.knowledge.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.shared.config.SkillForgeProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Elasticsearch 检索索引器单元测试（纯函数部分：hits 解析、查询体构造、向量处理）。
 */
class ElasticsearchSearchIndexerTest {

    /** 同一分块在标题修订后复用，精确来源投影仍分别保留。 */
    @Test void projectionIdentityContainsTenantBaseVersionAndChunk() {
        var initial=Map.<String,Object>of("kb_id","10","source_version","v1","chunk_id","31");
        String first=ElasticsearchSearchIndexer.projectionId("7",initial);
        assertEquals(first,ElasticsearchSearchIndexer.projectionId("7",initial));
        org.junit.jupiter.api.Assertions.assertNotEquals(first,ElasticsearchSearchIndexer.projectionId("8",initial));
        org.junit.jupiter.api.Assertions.assertNotEquals(first,ElasticsearchSearchIndexer.projectionId("7",Map.of("kb_id","11","source_version","v1","chunk_id","31")));
        org.junit.jupiter.api.Assertions.assertNotEquals(first,ElasticsearchSearchIndexer.projectionId("7",Map.of("kb_id","10","source_version","v2","chunk_id","31")));
        assertEquals("31",initial.get("chunk_id"));
    }

    /** 单版本退役只按精确三元组删除，不用共享chunk ID扩大删除。 */
    @Test void sourceDeletionTargetsExactTenantBaseAndVersion() {
        assertEquals(Map.of("query",Map.of("bool",Map.of("filter",List.of(
                Map.of("term",Map.of("tenant_id","7")),Map.of("term",Map.of("kb_id","10")),
                Map.of("term",Map.of("source_version","v1")))))),ElasticsearchSearchIndexer.sourceVersionDeleteBody("7","10","v1"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,()->ElasticsearchSearchIndexer.sourceVersionDeleteBody("7","10",""));
    }

    /** 被测索引器。 */
    private ElasticsearchSearchIndexer indexer;

    /**
     * 构造被测对象（不触发网络调用）。
     */
    @BeforeEach
    void setUp() {
        indexer = new ElasticsearchSearchIndexer(
                new ObjectMapper(), new SkillForgeProperties(), null);
    }

    /**
     * hits 解析：_source 与 _score 映射为统一检索项。
     */
    @Test
    void parsesHits() {
        String raw = "{\"hits\":{\"hits\":["
                + "{\"_source\":{\"content\":\"文本A\",\"chunk_id\":\"1\",\"document_id\":\"10\"},\"_score\":0.9},"
                + "{\"_source\":{\"content\":\"文本B\",\"chunk_id\":\"2\",\"document_id\":\"10\"},\"_score\":0.7}"
                + "]}}";
        List<Map<String, Object>> items = indexer.parseHits(raw);
        assertEquals(2, items.size());
        assertEquals("文本A", items.get(0).get("content"));
        assertEquals("1", String.valueOf(items.get(0).get("chunk_id")));
        assertEquals(0.9, ((Number) items.get(0).get("score")).doubleValue());
    }

    /**
     * hits 解析：空与非法响应返回空列表。
     */
    @Test
    void parsesEmptyOrInvalidResponse() {
        assertTrue(indexer.parseHits(null).isEmpty());
        assertTrue(indexer.parseHits("").isEmpty());
        assertTrue(indexer.parseHits("not json").isEmpty());
        assertTrue(indexer.parseHits("{\"hits\":{}}").isEmpty());
    }

    /**
     * 维度字段选择：预设维度命中，其他返回 null。
     */
    @Test
    void selectsVectorFieldByDims() {
        assertEquals("embedding_1536", ElasticsearchSearchIndexer.vectorFieldName(1536));
        assertEquals("embedding_1024", ElasticsearchSearchIndexer.vectorFieldName(1024));
        assertEquals("embedding_768", ElasticsearchSearchIndexer.vectorFieldName(768));
        assertNull(ElasticsearchSearchIndexer.vectorFieldName(0));
        assertNull(ElasticsearchSearchIndexer.vectorFieldName(512));
        assertNull(ElasticsearchSearchIndexer.vectorFieldName(2048));
    }

    /**
     * kNN 查询体：字段选择、租户/知识库过滤、候选数。
     */
    @Test
    void buildsKnnBody() {
        double[] vector = new double[1536];
        vector[0] = 0.5;
        Map<String, Object> body = ElasticsearchSearchIndexer.buildKnnBody(
                "42", "7", "embedding_1536", vector, 5, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> knn = (Map<String, Object>) body.get("knn");
        assertEquals("embedding_1536", knn.get("field"));
        assertEquals(5, knn.get("k"));
        assertEquals(20, knn.get("num_candidates"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> filter = (List<Map<String, Object>>) knn.get("filter");
        assertEquals(2, filter.size());
        assertEquals(5, body.get("size"));
    }

    @Test
    void buildsPinnedKnnBodyWithSourceVersionFilter() {
        Map<String, Object> body = ElasticsearchSearchIndexer.buildKnnBody(
                "42", "7", "embedding_1536", new double[1536], 5, "kbv-1");
        @SuppressWarnings("unchecked")
        Map<String, Object> knn = (Map<String, Object>) body.get("knn");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> filter = (List<Map<String, Object>>) knn.get("filter");
        assertEquals(3, filter.size());
        assertTrue(filter.toString().contains("kbv-1"));
    }

    /**
     * 向量 JSON 解析：正常数组、非法输入与空输入。
     */
    @Test
    void parsesVectorJson() {
        double[] vector = indexer.parseVector("[0.1,0.2,0.3]");
        assertEquals(3, vector.length);
        assertEquals(0.1, vector[0]);
        assertNull(indexer.parseVector(null));
        assertNull(indexer.parseVector(""));
        assertNull(indexer.parseVector("not-a-vector"));
        assertNull(indexer.parseVector("[]"));
    }

    /**
     * 索引 mapping：含 cjk 分词与全部预设 dense_vector 字段。
     */
    @Test
    void buildsIndexMapping() {
        Map<String, Object> mapping = ElasticsearchSearchIndexer.indexMapping();
        @SuppressWarnings("unchecked")
        Map<String, Object> mappings = (Map<String, Object>) mapping.get("mappings");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) mappings.get("properties");
        assertTrue(((Map<?, ?>) properties.get("content")).get("analyzer").equals("cjk"));
        assertTrue(properties.containsKey("embedding_1536"));
        assertTrue(properties.containsKey("embedding_1024"));
        assertTrue(properties.containsKey("embedding_768"));
        assertTrue(properties.containsKey("tenant_id"));
        assertTrue(properties.containsKey("chunk_id"));
        assertTrue(properties.containsKey("source_version"));
    }
}
