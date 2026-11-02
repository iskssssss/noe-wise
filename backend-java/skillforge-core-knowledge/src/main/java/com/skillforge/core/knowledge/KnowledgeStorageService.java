package com.skillforge.core.knowledge;

import com.skillforge.core.knowledge.mapper.KnowledgeBaseRevisionMapper;
import com.skillforge.core.knowledge.mapper.KnowledgeStorageMapper;
import com.skillforge.core.knowledge.model.KnowledgeChunkDO;
import com.skillforge.core.knowledge.model.KnowledgeDocumentVersionDO;
import com.skillforge.core.knowledge.model.KnowledgeStorageCapacity;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.shared.exception.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识领域大型不可变材料的所有者；版本和重建回执只引用精确身份。
 * 写入与引用加入所属来源版本事务，容量锁按租户串行化受理；正文复用不跨文档或租户。
 */
@Service
public class KnowledgeStorageService {
    private final KnowledgeStorageMapper mapper;
    private final KnowledgeBaseRevisionMapper revisions;
    private final KnowledgeIndexLifecycleService indexes;

    /** 通过正常宿主组件装配注入领域 Mapper。 */
    public KnowledgeStorageService(KnowledgeStorageMapper mapper, KnowledgeBaseRevisionMapper revisions,
            KnowledgeIndexLifecycleService indexes) {
        this.mapper = mapper;
        this.revisions = revisions;
        this.indexes = indexes;
    }

    /** 在版本事务中保存新正文/集合；已有身份继续复用，失败使整个版本事务回滚。 */
    @Transactional(propagation = Propagation.MANDATORY)
    void prepareVersion(KnowledgeDocumentVersionDO version, List<KnowledgeChunkDO> chunks) {
        lock(version.getTenantId());
        Admission admission = new Admission(mapper.capacity(version.getTenantId()));
        if (version.getContentId() == null) {
            String content = version.getContent();
            String digest = digest(content);
            Long id = mapper.findDocumentContent(version.getTenantId(), version.getDocumentId(), digest, content);
            if (id == null) {
                admission.admit(bytes(content), 0);
                id = SnowflakeIds.nextId();
                mapper.insertDocumentContent(version.getTenantId(), version.getDocumentId(), id, digest, content);
            }
            version.setContentId(id);
        }
        // 标题修订复用集合，不追加集合成员或向量；ready 守卫核对集合实际数量。
        if (version.getChunkSetId() != null) return;
        version.setChunkSetId(SnowflakeIds.nextId());
        mapper.insertSet(version.getTenantId(), version.getKbId(), version.getDocumentId(), version.getChunkSetId());
        for (KnowledgeChunkDO chunk : chunks) {
            chunk.setChunkSetId(version.getChunkSetId());
            if (chunk.getContentId() == null) chunk.setContentId(storeChunkContent(chunk, admission));
            if (chunk.getEmbedding() != null && chunk.getEmbeddingId() == null) {
                chunk.setEmbeddingId(storeEmbedding(version, chunk.getContentId(), chunk.getEmbedding(), admission));
            }
            mapper.insertChunk(chunk);
        }
    }

    /** 精确加载所属租户和文档的原始正文，历史详情不读取当前版本。 */
    String content(KnowledgeDocumentVersionDO version) {
        return mapper.documentContent(version.getTenantId(), version.getDocumentId(), version.getContentId());
    }

    /** 保存成功重建批次的向量与引用；向量与 READY 回执在同一 fence 事务内提交。 */
    @Transactional(propagation = Propagation.MANDATORY)
    void saveBatch(KnowledgeDocumentVersionDO target, long batchId,
            List<KnowledgeChunkDO> input, List<String> vectors) {
        if (input.size() != vectors.size()) throw conflict("knowledge_rebuild_vectors_incomplete");
        lock(target.getTenantId());
        Admission admission = new Admission(mapper.capacity(target.getTenantId()));
        for (int index = 0; index < input.size(); index++) {
            Long embeddingId = storeEmbedding(target, input.get(index).getContentId(), vectors.get(index), admission);
            mapper.insertBatchEmbedding(target.getTenantId(), batchId, index, embeddingId);
        }
    }

    /** 恢复成功批次只解析其持久向量身份，不复制向量正文。 */
    public List<Long> batchEmbeddings(long tenantId, long batchId) { return mapper.batchEmbeddings(tenantId, batchId); }

    /** 原子发布结束后解除暂存持有者，正式集合继续持有目标向量。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releasePublishedStaging(long tenantId, long jobId) { mapper.releasePublishedStaging(tenantId, jobId); }

    /** 展示真实租户容量；不将逻辑载荷字节或列物理字节推算为生产磁盘规模。 */
    public KnowledgeStorageCapacity capacity(long tenantId) { return mapper.capacity(tenantId); }

    /**
     * 设置租户知识总预算；NULL 明确表示不设该维度上限。调用方必须已完成运营授权。
     * 已有占用高于新额度时保留数据并拒绝后续增长；配置本身不删数据，纯引用仍可受理。
     */
    @Transactional
    public void configureBudget(long tenantId, Long bytes, Long chunks) {
        if (bytes != null && bytes <= 0 || chunks != null && chunks <= 0) throw conflict("knowledge_storage_budget_invalid");
        lock(tenantId);
        mapper.configureBudget(tenantId, bytes, chunks);
    }

    /**
     * 使用调用方已确认的历史保留边界有界回收；不提供默认 TTL。
     * 先锁知识库，再锁租户容量；发布、绑定和重建均锁同一知识库，删除前数据库再次核引用。
     * FAILED/CANCELLED/UNKNOWN 重建材料保持引用，未确定其保留策略前不释放。
     */
    @Transactional
    public Recycled recycle(long tenantId, long kbId, Instant before, int limit) {
        if (before == null || limit < 1 || limit > 200) throw conflict("knowledge_storage_recycle_parameters_invalid");
        if (revisions.lockBase(tenantId, kbId) == null) throw conflict("knowledge_base_not_found");
        lock(tenantId);
        int versions = 0;
        for (long id : mapper.recyclableVersions(tenantId, kbId, before, limit)) {
            indexes.enqueue(tenantId,kbId,id);
            versions += mapper.deleteVersion(tenantId, id);
        }
        int sets = mapper.deleteOrphanSets(tenantId, kbId, limit);
        int embeddings = mapper.deleteOrphanEmbeddings(tenantId, kbId, limit);
        int chunks = mapper.deleteOrphanChunkContents(tenantId, kbId, limit);
        int documents = mapper.deleteOrphanDocumentContents(tenantId, kbId, limit);
        return new Recycled(versions, sets, embeddings, chunks, documents);
    }

    /** 以与回收相同的边界预览候选身份；锁内读取，但不删除或创建任何材料。 */
    @Transactional
    public List<String> previewRecycling(long tenantId, long kbId, Instant before, int limit) {
        if (before == null || limit < 1 || limit > 200) throw conflict("knowledge_storage_recycle_parameters_invalid");
        if (revisions.lockBase(tenantId, kbId) == null) throw conflict("knowledge_base_not_found");
        return mapper.recyclableVersions(tenantId, kbId, before, limit).stream().map(String::valueOf).toList();
    }

    /**
     * 本轮实际受影响行数；每种事实分别统计，不把 SQL 调用数当成删除量。
     * @param versions 已删除的无引用来源版本数
     * @param sets 已删除的无版本/冻结持有集合数
     * @param embeddings 已删除的无集合/重建持有向量数
     * @param chunkContents 已删除的无集合/向量持有分块正文数
     * @param documentContents 已删除的无版本持有原文数
     */
    public record Recycled(int versions, int sets, int embeddings, int chunkContents, int documentContents) {}

    /** 来源事务在外部调用前登记引用，保护提交后索引与回收的并发顺序。 */
    public Long protectIndex(long tenant,long kb,String source) { return indexes.protect(tenant,kb,source); }
    /** 显式修复先独立提交来源保护，再调用外部引擎。 */
    public Long startIndex(long tenant,long kb,String source) { return indexes.start(tenant,kb,source); }
    /** 仅解除本次得到完整确认的持有者；失败保留PENDING/UNKNOWN用于恢复核对。 */
    public void finishIndex(long tenant,Long hold,boolean confirmed) { indexes.finish(tenant,hold,confirmed); }

    /** 保存同文档不可变分块，摘要碰撞仍要求完整正文一致。 */
    private Long storeChunkContent(KnowledgeChunkDO chunk, Admission admission) {
        String digest = digest(chunk.getContent());
        Long id = mapper.findChunkContent(chunk.getTenantId(), chunk.getDocumentId(), digest, chunk.getContent());
        if (id != null) return id;
        admission.admit(bytes(chunk.getContent()), 1);
        id = SnowflakeIds.nextId();
        mapper.insertChunkContent(chunk.getTenantId(), chunk.getDocumentId(), id, digest, chunk.getContent());
        return id;
    }

    /** 向量按完整正文身份与冻结模型空间复用，正文只在知识向量所有者表出现一次。 */
    private Long storeEmbedding(KnowledgeDocumentVersionDO version, long contentId, String vector, Admission admission) {
        Long id = mapper.findEmbedding(version.getTenantId(), contentId, version.getEmbeddingModelConfigId(),
                version.getEmbeddingModelRevision(), version.getEmbeddingDimensions(), vector);
        if (id != null) return id;
        admission.admit((long) version.getEmbeddingDimensions() * Float.BYTES, 0);
        id = SnowflakeIds.nextId();
        mapper.insertEmbedding(version.getTenantId(), id, contentId, version.getEmbeddingModelConfigId(),
                version.getEmbeddingModelRevision(), version.getEmbeddingDimensions(), vector);
        return id;
    }

    /** 容量检查和正文 INSERT 共用该租户的事务锁。 */
    private void lock(long tenantId) {
        mapper.ensureBudget(tenantId);
        if (mapper.lockBudget(tenantId) == null) throw conflict("knowledge_storage_budget_lock_missing");
    }

    /** 容量不足在插入前拒绝；事务回滚同时撤销该版本的全部正文及关系。 */
    private static final class Admission {
        /** 单事务累计的逻辑字节与正文数量，不作为数据库外的容量权威。 */
        private long bytes;
        private long chunks;
        private final Long maxBytes;
        private final Long maxChunks;
        /** 持有租户锁后只读取一次容量，避免每个分块重复全租户聚合。 */
        private Admission(KnowledgeStorageCapacity current) {
            bytes=current.payloadBytes(); chunks=current.chunkBodies();
            maxBytes=current.maxContentBytes(); maxChunks=current.maxChunkBodies();
        }
        /** 累加本事务真实新增载荷；复用已存在身份不扣预算。 */
        private void admit(long addedBytes, long addedChunks) {
            bytes=Math.addExact(bytes,addedBytes); chunks=Math.addExact(chunks,addedChunks);
            if (addedBytes>0 && maxBytes != null && bytes > maxBytes || addedChunks>0 && maxChunks != null && chunks > maxChunks)
                throw conflict("knowledge_storage_budget_exceeded");
        }
    }

    /** 摘要只用于所属范围内检索候选，不能替代身份与全值比较。 */
    private static String digest(String content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 unavailable", unavailable); }
    }

    /** 字节预算保留 UTF-8 实际载荷，中文和 emoji 不按字符数折算。 */
    private static long bytes(String content) { return content.getBytes(StandardCharsets.UTF_8).length; }
    /** 领域冲突由调用边界按正常业务错误返回。 */
    private static ApiException conflict(String code) { return new ApiException(HttpStatus.CONFLICT, code); }
}
