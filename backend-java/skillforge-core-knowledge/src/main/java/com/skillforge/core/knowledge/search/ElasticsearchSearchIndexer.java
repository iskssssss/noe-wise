package com.skillforge.core.knowledge.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.shared.logging.SafeFailureDiagnostics;
import com.skillforge.core.shared.config.SkillForgeProperties;
import com.skillforge.core.shared.resilience.Resilience;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Elasticsearch 检索索引器。
 *
 * <p>通过 REST 接口将知识分块写入 bulk API，并执行带租户/知识库过滤的检索：
 * BM25 全文腿（match content）+ kNN 语义腿（dense_vector）；未启用或请求失败时
 * 返回空结果，由上层回退到 pgvector/关键词。
 *
 * <p>维度策略：ES dense_vector 需固定维度，预声明 1536/1024/768 三个字段
 * （与 V6 解除 pgvector 维度限制后的多模型共存对齐），写入/查询按向量长度选字段；
 * 非预设维度返回空结果，由上层回退 pgvector。
 */
@Component
public class ElasticsearchSearchIndexer implements SearchIndexer {

    /** 日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(ElasticsearchSearchIndexer.class);

    /** 预设向量维度字段：维度 → dense_vector 字段名。 */
    private static final Map<Integer, String> VECTOR_FIELDS = Map.of(
            1536, "embedding_1536",
            1024, "embedding_1024",
            768, "embedding_768");

    /** Jackson 对象映射器。 */
    private final ObjectMapper objectMapper;

    /** 应用配置。 */
    private final SkillForgeProperties properties;

    /** 外部调用弹性组件。 */
    private final Resilience resilience;

    /**
     * 构造 ES 索引器。
     *
     * @param objectMapper Jackson 对象映射器
     * @param properties   应用配置
     * @param resilience   外部调用弹性组件
     */
    public ElasticsearchSearchIndexer(
            ObjectMapper objectMapper,
            SkillForgeProperties properties,
            Resilience resilience) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.resilience = resilience;
    }

    /**
     * 启动时确保索引存在（幂等）：content 用 cjk 分词（官方内置，中文检索
     * 优于 standard 的单字切分），元数据列 keyword，向量列按预设维度声明。
     */
    @PostConstruct
    void ensureIndex() {
        if (!properties.isElasticsearchEnabled()) {
            return;
        }
        try {
            resilience.call("elasticsearch:ensure-index", () -> {
                client().put()
                        .uri(properties.getElasticsearchUri() + "/" + properties.getElasticsearchIndex())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(indexMapping())
                        .retrieve()
                        .toBodilessEntity();
                return null;
            });
        } catch (Exception e) {
            // 索引已存在（400 resource_already_exists）或 ES 未就绪：不阻断启动，
            // 后续写入失败也仅 warn（ES 为可选索引）
            log.warn("ES 索引确保失败（已存在或未就绪时忽略） reason=ensure_index_failed diagnostic={}", SafeFailureDiagnostics.of(e));
        }
    }

    /**
     * 按租户/知识库/精确来源版本/分块组合身份写入，标题复用集合不会覆盖旧版投影。
     *
     * @param tenantId 租户 ID
     * @param chunks   分块数据列表
     */
    @Override
    public boolean indexChunks(String tenantId, List<Map<String, Object>> chunks) {
        if (!properties.isElasticsearchEnabled() || chunks == null || chunks.isEmpty()) {
            return false;
        }
        try {
            StringBuilder ndjson = new StringBuilder();
            for (Map<String, Object> chunk : chunks) {
                ndjson.append(objectMapper.writeValueAsString(
                                Map.of("index", Map.of(
                                        "_index", properties.getElasticsearchIndex(),
                                        "_id", projectionId(tenantId,chunk)))))
                        .append('\n');
                Map<String, Object> doc = new LinkedHashMap<>(chunk);
                doc.put("tenant_id", tenantId);
                // embedding 字符串（"[0.1,...]"）→ 按维度写入对应 dense_vector 字段
                Object embedding = doc.remove("embedding");
                if (embedding != null) {
                    double[] vector = parseVector(String.valueOf(embedding));
                    String field = vectorFieldName(vector == null ? 0 : vector.length);
                    if (field == null) throw new IllegalArgumentException("es_embedding_dimension_unsupported");
                    doc.put(field, vector);
                }
                ndjson.append(objectMapper.writeValueAsString(doc)).append('\n');
            }
            resilience.call("elasticsearch:bulk", () -> {
                BulkResponse response = client().post()
                        .uri(properties.getElasticsearchUri() + "/" + properties.getElasticsearchIndex() + "/_bulk")
                        .contentType(MediaType.parseMediaType("application/x-ndjson;charset=UTF-8"))
                        .body(ndjson.toString())
                        .retrieve()
                        .body(BulkResponse.class);
                inspectBulk(response, chunks.size());
                return null;
            });
            return true;
        } catch (Exception e) {
            // 索引失败不阻断数据库主存储（记录原因便于排查，不阻断主链路）
            log.warn("ES 索引写入失败，不阻断数据库主存储 reason=index_failed diagnostic={}", SafeFailureDiagnostics.of(e));
            return false;
        }
    }

    /**
     * BM25 全文腿：按 content 匹配检索。
     *
     * @param tenantId 租户 ID
     * @param kbId     知识库 ID
     * @param query    查询文本
     * @param topK     返回条数上限
     * @param sourceVersion 精确 source_version；为空时明确表示不钉扎版本
     * @return 检索结果
     */
    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> search(
            String tenantId, String kbId, String query, int topK, String sourceVersion) {
        return searchFiltered(tenantId, kbId, query, topK,
                sourceVersion == null ? null : List.of(sourceVersion));
    }

    @Override
    public List<Map<String, Object>> searchVersions(String tenantId, String kbId, String query,
            int topK, List<String> sourceVersions) {
        if (!validVersions(sourceVersions)) return List.of();
        return searchFiltered(tenantId, kbId, query, topK, List.copyOf(sourceVersions));
    }

    private List<Map<String, Object>> searchFiltered(String tenantId, String kbId, String query,
            int topK, List<String> sourceVersions) {
        if (!properties.isElasticsearchEnabled()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> filters = new ArrayList<>();
            filters.add(Map.of("term", Map.of("tenant_id", tenantId)));
            filters.add(Map.of("term", Map.of("kb_id", kbId)));
            if (sourceVersions != null) {
                filters.add(Map.of("terms", Map.of("source_version", sourceVersions)));
            }
            Map<String, Object> body = Map.of(
                    "query", Map.of(
                            "bool", Map.of(
                                    "filter", filters,
                                    "must", Map.of("match", Map.of("content", query)))),
                    "size", topK,
                    "_source", List.of("content", "chunk_id", "document_id", "source_version"));
            return resilience.call("elasticsearch:search", () -> {
                String raw = client().post()
                        .uri(properties.getElasticsearchUri() + "/" + properties.getElasticsearchIndex() + "/_search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class);
                return parseHits(raw);
            });
        } catch (Exception e) {
            log.warn("ES 检索请求失败，返回空结果 reason=search_failed diagnostic={}", SafeFailureDiagnostics.of(e));
            return List.of();
        }
    }

    /**
     * kNN 语义腿：按查询向量做余弦相似度检索。
     *
     * @param tenantId       租户 ID
     * @param kbId           知识库 ID
     * @param queryVectorJson 查询向量 JSON 数组文本
     * @param topK           返回条数上限
     * @param sourceVersion  精确 source_version；为空时明确表示不钉扎版本
     * @return 检索结果；非预设维度或解析失败返回空列表（上层回退 pgvector）
     */
    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> searchKnn(
            String tenantId, String kbId, String queryVectorJson, int topK, String sourceVersion) {
        return searchKnnFiltered(tenantId, kbId, queryVectorJson, topK,
                sourceVersion == null ? null : List.of(sourceVersion));
    }

    @Override
    public List<Map<String, Object>> searchKnnVersions(String tenantId, String kbId, String queryVectorJson,
            int topK, List<String> sourceVersions) {
        if (!validVersions(sourceVersions)) return List.of();
        return searchKnnFiltered(tenantId, kbId, queryVectorJson, topK, List.copyOf(sourceVersions));
    }

    private List<Map<String, Object>> searchKnnFiltered(String tenantId, String kbId, String queryVectorJson,
            int topK, List<String> sourceVersions) {
        if (!properties.isElasticsearchEnabled()) {
            return List.of();
        }
        double[] vector = parseVector(queryVectorJson);
        String field = vectorFieldName(vector == null ? 0 : vector.length);
        if (field == null) {
            return List.of();
        }
        try {
            Map<String, Object> body = buildKnnVersionsBody(tenantId, kbId, field, vector, topK, sourceVersions);
            return resilience.call("elasticsearch:search", () -> {
                String raw = client().post()
                        .uri(properties.getElasticsearchUri() + "/" + properties.getElasticsearchIndex() + "/_search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class);
                return parseHits(raw);
            });
        } catch (Exception e) {
            log.warn("ES 向量检索请求失败，返回空结果 reason=knn_failed diagnostic={}", SafeFailureDiagnostics.of(e));
            return List.of();
        }
    }

    /**
     * 构造 kNN 查询体：filter 置于 knn 内部保证租户/知识库隔离。
     *
     * @param tenantId 租户 ID
     * @param kbId     知识库 ID
     * @param field    dense_vector 字段名
     * @param vector   查询向量
     * @param topK     返回条数上限
     * @param sourceVersion 精确 source_version；为空时明确表示不钉扎版本
     * @return 查询体
     */
    static Map<String, Object> buildKnnBody(
            String tenantId, String kbId, String field, double[] vector, int topK,
            String sourceVersion) {
        return buildKnnVersionsBody(tenantId, kbId, field, vector, topK,
                sourceVersion == null ? null : List.of(sourceVersion));
    }

    private static Map<String, Object> buildKnnVersionsBody(String tenantId, String kbId,
            String field, double[] vector, int topK, List<String> sourceVersions) {
        List<Map<String, Object>> filters = new ArrayList<>();
        filters.add(Map.of("term", Map.of("tenant_id", tenantId)));
        filters.add(Map.of("term", Map.of("kb_id", kbId)));
        if (sourceVersions != null) {
            filters.add(Map.of("terms", Map.of("source_version", sourceVersions)));
        }
        return Map.of(
                "knn", Map.of(
                        "field", field,
                        "query_vector", vector,
                        "k", topK,
                        "num_candidates", Math.max(topK * 4, 16),
                        "filter", filters),
                "size", topK,
                "_source", List.of("content", "chunk_id", "document_id", "source_version"));
    }

    /**
     * 解析向量 JSON 数组文本。
     *
     * @param json 向量 JSON 文本，如 "[0.1,0.2,...]"
     * @return 向量；解析失败或空输入返回 null
     */
    double[] parseVector(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            double[] vector = objectMapper.readValue(json, double[].class);
            return vector.length == 0 ? null : vector;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 向量维度 → dense_vector 字段名；非预设维度返回 null。
     *
     * @param dims 向量维度
     * @return 字段名；无匹配返回 null
     */
    static String vectorFieldName(int dims) {
        return VECTOR_FIELDS.get(dims);
    }

    /**
     * 索引 mapping 定义。
     *
     * @return mapping body
     */
    static Map<String, Object> indexMapping() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("content", Map.of("type", "text", "analyzer", "cjk"));
        properties.put("tenant_id", Map.of("type", "keyword"));
        properties.put("kb_id", Map.of("type", "keyword"));
        properties.put("document_id", Map.of("type", "keyword"));
        properties.put("chunk_id", Map.of("type", "keyword"));
        properties.put("chunk_index", Map.of("type", "keyword"));
        properties.put("source_version", Map.of("type", "keyword"));
        VECTOR_FIELDS.forEach((dims, field) -> properties.put(field, Map.of(
                "type", "dense_vector",
                "dims", dims,
                "index", true,
                "similarity", "cosine")));
        return Map.of("mappings", Map.of("properties", properties));
    }

    /**
     * 解析 ES hits 为统一检索项。
     *
     * @param raw ES 响应
     * @return 检索结果
     */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> parseHits(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> response;
        try {
            response = objectMapper.readValue(raw, Map.class);
        } catch (Exception e) {
            return items;
        }
        Map<String, Object> hits = (Map<String, Object>) response.get("hits");
        if (hits == null || !(hits.get("hits") instanceof List<?> list)) {
            return items;
        }
        for (Object hit : list) {
            Map<String, Object> hitMap = (Map<String, Object>) hit;
            Map<String, Object> source = (Map<String, Object>) hitMap.get("_source");
            if (source == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("content", source.get("content"));
            item.put("score", hitMap.get("_score"));
            item.put("chunk_id", source.get("chunk_id"));
            item.put("document_id", source.get("document_id"));
            item.put("source_version", source.get("source_version"));
            items.add(item);
        }
        return items;
    }

    /**
     * 按分块 ID 批量删除索引。
     *
     * @param tenantId 租户 ID
     * @param chunkIds 分块 ID 列表
     */
    @Override
    public void deleteChunks(String tenantId, List<String> chunkIds) {
        if (!properties.isElasticsearchEnabled() || chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        try {
            deleteProjection(Map.of("query",Map.of("bool",Map.of("filter",List.of(
                    Map.of("term",Map.of("tenant_id",tenantId)),Map.of("terms",Map.of("chunk_id",chunkIds)))))));
        } catch (Exception e) {
            // 删除失败不阻断数据库主存储（记录原因便于排查，不阻断主链路）
            log.warn("ES 索引删除失败，不阻断数据库主存储 reason=delete_failed diagnostic={}", SafeFailureDiagnostics.of(e));
        }
    }

    /** {@inheritDoc} */
    @Override public boolean indexingEnabled() { return properties.isElasticsearchEnabled(); }

    /** {@inheritDoc} */
    @Override public boolean deleteSourceVersion(String tenantId,String kbId,String sourceVersion) {
        if(!indexingEnabled())return false;
        try { return deleteProjection(sourceVersionDeleteBody(tenantId,kbId,sourceVersion)); }
        catch(RuntimeException failure) {
            log.warn("ES精确来源投影删除未确认 reason=source_projection_delete_unconfirmed diagnostic={}",SafeFailureDiagnostics.of(failure));
            return false;
        }
    }

    /** 完整组合身份先按长度编码再摘要，避免分隔符歧义和ES文档ID字节上限。 */
    static String projectionId(String tenantId,Map<String,Object> chunk) {
        StringBuilder key=new StringBuilder();
        for(Object value:List.of(tenantId,chunk.get("kb_id"),chunk.get("source_version"),chunk.get("chunk_id"))) {
            String part=value.toString();if(part.isBlank())throw new IllegalArgumentException("knowledge_projection_identity_missing");
            key.append(part.length()).append(':').append(part);
        }
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(key.toString().getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 unavailable",unavailable); }
    }

    /** 删除只命中精确来源三元组；同一chunk被其他元数据版本复用时不受影响。 */
    static Map<String,Object> sourceVersionDeleteBody(String tenantId,String kbId,String sourceVersion) {
        if(tenantId==null||tenantId.isBlank()||kbId==null||kbId.isBlank()||sourceVersion==null||sourceVersion.isBlank())
            throw new IllegalArgumentException("knowledge_projection_identity_missing");
        return Map.of("query",Map.of("bool",Map.of("filter",List.of(
                Map.of("term",Map.of("tenant_id",tenantId)),Map.of("term",Map.of("kb_id",kbId)),
                Map.of("term",Map.of("source_version",sourceVersion))))));
    }

    /** 外部删除不占用数据库事务；冲突、超时及逐项失败均不能解除持久清理意图。 */
    private boolean deleteProjection(Map<String,Object> query) {
        return resilience.call("elasticsearch:delete-by-query",()-> {
            DeleteResponse response=client().post().uri(properties.getElasticsearchUri()+"/"+properties.getElasticsearchIndex()+"/_delete_by_query?conflicts=proceed")
                    .contentType(MediaType.APPLICATION_JSON).body(query).retrieve().body(DeleteResponse.class);
            return response!=null&&Boolean.FALSE.equals(response.timedOut())&&response.versionConflicts()!=null
                    &&response.versionConflicts()==0&&response.failures()!=null&&response.failures().isEmpty();
        });
    }

    /** ES删除确认回执只保留判断字段，不输出可能含正文的错误材料。 */
    @JsonIgnoreProperties(ignoreUnknown=true)
    private record DeleteResponse(@JsonProperty("timed_out") Boolean timedOut,
            @JsonProperty("version_conflicts") Long versionConflicts,List<Object> failures) {}

    private static boolean validVersions(List<String> versions) {
        return versions != null && !versions.isEmpty()
                && versions.stream().allMatch(version -> version != null && !version.isBlank());
    }

    /** HTTP 200 仍可能包含逐项失败，只记录计数和状态，不输出供应商 reason。 */
    private static void inspectBulk(BulkResponse response, int expectedItems) {
        if (response == null || response.errors() == null || response.items() == null
                || response.items().size() != expectedItems) {
            throw new IllegalStateException("es_bulk_response_invalid");
        }
        long failed = response.items().stream().filter(item -> {
            BulkOperation operation = item == null ? null : item.index() != null ? item.index() : item.delete();
            return operation == null || operation.status() == null || operation.status() >= 300
                    || operation.error() != null;
        }).count();
        if (response.errors() || failed > 0) {
            log.warn("ES 批量索引部分失败 reason=es_bulk_items_failed failedItems={}", Math.max(1, failed));
            throw new IllegalStateException("es_bulk_items_failed");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BulkResponse(Boolean errors, List<BulkItem> items) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BulkItem(BulkOperation index, BulkOperation delete) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BulkOperation(Integer status, BulkError error) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BulkError(String type) { }

    /**
     * 构建带超时的 REST 客户端。
     *
     * @return REST 客户端
     */
    private RestClient client() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        return RestClient.builder().requestFactory(factory).build();
    }
}
